/**
 * Safe one-line summaries of a tool call's arguments and result for the `tool` chat event
 * (docs/protocol.md A8: `argsSummary`, `resultSummary`). Built here, never by the model,
 * at most `TOOL_SUMMARY_LIMIT` characters, from counts, sizes, ids, kinds and statuses
 * only. File paths, media names, tokens and free text never pass through: unknown shapes
 * degrade to argument names, a status or a character count, and an error message is
 * redacted the way `diagnostics.ts` redacts a diagnostics detail.
 */
import { redactDetail } from "./diagnostics.ts";

export const TOOL_SUMMARY_LIMIT = 300;

type Loose = Record<string, unknown>;

function isObject(value: unknown): value is Loose {
	return value !== null && typeof value === "object" && !Array.isArray(value);
}

function cap(text: string): string {
	return text.length > TOOL_SUMMARY_LIMIT ? `${text.slice(0, TOOL_SUMMARY_LIMIT - 1)}…` : text;
}

function plural(count: number, one: string, many = `${one}s`): string {
	return `${count} ${count === 1 ? one : many}`;
}

/** Argument names that are short identifiers are safe to show; anything else is not. */
const NAME_RE = /^[A-Za-z_][A-Za-z0-9_]{0,31}$/;
/** A Pi tool-call id: short, id-shaped, safe to send. */
const CALL_ID_RE = /^[A-Za-z0-9_-]{1,64}$/;
/** An id the server minted (job, proposal): safe to show. */
const SAFE_ID_RE = /^[A-Za-z0-9_-]{1,64}$/;

export function callIdOf(id: unknown): string | undefined {
	return typeof id === "string" && CALL_ID_RE.test(id) ? id : undefined;
}

export function summariseToolArgs(name: string, args: unknown): string {
	if (!isObject(args) || Object.keys(args).length === 0) return "no arguments";
	switch (name) {
		case "phone_media_index": {
			const parts: string[] = [];
			if (typeof args.mode === "string" && /^[a-z_]{1,32}$/.test(args.mode)) parts.push(`mode ${args.mode}`);
			if (Array.isArray(args.folders)) parts.push(plural(args.folders.length, "folder"));
			if (typeof args.olderThanDays === "number" && Number.isFinite(args.olderThanDays)) {
				parts.push(`older than ${plural(Math.trunc(args.olderThanDays), "day")}`);
			}
			if (parts.length > 0) return cap(parts.join(", "));
			break;
		}
		case "phone_files": {
			// A10.1: the op, depth, kind and limits only; never a path, folder name or pattern.
			const parts: string[] = [];
			if (typeof args.op === "string" && /^[a-z_]{1,16}$/.test(args.op)) parts.push(`op ${args.op}`);
			if (typeof args.depth === "number" && Number.isFinite(args.depth)) parts.push(`depth ${Math.trunc(args.depth)}`);
			if (typeof args.type === "string" && /^[a-z]{1,8}$/.test(args.type)) parts.push(`type ${args.type}`);
			if (typeof args.minBytes === "number" && Number.isFinite(args.minBytes) && args.minBytes > 0) parts.push(`min ${Math.trunc(args.minBytes)} bytes`);
			if (typeof args.olderThanDays === "number" && Number.isFinite(args.olderThanDays)) parts.push(`older than ${plural(Math.trunc(args.olderThanDays), "day")}`);
			if (typeof args.limit === "number" && Number.isFinite(args.limit)) parts.push(`limit ${Math.trunc(args.limit)}`);
			if (Array.isArray(args.paths)) parts.push(plural(args.paths.length, "path"));
			if (parts.length > 0) return cap(parts.join(", "));
			break;
		}
		case "phone_shell_info":
			if (typeof args.op === "string" && /^[a-z_]{1,16}$/.test(args.op)) return `op ${args.op}`;
			break;
		case "phone_force_stop":
			return "1 package";
		case "decisions":
			if (typeof args.limit === "number" && Number.isFinite(args.limit)) return `limit ${Math.trunc(args.limit)}`;
			break;
		case "propose_cleanup": {
			if (Array.isArray(args.items)) {
				const counts = new Map<string, number>();
				for (const item of args.items) {
					const kind = isObject(item) && typeof item.kind === "string" && /^[a-z_]{1,32}$/.test(item.kind) ? item.kind : "other";
					counts.set(kind, (counts.get(kind) ?? 0) + 1);
				}
				const kinds = [...counts].map(([kind, count]) => `${count} ${kind}`).join(", ");
				return cap(`${plural(args.items.length, "item")}${kinds ? `: ${kinds}` : ""}`);
			}
			break;
		}
		default:
			break;
	}
	const names = Object.keys(args).filter((key) => NAME_RE.test(key));
	return names.length === 0 ? "arguments" : cap(`arguments: ${names.join(", ")}`);
}

