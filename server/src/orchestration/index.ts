/**
 * `createOrchestrationIntegrations` — the one entry point the steward core wires in at merge
 * (docs/protocol.md §8 and §10). Implements `StewardIntegrations` with no
 * change to any core file.
 *
 * Behaviour:
 * - `onHarnessOpen` starts the local event mirror (protocol §10) for every existing
 *   conversation; `stewardEvent` records every event durably for that mirror inside the
 *   caller's commit, and enqueues the event's external effects on the durable outbox:
 *   `run_start` → presence `working` with the reason as the step; `run_end` → `waiting` (an open proposal awaits the owner), else `idle`;
 *   `waiting` when `data.openProposals > 0` (a proposal awaits the owner); `review_line`
 *   → one capture when the result is durable (failure, cancellation, or freed bytes off
 *   the estimate by more than 50 %).
 * - `enabled: false` makes every external call a no-op — no outbox entries, no delivery —
 *   while the local event mirror still writes.
 * - Nothing here can fail a run for orchestration reasons: the only throw paths are the
 *   durable record itself (a storage failure like any other commit), and every effect
 *   rides the outbox, which waits instead of failing when the external tool is down.
 */
import { createHash } from "node:crypto";
import type { ConversationId, Tx } from "@earendil-works/pi-durable";
import type { Context } from "@earendil-works/chord";
import type {
	OrchestrationConfig,
	StewardEvent,
	StewardIntegrations,
} from "../integrations.ts";
import { recordStewardEvent } from "../worklog/docs.ts";
import { sanitizeStewardEvent } from "../worklog/event-data.ts";
import { WorklogMirror } from "../worklog/mirror.ts";
import { reviewLineCapture } from "./capture.ts";
import {
	assertAllowlisted,
	presenceUpdateArgv,
	captureArgv,
	execFileRunner,
	type OrchestrationRunner,
} from "./client.ts";
import {
	orchestrationOutboxExtension,
	enqueueOrchestrationEffect,
	setOrchestrationOutboxDelivery,
} from "./outbox.ts";

export type OrchestrationDeps = {
	/** Injectable command runner (the default runs the configured command with `execFile`). */
	runCommand?: OrchestrationRunner;
	/** Clock for mirror timestamps; tests inject a fixed one. */
	now?: () => number;
	/** Prepended to `worklogDir`; scratch roots for tests. */
	fsRoot?: string;
};

/** Stable dedupe key part for one event's facts: 16 hex over kind, time and data. */
function eventToken(event: StewardEvent): string {
	return createHash("sha256")
		.update(JSON.stringify({ kind: event.kind, atMs: event.atMs, data: event.data }))
		.digest("hex")
		.slice(0, 16);
}

function eventReason(event: StewardEvent, fallback: string): string {
	return typeof event.data.reason === "string" && event.data.reason !== "" ? event.data.reason : fallback;
}

/**
 * A1: run end always reports `waiting` (the board has no idle). The step is `idle`, or,
 * when the event carries an allowlisted proposal id, the owner-approval step naming it;
 * anything else that looks like an id falls back to the generic step.
 */
function runEndStep(event: StewardEvent): string {
	const openProposals = event.data.openProposals;
	if (typeof openProposals === "number" && openProposals > 0) {
		const id = event.data.proposalId;
		return typeof id === "string" ? `waiting for owner approval of ${id}` : "waiting for owner approval";
	}
	return "idle";
}

export function createOrchestrationIntegrations(config: OrchestrationConfig, deps: OrchestrationDeps = {}): StewardIntegrations {
	const now = deps.now ?? Date.now;
	const mirrors = new Map<ConversationId, WorklogMirror>();
	const runner = deps.runCommand ?? execFileRunner;
	// Delivery environment: scratch memory/board roots from the config, the configured timeout.
	const deliveryEnv: NodeJS.ProcessEnv = {
		...process.env,
		...(config.memoryRoot === undefined ? {} : { STEWARD_MEMORY_ROOT: config.memoryRoot }),
		...(config.boardDir === undefined ? {} : { STEWARD_BOARD_DIR: config.boardDir }),
	};
	setOrchestrationOutboxDelivery(async (argv) => {
		if (!config.enabled) return { ok: true, stdout: "" };
		return runner(argv, { command: config.command ?? "", env: deliveryEnv, timeoutMs: config.timeoutMs });
	});

	const enqueue = async (
		tx: Tx,
		conversationId: ConversationId,
		dedupeKey: string,
		kind: "presence" | "capture",
		argv: string[],
	): Promise<void> => {
		assertAllowlisted(argv);
		await enqueueOrchestrationEffect(tx, conversationId, { dedupeKey, kind, argv });
	};

	return {
		extensions: [orchestrationOutboxExtension],

		async onHarnessOpen(harness, context) {
			// One mirror per existing conversation (the default deployment has one: `main`).
			const ids = await harness.commit(async (tx) => {
				const page = await tx.scanConversations({}, 50, undefined);
				return page.items.map((conversation) => conversation.id);
			}, context);
			for (const id of ids) {
				const mirror = await WorklogMirror.start(
					harness,
					id,
					context,
					{ worklogDir: config.worklogDir },
					{ now, fsRoot: deps.fsRoot },
				);
				mirrors.set(id, mirror);
			}
		},

		async stewardEvent(tx, conversationId, event) {
			// One filtered copy drives the durable record, the
			// dedupe keys and the capture template, so file names, phone numbers and chat
			// text can never reach the worklog, a capture note or a key.
			const clean = sanitizeStewardEvent(event);
			// Durable worklog record, atomic with the commit that caused the event
			// (recordStewardEvent filters again — the persistence gate).
			await recordStewardEvent(tx, conversationId, clean);
			// Effects ride the outbox; skipped entirely when disabled.
			if (!config.enabled) return;
			try {
				const token = eventToken(clean);
				if (clean.kind === "run_start") {
					await enqueue(
						tx,
						conversationId,
						`presence:${token}`,
						"presence",
						presenceUpdateArgv(eventReason(clean, "run started"), "working"),
					);
				} else if (clean.kind === "run_end") {
					// Run end is waiting, never idle; the step tells which.
					await enqueue(
						tx,
						conversationId,
						`presence:${token}`,
						"presence",
						presenceUpdateArgv(runEndStep(clean), "waiting"),
					);
				} else if (clean.kind === "review_line") {
					const capture = reviewLineCapture(clean);
					if (capture !== undefined) {
						// The capture token is the command's idempotency key.
						await enqueue(tx, conversationId, `capture:${capture.token}`, "capture", captureArgv(capture.note, capture.token));
					}
				}
			} catch {
				// External effects must never fail the commit that caused the event; the
				// worklog record above is already durable, and the next identical event
				// re-enqueues under the same dedupe key.
			}
		},

		async close() {
			for (const mirror of mirrors.values()) await mirror.close();
			mirrors.clear();
		},
	};
}
