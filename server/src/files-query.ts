/**
 * Protocol A10.1, server half: the phone's file facts and what the steward may propose to
 * delete. Mirrors `media-index.ts` (A9.1): a `delete_files` proposal target is completed
 * from the newest `files_query` result the phone reported, never from what the model typed,
 * because the app re-stats the path and refuses to act when size, count or modified time
 * differ (the A6 identity rule). The refusals are enforced here in code, not in the prompt.
 */
import type { JsonObject, ConversationId, Tx } from "@earendil-works/pi-durable";
import { badRequest } from "./errors.ts";
import { Jobs } from "./jobs.ts";
import type { ProposalItemInput } from "./proposals.ts";

/** One file or folder as the phone reports it (A10.1 `FileEntry`). */
export type FileEntry = {
	path: string;
	type: "file" | "dir";
	sizeBytes: number;
	modifiedMs?: number;
	/** Recursive file count; present for a dir only. */
	fileCount?: number;
	/** The phone could not read it (Android/data and Android/obb on Android 13). */
	unreadable?: boolean;
};

function badPath(detail: string): never {
	throw badRequest(`BAD_PATH: ${detail}`);
}

/**
 * A path relative to the primary shared-storage root. `""` is the root. Empty segments and
 * `.` are dropped; an absolute path, `..`, a backslash or a NUL is refused.
 */
export function normaliseRelPath(raw: unknown): string {
	if (typeof raw !== "string") return badPath("a path must be a string");
	if (raw.includes("\0") || raw.includes("\\")) return badPath("a path may not contain backslashes or NUL");
	if (raw.startsWith("/")) return badPath("paths are relative to the shared storage root, not absolute");
	const segments: string[] = [];
	for (const segment of raw.split("/")) {
		if (segment === "" || segment === ".") continue;
		if (segment === "..") return badPath("a path may not contain ..");
		segments.push(segment);
	}
	return segments.join("/");
}

function isObject(value: unknown): value is Record<string, unknown> {
	return value !== null && typeof value === "object" && !Array.isArray(value);
}

function intInRange(value: unknown, name: string, min: number, max: number, fallback: number): number {
	if (value === undefined || value === null) return fallback;
	if (typeof value !== "number" || !Number.isInteger(value) || value < min || value > max) {
		throw badRequest(`${name} must be a whole number from ${min} to ${max}`);
	}
	return value;
}

export const FILES_MAX_PATHS = 20;
export const FILES_MAX_LIMIT = 500;

/**
 * Validate and normalise the `phone_files` arguments into the job's `params` (A10.1).
 * Throws a plain `badRequest` (a tool error the model reads) naming the field.
 */
export function validateFilesQuery(args: unknown): JsonObject {
	if (!isObject(args)) throw badRequest("op is required: list, find or du");
	switch (args.op) {
		case "list":
			return { op: "list", path: normaliseRelPath(args.path ?? ""), depth: intInRange(args.depth, "depth", 1, 3, 1) };
		case "find": {
			const pattern = args.namePattern;
			if (typeof pattern !== "string" || pattern.trim().length === 0 || pattern.length > 200) {
				throw badRequest("namePattern is required (a glob on the file name, for example *.mwm; at most 200 characters)");
			}
			const type = args.type ?? "any";
			if (type !== "file" && type !== "dir" && type !== "any") throw badRequest("type must be file, dir or any");
			const minBytes = args.minBytes ?? 0;
			if (typeof minBytes !== "number" || !Number.isFinite(minBytes) || minBytes < 0) throw badRequest("minBytes must be a number of bytes, 0 or more");
			const older = args.olderThanDays ?? null;
			if (older !== null && (typeof older !== "number" || !Number.isFinite(older) || older < 0)) {
				throw badRequest("olderThanDays must be a number of days, or left out");
			}
			return {
				op: "find",
				root: normaliseRelPath(args.root ?? ""),
				namePattern: pattern,
				type,
				minBytes,
				olderThanDays: older,
				limit: intInRange(args.limit, "limit", 1, FILES_MAX_LIMIT, 200),
			};
		}
		case "du": {
			const paths = args.paths;
			if (!Array.isArray(paths) || paths.length === 0 || paths.length > FILES_MAX_PATHS) {
				throw badRequest(`paths must be a list of 1 to ${FILES_MAX_PATHS} relative paths`);
			}
			return { op: "du", paths: paths.map((path) => normaliseRelPath(path)) };
		}
		default:
			throw badRequest("op must be list, find or du");
	}
}

