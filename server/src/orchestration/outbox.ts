/**
 * The external orchestration outbox : a Pi Durable task plus a conversation
 * document that deliver presence updates and captures at most once each per dedupe key,
 * with retry and backoff. Nothing here can fail or block a steward run: enqueueing is a
 * document write inside the caller's commit, delivery happens on a background task, and
 * a failing or absent CLI leaves entries pending — external orchestration down means entries wait.
 *
 * At-most-once across crashes. The dedupe key gates enqueueing (a replayed transaction
 * re-enqueues nothing) and marks delivery. The pump claims an entry (checkpoint), calls
 * the CLI behind crash gates on both sides, then marks the result. A kill after the call
 * but before the mark replays the call exactly once:
 * - a presence update is idempotent by design — re-sending it updates the same card;
 * - a capture is not, so its note carries the stable dedupe token (capture.ts): a
 *   re-sent capture is detected as a duplicate by its token, identical facts, and is
 *   accepted as such. The token, not the call count, is the delivery contract.
 */
import { defineDoc, defineExtension, defineTask, type ConversationId, type Tx } from "@earendil-works/pi-durable";
import { crashPoint } from "../crash-gate.ts";
import { assertAllowlisted, type OrchestrationCallResult } from "./client.ts";
import { periodicCheckpoint } from "../checkpoint.ts";

export type OutboxEffectKind = "presence" | "capture";

export type OutboxEntry = {
	dedupeKey: string;
	seq: number;
	kind: OutboxEffectKind;
	/** Fully built, allowlist-shaped argv, frozen at enqueue time. */
	argv: string[];
	attempts: number;
	/** Epoch ms: the next attempt is allowed from here. */
	nextAttemptAtMs: number;
	state: "pending" | "inflight" | "done";
	deliveredAtMs?: number;
	lastError?: string;
};

export type OutboxState = {
	nextSeq: number;
	entries: Record<string, OutboxEntry>;
	/** The live runner task, or undefined when the queue is empty and none is running. */
	runnerTaskId?: string;
};

export const OrchestrationOutbox = defineDoc<OutboxState>({
	kind: "steward.orchestration-outbox",
	version: 1,
	// Pi Durable 1.4.0 requires an explicit checkpoint policy (no default).
	checkpointWhen: periodicCheckpoint(),
	scope: "conversation",
	history: "latest",
	fork: "initial",
	initial: () => ({ nextSeq: 1, entries: {} }),
});

/** Backoff schedule: 2 s, 4 s, 8 s … capped at 60 s. */
export function backoffDelayMs(attempts: number): number {
	return Math.min(60_000, 2 ** Math.max(1, attempts) * 1_000);
}

/**
 * The delivery the pump calls. Set once by `createOrchestrationIntegrations` (production wires
 * the configured command runner with the config's scratch memory/board env and timeout; tests
 * inject a script). Unset means a wiring gap: entries wait with a recorded error
 * instead of being dropped or looped hot.
 */
let activeDelivery: ((argv: readonly string[]) => Promise<OrchestrationCallResult>) | undefined;

export function setOrchestrationOutboxDelivery(
	delivery: ((argv: readonly string[]) => Promise<OrchestrationCallResult>) | undefined,
): void {
	activeDelivery = delivery;
}

export function getOrchestrationOutboxDelivery(): ((argv: readonly string[]) => Promise<OrchestrationCallResult>) | undefined {
	return activeDelivery;
}

export type EnqueueInput = {
	dedupeKey: string;
	kind: OutboxEffectKind;
	argv: string[];
};

/**
 * Record one external orchestration effect durably inside the caller's commit and make sure a runner
 * task exists. Idempotent per dedupe key: a replayed commit finds the entry its first
 * attempt recorded and adds nothing.
 */
export async function enqueueOrchestrationEffect(
	tx: Tx,
	conversationId: ConversationId,
	input: EnqueueInput,
): Promise<void> {
	const doc = await tx.doc(OrchestrationOutbox, conversationId);
	if (doc.entries[input.dedupeKey] !== undefined) return;
	const seq = doc.nextSeq;
	doc.nextSeq = seq + 1;
	doc.entries[input.dedupeKey] = {
		dedupeKey: input.dedupeKey,
		seq,
		kind: input.kind,
		argv: input.argv,
		attempts: 0,
		nextAttemptAtMs: 0,
		state: "pending",
	};
	if (doc.runnerTaskId === undefined) {
		const taskId = await tx.createTask(
			OrchestrationOutboxTask,
			{},
			{ ownership: { kind: "conversation" }, background: true, conversationId },
		);
		doc.runnerTaskId = `${taskId}`;
	}
}

