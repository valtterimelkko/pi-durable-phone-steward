/**
 * The worklog mirror's durable documents (`docs/protocol.md` §10).
 *
 * Steward events arrive through `stewardEvent(tx, …)` inside the core's own commit, so
 * they are recorded into a conversation document in that same transaction — the event is
 * durable exactly when the change that caused it is. The mirror drains the document and
 * writes the `steward-event` lines after the commit; the file's line ids make the drain
 * idempotent, so the document trim is an optimisation, never a correctness rule.
 *
 * Each event also records an `anchorEntryId`: the newest entry of the conversation at
 * event time, read inside the caller's transaction (one indexed scan). The mirror writes
 * the event line after all entries up to its anchor and before later entries, which
 * reproduces the fixture's interleaving and survives a crash between commit and write.
 */
import {
	defineDoc,
	type ConversationId,
	type EntryId,
	type Tx,
} from "@earendil-works/pi-durable";
import type { StewardEvent, StewardEventKind } from "../integrations.ts";
import { sanitizeStewardEvent } from "./event-data.ts";
import { neverCheckpoint, periodicCheckpoint } from "../checkpoint.ts";

export type PendingStewardEvent = {
	/** Conversation-wide drain sequence, 1, 2, 3… assigned in commit order. */
	seq: number;
	kind: StewardEventKind;
	atMs: number;
	data: Record<string, string | number | boolean | null>;
	/** Newest entry id in the conversation when the event was recorded (0 when none). */
	anchorEntryId: number;
};

export type PendingEventsState = {
	nextSeq: number;
	events: PendingStewardEvent[];
};

/** The doc's kind string, shared with the mirror's commit filter. */
export const PENDING_EVENTS_DOC_KIND = "steward.pending-events";

export const StewardPendingEvents = defineDoc<PendingEventsState>({
	kind: PENDING_EVENTS_DOC_KIND,
	version: 1,
	// Pi Durable 1.4.0 requires an explicit checkpoint policy (no default).
	checkpointWhen: periodicCheckpoint(),
	scope: "conversation",
	history: "latest",
	fork: "initial",
	initial: () => ({ nextSeq: 1, events: [] }),
});

export type SessionInfoState = {
	/** Minted once per conversation and kept here, so a restart keeps it (protocol §10). */
	sessionUuid: string;
	/** Epoch ms of the first mirror start; also the mirror file-name timestamp. */
	startedAtMs: number;
};

export const StewardSessionInfo = defineDoc<SessionInfoState>({
	kind: "steward.session-info",
	version: 1,
	// Pi Durable 1.4.0 requires an explicit checkpoint policy (no default).
	checkpointWhen: neverCheckpoint(),
	scope: "conversation",
	history: "latest",
	fork: "initial",
	initial: () => ({
		sessionUuid: crypto.randomUUID(),
		startedAtMs: Date.now(),
	}),
});

/**
 * The newest entry of the conversation in the caller's transaction, uncommitted appends
 * included: the sqlite scan is `ORDER BY id DESC LIMIT 1` over `id >= 1`, so this is one
 * indexed read. Returns 0 when the conversation has no entries yet.
 *
 * A transaction that has already written a table (an entry appended earlier in the same
 * commit) cannot read tables again (`ReadAfterWrite`); in that case the caller recorded
 * the event after its own entry appends, and the event is anchored to the end — it is
 * written after all entries known at record time, never before them. Recording an event
 * before the entries of its own commit (e.g. a run_start before its input) keeps the
 * scan legal and lands the event line before those entries, like the fixture.
 */
export async function newestEntryIdInTx(tx: Tx, conversationId: ConversationId): Promise<number> {
	try {
		const page = await tx.scanEntries(
			{ conversationId, minEntryId: 1 as unknown as EntryId },
			1,
			undefined,
		);
		const newest = page.items[0];
		return newest === undefined ? 0 : Number(newest.id);
	} catch {
		return Number.MAX_SAFE_INTEGER;
	}
}

/**
 * Record one steward event durably inside the caller's commit. Called by the core
 * through the integration seam; throws only what the surrounding commit would throw
 * anyway (storage errors) — never for orchestration reasons, since orchestration is not involved.
 *
 * The event's `data` is allowlisted per kind (amendment A3) BEFORE it reaches the
 * document, so the worklog line and every downstream consumer see allowlisted facts
 * only: file names, phone numbers and chat text are dropped here, at the persistence
 * gate, not later at the template.
 */
export async function recordStewardEvent(
	tx: Tx,
	conversationId: ConversationId,
	event: StewardEvent,
): Promise<void> {
	const clean = sanitizeStewardEvent(event);
	const anchorEntryId = await newestEntryIdInTx(tx, conversationId);
	const doc = await tx.doc(StewardPendingEvents, conversationId);
	const seq = doc.nextSeq;
	doc.nextSeq = seq + 1;
	doc.events.push({
		seq,
		kind: clean.kind,
		atMs: clean.atMs,
		data: clean.data,
		anchorEntryId,
	});
}
