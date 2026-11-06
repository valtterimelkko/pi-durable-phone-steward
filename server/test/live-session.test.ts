/**
 * Protocol A10.3 server half: the live-session turn. A session is its own conversation
 * whose tool set is ONLY screen_observe / screen_act / screen_end; the app long-polls
 * steps and answers them. Fakes only: the faux model is scripted, the "phone" is the test.
 */
import { BACKGROUND_CONTEXT } from "@earendil-works/chord/context";
import { fauxAssistantMessage, fauxText, fauxToolCall } from "@earendil-works/pi-ai/providers/faux";
import { afterEach, beforeEach, describe, expect, it } from "vitest";
import { Decisions } from "../src/documents.ts";
import { ChatEvents, chatEventsAfter } from "../src/chat-events.ts";
import { ModelProblems, setActiveModels } from "../src/model-failure.ts";
import {
	LIVE_SCREEN_TOOLS,
	LiveSessions,
	answerLiveStep,
	configureLive,
	nextLiveStep,
	resetLiveConfig,
	startLiveSession,
	stopLiveSession,
	sweepLiveSessions,
	liveConversationTools,
	deliverOwnerMessage,
	type LiveDeps,
} from "../src/live/sessions.ts";
import { PROTOCOL_TOOL_NAMES, resolveAgentTools } from "../src/tools.ts";
import { openTestHarness, type TestHarness } from "./helpers.ts";

const context = BACKGROUND_CONTEXT;
const DEVICE = "dev_test0001";

const tree = {
	package: "com.android.settings",
	activity: "Settings",
	nodes: [
		{ id: "n1", role: "text", text: "Storage", clickable: false, editable: false, bounds: [0, 0, 10, 10] },
		{ id: "n2", role: "button", text: "Clear cache", clickable: true, editable: false, bounds: [0, 20, 10, 30] },
	],
	truncated: false,
};

let t: TestHarness;
let deps: LiveDeps;
const seenToolResults: string[] = [];

