/**
 * A11.2: a deterministic, per-kind digest of a phone result for the conversation.
 *
 * The full result stays under the job and is fetched again with the `phone_result` tool;
 * the conversation only needs the shape of it: totals and counts always, the largest items
 * first. Every rule is pure and order-stable, so a replayed follow-up is byte-identical, and
 * a result whose canonical JSON already fits the budget is not summarised at all.
 */
import type { JsonValue } from "@earendil-works/chord";
import { canonicalJson } from "./canonical.ts";
import type { JobKind } from "./jobs.ts";

/** The most a summary may hold, in characters of its canonical JSON. */
export const PHONE_SUMMARY_MAX_CHARS = 4_000;
/** The generic fallback keeps this many items of any array. */
const GENERIC_ARRAY_CAP = 20;
/** A scalar string longer than this is clipped. */
const STRING_CAP = 300;

type Rec = Record<string, JsonValue>;

function isRec(value: unknown): value is Rec {
	return value !== null && typeof value === "object" && !Array.isArray(value);
}

function compareText(a: string, b: string): number {
	return a < b ? -1 : a > b ? 1 : 0;
}

function textField(row: JsonValue, key: string): string {
	const value = isRec(row) ? row[key] : undefined;
	return typeof value === "string" ? value : "";
}

function numberField(row: JsonValue, key: string, fallback = Number.NEGATIVE_INFINITY): number {
	const value = isRec(row) ? row[key] : undefined;
	return typeof value === "number" && Number.isFinite(value) ? value : fallback;
}

function bytesOf(row: JsonValue): number {
	if (!isRec(row)) return 0;
	for (const key of ["sizeBytes", "bytes", "totalBytes"]) {
		const value = row[key];
		if (typeof value === "number" && Number.isFinite(value)) return value;
	}
	return numberField(row, "appBytes", 0) + numberField(row, "dataBytes", 0) + numberField(row, "cacheBytes", 0);
}

function clipScalar(value: JsonValue): JsonValue {
	return typeof value === "string" && value.length > STRING_CAP ? `${value.slice(0, STRING_CAP)}…` : value;
}

/** Scalars kept; arrays cut to their first GENERIC_ARRAY_CAP items with an `<key>Count`. */
function digestGeneric(value: JsonValue): JsonValue {
	if (Array.isArray(value)) {
		return { items: value.slice(0, GENERIC_ARRAY_CAP).map(digestGeneric), itemsCount: value.length };
	}
	if (isRec(value)) {
		const out: Rec = {};
		for (const key of Object.keys(value).sort()) {
			const child = value[key];
			if (Array.isArray(child)) {
				out[key] = child.slice(0, GENERIC_ARRAY_CAP).map(digestGeneric);
				out[`${key}Count`] = child.length;
			} else if (isRec(child)) {
				out[key] = digestGeneric(child);
			} else {
				out[key] = clipScalar(child);
			}
		}
		return out;
	}
	return clipScalar(value);
}

type ArrayRule = (rows: JsonValue[]) => JsonValue[];

/** The top level of a result: each named array gets its own rule, everything else generic. */
function digestTop(data: JsonValue, rules: Record<string, ArrayRule>): JsonValue {
	if (!isRec(data)) return digestGeneric(data);
	const out: Rec = {};
	for (const key of Object.keys(data).sort()) {
		const child = data[key];
		if (Array.isArray(child)) {
			const rule = rules[key];
			out[key] = (rule === undefined ? child.slice(0, GENERIC_ARRAY_CAP) : rule(child)).map(digestGeneric);
			out[`${key}Count`] = child.length;
		} else if (isRec(child)) {
			out[key] = digestGeneric(child);
		} else {
			out[key] = clipScalar(child);
		}
	}
	return out;
}

function sortByBytesDesc(rows: JsonValue[], key?: string): JsonValue[] {
	const cast = rows.slice();
	cast.sort((a, b) => {
		const delta = (key === undefined ? bytesOf(b) : numberField(b, key, 0)) - (key === undefined ? bytesOf(a) : numberField(a, key, 0));
		if (delta !== 0) return delta;
		return compareText(textField(a, "path") || textField(a, "folderKey") || textField(a, "packageName"), textField(b, "path") || textField(b, "folderKey") || textField(b, "packageName"));
	});
	return cast;
}

/** The 25 largest apps (app + data + cache), ties by package name. */
function largestApps(rows: JsonValue[], cap = 25): JsonValue[] {
	return sortByBytesDesc(rows).slice(0, cap);
}

/** Never-used apps first, then the least recently used ones (A11.2). */
function usageOrderedApps(rows: JsonValue[], cap = 25): JsonValue[] {
	const never = rows.filter((row) => !isRec(row) || row.lastUsedMs === null || row.lastUsedMs === undefined);
	const used = rows.filter((row) => isRec(row) && typeof row.lastUsedMs === "number");
	never.sort((a, b) => compareText(textField(a, "packageName"), textField(b, "packageName")));
	used.sort((a, b) => numberField(a, "lastUsedMs", 0) - numberField(b, "lastUsedMs", 0) || compareText(textField(a, "packageName"), textField(b, "packageName")));
	return [...never, ...used].slice(0, cap);
}

