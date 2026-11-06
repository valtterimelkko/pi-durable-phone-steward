/**
 * Worklog line shapes against the frozen contract fixture
 * (`docs/protocol-fixtures/steward-worklog.jsonl`, `docs/protocol.md` §10).
 *
 * The fixture's line types, keys and value types must match the writer's line for
 * line; line ids, the session uuid and timestamps may differ (their formats are
 * checked separately). Everything else — including the fixture's data values — is
 * compared for equality, because the builder reproduces the fixture's flow exactly.
 */
import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { describe, expect, it } from "vitest";
import type { AssistantMessage, ToolResultMessage, UserMessage } from "@earendil-works/pi-ai";
import {
	lineId,
	modelChangeLine,
	messageLineFromEntry,
	sessionHeaderLine,
	stewardEventLine,
	type StewardLine,
} from "../../src/worklog/lines.ts";
import type { EntryRecord } from "@earendil-works/pi-durable";

const FIXTURE_PATH = fileURLToPath(
	new URL("../../../docs/protocol-fixtures/steward-worklog.jsonl", import.meta.url),
);

const UUID = "0f9e2c4a-7b1d-4e8a-9c3f-5a6b7c8d9e01";

type Json = Record<string, unknown>;

function fixtureLines(): Json[] {
	return readFileSync(FIXTURE_PATH, "utf8")
		.split("\n")
		.filter((line) => line.trim() !== "")
		.map((line) => JSON.parse(line) as Json);
}

/** A value-shape tree: leaf type names, objects as key maps, arrays as ordered shapes. */
function shapeOf(value: unknown): unknown {
	if (Array.isArray(value)) return value.map(shapeOf);
	if (value !== null && typeof value === "object") {
		const out: Record<string, unknown> = {};
		for (const [key, item] of Object.entries(value as Json)) out[key] = shapeOf(item);
		return out;
	}
	if (value === null) return "null";
	return typeof value;
}

function userEntry(text: string, atMs: number): { entry: EntryRecord; message: UserMessage } {
	const message: UserMessage = { role: "user", content: [{ type: "text", text }], timestamp: atMs };
	return { entry: { id: 101 as never, conversationId: "main" as never, kind: "pi.user", model: [message] }, message };
}

function assistantEntry(message: AssistantMessage, id: number): { entry: EntryRecord; message: AssistantMessage } {
	return {
		entry: { id: id as never, conversationId: "main" as never, kind: "pi.assistant", model: [message] },
		message,
	};
}

function toolResultEntry(message: ToolResultMessage, id: number): { entry: EntryRecord; message: ToolResultMessage } {
	return {
		entry: { id: id as never, conversationId: "main" as never, kind: "pi.tool-result", model: [message] },
		message,
	};
}

describe("worklog line ids", () => {
	it("are 8 lowercase hex and deterministic in their inputs", () => {
		const a = lineId(UUID, "entry", "101");
		expect(a).toMatch(/^[0-9a-f]{8}$/);
		expect(lineId(UUID, "entry", "101")).toBe(a);
		expect(lineId(UUID, "entry", "102")).not.toBe(a);
		expect(lineId("other-uuid", "entry", "101")).not.toBe(a);
	});
});

