/**
 * A8 through the bridge: the `tool` events the chat bridge writes carry a callId, safe
 * argsSummary/resultSummary, a duration on the closing event, and atMs; the run events
 * carry atMs and the ended one a durationMs. A fake conversation drives the bridge; no
 * server, no model.
 */
import { describe, expect, it } from "vitest";
import { startChatBridge } from "../src/chat-bridge.ts";
import { ChatEvents } from "../src/chat-events.ts";

type Entry = { id: number; model: unknown[] };

/**
 * A hand-built fake, deliberately minimal: Pi Durable 1.5.0 hands a watch listener one tagged
 * call (`{ kind: "value", value, ops, retired }`) and a `ConversationView` whose `entries` is a
 * window, so this fake speaks that shape by hand — a cast would hide the very change under test.
 */
function fakeConversation() {
	const doc = { seq: 0, events: [] as Array<{ seq: number; type: string; data: any; atMs: number }> };
	let push: ((call: { kind: "value"; value: { entries: Entry[] }; ops: []; retired: boolean }) => Promise<void>) | undefined;
	const conversation = {
		async watch() {
			return {
				value: { entries: [] as Entry[], conversation: {}, docs: {} },
				start(cb: typeof push) {
					push = cb;
				},
			};
		},
		async commit(fn: (tx: any) => Promise<unknown>) {
			return fn({ doc: async (def: unknown) => (def === ChatEvents ? doc : undefined) });
		},
	};
	return {
		conversation,
		doc,
		deliver: async (entries: Entry[]) => {
			while (push === undefined) await new Promise((r) => setTimeout(r, 5));
			await push({ kind: "value", value: { entries }, ops: [], retired: false });
		},
	};
}

describe("chat bridge tool events (A8)", () => {
	it("writes started and done events with summaries, callId and duration, and no raw arguments or result", async () => {
		const { conversation, doc, deliver } = fakeConversation();
		startChatBridge(conversation as never);
		await deliver([
			{
				id: 1,
				model: [
					{
						role: "assistant",
						content: [
							{ type: "text", text: "Checking." },
							{
								type: "toolCall",
								id: "call_1",
								name: "phone_media_index",
								arguments: { mode: "summary", folders: ["DCIM/Camera", "Pictures/Secret Folder"] },
							},
						],
					},
				],
			},
		]);
		await new Promise((r) => setTimeout(r, 30));
		await deliver([
			{
				id: 2,
				model: [
					{
						role: "toolResult",
						toolCallId: "call_1",
						toolName: "phone_media_index",
						isError: false,
						content: [{ type: "text", text: JSON.stringify({ status: "queued", jobId: "pj-abc123" }) }],
					},
				],
			},
		]);
		const tools = doc.events.filter((event) => event.type === "tool");
		expect(tools).toHaveLength(2);
		expect(tools[0].data).toMatchObject({
			name: "phone_media_index",
			status: "started",
			callId: "call_1",
			argsSummary: "mode summary, 2 folders",
		});
		expect(typeof tools[0].data.atMs).toBe("number");
		expect(tools[1].data).toMatchObject({
			name: "phone_media_index",
			status: "done",
			callId: "call_1",
			argsSummary: "mode summary, 2 folders",
			resultSummary: "queued job pj-abc123",
		});
		expect(tools[1].data.durationMs).toBeGreaterThanOrEqual(25);
		const wire = JSON.stringify(tools);
		expect(wire).not.toMatch(/DCIM|Camera|Secret/);
		for (const event of tools) {
			expect(String(event.data.argsSummary ?? "").length).toBeLessThanOrEqual(300);
			expect(String(event.data.resultSummary ?? "").length).toBeLessThanOrEqual(300);
		}
	});

	it("marks a failed tool as an error with a redacted summary", async () => {
		const { conversation, doc, deliver } = fakeConversation();
		startChatBridge(conversation as never);
		await deliver([
			{
				id: 1,
				model: [
					{
						role: "toolResult",
						toolCallId: "call_9",
						toolName: "propose_cleanup",
						isError: true,
						content: [{ type: "text", text: "Error: no such item /sdcard/DCIM/IMG_1.jpg" }],
					},
				],
			},
		]);
		const [event] = doc.events;
		expect(event.data.status).toBe("error");
		expect(event.data.resultSummary).toContain("[redacted]");
		expect(JSON.stringify(event)).not.toMatch(/sdcard|IMG_1/);
	});
});