/**
 * Why a path may never be proposed for deletion (A10.1), or `undefined` when it may.
 * Refused: the root, `Android/` and anything under it, `.StewardTrash/` and anything under it,
 * any path with a segment named `Keep` (any case), and the media folders `DCIM/` and
 * `Pictures/` (photos and videos go through `trash_media`).
 */
export function refusedDeleteReason(path: string): string | undefined {
	if (path === "") return "the storage root can never be deleted";
	const segments = path.split("/");
	const first = segments[0]!.toLowerCase();
	if (first === "android") return "Android/ and everything under it belongs to the system and the apps; it is never deleted from here";
	if (first === ".stewardtrash") return "the Steward trash is emptied by the owner or automatically, never through a proposal";
	if (segments.some((segment) => segment.toLowerCase() === "keep")) return "a path with a Keep segment is protected and never proposed";
	if (first === "dcim" || first === "pictures") return "photos and videos go through trash_media (they use Android's trash), not delete_files";
	return undefined;
}

function toEntry(row: Record<string, unknown>): Partial<FileEntry> | undefined {
	if (typeof row.path !== "string") return undefined;
	let path: string;
	try {
		path = normaliseRelPath(row.path);
	} catch {
		return undefined;
	}
	const out: Partial<FileEntry> = { path };
	if (row.type === "file" || row.type === "dir") out.type = row.type;
	if (typeof row.sizeBytes === "number" && Number.isFinite(row.sizeBytes)) out.sizeBytes = row.sizeBytes;
	if (typeof row.modifiedMs === "number" && Number.isFinite(row.modifiedMs)) out.modifiedMs = row.modifiedMs;
	if (typeof row.fileCount === "number" && Number.isFinite(row.fileCount)) out.fileCount = row.fileCount;
	if (row.unreadable === true) out.unreadable = true;
	return out;
}

/**
 * Every file or folder the phone has reported through `files_query`, keyed by normalised
 * path. Reports are applied oldest first, so the newest report wins field by field: a `du`
 * row (size and counts, no modified time) refreshes the size of a path an older `list` row
 * described, and keeps that row's `type` and `modifiedMs`. A `du` row with no known type is
 * guessed from its counts. Only results with status `ok` or `partial` count. Returns copies.
 */
