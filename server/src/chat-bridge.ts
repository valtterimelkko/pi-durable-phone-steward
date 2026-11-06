/**
 * Bridges committed conversation activity into the chat event store (docs/protocol.md §6):
 * user/assistant messages and tool results become `message` and `tool` SSE events. The
 * steward events that the core writes inside its own commits (run, proposal, job) are
 * stored there directly; this bridge covers what Pi Durable's generation task commits.
 *
 * The bridge is a view: it records what it sees after the fact, so a crash can lose an SSE
 * event (the durable record of a run is the worklog mirror and the transcript, not SSE).
 *
 * Pi Durable 1.5.0 hands the listener one tagged call and a windowed view, so two things
 * changed here: the call is read only when `kind === "value"`, and a window splice is paged
 * back with `missedEntries()` so no entry is silently skipped while a backlog builds up.
 */
import { BACKGROUND_CONTEXT } from "@earendil-works/chord/context";
import type { Conversation, EntryRecord } from "@earendil-works/pi-durable";
import { appendChatEvent } from "./chat-events.ts";
import { modelFieldOf } from "./model-failure.ts";
import { stripServerNote } from "./server-note.ts";
import { callIdOf, summariseToolArgs, summariseToolResult, toolResultText } from "./chat-tool-summary.ts";
import { missedEntries } from "./watched-entries.ts";

type BridgeState = {
	seen: Set<string>;
	/** Newest entry id handled, so a window splice can be paged back (`watched-entries.ts`). */
	newest: number | undefined;
	stopped: boolean;
	/** Open tool calls by call id (A8): when each started and its argument summary. */
	calls: Map<string, { atMs: number; argsSummary: string }>;
};

const MAX_OPEN_CALLS = 200;

export function startChatBridge(conversation: Conversation): { stop(): void } {
	const context = BACKGROUND_CONTEXT;
	const state: BridgeState = { seen: new Set(), newest: undefined, stopped: false, calls: new Map() };

	/** One committed entry → its Chat events. */
	const handle = async (entry: EntryRecord): Promise<void> => {
		const message = entry.model?.[0];
		if (message === undefined) return;
		const atMs = Date.now();
		if (message.role === "user") {
			// S9 (A9.2): the server note is for the model only; the chat log shows what the owner typed.
			const text = stripServerNote(typeof message.content === "string" ? message.content : "");
			// A10.9: a fresh-start brief is all server note; it is not something the owner typed.
			if (text === "") return;
			await conversation.commit((tx) => appendChatEvent(tx, "message", { role: "user", text, atMs }, atMs), context);
		} else if (message.role === "assistant") {
			const text = message.content
				.map((part) => (part.type === "text" && typeof part.text === "string" ? part.text : ""))
				.join("");
			// A15.10: the message names the model that actually produced it (its own fields).
			const model = modelFieldOf(message);
			await conversation.commit(
				(tx) => appendChatEvent(tx, "message", { role: "assistant", text, ...(model === undefined ? {} : { model }), atMs }, atMs),
				context,
			);
			for (const part of message.content) {
				if (part.type === "toolCall") {
					const argsSummary = summariseToolArgs(part.name, (part as { arguments?: unknown }).arguments);
					const callId = callIdOf((part as { id?: unknown }).id);
					if (callId !== undefined) {
						state.calls.set(callId, { atMs, argsSummary });
						if (state.calls.size > MAX_OPEN_CALLS) {
							const oldest = state.calls.keys().next().value;
							if (oldest !== undefined) state.calls.delete(oldest);
						}
					}
					await conversation.commit(
						(tx) =>
							appendChatEvent(
								tx,
								"tool",
								{ name: part.name, status: "started", ...(callId !== undefined ? { callId } : {}), argsSummary, atMs },
								atMs,
							),
						context,
					);
				}
			}
		} else if (message.role === "toolResult") {
			const name = (message as { toolName?: string }).toolName ?? "tool";
			const isError = (message as { isError?: boolean }).isError === true;
			const callId = callIdOf((message as { toolCallId?: unknown }).toolCallId);
			const call = callId !== undefined ? state.calls.get(callId) : undefined;
			if (callId !== undefined) state.calls.delete(callId);
			const resultSummary = summariseToolResult(name, toolResultText(message), isError);
			const durationMs = call !== undefined ? Math.max(0, atMs - call.atMs) : undefined;
			await conversation.commit(
				(tx) =>
					appendChatEvent(
						tx,
						"tool",
						{
							name,
							status: isError ? "error" : "done",
							...(callId !== undefined ? { callId } : {}),
							...(call !== undefined ? { argsSummary: call.argsSummary } : {}),
							resultSummary,
							...(durationMs !== undefined ? { durationMs } : {}),
							atMs,
						},
						atMs,
					),
				context,
			);
		}
	};

	/** Handle entries in id order, skipping (and recording) the ones already mirrored. */
	const handleAll = async (entries: readonly EntryRecord[]): Promise<void> => {
		for (const entry of entries) {
			if (state.stopped) return;
			const entryKey = String(entry.id);
			if (state.seen.has(entryKey)) continue;
			state.seen.add(entryKey);
			state.newest = Math.max(state.newest ?? 0, Number(entry.id));
			await handle(entry);
		}
	};

	void (async () => {
		const watch = await conversation.watch(context);
		// Skip the history: events already in the store cover what happened before.
		for (const entry of watch.value.entries) {
			state.seen.add(String(entry.id));
			state.newest = Math.max(state.newest ?? 0, Number(entry.id));
		}
		watch.start((call) => {
			if (state.stopped || call.kind !== "value") return Promise.resolve();
			return (async () => {
				// The window may have spliced past entries this listener has not seen yet.
				await handleAll(await missedEntries(conversation, state.newest, call.value, context));
				await handleAll(call.value.entries);
			})().catch(() => undefined);
		});
	})().catch(() => undefined);
	return {
		stop() {
			state.stopped = true;
		},
	};
}
