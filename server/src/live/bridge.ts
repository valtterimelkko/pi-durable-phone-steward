/**
 * A13.3: bridges a live session's conversation into the chat event store: the session
 * model's own text becomes a `phone_use` `say` event (S13-masked; capped). Step, ask and
 * ended events are written by the code that performs them (`answerLiveStep`,
 * `queueAskTx`, `endSessionTx`); this bridge covers only the model's prose, which no
 * other server path sees.
 *
 * A view like `chat-bridge.ts`: it records what it sees after the fact, so a crash can
 * lose an event; the durable record of a session is the session document.
 *
 * Pi Durable 1.5.0 hands the listener one tagged call and a windowed view, so this bridge
 * reads the call only when `kind === "value"` and pages a window splice back with
 * `missedEntries()` — a session's prose must not be silently dropped either.
 */
import { BACKGROUND_CONTEXT } from "@earendil-works/chord/context";
import type { Conversation, EntryRecord } from "@earendil-works/pi-durable";
import { appendChatEvent } from "../chat-events.ts";
import { missedEntries } from "../watched-entries.ts";
import { redactCodes } from "./tree.ts";

/** The most text one say event carries. */
export const MAX_SAY_TEXT = 1000;

const liveBridges = new Map<string, { stop(): void }>();

export function startLiveChatBridge(conversation: Conversation, sessionId: string): void {
	stopLiveChatBridge(sessionId);
	const context = BACKGROUND_CONTEXT;
	const seen = new Set<string>();
	let newest: number | undefined;
	let stopped = false;

	/** One committed entry → the session model's prose, if it has any. */
	const handle = async (entry: EntryRecord): Promise<void> => {
		const message = entry.model?.[0];
		if (message === undefined || message.role !== "assistant") return;
		const text = message.content
			.map((part) => (part.type === "text" && typeof part.text === "string" ? part.text : ""))
			.join("")
			.trim();
		if (text === "") return;
		const atMs = Date.now();
		// S13: a one-time code never leaves the session, even in the model's own words.
		await conversation.commit(
			(tx) => appendChatEvent(tx, "phone_use", { kind: "say", sessionId, text: redactCodes(text).slice(0, MAX_SAY_TEXT), atMs }, atMs),
			context,
		);
	};

	const handleAll = async (entries: readonly EntryRecord[]): Promise<void> => {
		for (const entry of entries) {
			if (stopped) return;
			const key = String(entry.id);
			if (seen.has(key)) continue;
			seen.add(key);
			newest = Math.max(newest ?? 0, Number(entry.id));
			await handle(entry);
		}
	};

	void (async () => {
		const watch = await conversation.watch(context);
		for (const entry of watch.value.entries) {
			seen.add(String(entry.id));
			newest = Math.max(newest ?? 0, Number(entry.id));
		}
		watch.start((call) => {
			if (stopped || call.kind !== "value") return Promise.resolve();
			return (async () => {
				await handleAll(await missedEntries(conversation, newest, call.value, context));
				await handleAll(call.value.entries);
			})().catch(() => undefined);
		});
	})().catch(() => undefined);
	liveBridges.set(sessionId, {
		stop() {
			stopped = true;
		},
	});
}

export function stopLiveChatBridge(sessionId: string): void {
	liveBridges.get(sessionId)?.stop();
	liveBridges.delete(sessionId);
}
