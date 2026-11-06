/**
 * Client request-id idempotency for mutating POSTs (docs/protocol.md §1): a repeat with
 * the same requestId and body returns the first response with `"duplicate": true`; the
 * same requestId with a different body gets `409 REQUEST_ID_CONFLICT`. The log is bounded.
 */
import type { JsonValue } from "@earendil-works/chord";
import { defineDoc, type Tx } from "@earendil-works/pi-durable";
import { canonicalJson } from "./canonical.ts";
import { ApiError } from "./errors.ts";
import { sha256Hex } from "./ids.ts";
import { periodicCheckpoint } from "./checkpoint.ts";

const MAX_ENTRIES = 500;

export type RequestEntry = {
	/** sha256 of the client request id. */
	idHash: string;
	route: string;
	bodyHash: string;
	response: JsonValue;
	atMs: number;
};

export type RequestsState = { entries: Record<string, RequestEntry> };

export const Requests = defineDoc<RequestsState>({
	kind: "steward.requests",
	version: 1,
	// Pi Durable 1.4.0 requires an explicit checkpoint policy (no default).
	checkpointWhen: periodicCheckpoint(),
	scope: "session",
	initial: (): RequestsState => ({ entries: {} }),
});

export type RequestFacts = { requestId: string; route: string; body: unknown };

/** Look up a request id. Returns the stored response with `duplicate: true` when repeated. */
export async function resolveRequestId(
	tx: Tx,
	facts: RequestFacts,
): Promise<{ response: JsonValue; duplicate: true } | undefined> {
	const state = await tx.doc(Requests);
	const entry = state.entries[sha256Hex(facts.requestId)];
	if (entry === undefined) return undefined;
	if (entry.route !== facts.route || entry.bodyHash !== sha256Hex(canonicalJson(facts.body))) {
		throw new ApiError(409, "REQUEST_ID_CONFLICT", "this requestId was already used with a different request");
	}
	// Detach from the document draft: the stored response may be a structured object, and
	// handing a live draft proxy to the caller breaks once the commit settles.
	return { response: JSON.parse(JSON.stringify(entry.response)) as JsonValue, duplicate: true };
}

/** Record the first response for a request id, pruning the log to the newest MAX_ENTRIES. */
export async function recordRequest(
	tx: Tx,
	facts: RequestFacts & { response: JsonValue; nowMs: number },
): Promise<void> {
	const state = await tx.doc(Requests);
	state.entries[sha256Hex(facts.requestId)] = {
		idHash: sha256Hex(facts.requestId),
		route: facts.route,
		bodyHash: sha256Hex(canonicalJson(facts.body)),
		response: JSON.parse(JSON.stringify(facts.response)) as JsonValue,
		atMs: facts.nowMs,
	};
	pruneRequestLog(tx, facts.nowMs);
}

/** Keep only the newest MAX_ENTRIES entries; returns how many remain. */
export async function pruneRequestLog(tx: Tx, nowMs: number): Promise<number> {
	const state = await tx.doc(Requests);
	const all = Object.values(state.entries).sort((a, b) => b.atMs - a.atMs);
	const keep = new Set(all.slice(0, MAX_ENTRIES).map((e) => e.idHash));
	for (const key of Object.keys(state.entries)) {
		if (!keep.has(state.entries[key]!.idHash)) delete state.entries[key];
	}
	return Object.keys(state.entries).length;
}