export async function reportedFiles(tx: Tx, conversationId: ConversationId): Promise<Map<string, FileEntry>> {
	const doc = await tx.doc(Jobs, conversationId);
	const reported = Object.values(doc.jobs)
		.filter((job) => job.kind === "files_query" && job.result !== undefined && (job.result.status === "ok" || job.result.status === "partial"))
		.sort((a, b) => (a.result?.reportedAtMs ?? 0) - (b.result?.reportedAtMs ?? 0));
	const rows = new Map<string, Partial<FileEntry>>();
	const duCounts = new Map<string, { files: number; dirs: number }>();
	for (const job of reported) {
		const data = job.result?.data as { entries?: unknown; items?: unknown; paths?: unknown } | null | undefined;
		for (const list of [data?.entries, data?.items]) {
			if (!Array.isArray(list)) continue;
			for (const raw of list) {
				const row = isObject(raw) ? toEntry(raw) : undefined;
				if (row?.path === undefined) continue;
				rows.set(row.path, { ...(rows.get(row.path) ?? {}), ...row });
				duCounts.delete(row.path);
			}
		}
		if (Array.isArray(data?.paths)) {
			for (const raw of data.paths) {
				if (!isObject(raw) || typeof raw.path !== "string") continue;
				const row = toEntry({
					path: raw.path,
					type: raw.type,
					sizeBytes: raw.bytes,
					modifiedMs: raw.modifiedMs,
					fileCount: raw.files,
					unreadable: raw.unreadable,
				});
				if (row?.path === undefined) continue;
				rows.set(row.path, { ...(rows.get(row.path) ?? {}), ...row });
				if (typeof raw.files === "number" && typeof raw.dirs === "number") duCounts.set(row.path, { files: raw.files, dirs: raw.dirs });
			}
		}
	}
	const out = new Map<string, FileEntry>();
	for (const [path, row] of rows) {
		if (typeof row.sizeBytes !== "number") continue;
		const counts = duCounts.get(path);
		const guessed: "file" | "dir" = counts !== undefined && counts.dirs === 0 && counts.files <= 1 ? "file" : "dir";
		const type = row.type ?? guessed;
		const entry: FileEntry = { path, type, sizeBytes: row.sizeBytes };
		if (row.modifiedMs !== undefined) entry.modifiedMs = row.modifiedMs;
		if (type === "dir" && row.fileCount !== undefined) entry.fileCount = row.fileCount;
		if (row.unreadable === true) entry.unreadable = true;
		out.set(path, entry);
	}
	return out;
}

/** True when `inner` is the same path as, or lies inside, `outer`. */
function isInside(inner: string, outer: string): boolean {
	return inner === outer || inner.startsWith(`${outer}/`);
}

/**
 * Complete and check the `delete_files` items of a proposal about to be written (inside the
 * caller's commit). Every target becomes the full `FileEntry` of the newest reported row
 * with that path (model-supplied fields lose to the phone's); a missing `bytesEstimate`
 * becomes the row's `sizeBytes`. The refusals of `refusedDeleteReason` always apply.
 *
 * `strict` (the model's proposals, source `agent`): a path in no reported `files_query`
 * result is refused with an error naming it and `phone_files`, and so is an unreadable row.
 * A proposal may not list a path twice or a path inside another listed path. Other item
 * kinds pass through.
 */
export async function completeDeleteFiles(
	tx: Tx,
	conversationId: ConversationId,
	items: ProposalItemInput[],
	strict: boolean,
): Promise<ProposalItemInput[]> {
	if (!items.some((item) => item.kind === "delete_files")) return items;
	const index = await reportedFiles(tx, conversationId);
	const seen: string[] = [];
	const out = items.map((item) => {
		if (item.kind !== "delete_files") return item;
		const raw = (item.target as { path?: unknown } | null | undefined)?.path;
		if (typeof raw !== "string") throw badRequest("delete_files items need target.path");
		const path = normaliseRelPath(raw);
		const refusal = refusedDeleteReason(path);
		if (refusal !== undefined) throw badRequest(`delete_files item "${path}" is refused: ${refusal}.`);
		for (const earlier of seen) {
			if (earlier === path) throw badRequest(`delete_files item "${path}" is listed twice.`);
			if (isInside(path, earlier) || isInside(earlier, path)) {
				throw badRequest(`delete_files items "${earlier}" and "${path}" overlap: one lies inside the other. List only the outer path.`);
			}
		}
		seen.push(path);
		const row = index.get(path);
		if (row === undefined) {
			if (!strict) return { ...item, target: { ...(item.target as object), path } };
			throw badRequest(
				`delete_files item "${path}" is in no files_query result the phone has reported, so the phone could not verify it. Run phone_files (list, find or du) and propose from the paths it returns.`,
			);
		}
		if (strict && row.unreadable === true) {
			throw badRequest(`delete_files item "${path}" was reported unreadable by the phone, so it cannot be moved to the Steward trash.`);
		}
		const bytesEstimate = typeof item.bytesEstimate === "number" ? item.bytesEstimate : row.sizeBytes;
		return { ...item, target: row, bytesEstimate };
	});
	return out;
}