describe("worklog line shapes vs the fixture", () => {
	const fixture = fixtureLines();

	it("the fixture has the expected 17 lines (sanity, counted)", () => {
		expect(fixture.length).toBe(17);
	});

	it("header matches fixture line 1 in keys, value types and fixed values", () => {
		const mine = sessionHeaderLine({ sessionUuid: UUID, startedAtMs: 0, cwd: "/opt/phone-steward" }) as Json;
		expect(Object.keys(mine)).toEqual(Object.keys(fixture[0]!));
		expect(shapeOf(mine)).toEqual(shapeOf(fixture[0]!));
		expect(mine.type).toBe("session");
		expect(mine.version).toBe(3);
		expect(mine.cwd).toBe("/opt/phone-steward");
		expect(mine.runtime).toBe("steward");
		expect(mine.id).toBe(UUID);
	});

	it("model_change matches fixture line 2 (its parentId is null after the header)", () => {
		const mine = modelChangeLine({
			sessionUuid: UUID,
			provider: "zai",
			modelId: "glm-5.3-flash",
			atMs: Date.parse("2026-10-07T09:00:00.010Z"),
			parentId: null,
			ordinal: 0,
		}) as Json;
		expect(Object.keys(mine)).toEqual(Object.keys(fixture[1]!));
		expect(shapeOf(mine)).toEqual(shapeOf(fixture[1]!));
		expect(mine.type).toBe("model_change");
		expect(mine.parentId).toBeNull();
		expect(mine.provider).toBe("zai");
		expect(mine.modelId).toBe("glm-5.3-flash");
	});

	it("steward-event matches fixture line 3 (run_start) with data keys first-class", () => {
		const mine = stewardEventLine({
			sessionUuid: UUID,
			seq: 1,
			kind: "run_start",
			atMs: Date.parse("2026-10-07T09:00:00.020Z"),
			data: { reason: "owner" },
			parentId: "a1000001",
		}) as Json;
		const expected = fixture[2]!;
		expect(mine.type).toBe(expected.type);
		expect(mine.customType).toBe("steward-event");
		expect(Object.keys(mine)).toEqual(Object.keys(expected));
		expect(mine.data).toEqual({ kind: "run_start", reason: "owner" });
		expect(shapeOf(mine)).toEqual(shapeOf(expected));
	});

	it("user message matches fixture line 4", () => {
		const { entry } = userEntry("What is filling my phone?", 1791363600030);
		const mine = messageLineFromEntry({
			sessionUuid: UUID,
			entry,
			parentId: "a1000002",
		}) as Json;
		const expected = fixture[3]!;
		expect(mine).not.toBeUndefined();
		expect(mine.type).toBe("message");
		expect(Object.keys(mine)).toEqual(Object.keys(expected));
		expect(Object.keys(mine.message as Json)).toEqual(Object.keys(expected.message as Json));
		expect((mine.message as Json).role).toBe("user");
		expect((mine.message as Json).content).toEqual([{ type: "text", text: "What is filling my phone?" }]);
		expect(shapeOf(mine)).toEqual(shapeOf(expected));
	});

	it("assistant message with a tool call matches fixture line 5", () => {
		const message: AssistantMessage = {
			role: "assistant",
			content: [
				{ type: "text", text: "I will ask the phone for a storage overview." },
				{ type: "toolCall", id: "call_01", name: "phone_storage_overview", arguments: {} },
			],
			api: "openai-completions",
			provider: "zai",
			model: "glm-5.3-flash",
			usage: {
				input: 1,
				output: 1,
				cacheRead: 0,
				cacheWrite: 0,
				totalTokens: 2,
				cost: { total: 0, input: 0, output: 0, cacheRead: 0, cacheWrite: 0 },
			},
			stopReason: "toolUse",
			timestamp: 1791363602000,
		};
		const { entry } = assistantEntry(message, 102);
		const mine = messageLineFromEntry({ sessionUuid: UUID, entry, parentId: "a1000003" }) as Json;
		const expected = fixture[4]!;
		expect(Object.keys(mine.message as Json)).toEqual(Object.keys(expected.message as Json));
		expect((mine.message as Json).content).toEqual(expected.message && (expected.message as Json).content);
		expect((mine.message as Json).provider).toBe("zai");
		expect((mine.message as Json).model).toBe("glm-5.3-flash");
		expect((mine.message as Json).stopReason).toBe("toolUse");
		expect(shapeOf(mine)).toEqual(shapeOf(expected));
	});

	it("tool result matches fixture line 6", () => {
		const message: ToolResultMessage = {
			role: "toolResult",
			toolCallId: "call_01",
			toolName: "phone_storage_overview",
			content: [{ type: "text", text: '{"status":"queued","jobId":"pj-task01"}' }],
			isError: false,
			timestamp: 1791363602050,
		};
		const { entry } = toolResultEntry(message, 103);
		const mine = messageLineFromEntry({ sessionUuid: UUID, entry, parentId: "a1000004" }) as Json;
		const expected = fixture[5]!;
		expect(Object.keys(mine.message as Json)).toEqual(Object.keys(expected.message as Json));
		expect((mine.message as Json).isError).toBe(false);
		expect(shapeOf(mine)).toEqual(shapeOf(expected));
	});

	it("drops thinking content from assistant messages", () => {
		const message: AssistantMessage = {
			role: "assistant",
			content: [
				{ type: "thinking", thinking: "secret reasoning" },
				{ type: "text", text: "Answer." },
			],
			api: "openai-completions",
			provider: "zai",
			model: "glm-5.3-flash",
			usage: {
				input: 1,
				output: 1,
				cacheRead: 0,
				cacheWrite: 0,
				totalTokens: 2,
				cost: { total: 0, input: 0, output: 0, cacheRead: 0, cacheWrite: 0 },
			},
			stopReason: "stop",
			timestamp: 5,
		};
		const { entry } = assistantEntry(message, 104);
		const mine = messageLineFromEntry({ sessionUuid: UUID, entry, parentId: null }) as Json;
		expect((mine.message as Json).content).toEqual([{ type: "text", text: "Answer." }]);
	});

	it("returns undefined for entries that carry no mirrorable message", () => {
		const entry: EntryRecord = { id: 105 as never, conversationId: "main" as never, kind: "pi.compaction" };
		expect(messageLineFromEntry({ sessionUuid: UUID, entry, parentId: null })).toBeUndefined();
		const noModel: EntryRecord = { id: 106 as never, conversationId: "main" as never, kind: "pi.user" };
		expect(messageLineFromEntry({ sessionUuid: UUID, entry: noModel, parentId: null })).toBeUndefined();
	});

	it("every produced line is JSON with the shared envelope key order", () => {
		const line: StewardLine = stewardEventLine({
			sessionUuid: UUID,
			seq: 2,
			kind: "job_queued",
			atMs: 0,
			data: { jobId: "pj-task01", jobKind: "storage_overview" },
			parentId: null,
		});
		const parsed = JSON.parse(JSON.stringify(line)) as Json;
		expect(Object.keys(parsed)).toEqual(["type", "customType", "id", "parentId", "timestamp", "data"]);
	});
});
