/**
 * Review fixes (0.3.1): S13 codes never stored from screen taps, S14 screen
 * actions never crowd out the decisions ledger, S15 a late answer is recorded not lost,
 * S16 the session limits run on a clock. Fakes only: scripted faux model, the test is the phone.
 */
import { BACKGROUND_CONTEXT } from "@earendil-works/chord/context";
import { fauxAssistantMessage, fauxText, fauxToolCall } from "@earendil-works/pi-ai/providers/faux";
import { afterEach, beforeEach, describe, expect, it } from "vitest";
import { DECISIONS_KEEP, Decisions, SCREEN_ACTION_KEEP, recordDecision, type DecisionLedgerEntry } from "../src/documents.ts";
import {
	LiveSessions,
	answerLiveStep,
	configureLive,
	expireLiveSessions,
	nextLiveStep,
	resetLiveConfig,
	startLiveSession,
	startLiveSweeper,
	stopLiveSession,
	type LiveDeps,
} from "../src/live/sessions.ts";
import { redactCodes } from "../src/live/tree.ts";
import { serverToolsExtension } from "../src/tools.ts";
import { openTestHarness, type TestHarness } from "./helpers.ts";

const context = BACKGROUND_CONTEXT;
const DEVICE = "dev_test0001";

let t: TestHarness;
let deps: LiveDeps;
const seenToolResults: string[] = [];

function script(turns: Array<() => ReturnType<typeof fauxAssistantMessage>>): void {
	let index = 0;
	t.faux!.setResponses(
		Array.from({ length: 12 }, () => (request) => {
			const last = request.messages.findLast((m) => m.role !== "system");
			if (last?.role === "toolResult") {
				const content = typeof last.content === "string" ? last.content : last.content.map((p) => (p.type === "text" ? p.text : "")).join("");
				seenToolResults.push(content);
			}
			const turn = turns[Math.min(index, turns.length - 1)]!;
			index += 1;
			return turn();
		}),
	);
}

beforeEach(async () => {
	seenToolResults.length = 0;
	resetLiveConfig();
	t = await openTestHarness();
	await t.root.configure({ model: { provider: "faux", modelId: t.faux!.getModel().id } }, context);
	deps = { harness: t.harness, root: t.root, context };
});
afterEach(async () => {
	resetLiveConfig();
	await t.close();
});

async function until<T>(read: () => Promise<T | undefined>, ms = 8000): Promise<T> {
	const deadline = Date.now() + ms;
	for (;;) {
		const value = await read();
		if (value !== undefined) return value;
		if (Date.now() > deadline) throw new Error("timed out waiting");
		await new Promise((resolve) => setTimeout(resolve, 20));
	}
}

async function sessionDoc(sessionId: string) {
	return (await t.snapshot(LiveSessions))?.sessions[sessionId];
}

async function ledger(): Promise<Array<Record<string, unknown>>> {
	return t.commit(async (tx) => (await tx.doc(Decisions, t.root.id)).entries.map((e) => JSON.parse(JSON.stringify(e)) as Record<string, unknown>));
}

const tapScript = () => script([() => fauxAssistantMessage([fauxToolCall("screen_act", { action: "tap", nodeId: "n2" })], { stopReason: "toolUse" })]);

describe("S13 one-time codes are never stored from screen taps", () => {
	it("redactCodes masks a 4 to 8 digit run only when the text talks about a code", () => {
		expect(redactCodes("Login code 48213")).toBe("Login code ••••");
		expect(redactCodes("Vahvistuskoodi: koodi 123456")).toBe("Vahvistuskoodi: koodi ••••");
		expect(redactCodes("Your PIN is 4821")).toBe("Your PIN is ••••");
		expect(redactCodes("Storage 48213 MB")).toBe("Storage 48213 MB");
		expect(redactCodes("code 123")).toBe("code 123");
	});

	it("the ledger note, the step record and the model's result carry no digits of the code", async () => {
		script([
			() => fauxAssistantMessage([fauxToolCall("screen_act", { action: "tap", nodeId: "n2" })], { stopReason: "toolUse" }),
			() => fauxAssistantMessage([fauxText("done")]),
		]);
		const { sessionId } = await startLiveSession(deps, { deviceId: DEVICE, task: "t" });
		const next = await nextLiveStep(deps, sessionId, DEVICE, 8);
		const stepId = (next as { step: { stepId: string } }).step.stepId;
		await answerLiveStep(deps, sessionId, stepId, DEVICE, {
			outcome: "ok",
			nodeText: "Login code 48213",
			detail: "Entered verification code 48213 as asked",
		});
		await until(async () => ((await sessionDoc(sessionId))?.status === "ended" ? true : undefined));
		const entry = (await ledger()).find((e) => e.type === "screen_action");
		expect(String(entry?.note)).toContain("Login code ••••");
		expect(JSON.stringify(await ledger())).not.toContain("48213");
		expect(JSON.stringify(await sessionDoc(sessionId))).not.toContain("48213");
		expect(seenToolResults.join("\n")).not.toContain("48213");
	});

	it("a code in the tree text is masked again on the server", async () => {
		tapScript();
		const { sessionId } = await startLiveSession(deps, { deviceId: DEVICE, task: "t" });
		const next = await nextLiveStep(deps, sessionId, DEVICE, 8);
		await answerLiveStep(deps, sessionId, (next as { step: { stepId: string } }).step.stepId, DEVICE, {
			outcome: "ok",
			tree: {
				package: "com.example.mail",
				nodes: [{ id: "n1", role: "text", text: "Your verification code is 998877", clickable: false, editable: false, bounds: [0, 0, 1, 1] }],
				truncated: false,
			},
		});
		expect(JSON.stringify(await sessionDoc(sessionId))).not.toContain("998877");
		await stopLiveSession(deps, sessionId, DEVICE);
	});
});