function parseJson(text: string): unknown {
	try {
		return JSON.parse(text);
	} catch {
		return undefined;
	}
}

function ageText(ageMs: number): string {
	const hours = Math.floor(ageMs / 3_600_000);
	if (hours >= 48) return `${Math.floor(hours / 24)} d`;
	if (hours >= 1) return `${hours} h`;
	return `${Math.max(0, Math.floor(ageMs / 60_000))} min`;
}

export function summariseToolResult(name: string, resultText: string, isError: boolean): string {
	if (isError) {
		const redacted = redactDetail(resultText.replace(/\s+/g, " ").trim()).replace(/^error:\s*/i, "");
		return cap(`error: ${redacted}`);
	}
	const data = parseJson(resultText);
	if (isObject(data)) {
		switch (name) {
			case "phone_storage_overview":
			case "phone_app_usage":
			case "phone_media_index":
			case "phone_files":
			case "phone_shell_info":
			case "phone_force_stop":
				if (typeof data.jobId === "string" && SAFE_ID_RE.test(data.jobId)) {
					return `${typeof data.status === "string" && /^[a-z_-]{1,24}$/.test(data.status) ? data.status : "queued"} job ${data.jobId}`;
				}
				break;
			case "propose_cleanup":
				if (typeof data.proposalId === "string" && SAFE_ID_RE.test(data.proposalId)) {
					const replaced = Array.isArray(data.superseded) ? data.superseded.length : 0;
					return cap(
						`proposal ${data.proposalId} written${replaced > 0 ? `, replaced ${plural(replaced, "earlier proposal")}` : ""}`,
					);
				}
				break;
			case "decisions":
				if (Array.isArray(data.entries)) return data.entries.length === 0 ? "no entries yet" : plural(data.entries.length, "entry", "entries");
				break;
			case "proposals":
				if (Array.isArray(data.openAndApproved)) {
					const closed = Array.isArray(data.recentClosed) ? data.recentClosed.length : 0;
					return `${data.openAndApproved.length} open, ${closed} recently closed`;
				}
				break;
			case "phone_snapshot":
				if (data.status === "no-snapshot") return "no snapshot yet";
				if (typeof data.ageMs === "number" && Number.isFinite(data.ageMs)) return `snapshot ${ageText(data.ageMs)} old`;
				break;
			case "storage_trend":
				if (data.status === "ok") return "trend available";
				if (data.status === "not-enough-snapshots") return "not enough snapshots yet";
				break;
			case "rules":
				if (data.status === "ok") return "rules read";
				break;
			default:
				break;
		}
		if (typeof data.status === "string" && /^[A-Za-z_-]{1,24}$/.test(data.status)) return `status ${data.status}`;
	}
	return `${resultText.length} characters`;
}

/** The text of a Pi `toolResult` message: its text parts joined, or a plain string. */
export function toolResultText(message: { content?: unknown; [key: string]: unknown }): string {
	const content = message.content;
	if (typeof content === "string") return content;
	if (!Array.isArray(content)) return "";
	return content
		.map((part) => (isObject(part) && part.type === "text" && typeof part.text === "string" ? part.text : ""))
		.join("");
}