function rulesFor(kind: JobKind): Record<string, ArrayRule> {
	switch (kind) {
		case "storage_overview":
			return { apps: (rows) => largestApps(rows) };
		case "app_usage":
			return { apps: (rows) => usageOrderedApps(rows) };
		case "media_index":
			return {
				folders: (rows) => sortByBytesDesc(rows, "bytes").slice(0, 50),
				largest: (rows) => rows.slice(0, 25),
				items: (rows) => rows.slice(0, 60),
				topLevelFiles: (rows) => rows.slice(0, 20),
				oldApks: (rows) => rows.slice(0, 20),
			};
		case "files_query":
			return {
				entries: (rows) => sortByBytesDesc(rows, "sizeBytes").slice(0, 60),
				items: (rows) => sortByBytesDesc(rows, "sizeBytes").slice(0, 60),
				paths: (rows) => sortByBytesDesc(rows, "bytes").slice(0, 60),
			};
		case "shell_info":
			return { packages: (rows) => rows.slice(0, 60), apps: (rows) => largestApps(rows) };
		case "rule_batch":
			return { items: (rows) => rows.slice(0, 60) };
		case "phone_selftest":
			return { checks: (rows) => rows.slice(0, 20) };
		default:
			return {};
	}
}

/**
 * A key whose value carries a total or a count, kept longest when keys must be pruned. `…Count`
 * fields describe the whole result, so they stay meaningful even when their array was cut.
 */
function isPriorityKey(key: string): boolean {
	const lower = key.toLowerCase();
	return lower.endsWith("count") || lower.includes("total") || lower === "truncated";
}

/**
 * Halve every array and every object's non-priority keys. Deterministic and monotone (the
 * result is never longer than its input unless nothing can be cut), so the loop in `fitToBudget`
 * always makes progress. Priority keys (`…Count`, total fields) are never dropped by this pass.
 */
function shrink(value: JsonValue): JsonValue {
	if (Array.isArray(value)) return value.slice(0, Math.ceil(value.length / 2)).map(shrink);
	if (isRec(value)) {
		const keys = Object.keys(value).sort();
		const priority = keys.filter(isPriorityKey);
		const rest = keys.filter((key) => !isPriorityKey(key));
		const out: Rec = {};
		for (const key of [...priority, ...rest.slice(0, Math.ceil(rest.length / 2))]) out[key] = shrink(value[key]);
		return out;
	}
	return value;
}

/** Clip every string, values and key names alike, to `cap` characters. */
function clipAllStrings(value: JsonValue, cap: number): JsonValue {
	if (typeof value === "string") return value.length <= cap ? value : `${value.slice(0, cap)}…`;
	if (Array.isArray(value)) return value.map((item) => clipAllStrings(item, cap));
	if (isRec(value)) {
		const out: Rec = {};
		const used = new Set<string>();
		for (const key of Object.keys(value).sort()) {
			let name = key.length <= cap ? key : `${key.slice(0, cap)}…`;
			// Deterministic collision handling: the first key in sorted order keeps the name.
			while (used.has(name)) name = `${name}~`;
			used.add(name);
			out[name] = clipAllStrings(value[key], cap);
		}
		return out;
	}
	return value;
}

/** The last resort: a shape the model can act on, and nothing else. */
function minimalShape(value: JsonValue): JsonValue {
	const keys = Array.isArray(value) ? value.length : isRec(value) ? Object.keys(value).length : 1;
	return { truncated: true, keys, note: "use phone_result" };
}

/**
 * Bring any digest inside PHONE_SUMMARY_MAX_CHARS. Arrays and object keys are halved first,
 * then every string (values and key names) is clipped, and as a last resort the value is
 * replaced by a minimal deterministic shape. The cap is therefore absolute for any JSON the
 * phone can send, however key-dominated or deeply nested (correction 1, review finding 1).
 */
function fitToBudget(summary: JsonValue): JsonValue {
	let current = summary;
	for (let i = 0; i < 64 && canonicalJson(current).length > PHONE_SUMMARY_MAX_CHARS; i++) {
		const next = shrink(current);
		if (canonicalJson(next).length >= canonicalJson(current).length) break;
		current = next;
	}
	if (canonicalJson(current).length > PHONE_SUMMARY_MAX_CHARS) current = clipAllStrings(current, 64);
	if (canonicalJson(current).length > PHONE_SUMMARY_MAX_CHARS) current = minimalShape(current);
	return current;
}

/**
 * The deterministic digest of one phone result, at most PHONE_SUMMARY_MAX_CHARS characters
 * of canonical JSON. `kind` picks the per-kind rule; an unknown kind gets the generic one.
 */
export function summarisePhoneResult(kind: JobKind, data: JsonValue): JsonValue {
	return fitToBudget(digestTop(data, rulesFor(kind)));
}