describe("S14 screen actions never crowd out the decisions ledger", () => {
	const entry = (type: DecisionLedgerEntry["type"], n: number): DecisionLedgerEntry => ({ atMs: n, type, note: `${type} ${n}` });

	async function record(entries: DecisionLedgerEntry[]): Promise<void> {
		for (const e of entries) await t.commit((tx) => recordDecision(tx, t.root.id, e));
	}

	it("recordDecision keeps only the newest screen_action entries, whatever the ledger size", async () => {
		await record([entry("kept", 0)]);
		await record(Array.from({ length: SCREEN_ACTION_KEEP + 50 }, (_, i) => entry("screen_action", 100 + i)));
		const all = await ledger();
		const screen = all.filter((e) => e.type === "screen_action");
		expect(screen).toHaveLength(SCREEN_ACTION_KEEP);
		expect(screen[0]!.atMs).toBe(150);
		expect(screen.at(-1)!.atMs).toBe(100 + SCREEN_ACTION_KEEP + 49);
		expect(all.filter((e) => e.type === "kept")).toHaveLength(1);
	});

	it("over the cap, screen actions go first: owner decisions are never evicted by taps", async () => {
		const owner = Array.from({ length: DECISIONS_KEEP - 100 }, (_, i) => entry(i % 2 === 0 ? "kept" : "restored", i));
		await t.commit(async (tx) => {
			(await tx.doc(Decisions, t.root.id)).entries = owner as never;
		});
		await record(Array.from({ length: 150 }, (_, i) => entry("screen_action", 10_000 + i)));
		const all = await ledger();
		expect(all.length).toBeLessThanOrEqual(DECISIONS_KEEP);
		expect(all.filter((e) => e.type !== "screen_action")).toHaveLength(DECISIONS_KEEP - 100);
		expect(all.filter((e) => e.type === "screen_action")).toHaveLength(100);
	});

	it("when owner entries alone exceed the cap, the oldest of them go (as before)", async () => {
		await record(Array.from({ length: DECISIONS_KEEP + 5 }, (_, i) => entry("kept", i)));
		const all = await ledger();
		expect(all).toHaveLength(DECISIONS_KEEP);
		expect(all[0]!.atMs).toBe(5);
	});

	type Tool = { name: string; execute: (args: unknown, api: unknown, callContext: unknown) => Promise<{ output: Array<{ text: string }> }> };
	async function decisionsTool(args: unknown): Promise<Array<Record<string, unknown>>> {
		const tool = (serverToolsExtension as unknown as { tools: Tool[] }).tools.find((x) => x.name === "decisions")!;
		const api = { taskId: "task-d", conversationId: t.root.id, commit: <T>(fn: Parameters<typeof t.commit>[0]) => t.commit(fn as never) as Promise<T> };
		const out = await tool.execute(args, api, context);
		return (JSON.parse(out.output[0]!.text) as { entries: Array<Record<string, unknown>> }).entries;
	}

	it("the decisions tool leaves screen_action out by default and includes it on request", async () => {
		await record([entry("kept", 1), entry("screen_action", 2), entry("screen_action", 3), entry("restored", 4)]);
		const plain = await decisionsTool({});
		expect(plain.map((e) => e.type)).toEqual(["kept", "restored"]);
		const withScreen = await decisionsTool({ includeScreenActions: true });
		expect(withScreen.map((e) => e.type)).toEqual(["kept", "screen_action", "screen_action", "restored"]);
		// The limit counts what is shown: taps do not push owner decisions out of the window.
		await record(Array.from({ length: 30 }, (_, i) => entry("screen_action", 100 + i)));
		const limited = await decisionsTool({ limit: 2 });
		expect(limited.map((e) => e.type)).toEqual(["kept", "restored"]);
	});
});

