/**
 * Capture builder with redaction .
 *
 * Captures are built only from `StewardEvent` data — ids, counts, sizes, outcomes —
 * through fixed templates. Every value passes a gate before it is interpolated: outcomes
 * must be members of the fixed enum, ids must match the steward id shape, numbers must
 * be safe integers. Anything else — file names, phone numbers, prose, model or chat
 * text — is dropped from the note, never quoted.
 *
 * Every note ends with a stable dedupe token (16 hex over the event facts), so a
 * re-sent capture (a crash between the CLI call and the outbox mark) is externally
 * detectable as a duplicate: identical token, identical facts (see outbox.ts).
 */
import { createHash } from "node:crypto";
import type { StewardEvent } from "../integrations.ts";

const OUTCOME_ENUM = new Set(["ok", "failure", "cancelled", "partial"]);

/**
 * Steward id shapes (correction 02, amendment A3): proposal, job, batch, decision, item
 * and device ids only. File names (`IMG_20260601_101010.jpg`) and phone numbers
 * (`07700900123`) are not steward ids and are withheld from notes and worklog lines.
 */
const ID_SHAPES = [
	/^(pr|pj|rb|dc|it)-[A-Za-z0-9_-]{1,64}$/,
	/^dev_[A-Za-z0-9_-]{1,64}$/,
];

function safeId(value: unknown): string | undefined {
	if (typeof value !== "string") return undefined;
	return ID_SHAPES.some((shape) => shape.test(value)) ? value : undefined;
}

function safeInt(value: unknown): number | undefined {
	return typeof value === "number" && Number.isSafeInteger(value) ? value : undefined;
}

/**
 * The stable dedupe token for one event's facts: 16 hex over the canonical JSON of kind,
 * time and data. A replayed delivery of the same event carries the same token.
 */
export function captureToken(event: StewardEvent): string {
	const canonical = JSON.stringify({ kind: event.kind, atMs: event.atMs, data: event.data });
	return createHash("sha256").update(canonical).digest("hex").slice(0, 16);
}

export type CaptureNote = { note: string; token: string };

/**
 * The capture for a post-batch review line, when the result is durable : a
 * failure, a cancellation, or freed bytes differing from the estimate by more than 50 %.
 * Everything else returns `undefined` — no capture.
 */
export function reviewLineCapture(event: StewardEvent): CaptureNote | undefined {
	if (event.kind !== "review_line") return undefined;
	const rawOutcome = event.data.outcome;
	const outcome = typeof rawOutcome === "string" && OUTCOME_ENUM.has(rawOutcome) ? rawOutcome : undefined;
	const estimate = safeInt(event.data.bytesEstimate);
	const freed = safeInt(event.data.bytesFreed);
	const durableFailure = outcome === "failure" || outcome === "cancelled";
	// Strictly more than half the estimate off — exactly 50 % is not durable news.
	const bigDelta =
		estimate !== undefined &&
		freed !== undefined &&
		estimate > 0 &&
		Math.abs(freed - estimate) * 2 > estimate;
	if (!durableFailure && !bigDelta) return undefined;

	const token = captureToken(event);
	const parts: string[] = ["Post-batch review:"];
	const id = safeId(event.data.proposalId);
	parts.push(id === undefined ? "proposal (id withheld)" : `proposal ${id}`);
	if (outcome !== undefined) parts.push(`finished ${outcome}`);
	if (estimate !== undefined && freed !== undefined) {
		const deltaPct = estimate > 0 ? Math.round((Math.abs(freed - estimate) / estimate) * 100) : 0;
		parts.push(`estimated ${estimate} bytes, freed ${freed} bytes (delta ${deltaPct}%).`);
	} else {
		parts.push("sizes unavailable.");
	}
	parts.push(`[capture ${token}]`);
	return { note: parts.join(" "), token };
}
