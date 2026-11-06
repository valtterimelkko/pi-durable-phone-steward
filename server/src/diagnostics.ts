/**
 * `phone_diagnostics` result handling (docs/protocol.md A6, O6; correction 01
 * item 3). The app answers with its version, permissions, WorkManager state, outbox
 * counts and its in-memory event log. The server stores ONLY the A6 fields — every other
 * key is dropped — caps the log at `DIAGNOSTICS_LIMITS.logEntries` entries (keeping the
 * newest: the app's log is newest last), truncates every `detail` at
 * `DIAGNOSTICS_LIMITS.detailChars`, replaces an `event` that is not a short snake_case
 * name with `invalid_event`, and redacts path-like, file-name-like and token-like runs in
 * `detail` as `[redacted]` (A6: a detail never carries file names, media metadata, tokens
 * or other personal data — enforced here, not trusted). The result is for engineers: it
 * never reaches the conversation (jobs.ts keeps it out of the reporter).
 */
import type { JsonValue } from "@earendil-works/chord";

export const DIAGNOSTICS_LIMITS = {
	logEntries: 200,
	detailChars: 200,
} as const;

const A6_PERMISSION_KEYS = ["usageAccess", "allFiles", "mediaRead", "manageMedia", "batteryUnrestricted"] as const;
const A6_OUTBOX_KEYS = ["crashes", "errors"] as const;

/** A6: `event` is a short snake_case name (dots allowed), at most 64 characters. */
const EVENT_RE = /^[a-z0-9_.]{1,64}$/;
/** Anything with a slash-shaped run: a path (either separator). */
const PATH_RE = /\S*[/\\]\S*/g;
/** A run of 20+ base64url characters: token-shaped. */
const TOKEN_RE = /[A-Za-z0-9_-]{20,}/g;
/** A word with a dot and a 2–5 letter extension: file-name-shaped. */
const FILE_NAME_RE = /\b[\w.-]+\.[a-zA-Z]{2,5}\b/g;

type LooseRecord = Record<string, unknown>;

function isObject(value: unknown): value is LooseRecord {
	return value !== null && typeof value === "object" && !Array.isArray(value);
}

/** A6 value types: a field whose value has another type is dropped, never stored. */
type FieldRule = (value: unknown) => boolean;
const isBool: FieldRule = (v) => typeof v === "boolean";
const isCount: FieldRule = (v) => typeof v === "number" && Number.isInteger(v) && v >= 0;
const WORKER_STATES = new Set(["ENQUEUED", "RUNNING", "BLOCKED", "CANCELLED", "FAILED", "SUCCEEDED", "NONE"]);
const LOG_LEVELS = new Set(["info", "warn", "error"]);

/** Keep only the named keys whose values pass their A6 type rule. */
function pickTyped(source: LooseRecord, rules: Record<string, FieldRule>): LooseRecord {
	const out: LooseRecord = {};
	for (const [key, rule] of Object.entries(rules)) {
		if (source[key] !== undefined && rule(source[key])) out[key] = source[key];
	}
	return out;
}

/** Redact path-like, token-like and file-name-like runs as `[redacted]` (in that order). */
export function redactDetail(detail: string): string {
	let out = detail.replace(PATH_RE, "[redacted]");
	out = out.replace(TOKEN_RE, "[redacted]");
	out = out.replace(FILE_NAME_RE, "[redacted]");
	return out;
}

function cleanLogEntry(entry: LooseRecord): LooseRecord {
	const cleaned = pickTyped(entry, {
		atMs: isCount,
		level: (v) => typeof v === "string" && LOG_LEVELS.has(v),
	});
	cleaned.event = typeof entry.event === "string" && EVENT_RE.test(entry.event) ? entry.event : "invalid_event";
	// A non-string detail is dropped whole: only text can be redacted.
	if (typeof entry.detail === "string") {
		cleaned.detail = redactDetail(entry.detail).slice(0, DIAGNOSTICS_LIMITS.detailChars);
	}
	return cleaned;
}

/**
 * Normalise one diagnostics result for storage: A6 fields only, `log` capped to the
 * newest `logEntries` entries with every `detail` redacted and capped. Anything that is
 * not a plain object cleans to `null` — junk carries no fields worth keeping.
 */
export function normaliseDiagnosticsResult(data: JsonValue): JsonValue {
	if (!isObject(data)) return null;
	const cleaned: LooseRecord = {};
	// Free text from the phone: redacted like a detail, capped at the A5 version length.
	if (typeof data.appVersion === "string") cleaned.appVersion = redactDetail(data.appVersion).slice(0, 64);
	if (isCount(data.versionCode)) cleaned.versionCode = data.versionCode;
	if (isObject(data.permissions)) {
		cleaned.permissions = pickTyped(data.permissions, Object.fromEntries(A6_PERMISSION_KEYS.map((k) => [k, isBool])));
	}
	if (isObject(data.worker)) {
		cleaned.worker = pickTyped(data.worker, {
			state: (v) => typeof v === "string" && WORKER_STATES.has(v),
			lastRunMs: isCount,
			runAttemptCount: isCount,
		});
	}
	if (isObject(data.outbox)) {
		cleaned.outbox = pickTyped(data.outbox, Object.fromEntries(A6_OUTBOX_KEYS.map((k) => [k, isCount])));
	}
	if (Array.isArray(data.log)) {
		const entries = (data.log as unknown[]).filter(isObject).slice(-DIAGNOSTICS_LIMITS.logEntries).map(cleanLogEntry);
		cleaned.log = entries;
	}
	return JSON.parse(JSON.stringify(cleaned)) as JsonValue;
}