describe("S15 a late answer is recorded, not lost", () => {
	it("an answer to a step the server timed out is accepted as late, ledgered, and does not wake the model", async () => {
		configureLive({ stepTimeoutMs: 400 });
		script([
			() => fauxAssistantMessage([fauxToolCall("screen_act", { action: "tap", nodeId: "n2" })], { stopReason: "toolUse" }),
			() => fauxAssistantMessage([fauxToolCall("screen_observe", {})], { stopReason: "toolUse" }),
		]);
		const { sessionId } = await startLiveSession(deps, { deviceId: DEVICE, task: "t" });
		const next = await nextLiveStep(deps, sessionId, DEVICE, 8);
		const stepId = (next as { step: { stepId: string } }).step.stepId;
		await until(async () => ((await sessionDoc(sessionId))?.steps[stepId]?.status === "abandoned" ? true : undefined));
		const seenBefore = seenToolResults.length;
		const result = await answerLiveStep(deps, sessionId, stepId, DEVICE, { outcome: "ok", nodeText: "Clear cache", detail: "late-detail-marker" });
		expect(result).toEqual({ accepted: true, late: true });
		const entry = (await ledger()).find((e) => e.type === "screen_action");
		expect(String(entry?.note)).toContain("Clear cache");
		expect(String(entry?.note)).toContain("ok");
		// The model had moved on: nothing from the late answer reaches it.
		expect(seenToolResults.slice(seenBefore).join("\n")).not.toContain("late-detail-marker");
		// A second answer to the same step is refused, and nothing is ledgered twice.
		await expect(answerLiveStep(deps, sessionId, stepId, DEVICE, { outcome: "ok", nodeText: "Clear cache" })).rejects.toMatchObject({
			status: 409,
			code: "STEP_NOT_OPEN",
		});
		expect((await ledger()).filter((e) => e.type === "screen_action")).toHaveLength(1);
		await stopLiveSession(deps, sessionId, DEVICE);
	});

	it("an answer to a delivered step of an ended session is ledgered, then refused with SESSION_ENDED", async () => {
		tapScript();
		const { sessionId } = await startLiveSession(deps, { deviceId: DEVICE, task: "t" });
		const next = await nextLiveStep(deps, sessionId, DEVICE, 8);
		const stepId = (next as { step: { stepId: string } }).step.stepId;
		await stopLiveSession(deps, sessionId, DEVICE);
		await expect(answerLiveStep(deps, sessionId, stepId, DEVICE, { outcome: "ok", nodeText: "Clear cache" })).rejects.toMatchObject({
			status: 409,
			code: "SESSION_ENDED",
		});
		const entries = (await ledger()).filter((e) => e.type === "screen_action");
		expect(entries).toHaveLength(1);
		expect(String(entries[0]!.note)).toContain("Clear cache");
		// Repeating it records nothing more.
		await expect(answerLiveStep(deps, sessionId, stepId, DEVICE, { outcome: "ok", nodeText: "Clear cache" })).rejects.toMatchObject({ status: 409 });
		expect((await ledger()).filter((e) => e.type === "screen_action")).toHaveLength(1);
	});

	it("a step that was never delivered leaves no ledger entry when the session has ended", async () => {
		tapScript();
		const { sessionId } = await startLiveSession(deps, { deviceId: DEVICE, task: "t" });
		const stepId = await until(async () => Object.keys((await sessionDoc(sessionId))?.steps ?? {})[0]);
		await stopLiveSession(deps, sessionId, DEVICE);
		await expect(answerLiveStep(deps, sessionId, stepId, DEVICE, { outcome: "ok", nodeText: "x" })).rejects.toMatchObject({ status: 409 });
		expect((await ledger()).filter((e) => e.type === "screen_action")).toHaveLength(0);
	});

	it("an ordinary second answer to an answered step stays STEP_NOT_OPEN", async () => {
		tapScript();
		const { sessionId } = await startLiveSession(deps, { deviceId: DEVICE, task: "t" });
		const next = await nextLiveStep(deps, sessionId, DEVICE, 8);
		const stepId = (next as { step: { stepId: string } }).step.stepId;
		expect(await answerLiveStep(deps, sessionId, stepId, DEVICE, { outcome: "ok", nodeText: "Clear cache" })).toEqual({ accepted: true });
		await expect(answerLiveStep(deps, sessionId, stepId, DEVICE, { outcome: "ok" })).rejects.toMatchObject({ status: 409, code: "STEP_NOT_OPEN" });
		await stopLiveSession(deps, sessionId, DEVICE);
	});
});

