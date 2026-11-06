/**
 * The seam between the steward core and its orchestration integration
 * (`server/src/orchestration/`): docs/protocol.md §8 and §10. The core calls
 * `stewardEvent` inside the commit that caused the event, so an integration can enqueue
 * durable work (outbox entries, event-mirror lines) atomically with it.
 *
 * Nothing here may make a run fail: external orchestration is never on the critical path.
 */
import type { Context } from "@earendil-works/chord";
import type { ConversationId, Extension, Harness, Tx } from "@earendil-works/pi-durable";

/** The `data.kind` values of an event-mirror `steward-event` line (docs/protocol.md §10). */
export type StewardEventKind =
	| "run_start"
	| "run_end"
	| "job_queued"
	| "job_reported"
	| "proposal_created"
	| "decision"
	| "rule_batch"
	| "selftest"
	/** The deterministic post-batch review line: estimate against bytes freed. */
	| "review_line";

export type StewardEvent = {
	kind: StewardEventKind;
	/** Epoch milliseconds. */
	atMs: number;
	/** Kind-specific facts. Ids, counts, sizes and outcomes only: never file names, chat text or contact names. */
	data: Record<string, string | number | boolean | null>;
};

export type OrchestrationConfig = {
	/** False (the default) turns every external call into a no-op (tests, and a host with no orchestration tool). */
	enabled: boolean;
	/**
	 * The executable the seam calls for presence updates and captures, as
	 * `<command> <subcommand> …`. Example: `/usr/local/bin/my-orchestrator`. Required
	 * when `enabled`; with `enabled: false` it is never called.
	 */
	command?: string;
	/** Passed to the command as STEWARD_MEMORY_ROOT; point it at your memory store. */
	memoryRoot?: string;
	/** Passed to the command as STEWARD_BOARD_DIR; point it at your presence board. */
	boardDir?: string;
	/** Where the local event mirror writes (docs/protocol.md §10). */
	worklogDir: string;
	/** Per-call timeout; the default is 10 s. */
	timeoutMs: number;
};

export interface StewardIntegrations {
	/** Installed after the core extensions, in this order, on every start. */
	extensions: readonly Extension[];
	/** Called once after `Harness.open` and `resume()`; for example to start the event mirror. */
	onHarnessOpen?(harness: Harness, context: Context): Promise<void>;
	/** Called inside the commit that caused the event. Must not throw for orchestration reasons. */
	stewardEvent?(tx: Tx, conversationId: ConversationId, event: StewardEvent): Promise<void>;
	/** Called on shutdown, before the storage closes. */
	close?(): Promise<void>;
}

export const noIntegrations: StewardIntegrations = { extensions: [] };