function isDeliverable(entry: OutboxEntry, now: number): boolean {
	return entry.state !== "done" && entry.nextAttemptAtMs <= now;
}

async function interruptibleSleep(ms: number, signal: AbortSignal): Promise<void> {
	const deadline = Date.now() + ms;
	while (!signal.aborted && Date.now() < deadline) {
		await new Promise((resolve) => setTimeout(resolve, Math.min(50, Math.max(1, deadline - Date.now()))));
	}
}

export const OrchestrationOutboxTask = defineTask<Record<string, never>, { phase: "pump" }, null>({
	name: "steward.orchestration-outbox",
	version: 1,
	initial: () => ({ phase: "pump" }),
	phases: {
		pump: async (_task, runtime, context) => {
			for (;;) {
				if (runtime.signal.aborted) return;
				const doc = await runtime.snapshot(OrchestrationOutbox, runtime.conversationId, context);
				const entries = Object.values(doc?.entries ?? {});
				const now = Date.now();
				const due = entries
					.filter((entry) => isDeliverable(entry, now))
					.sort((a, b) => a.seq - b.seq)[0];
				if (due === undefined) {
					const waiting = entries.filter((entry) => entry.state !== "done");
					if (waiting.length === 0) {
						// Retire — unless a racing enqueue appeared between snapshot and commit.
						let retired = false;
						await runtime.commit(async (tx) => {
							const fresh = await tx.doc(OrchestrationOutbox, runtime.conversationId);
							if (Object.values(fresh.entries).some((entry) => entry.state !== "done")) return undefined;
							fresh.runnerTaskId = undefined;
							retired = true;
							return { status: "terminal", outcome: { status: "completed", result: null } };
						}, context);
						if (retired) return;
						continue;
					}
					const nextWake = Math.min(...waiting.map((entry) => entry.nextAttemptAtMs));
					await interruptibleSleep(Math.min(5_000, Math.max(0, nextWake - now)), runtime.signal);
					continue;
				}
				// Claim: the attempt counter steps durably before the call.
				await runtime.commit(async (tx) => {
					const fresh = await tx.doc(OrchestrationOutbox, runtime.conversationId);
					const entry = fresh.entries[due.dedupeKey];
					if (entry !== undefined && entry.state !== "done") {
						entry.state = "inflight";
						entry.attempts += 1;
						entry.nextAttemptAtMs = Date.now();
					}
					return undefined;
				}, context);
				await crashPoint("orchestration-outbox:before-call");
				// Belt and braces: the argv was allowlist-checked when enqueued; check again
				// at the boundary where it finally reaches the CLI.
				assertAllowlisted(due.argv);
				const delivery = activeDelivery;
				const result: OrchestrationCallResult =
					delivery === undefined
						? { ok: false, error: "orchestration delivery not configured" }
						: await delivery(due.argv);
				await crashPoint("orchestration-outbox:after-call");
				await runtime.commit(async (tx) => {
					const fresh = await tx.doc(OrchestrationOutbox, runtime.conversationId);
					const entry = fresh.entries[due.dedupeKey];
					if (entry === undefined || entry.state === "done") return undefined;
					if (result.ok) {
						entry.state = "done";
						entry.deliveredAtMs = Date.now();
						entry.lastError = undefined;
					} else {
						entry.state = "pending";
						entry.nextAttemptAtMs = Date.now() + backoffDelayMs(entry.attempts);
						entry.lastError = result.error.slice(0, 200);
					}
				}, context);
			}
		},
	},
	abort: async (_task, runtime, context) => {
		await runtime.commit(() => ({ status: "terminal", outcome: { status: "aborted" } }), context);
	},
});

export const orchestrationOutboxExtension = defineExtension({
	name: "steward-orchestration-outbox",
	tasks: [OrchestrationOutboxTask],
});