/** A scripted model: each request returns the next scripted assistant turn; tool results are captured. */
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
	// The helper's root has no model of its own; a live session copies the root's model.
	await t.root.configure({ model: { provider: "faux", modelId: t.faux!.getModel().id } }, context);
	deps = { harness: t.harness, root: t.root, context };
});
afterEach(async () => {
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
	const doc = await t.snapshot(LiveSessions);
	return doc?.sessions[sessionId];
}

describe("the live session turn", () => {
	it("runs observe -> act -> end through the app's long-poll, shows the model a text tree, and ledgers the action", async () => {
		script([
			() => fauxAssistantMessage([fauxToolCall("screen_observe", {})], { stopReason: "toolUse" }),
			() => fauxAssistantMessage([fauxToolCall("screen_act", { action: "tap", nodeId: "n2" })], { stopReason: "toolUse" }),
			() => fauxAssistantMessage([fauxToolCall("screen_end", { summary: "Cleared the cache." })], { stopReason: "toolUse" }),
			() => fauxAssistantMessage([fauxText("Done.")]),
		]);
		const { sessionId } = await startLiveSession(deps, { deviceId: DEVICE, task: "clear the cache of one app" });
		expect(sessionId).toMatch(/^ls-/);

		const first = await nextLiveStep(deps, sessionId, DEVICE, 8);
		expect(first).toMatchObject({ step: { tool: "screen_observe", args: {} } });
		const observeId = (first as { step: { stepId: string } }).step.stepId;
		await answerLiveStep(deps, sessionId, observeId, DEVICE, { outcome: "ok", tree });

		const second = await nextLiveStep(deps, sessionId, DEVICE, 8);
		expect(second).toMatchObject({ step: { tool: "screen_act", args: { action: "tap", nodeId: "n2" } } });
		await answerLiveStep(deps, sessionId, (second as { step: { stepId: string } }).step.stepId, DEVICE, {
			outcome: "ok",
			detail: "tapped",
			nodeText: "Clear cache",
		});

		// screen_end finishes the session without a step for the phone.
		const third = await nextLiveStep(deps, sessionId, DEVICE, 8);
		expect(third).toMatchObject({ ended: true });
		expect((third as { summary?: string }).summary).toBe("Cleared the cache.");

		// The model saw the rendered text tree, not JSON.
		expect(seenToolResults.some((r) => r.includes('n2 button "Clear cache" [tap]'))).toBe(true);
		// The action is a ledger entry with the node text and outcome.
		const entries = await t.commit(async (tx) => (await tx.doc(Decisions, t.root.id)).entries.map((e) => JSON.parse(JSON.stringify(e)) as Record<string, unknown>));
		const action = entries.find((e) => e.type === "screen_action");
		expect(action).toMatchObject({ type: "screen_action", kind: "tap" });
		expect(String(action?.note)).toContain("Clear cache");
		expect(String(action?.note)).toContain("ok");
		// The owner sees a one-line summary in Chat.
		const chat = await t.commit(async (tx) => JSON.stringify((await tx.doc(ChatEvents)).events));
		expect(chat).toContain("Cleared the cache.");
		expect((await sessionDoc(sessionId))?.status).toBe("ended");
		expect((await sessionDoc(sessionId))?.endReason).toBe("finished");
	});

	it("offers the live conversation exactly the four screen tools (A13.4 adds screen_ask), and the root conversation none of them", async () => {
		script([() => fauxAssistantMessage([fauxText("ok")])]);
		const { sessionId } = await startLiveSession(deps, { deviceId: DEVICE, task: "t" });
		const live = await sessionDoc(sessionId);
		const names = await liveConversationTools(deps, live!.conversationId);
		expect([...names].sort()).toEqual(["screen_act", "screen_ask", "screen_end", "screen_observe"]);
		expect([...LIVE_SCREEN_TOOLS].sort()).toEqual(["screen_act", "screen_ask", "screen_end", "screen_observe"]);
		// A13.1: the main conversation's ask tool never enters a live conversation.
		expect(names).not.toContain("phone_use_request");
		const rootTools = await resolveAgentTools(t.root, context);
		for (const name of LIVE_SCREEN_TOOLS) expect(rootTools).not.toContain(name);
		expect([...rootTools].sort()).toEqual([...PROTOCOL_TOOL_NAMES].sort());
	});

	it("allows only one running session, and answers the same request id twice with one session", async () => {
		script([() => fauxAssistantMessage([fauxToolCall("screen_observe", {})], { stopReason: "toolUse" })]);
		const first = await startLiveSession(deps, { deviceId: DEVICE, task: "one" });
		await expect(startLiveSession(deps, { deviceId: DEVICE, task: "two" })).rejects.toMatchObject({ status: 409, code: "LIVE_SESSION_ACTIVE" });
		await stopLiveSession(deps, first.sessionId, DEVICE);
		// After Stop a new one may start.
		script([() => fauxAssistantMessage([fauxText("hi")])]);
		const second = await startLiveSession(deps, { deviceId: DEVICE, task: "three" });
		expect(second.sessionId).not.toBe(first.sessionId);
	});

	it("Stop ends the session at once, releases the waiting tool call and the next poll says ended", async () => {
		script([() => fauxAssistantMessage([fauxToolCall("screen_observe", {})], { stopReason: "toolUse" }), () => fauxAssistantMessage([fauxText("stopped")])]);
		const { sessionId } = await startLiveSession(deps, { deviceId: DEVICE, task: "t" });
		const step = await nextLiveStep(deps, sessionId, DEVICE, 8);
		expect(step).toMatchObject({ step: { tool: "screen_observe" } });
		await stopLiveSession(deps, sessionId, DEVICE);
		const doc = await sessionDoc(sessionId);
		expect(doc?.status).toBe("ended");
		expect(doc?.endReason).toBe("stopped");
		expect(await nextLiveStep(deps, sessionId, DEVICE, 1)).toMatchObject({ ended: true, reason: "stopped" });
		// A late answer to the abandoned step is refused, not applied.
		await expect(answerLiveStep(deps, sessionId, (step as { step: { stepId: string } }).step.stepId, DEVICE, { outcome: "ok", tree })).rejects.toMatchObject({ status: 409 });
	});

	it("a step the phone never answers fails the call, and two in a row end the session", async () => {
		configureLive({ stepTimeoutMs: 150 });
		script([
			() => fauxAssistantMessage([fauxToolCall("screen_observe", {})], { stopReason: "toolUse" }),
			() => fauxAssistantMessage([fauxToolCall("screen_observe", {})], { stopReason: "toolUse" }),
			() => fauxAssistantMessage([fauxText("giving up")]),
		]);
		const { sessionId } = await startLiveSession(deps, { deviceId: DEVICE, task: "t" });
		const doc = await until(async () => {
			const d = await sessionDoc(sessionId);
			return d?.status === "ended" ? d : undefined;
		});
		expect(doc.endReason).toBe("app_lost");
		expect(seenToolResults.some((r) => /did not answer|no answer/i.test(r))).toBe(true);
	});

	it("enforces the server-side step budget (defence in depth beside the app's)", async () => {
		configureLive({ maxSteps: 2 });
		script([() => fauxAssistantMessage([fauxToolCall("screen_observe", {})], { stopReason: "toolUse" })]);
		const { sessionId } = await startLiveSession(deps, { deviceId: DEVICE, task: "t" });
		for (let i = 0; i < 2; i++) {
			const next = await nextLiveStep(deps, sessionId, DEVICE, 8);
			await answerLiveStep(deps, sessionId, (next as { step: { stepId: string } }).step.stepId, DEVICE, { outcome: "ok", tree });
		}
		const doc = await until(async () => {
			const d = await sessionDoc(sessionId);
			return d?.status === "ended" ? d : undefined;
		});
		expect(doc.endReason).toBe("budget");
		expect(seenToolResults.some((r) => /budget/i.test(r))).toBe(true);
	});

	it("only the device that started the session may poll, answer or stop it", async () => {
		script([() => fauxAssistantMessage([fauxToolCall("screen_observe", {})], { stopReason: "toolUse" })]);
		const { sessionId } = await startLiveSession(deps, { deviceId: DEVICE, task: "t" });
		await expect(nextLiveStep(deps, sessionId, "dev_other", 1)).rejects.toMatchObject({ status: 404 });
		await expect(stopLiveSession(deps, sessionId, "dev_other")).rejects.toMatchObject({ status: 404 });
		await expect(nextLiveStep(deps, "ls-nope", DEVICE, 1)).rejects.toMatchObject({ status: 404 });
		await stopLiveSession(deps, sessionId, DEVICE);
	});

	it("a server restart ends every running session as interrupted (a step is never resent)", async () => {
		script([() => fauxAssistantMessage([fauxToolCall("screen_observe", {})], { stopReason: "toolUse" })]);
		const { sessionId } = await startLiveSession(deps, { deviceId: DEVICE, task: "t" });
		await nextLiveStep(deps, sessionId, DEVICE, 8);
		const swept = await sweepLiveSessions(t.harness, context);
		expect(swept).toBe(1);
		const doc = await sessionDoc(sessionId);
		expect(doc?.status).toBe("ended");
		expect(doc?.endReason).toBe("interrupted");
	});

	it("refuses an unknown action or a malformed screen_act without bothering the phone", async () => {
		script([
			() => fauxAssistantMessage([fauxToolCall("screen_act", { action: "format_disk" })], { stopReason: "toolUse" }),
			() => fauxAssistantMessage([fauxToolCall("screen_act", { action: "tap" })], { stopReason: "toolUse" }),
			() => fauxAssistantMessage([fauxToolCall("screen_end", { summary: "x" })], { stopReason: "toolUse" }),
			() => fauxAssistantMessage([fauxText("ok")]),
		]);
		const { sessionId } = await startLiveSession(deps, { deviceId: DEVICE, task: "t" });
		const outcome = await nextLiveStep(deps, sessionId, DEVICE, 8);
		expect(outcome).toMatchObject({ ended: true });
		expect(seenToolResults.join("\n")).toMatch(/not a screen action|unknown action/i);
		expect(seenToolResults.join("\n")).toMatch(/nodeId/);
	});
});

// ————— A15.3: a phone session whose model fails before any output —————

describe("A15.3: a failed session turn is said plainly", () => {
	it("the session's failed model call leaves one plain say line and marks the model", async () => {
		// Hermetic: no real auth file is stat-ed (the mtime check finds nothing and matches nothing).
		process.env.PHONE_STEWARD_PI_DIR = "/nonexistent-p13s-hermetic";
		setActiveModels({ getModel: (_provider, id) => t.faux!.getModel(id) });
		try {
			t.faux!.setResponses([fauxAssistantMessage([], { stopReason: "error", errorMessage: "401: Your authentication token has expired" })]);
			const { sessionId } = await startLiveSession(deps, { deviceId: DEVICE, task: "t" });
			const said = await until(async () => {
				const events = (await t.commit(async (tx) => JSON.parse(JSON.stringify((await tx.doc(ChatEvents)).events)))) as Array<{
					type: string;
					data: { kind?: string; sessionId?: string; text?: string };
				}>;
				const says = events.filter((e) => e.type === "phone_use" && e.data.kind === "say" && e.data.sessionId === sessionId);
				return says.length > 0 ? says : undefined;
			});
			console.log("SAYS:", JSON.stringify(said, null, 1));
			expect(said.length).toBe(1);
			expect(said[0]!.data.text).toBe("Faux Model could not run: its sign-in on the server has expired. Pick another model in Settings.");
			// The session still ends as an error, as today, and the model is marked.
			expect((await sessionDoc(sessionId))?.endReason).toBe("error");
			const problems = await t.commit(async (tx) => JSON.parse(JSON.stringify((await tx.doc(ModelProblems)).problems))) as Record<string, { problem: string }>;
			expect(Object.values(problems).map((p) => p.problem)).toContain("its sign-in on the server has expired");
		} finally {
			delete process.env.PHONE_STEWARD_PI_DIR;
			setActiveModels(undefined);
		}
	});

	it("an ordinary session failure says nothing extra", async () => {
		process.env.PHONE_STEWARD_PI_DIR = "/nonexistent-p13s-hermetic";
		try {
			t.faux!.setResponses([fauxAssistantMessage([], { stopReason: "error", errorMessage: "Model returned an unusable response" })]);
			const { sessionId } = await startLiveSession(deps, { deviceId: DEVICE, task: "t" });
			await until(async () => ((await sessionDoc(sessionId))?.status === "ended" ? true : undefined));
			const events = (await t.commit(async (tx) => JSON.parse(JSON.stringify((await tx.doc(ChatEvents)).events)))) as Array<{
				type: string;
				data: { kind?: string; sessionId?: string; text?: string };
			}>;
			expect(events.filter((e) => e.type === "phone_use" && e.data.kind === "say" && e.data.sessionId === sessionId)).toEqual([]);
		} finally {
			delete process.env.PHONE_STEWARD_PI_DIR;
		}
	});
});

describe("A15.3: a failure after the model already spoke keeps today's silence", () => {
	it("adds no plain line when the turn had output before the failure", async () => {
		process.env.PHONE_STEWARD_PI_DIR = "/nonexistent-p13s-hermetic";
		try {
			t.faux!.setResponses([
				() => fauxAssistantMessage([fauxText("Let me look."), fauxToolCall("screen_observe", {})], { stopReason: "toolUse" }),
				fauxAssistantMessage([], { stopReason: "error", errorMessage: "401: Your authentication token has expired" }),
			]);
			const { sessionId } = await startLiveSession(deps, { deviceId: DEVICE, task: "t" });
			// The test is the phone: answer the one observe step.
			const step = await nextLiveStep(deps, sessionId, DEVICE, 5);
			expect(step).toMatchObject({ step: { tool: "screen_observe" } });
			await answerLiveStep(deps, sessionId, (step as { step: { stepId: string } }).step.stepId, DEVICE, { outcome: "ok", tree });
			await until(async () => ((await sessionDoc(sessionId))?.status === "ended" ? true : undefined));
			const events = (await t.commit(async (tx) => JSON.parse(JSON.stringify((await tx.doc(ChatEvents)).events)))) as Array<{
				type: string;
				data: { kind?: string; sessionId?: string; text?: string };
			}>;
			const says = events.filter((e) => e.type === "phone_use" && e.data.kind === "say" && e.data.sessionId === sessionId).map((e) => e.data.text);
			expect(says).toEqual(["Let me look."]);
		} finally {
			delete process.env.PHONE_STEWARD_PI_DIR;
		}
	});
});

// ————— A15.1: the session prompt asks for persistence, and every step result frames the budget —————

import { liveScreenExtension } from "../src/live/tools.ts";
import { DEFAULT_LIVE_CONFIG } from "../src/live/state.ts";

function livePromptText(): string {
	const extension = liveScreenExtension as unknown as { sections: Array<{ key: string; render: (input?: unknown) => string }> };
	return extension.sections.map((s) => s.render({} as never)).join("\n");
}

describe("A15.1: the session prompt asks for persistence", () => {
	it("tells the model to explore before ending: scroll, overflow menus, tabs, search, an alternative route", () => {
		const text = livePromptText();
		for (const hint of ["scroll", "More options", "tabs", "search", "alternative route"]) {
			expect(text.toLowerCase()).toContain(hint.toLowerCase());
		}
	});

	it("ends only when the task is done, the app refused, the owner said stop, or the budget is nearly spent — after at least three distinct routes", () => {
		const text = livePromptText();
		for (const word of ["only", "done", "refus", "stop", "budget"]) expect(text.toLowerCase()).toContain(word);
		expect(text).toMatch(/three distinct routes/i);
	});

	it("states the phone's 40-step limit, the 60-step backstop and 15 minutes, and keeps every safety line", () => {
		const text = livePromptText();
		expect(text).toContain("40 steps");
		expect(text).toContain("60");
		expect(text).toContain("15 minutes");
		expect(text).toContain("15 minutes");
		// §13.3 safety lines stay .
		expect(text).toMatch(/untrusted text/i);
		expect(text).toMatch(/passwords, PINs or one-time codes/);
		expect(text).toMatch(/Clear storage or Clear data/);
		expect(text).toMatch(/off limits/);
		expect(text).toMatch(/screen_ask/);
	});

	it("the default step budget is 60 with the 15 minutes unchanged", () => {
		expect(DEFAULT_LIVE_CONFIG.maxSteps).toBe(60);
		expect(DEFAULT_LIVE_CONFIG.maxDurationMs).toBe(15 * 60_000);
	});

	it("every step result starts with step N of M", async () => {
		script([
			() => fauxAssistantMessage([fauxToolCall("screen_observe", {})], { stopReason: "toolUse" }),
			() => fauxAssistantMessage([fauxToolCall("screen_act", { action: "tap", nodeId: "n2" })], { stopReason: "toolUse" }),
			() => fauxAssistantMessage([fauxText("done")]),
		]);
		const { sessionId } = await startLiveSession(deps, { deviceId: DEVICE, task: "t" });
		const observe = await nextLiveStep(deps, sessionId, DEVICE, 5);
		await answerLiveStep(deps, sessionId, (observe as { step: { stepId: string } }).step.stepId, DEVICE, { outcome: "ok", tree });
		const act = await nextLiveStep(deps, sessionId, DEVICE, 5);
		await answerLiveStep(deps, sessionId, (act as { step: { stepId: string } }).step.stepId, DEVICE, { outcome: "ok", tree, nodeText: "Clear cache" });
		await until(async () => ((await sessionDoc(sessionId))?.status === "ended" ? true : undefined));
		const framed = seenToolResults.filter((r) => /^step \d+ of \d+/.test(r));
		expect(framed.length).toBeGreaterThanOrEqual(2);
		expect(framed[0]).toMatch(/^step 1 of 60/);
		expect(framed[1]).toMatch(/^step 2 of 60/);
	});
});

// ————— A15.2: reopen the app when Phone Steward is in front —————

describe("A15.2: reopen the app after a question", () => {
	const stewardTree = { ...tree, package: "com.example.phonesteward", activity: "Chat" };
	const mapsTree = { ...tree, package: "com.example.maps", activity: "Maps" };

	it("a step whose tree shows Phone Steward in front adds the one reopen line naming the last other package", async () => {
		script([
			() => fauxAssistantMessage([fauxToolCall("screen_observe", {})], { stopReason: "toolUse" }),
			() => fauxAssistantMessage([fauxToolCall("screen_ask", { question: "Which account?" })], { stopReason: "toolUse" }),
			() => fauxAssistantMessage([fauxToolCall("screen_observe", {})], { stopReason: "toolUse" }),
			() => fauxAssistantMessage([fauxText("done")]),
		]);
		const { sessionId } = await startLiveSession(deps, { deviceId: DEVICE, task: "t" });
		const observe = await nextLiveStep(deps, sessionId, DEVICE, 5);
		await answerLiveStep(deps, sessionId, (observe as { step: { stepId: string } }).step.stepId, DEVICE, { outcome: "ok", tree: mapsTree });
		const ask = await nextLiveStep(deps, sessionId, DEVICE, 5);
		expect(ask).toEqual({ step: null }); // the ask waits server-side; the app sees no step
		await until(async () => {
			const doc = await sessionDoc(sessionId);
			return Object.values(doc?.asks ?? {}).some((a) => a.status === "waiting") ? true : undefined;
		});
		expect(await deliverOwnerMessage(deps, "The home one")).toMatchObject({ kind: "answered_ask" });
		await until(async () => seenToolResults.some((r) => r.includes("The home one")));
		// The owner answered in Chat, so the app came to the front: observe again.
		const observe2 = await nextLiveStep(deps, sessionId, DEVICE, 5);
		await answerLiveStep(deps, sessionId, (observe2 as { step: { stepId: string } }).step.stepId, DEVICE, { outcome: "ok", tree: stewardTree });
		await until(async () => (seenToolResults.some((r) => r.includes("com.example.phonesteward")) ? true : undefined));
		const stewardResult = seenToolResults.findLast((r) => r.includes("com.example.phonesteward"));
		expect(stewardResult).toBeDefined();
		expect(stewardResult).toContain("Phone Steward is in front (the owner answered in Chat). Reopen com.example.maps with open_app before acting.");
		// The first observe (Maps in front) has no reopen line.
		const mapsResult = seenToolResults.find((r) => r.includes("com.example.maps"));
		expect(mapsResult).not.toContain("Reopen");
	});

	it("every screen_ask reply carries the reopen line", async () => {
		script([
			() => fauxAssistantMessage([fauxToolCall("screen_observe", {})], { stopReason: "toolUse" }),
			() => fauxAssistantMessage([fauxToolCall("screen_ask", { question: "Proceed?" })], { stopReason: "toolUse" }),
			() => fauxAssistantMessage([fauxText("done")]),
		]);
		const { sessionId } = await startLiveSession(deps, { deviceId: DEVICE, task: "t" });
		const observe = await nextLiveStep(deps, sessionId, DEVICE, 5);
		await answerLiveStep(deps, sessionId, (observe as { step: { stepId: string } }).step.stepId, DEVICE, { outcome: "ok", tree: mapsTree });
		// The ask: the next tool call is screen_ask; it waits for the owner's chat reply.
		await until(async () => {
			const doc = await sessionDoc(sessionId);
			return Object.values(doc?.asks ?? {}).some((a) => a.status === "waiting") ? true : undefined;
		});
		expect(await deliverOwnerMessage(deps, "Yes")).toMatchObject({ kind: "answered_ask" });
		await until(async () => (seenToolResults.some((r) => r.includes("Yes")) ? true : undefined));
		const askResult = seenToolResults.findLast((r) => r.includes("Yes"));
		expect(askResult).toContain("Phone Steward is in front (the owner answered in Chat). Reopen com.example.maps with open_app before acting.");
	});

	it("a refused step that names the steward's own app gets the same line", async () => {
		script([
			() => fauxAssistantMessage([fauxToolCall("screen_observe", {})], { stopReason: "toolUse" }),
			() => fauxAssistantMessage([fauxToolCall("screen_act", { action: "tap", nodeId: "n2" })], { stopReason: "toolUse" }),
			() => fauxAssistantMessage([fauxText("done")]),
		]);
		const { sessionId } = await startLiveSession(deps, { deviceId: DEVICE, task: "t" });
		const observe = await nextLiveStep(deps, sessionId, DEVICE, 5);
		await answerLiveStep(deps, sessionId, (observe as { step: { stepId: string } }).step.stepId, DEVICE, { outcome: "ok", tree: mapsTree });
		const act = await nextLiveStep(deps, sessionId, DEVICE, 5);
		await answerLiveStep(deps, sessionId, (act as { step: { stepId: string } }).step.stepId, DEVICE, {
			outcome: "refused",
			detail: "refused: this is the steward's own app",
			tree: stewardTree,
		});
		await until(async () => (seenToolResults.filter((r) => r.includes("refused")).length >= 1 ? true : undefined));
		const refused = seenToolResults.findLast((r) => r.includes("refused"));
		expect(refused).toContain("Phone Steward is in front (the owner answered in Chat). Reopen com.example.maps with open_app before acting.");
	});

	it("without a last other package the line stays useful but names no package", async () => {
		script([
			() => fauxAssistantMessage([fauxToolCall("screen_ask", { question: "Start where?" })], { stopReason: "toolUse" }),
			() => fauxAssistantMessage([fauxText("done")]),
		]);
		const { sessionId } = await startLiveSession(deps, { deviceId: DEVICE, task: "t" });
		await until(async () => {
			const doc = await sessionDoc(sessionId);
			return Object.values(doc?.asks ?? {}).some((a) => a.status === "waiting") ? true : undefined;
		});
		expect(await deliverOwnerMessage(deps, "Maps")).toMatchObject({ kind: "answered_ask" });
		await until(async () => (seenToolResults.some((r) => r.includes("Maps")) ? true : undefined));
		const askResult = seenToolResults.findLast((r) => r.includes("Maps"));
		expect(askResult).toContain("Phone Steward is in front (the owner answered in Chat).");
		expect(askResult).toMatch(/Reopen the app you were using with open_app before acting\./);
	});
});

// ————— A15.11: the prompt explains the allow-list offer —————

describe("A15.11: the prompt explains the allow-list offer", () => {
	it("says an app outside the allow list may be offered to the owner, and what to do after 'not now'", () => {
		const text = livePromptText();
		expect(text).toMatch(/allow list/);
		expect(text).toMatch(/not now/i);
		expect(text).toMatch(/another route|alternative route/i);
		expect(text).toMatch(/say which app|which app you needed/i);
	});
});

// ————— the session's first line names the model its turn will run on —————

describe("the started event carries the model the session will run on", () => {
	it("names the steward's current model setting on the phone_use started event, as messages do", async () => {
		setActiveModels({ getModel: (_provider, id) => t.faux!.getModel(id) });
		try {
			script([() => fauxAssistantMessage([fauxText("ok")])]);
			const { sessionId } = await startLiveSession(deps, { deviceId: DEVICE, task: "tidy up" });
			// One store serves both readers: `/v1/chat/stream` reads `chatEventsAfter(tx, after)` and
			// `/v1/chat/history` reads `chatEventsAfter(tx, 0)` (server/src/http.ts:660 and :689), so a
			// started event that carries the field here carries it live and in history.
			const started = await until(async () => {
				const events = (await t.commit((tx) => chatEventsAfter(tx, 0))) as Array<{
					type: string;
					data: { kind?: string; sessionId?: string; task?: string; model?: unknown };
				}>;
				return events.find((e) => e.type === "phone_use" && e.data.kind === "started" && e.data.sessionId === sessionId);
			});
			expect(started.data.task).toBe("tidy up");
			// The same shape a steward message carries: three keys, no more.
			expect(started.data.model).toEqual({ provider: "faux", id: t.faux!.getModel().id, label: "Faux Model" });
		} finally {
			setActiveModels(undefined);
		}
	});
});
