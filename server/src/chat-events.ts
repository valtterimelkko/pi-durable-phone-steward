/**
 * The chat event store behind `GET /v1/chat/stream` and `/v1/chat/history`
 * (docs/protocol.md §6): one server-wide increasing `seq`, a bounded event array in a
 * session document, so a reconnect replays from the server's store.
 */
import type { JsonValue } from "@earendil-works/chord";
import { defineDoc, type Tx } from "@earendil-works/pi-durable";
import { periodicCheckpoint } from "./checkpoint.ts";

export const CHAT_EVENT_KEEP = 2000;

/** `context` (A10.9): a fresh-start divider; data `{kind: "fresh_start", text, atMs}`. */
/** `phone_use` (A13): a request card, an answer, or a running session's activity. */
export type ChatEventType = "message" | "tool" | "proposal" | "run" | "context" | "phone_use";

export type ChatEvent = {
	seq: number;
	type: ChatEventType;
	data: JsonValue;
	atMs: number;
};

export const ChatEvents = defineDoc<{ seq: number; events: ChatEvent[] }>({
	kind: "steward.chat",
	version: 1,
	// Pi Durable 1.4.0 requires an explicit checkpoint policy (no default).
	checkpointWhen: periodicCheckpoint(),
	scope: "session",
	initial: () => ({ seq: 0, events: [] }),
});

/** Append one event inside the caller's commit; returns its seq. */
export async function appendChatEvent(tx: Tx, type: ChatEventType, data: JsonValue, atMs: number): Promise<number> {
	const doc = await tx.doc(ChatEvents);
	doc.seq += 1;
	const event: ChatEvent = { seq: doc.seq, type, data, atMs };
	doc.events.push(JSON.parse(JSON.stringify(event)) as ChatEvent);
	if (doc.events.length > CHAT_EVENT_KEEP) {
		doc.events = doc.events.slice(doc.events.length - CHAT_EVENT_KEEP);
	}
	return doc.seq;
}

/** Events with `seq > afterSeq`, in order (the SSE replay window). */
export async function chatEventsAfter(tx: Tx, afterSeq: number): Promise<ChatEvent[]> {
	const doc = await tx.doc(ChatEvents);
	return JSON.parse(JSON.stringify(doc.events.filter((event) => event.seq > afterSeq))) as ChatEvent[];
}

/** The newest seq (0 when nothing has happened yet). */
export async function currentChatSeq(tx: Tx): Promise<number> {
	const doc = await tx.doc(ChatEvents);
	return doc.seq;
}
