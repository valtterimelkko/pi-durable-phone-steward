/**
 * The steward-event data allowlist (correction 02, amendment A3, protocol §10).
 *
 * Before an event reaches the pending-events document, the worklog line or a capture,
 * its `data` is filtered against the per-kind allowlist: steward ids in the frozen
 * shapes (`pr-`, `pj-`, `rb-`, `dc-`, `it-`, `dev_`), enum outcomes and statuses, safe
 * integers, booleans, and the protocol's run reasons. Unknown keys are dropped; known
 * keys whose value fails its check are dropped too. File names, phone numbers and chat
 * text can therefore never reach the worklog or a capture, even if the caller includes
 * them.
 */
import type { StewardEvent, StewardEventKind } from "../integrations.ts";

type ValueCheck = (value: unknown) => boolean;

/** The id shapes of correction 02 item 1 (mirrored here; capture.ts owns the same). */
const ID_SHAPES = [
	/^(pr|pj|rb|dc|it)-[A-Za-z0-9_-]{1,64}$/,
	/^dev_[A-Za-z0-9_-]{1,64}$/,
];

function isId(value: unknown): boolean {
	return typeof value === "string" && ID_SHAPES.some((shape) => shape.test(value));
}

function isSafeInt(value: unknown): boolean {
	return typeof value === "number" && Number.isSafeInteger(value);
}

function enumOf(...values: readonly string[]): ValueCheck {
	return (value) => typeof value === "string" && values.includes(value);
}

/** Protocol §6 run reasons. */
const runReason = enumOf("owner", "weekly_check", "phone_result", "photo_window");

/** Protocol §3.1 job kinds. */
const jobKind = enumOf("storage_overview", "app_usage", "media_index", "phone_selftest", "rule_batch", "files_query", "shell_info", "force_stop");

/** Protocol §3.2 result statuses. */
const resultStatus = enumOf("ok", "error", "partial");

/** Protocol §4 decisions. */
const decision = enumOf("approve", "reject");

/** Protocol §3.1 selftest checks and outcomes. */
const selftestCheck = enumOf("trash_dialog", "manage_media_skip", "trash_restorable", "clear_cache_intent", "workmanager_ran");
const selftestOutcome = enumOf("pass", "fail", "cancelled", "skipped");

/** Plan §3.8 post-batch review outcomes. */
const reviewOutcome = enumOf("ok", "failure", "cancelled", "partial");

/**
 * Allowed `data` keys per `StewardEventKind`. The core may include any subset; anything
 * outside this table — or a value that fails its check — is dropped before persistence.
 * The correction's allowed value classes are ids, enum outcomes/statuses, safe integers
 * and booleans; no kind needs a boolean key today, so none declares one.
 */
const EVENT_DATA_ALLOWLIST: Record<StewardEventKind, Record<string, ValueCheck>> = {
	run_start: { reason: runReason },
	run_end: { reason: runReason, openProposals: isSafeInt, proposalId: isId },
	job_queued: { jobId: isId, jobKind },
	job_reported: { jobId: isId, status: resultStatus },
	proposal_created: { proposalId: isId },
	decision: { decisionId: isId, proposalId: isId, decision },
	rule_batch: { batchId: isId, ruleVersion: isSafeInt, items: isSafeInt },
	selftest: { check: selftestCheck, outcome: selftestOutcome },
	review_line: { proposalId: isId, outcome: reviewOutcome, bytesEstimate: isSafeInt, bytesFreed: isSafeInt },
};

/** Filter one event's `data` down to its kind's allowlisted, valid facts. */
export function filterEventData(kind: StewardEventKind, data: Record<string, string | number | boolean | null>): Record<string, string | number | boolean | null> {
	const table = EVENT_DATA_ALLOWLIST[kind] ?? {};
	const out: Record<string, string | number | boolean | null> = {};
	for (const [key, check] of Object.entries(table)) {
		if (key in data && data[key] !== null && check(data[key])) {
			out[key] = data[key];
		}
	}
	return out;
}

/** One filtered copy of `event`: same kind and time, allowlisted data. */
export function sanitizeStewardEvent(event: StewardEvent): StewardEvent {
	return { kind: event.kind, atMs: event.atMs, data: filterEventData(event.kind, event.data) };
}
