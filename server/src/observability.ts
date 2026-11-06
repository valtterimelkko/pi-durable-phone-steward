/**
 * Observability : pure formatting shared by `bin/steward` in both its
 * modes — the admin socket routes (`/admin/chat`, `/admin/snapshot`, `/admin/usage`) and
 * direct-state reads of a stopped instance. Everything here is read-only: entries and
 * documents in, plain data out. Thinking content is never included; tool arguments and
 * phone result data are truncated, never a log line or an owner-facing message.
 */
import type { JsonValue } from "@earendil-works/chord";
import type { EntryRecord } from "@earendil-works/pi-durable";
import type { Snapshot } from "./documents.ts";
import { stripServerNote } from "./server-note.ts";

const ARGUMENT_CHARS = 300;
const DATA_CHARS = 400;

/** One JSON value as text, truncated with an ellipsis marker. */
export function truncatedJson(value: unknown, maxChars: number): string {
	const text = JSON.stringify(value ?? null) ?? "null";
	return text.length <= maxChars ? text : `${text.slice(0, maxChars)}…(truncated)`;
}

export type ConversationLine =
	| { type: "message"; role: "owner" | "agent"; atMs: number | undefined; text: string; serverNote?: true }
	| { type: "tool_call"; atMs: number | undefined; name: string; arguments: string }
	| { type: "tool_result"; atMs: number | undefined; name: string; isError: boolean }
	| { type: "phone_result"; atMs: number | undefined; jobId: string; kind: string; status: string; data: string }
	| { type: "phone_expired"; atMs: number | undefined; jobId: string; kind: string };

const PHONE_RESULT = /^\[phone result (pj-[0-9a-f]+)\] kind=(\w+) status=(\w+)(?: error=\S+)? ?(.*)$/s;
const PHONE_EXPIRED = /^\[phone expired (pj-[0-9a-f]+)\] kind=(\w+) status=expired$/;

/**
 * The conversation in order, oldest last: owner and agent messages as text, tool calls
 * with (truncated) arguments, and phone results with job id, kind, status and truncated
 * data. Thinking is omitted. `last` bounds the output to the newest N lines.
 */
export function formatConversation(entries: readonly EntryRecord[], last: number): ConversationLine[] {
	const lines: ConversationLine[] = [];
	for (const entry of entries) {
		for (const message of entry.model ?? []) {
			const atMs = (message as { timestamp?: number }).timestamp;
			if (message.role === "user") {
				const raw = typeof message.content === "string" ? message.content : "";
				// S9 (A9.2): show what the owner typed; `serverNote` marks that the model also got the note.
				const text = stripServerNote(raw);
				const phoneResult = PHONE_RESULT.exec(text);
				if (phoneResult !== null) {
					lines.push({
						type: "phone_result",
						atMs,
						jobId: phoneResult[1]!,
						kind: phoneResult[2]!,
						status: phoneResult[3]!,
						data: phoneResult[4]!.slice(0, DATA_CHARS),
					});
					continue;
				}
				const phoneExpired = PHONE_EXPIRED.exec(text);
				if (phoneExpired !== null) {
					lines.push({ type: "phone_expired", atMs, jobId: phoneExpired[1]!, kind: phoneExpired[2]! });
					continue;
				}
				lines.push({ type: "message", role: "owner", atMs, text: text.slice(0, DATA_CHARS), ...(text.length < raw.length ? { serverNote: true as const } : {}) });
				continue;
			}
			if (message.role === "assistant") {
				const text = message.content
					.filter((part): part is { type: "text"; text: string } => part.type === "text")
					.map((part) => part.text)
					.join("");
				if (text.length > 0) lines.push({ type: "message", role: "agent", atMs, text });
				for (const part of message.content) {
					if (part.type === "toolCall") {
						lines.push({
							type: "tool_call",
							atMs,
							name: part.name,
							arguments: truncatedJson((part as { arguments?: unknown }).arguments, ARGUMENT_CHARS),
						});
					}
					// Thinking parts are deliberately omitted.
				}
				continue;
			}
			if (message.role === "toolResult") {
				const named = message as { toolName?: string; isError?: boolean };
				lines.push({ type: "tool_result", atMs, name: named.toolName ?? "tool", isError: named.isError === true });
			}
		}
	}
	return lines.slice(-Math.max(1, last));
}

export type SnapshotSummary = {
	capturedAtMs: number;
	totalBytes: number;
	freeBytes: number;
	/** The 10 largest apps by total size (app+data+cache). */
	topAppsBySize: Array<{ packageName: string; label: string; totalBytes: number; cacheBytes: number }>;
	/** The 10 largest caches. */
	topAppsByCache: Array<{ packageName: string; label: string; cacheBytes: number }>;
	categories: Snapshot["categories"];
};

/** The latest snapshot, summarised for an engineer (O3). */
export function summariseSnapshot(snapshot: Snapshot | undefined): SnapshotSummary | { status: "no-snapshot" } {
	if (snapshot === undefined) return { status: "no-snapshot" };
	const bySize = [...snapshot.apps]
		.map((app) => ({
			packageName: app.packageName,
			label: app.label,
			totalBytes: app.appBytes + app.dataBytes + app.cacheBytes,
			cacheBytes: app.cacheBytes,
		}))
		.sort((a, b) => b.totalBytes - a.totalBytes)
		.slice(0, 10);
	const byCache = [...snapshot.apps]
		.map((app) => ({ packageName: app.packageName, label: app.label, cacheBytes: app.cacheBytes }))
		.sort((a, b) => b.cacheBytes - a.cacheBytes)
		.slice(0, 10);
	return {
		capturedAtMs: snapshot.capturedAtMs,
		totalBytes: snapshot.totalBytes,
		freeBytes: snapshot.freeBytes,
		topAppsBySize: bySize,
		topAppsByCache: byCache,
		categories: snapshot.categories,
	};
}

export type UsageDay = { day: string; inputTokens: number; outputTokens: number; cacheReadTokens: number; calls: number };

/**
 * Model tokens per day (O3), from what the harness records on assistant messages.
 * Days are UTC dates of the message timestamps.
 */
export function usagePerDay(entries: readonly EntryRecord[]): UsageDay[] {
	const days = new Map<string, UsageDay>();
	for (const entry of entries) {
		for (const message of entry.model ?? []) {
			if (message.role !== "assistant") continue;
			const usage = (message as unknown as { usage?: { input?: number; output?: number; cacheRead?: number } }).usage;
			const timestamp = (message as { timestamp?: number }).timestamp;
			if (usage === undefined || timestamp === undefined) continue;
			const day = new Date(timestamp).toISOString().slice(0, 10);
			const bucket = days.get(day) ?? { day, inputTokens: 0, outputTokens: 0, cacheReadTokens: 0, calls: 0 };
			bucket.inputTokens += usage.input ?? 0;
			bucket.outputTokens += usage.output ?? 0;
			bucket.cacheReadTokens += usage.cacheRead ?? 0;
			bucket.calls += 1;
			days.set(day, bucket);
		}
	}
	return [...days.values()].sort((a, b) => a.day.localeCompare(b.day));
}

/** Phone-result lines a diagnostics answer must never produce (O6 test helper). */
export function hasConversationEntryFor(lines: readonly ConversationLine[], jobId: string): boolean {
	return lines.some(
		(line) => (line.type === "phone_result" && line.jobId === jobId) || (line.type === "phone_expired" && line.jobId === jobId),
	);
}

export type { JsonValue };
