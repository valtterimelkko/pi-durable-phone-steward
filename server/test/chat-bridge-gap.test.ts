/**
 * The chat bridge across Pi Durable 1.5.0's conversation-view window .
 *
 * 1.5.0 hands a watch listener one tagged call (`{ kind: "value", value, ops, retired }`), and
 * `ConversationView.entries` is a window: it starts with the newest 50 entries and, once it
 * would hold more than 100, drops the oldest in one splice down to 50. The bridges `await`
 * commits inside the listener, so a backlog is possible — and if more than about 50 entries are
 * appended while the listener runs, entries leave the window unseen and are never mirrored into
 * Chat. This test appends 130 entries while one listener call is held: every one must reach Chat
 * exactly once, in order.
 *
 * The fake speaks the 1.5 shapes by hand (windows, folding, paging) rather than casting.
 */
import { describe, expect, it } from "vitest";
import { startChatBridge } from "../src/chat-bridge.ts";
import { startLiveChatBridge, stopLiveChatBridge } from "../src/live/bridge.ts";
import { ChatEvents } from "../src/chat-events.ts";

type Entry = { id: number; model: unknown[] };
type View = { entries: Entry[]; before?: number };
type Call = { kind: "value"; value: View; ops: []; retired: boolean };

const MIN = 50;
const MAX = 2 * MIN;

/** One assistant text entry, as a committed entry record's `model`. */
function assistantEntry(id: number, text: string): Entry {
	return { id, model: [{ role: "assistant", content: [{ type: "text", text }] }] };
}

/**
 * A windowed, folding conversation watch with a commit gate, plus `entries()` paging over the
 * whole history. The window semantics are the 1.5.0 ones the bridges must survive.
 */
function windowedFake() {
	const all: Entry[] = [];
	/** Index into `all` where the window starts; a splice moves it forward (1.5.0 keeps 50 of 100). */
	let start = 0;
	let listener: ((call: Call) => Promise<void>) | undefined;
	let inFlight: Promise<void> | undefined;
	let pending = false;
	let gate: { promise: Promise<void>; release: () => void } | undefined;
	const doc = { seq: 0, events: [] as Array<{ seq: number; type: string; data: Record<string, unknown>; atMs: number }> };

	function windowOf(): View {
		const entries = all.slice(start);
		const before = start > 0 ? all[start - 1]?.id : undefined;
		return { entries, ...(before === undefined ? {} : { before }) };
	}

	async function deliver(): Promise<void> {
		if (listener === undefined) return;
		if (inFlight !== undefined) {
			pending = true;
			return;
		}
		do {
			pending = false;
			const call: Call = { kind: "value", value: windowOf(), ops: [], retired: false };
			inFlight = listener(call);
			await inFlight;
			inFlight = undefined;
		} while (pending);
	}

	const conversation = {
		async watch() {
			start = Math.max(0, all.length - MIN);
			return {
				value: { ...windowOf(), conversation: {}, docs: {} },
				start(callback: (call: Call) => Promise<void>) {
					listener = callback;
				},
			};
		},
		async commit(change: (tx: unknown) => Promise<unknown>) {
			if (gate !== undefined) await gate.promise;
			return change({ doc: async (definition: unknown) => (definition === ChatEvents ? doc : undefined) });
		},
		/** Paging over the whole history, as `Conversation.entries()` does (descending by default). */
		async entries(
			query: { minEntryId?: number; maxEntryId?: number; order?: "ascending" | "descending" },
			limit: number,
		) {
			let rows = all.filter(
				(entry) =>
					(query.minEntryId === undefined || entry.id >= query.minEntryId) &&
					(query.maxEntryId === undefined || entry.id <= query.maxEntryId),
			);
			if ((query.order ?? "descending") === "descending") rows = [...rows].reverse();
			return { items: rows.slice(0, limit) };
		},
	};

	return {
		conversation,
		doc,
		/** Hold every commit the listener makes, so the next appends arrive as a backlog. */
		hold(): () => void {
			let release!: () => void;
			const promise = new Promise<void>((resolve) => {
				release = resolve;
			});
			gate = { promise, release };
			return () => {
				gate = undefined;
				release();
			};
		},
		/** Append one entry: the window takes it, and (if the listener is free) it is delivered. */
		append(entry: Entry): void {
			all.push(entry);
			// The 1.5.0 window: past MAX entries it drops the oldest in one splice, down to MIN.
			if (all.length - start > MAX) start = all.length - MIN;
			void deliver();
		},
		/** Wait until every pending delivery has finished. */
		async settled(): Promise<void> {
			const deadline = Date.now() + 5_000;
			while ((inFlight !== undefined || pending) && Date.now() < deadline) await new Promise((resolve) => setTimeout(resolve, 5));
			await new Promise((resolve) => setTimeout(resolve, 20));
		},
	};
}

describe("the chat bridge survives a 1.5.0 window splice", () => {
	it("puts every one of 130 entries into Chat exactly once, in order, when the listener is held", async () => {
		const fake = windowedFake();
		startChatBridge(fake.conversation as never);
		await fake.settled();

		// Ten entries with a free listener: the normal path.
		for (let id = 1; id <= 10; id++) fake.append(assistantEntry(id, `m${String(id)}`));
		await fake.settled();

		// Hold the listener on entry 11, then append 119 more: the window splices to the newest 50.
		const release = fake.hold();
		fake.append(assistantEntry(11, "m11"));
		for (let id = 12; id <= 130; id++) fake.append(assistantEntry(id, `m${String(id)}`));
		release();
		await fake.settled();

		const messages = fake.doc.events
			.filter((event) => event.type === "message")
			.map((event) => String((event.data as { text?: unknown }).text ?? ""));
		// Exactly once each, in entry order: no gap, no duplicate, no reordering.
		expect(messages).toHaveLength(130);
		expect(messages).toEqual(Array.from({ length: 130 }, (_, index) => `m${String(index + 1)}`));
	});
});

describe("the live session bridge survives the same window splice", () => {
	it("puts every one of the session model's 130 say events into Chat exactly once", async () => {
		// The live bridge shares `missedEntries()`; this proves its own listener (assistant prose
		// only, masked) is wired to it and not just the main chat bridge.
		const fake = windowedFake();
		startLiveChatBridge(fake.conversation as never, "ls-gap");
		await fake.settled();
		for (let id = 1; id <= 10; id++) fake.append(assistantEntry(id, `s${String(id)}`));
		await fake.settled();
		const release = fake.hold();
		fake.append(assistantEntry(11, "s11"));
		for (let id = 12; id <= 130; id++) fake.append(assistantEntry(id, `s${String(id)}`));
		release();
		await fake.settled();
		const says = fake.doc.events
			.filter((event) => event.type === "phone_use")
			.map((event) => String((event.data as { text?: unknown }).text ?? ""));
		expect(says).toEqual(Array.from({ length: 130 }, (_, index) => `s${String(index + 1)}`));
		stopLiveChatBridge("ls-gap");
	});
});
