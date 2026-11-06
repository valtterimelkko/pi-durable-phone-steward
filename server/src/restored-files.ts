/**
 * A11.3 server half: what a restore from the Steward trash reports.
 *
 * The app moves files and folders back from its Steward trash and then posts the items it
 * put back. Paths are validated exactly like `delete_files` targets (A10.1): a restore can
 * only ever undo a move that the same rules allowed, so the same normalisation and refusals
 * apply. A proposal id the server does not know is still recorded — the record is about the
 * paths, not the proposal.
 */
import type { DecisionLedgerEntry } from "./documents.ts";
import { badRequest } from "./errors.ts";
import { normaliseRelPath, refusedDeleteReason } from "./files-query.ts";

/** The most items one restore report may carry. */
export const RESTORED_MAX_ITEMS = 500;

export type RestoredItem = { path: string; bytes: number };

function isObject(value: unknown): value is Record<string, unknown> {
	return value !== null && typeof value === "object" && !Array.isArray(value);
}

/**
 * Validate and normalise the `items` of `POST /v1/files/restored`. Throws `400 BAD_REQUEST`
 * naming the offending item; nothing is recorded unless every item passes.
 */
export function validateRestoredItems(items: unknown): RestoredItem[] {
	if (!Array.isArray(items) || items.length === 0 || items.length > RESTORED_MAX_ITEMS) {
		throw badRequest(`items must be a list of 1 to ${RESTORED_MAX_ITEMS} restored files`);
	}
	return items.map((raw, index) => {
		if (!isObject(raw)) throw badRequest(`items[${index}] must be {path, bytes}`);
		if (typeof raw.path !== "string") throw badRequest(`items[${index}].path is required`);
		// `normaliseRelPath` throws a `BAD_PATH` detail for an absolute path, `..`, a
		// backslash or a NUL; the always-refused paths follow `delete_files`.
		const path = normaliseRelPath(raw.path);
		const refusal = refusedDeleteReason(path);
		if (refusal !== undefined) throw badRequest(`items[${index}] "${path}" is refused: ${refusal}.`);
		if (typeof raw.bytes !== "number" || !Number.isFinite(raw.bytes) || raw.bytes < 0) {
			throw badRequest(`items[${index}].bytes must be a number of bytes, 0 or more`);
		}
		return { path, bytes: Math.floor(raw.bytes) };
	});
}

/** One decisions-ledger entry for a restored item (A11.3). */
export function restoredLedgerEntry(item: RestoredItem, proposalId: string, nowMs: number): DecisionLedgerEntry {
	return {
		atMs: nowMs,
		type: "restored",
		kind: "delete_files",
		path: item.path,
		bytes: item.bytes,
		proposalId,
	};
}
