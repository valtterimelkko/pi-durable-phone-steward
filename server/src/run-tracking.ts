/**
 * Run tracking (plan a run starts on an owner message, a schedule, or a phone
 * result). `steward.runs` records the open runs of `main`; `run_start` / `run_end`
 * steward-events are emitted in the commits that open and close them, so the mirror and
 * the integration see each run exactly once, and a crash cannot leave one permanently open
 * (the task that closes a run replays it).
 */
import { BACKGROUND_CONTEXT } from "@earendil-works/chord/context";
import { defineDoc, type ConversationId, type Tx } from "@earendil-works/pi-durable";
import { emitStewardEvent } from "./steward-events.ts";
import { appendChatEvent } from "./chat-events.ts";
import { Proposals } from "./proposals.ts";
import { periodicCheckpoint } from "./checkpoint.ts";

/**
 * A13.5 adds `phone_use`: the run a finished phone session's hand-back input starts, so the
 * steward continues the conversation with what happened on the screen.
 */
export type RunReason = "owner" | "weekly_check" | "phone_result" | "photo_window" | "phone_use";

export type RunsState = {
	/** Open runs by key (the Pi Durable requestId of the input that started them). */
	open: Record<string, { reason: RunReason; startedAtMs: number }>;
};

export const Runs = defineDoc<RunsState>({
	kind: "steward.runs",
	version: 1,
	// Pi Durable 1.4.0 requires an explicit checkpoint policy (no default).
	checkpointWhen: periodicCheckpoint(),
	scope: "conversation",
	history: "latest",
	fork: "initial",
	initial: () => ({ open: {} }),
});

/** Open a run inside the caller's commit; emits `run_start` once for the key (plus the SSE event). */
export async function openRun(
	tx: Tx,
	conversationId: ConversationId,
	runKey: string,
	reason: RunReason,
	nowMs: number,
): Promise<void> {
	const doc = await tx.doc(Runs, conversationId);
	if (doc.open[runKey] !== undefined) return;
	doc.open[runKey] = { reason, startedAtMs: nowMs };
	await emitStewardEvent(tx, conversationId, { kind: "run_start", atMs: nowMs, data: { reason } });
	await appendChatEvent(tx, "run", { status: "started", reason, atMs: nowMs }, nowMs);
}

/**
 * The reason of the run in progress: the oldest open run's reason, or "idle" when none is
 * open. Diagnostic only — correction 1 removed it from the `phone_use_request` guard (a
 * lagging mirror attributes the wrong turn); the web budgets still key on the same
 * oldest-open-run key.
 */
export async function currentRunReason(tx: Tx, conversationId: ConversationId): Promise<RunReason | "idle"> {
	const doc = await tx.doc(Runs, conversationId);
	const open = Object.values(doc.open).sort((a, b) => a.startedAtMs - b.startedAtMs)[0];
	return open === undefined ? "idle" : open.reason;
}

/**
 * close a run and keep trying. A detached close whose commit throws must
 * never be silently swallowed — a run left open misled the old run-mirror guard, and an
 * open run lingers until the next start-up sweep. Retries with a short backoff and logs
 * the final failure; the start-up sweep (`sweepStaleRuns`) is the last resort.
 */
export async function closeRunReliably(
	harness: import("@earendil-works/pi-durable").Harness,
	conversationId: ConversationId,
	runKey: string,
	attempts = 5,
): Promise<void> {
	for (let attempt = 1; ; attempt += 1) {
		try {
			await harness.commit(async (tx) => {
				await closeRun(tx, conversationId, runKey, Date.now());
				return undefined;
			}, BACKGROUND_CONTEXT);
			return;
		} catch (error) {
			if (attempt >= attempts) {
				console.error(`[steward] run ${runKey} close failed after ${attempts} attempts: ${error instanceof Error ? error.message : String(error)}`);
				return;
			}
			await new Promise((resolve) => setTimeout(resolve, Math.min(2_000, 100 * 2 ** attempt)));
		}
	}
}

/** Close a run inside the caller's commit; emits `run_end` once for the key (plus the SSE event). */
export async function closeRun(
	tx: Tx,
	conversationId: ConversationId,
	runKey: string,
	nowMs: number,
): Promise<void> {
	const doc = await tx.doc(Runs, conversationId);
	const open = doc.open[runKey];
	if (open === undefined) return;
	delete doc.open[runKey];
	// The board integration picks `waiting` (not `idle`) while proposals await the owner
	// (02-answer note 1), so run_end carries the open-proposal count.
	const proposals = await tx.doc(Proposals, conversationId);
	const openProposals = Object.values(proposals.proposals).filter((proposal) => proposal.status === "open").length;
	await emitStewardEvent(tx, conversationId, { kind: "run_end", atMs: nowMs, data: { openProposals } });
	await appendChatEvent(tx, "run", { status: "ended", reason: open.reason, atMs: nowMs, durationMs: Math.max(0, nowMs - open.startedAtMs) }, nowMs);
}