describe("S16 the session limits run on a clock", () => {
	const MIN = 60_000;

	it("a running session past its hard limit is ended as budget, its open step released, and a new session may start", async () => {
		script([
			() => fauxAssistantMessage([fauxToolCall("screen_observe", {})], { stopReason: "toolUse" }),
			() => fauxAssistantMessage([fauxText("stopping")]),
		]);
		const { sessionId } = await startLiveSession(deps, { deviceId: DEVICE, task: "t" });
		await nextLiveStep(deps, sessionId, DEVICE, 8);
		const startedAt = (await sessionDoc(sessionId))!.startedAtMs;
		// The app is polling all along (so only the hard limit can end it).
		await t.commit(async (tx) => {
			(await tx.doc(LiveSessions)).sessions[sessionId]!.lastPollAtMs = startedAt + 15 * MIN;
		});
		// Just inside the limit (A13.4: 15 minutes): nothing happens.
		expect(await expireLiveSessions(deps, startedAt + 15 * MIN - 1000)).toBe(0);
		expect((await sessionDoc(sessionId))?.status).toBe("running");
		expect(await expireLiveSessions(deps, startedAt + 15 * MIN + 1000)).toBe(1);
		const doc = await sessionDoc(sessionId);
		expect(doc?.status).toBe("ended");
		expect(doc?.endReason).toBe("budget");
		// The model's waiting tool call was released, so its turn continued.
		await until(async () => (seenToolResults.some((r) => /over|stopped/i.test(r)) ? true : undefined));
		script([() => fauxAssistantMessage([fauxText("hi")])]);
		await expect(startLiveSession(deps, { deviceId: DEVICE, task: "next" })).resolves.toMatchObject({ sessionId: expect.stringMatching(/^ls-/) });
	});

	it("a session whose app has not polled for more than 2 minutes is ended as app_lost", async () => {
		tapScript();
		const { sessionId } = await startLiveSession(deps, { deviceId: DEVICE, task: "t" });
		await nextLiveStep(deps, sessionId, DEVICE, 8);
		const polledAt = (await sessionDoc(sessionId))!.lastPollAtMs!;
		expect(polledAt).toBeGreaterThan(0);
		expect(await expireLiveSessions(deps, polledAt + 2 * MIN - 1000)).toBe(0);
		expect((await sessionDoc(sessionId))?.status).toBe("running");
		expect(await expireLiveSessions(deps, polledAt + 2 * MIN + 1000)).toBe(1);
		expect((await sessionDoc(sessionId))?.endReason).toBe("app_lost");
	});

	it("a session the app never polled counts from its start", async () => {
		tapScript();
		const { sessionId } = await startLiveSession(deps, { deviceId: DEVICE, task: "t" });
		const startedAt = (await sessionDoc(sessionId))!.startedAtMs;
		expect(await expireLiveSessions(deps, startedAt + 2 * MIN + 1000)).toBe(1);
		expect((await sessionDoc(sessionId))?.endReason).toBe("app_lost");
	});

	it("an answer counts as a sign of life", async () => {
		tapScript();
		const { sessionId } = await startLiveSession(deps, { deviceId: DEVICE, task: "t" });
		const next = await nextLiveStep(deps, sessionId, DEVICE, 8);
		await answerLiveStep(deps, sessionId, (next as { step: { stepId: string } }).step.stepId, DEVICE, { outcome: "ok" });
		const alive = (await sessionDoc(sessionId))!.lastPollAtMs!;
		expect(await expireLiveSessions(deps, alive + 2 * MIN - 1000)).toBe(0);
		await stopLiveSession(deps, sessionId, DEVICE);
	});

	it("the sweeper runs expireLiveSessions on a timer and can be stopped", async () => {
		tapScript();
		const { sessionId } = await startLiveSession(deps, { deviceId: DEVICE, task: "t" });
		const startedAt = (await sessionDoc(sessionId))!.startedAtMs;
		const sweeper = startLiveSweeper(deps, { intervalMs: 30, now: () => startedAt + 16 * MIN });
		try {
			await until(async () => ((await sessionDoc(sessionId))?.status === "ended" ? true : undefined));
		} finally {
			sweeper.stop();
		}
		expect((await sessionDoc(sessionId))?.endReason).toBe("budget");
	});
});
