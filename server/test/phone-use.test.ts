/**
 * Protocol A13 server half: phone use from Chat. The steward asks with
 * `phone_use_request` (A13.1), the owner answers through the new routes (A13.2), the
 * session shows in Chat as `phone_use` events (A13.3), `screen_ask` and owner messages
 * route into the running session (A13.4), and the end hands back to the main conversation
 * as a `phone_use` run (A13.5). Fakes only: the faux model is scripted, no real phone.
 */
import { BACKGROUND_CONTEXT } from "@earendil-works/chord/context";
import { fauxAssistantMessage, fauxText, fauxToolCall } from "@earendil-works/pi-ai/providers/faux";
import { afterEach, beforeEach, describe, expect, it } from "vitest";
import { ChatEvents } from "../src/chat-events.ts";
import { Decisions, type DecisionLedgerEntry } from "../src/documents.ts";
import {
	LiveSessions,
	configureLive,
	deliverOwnerMessage,
	handbackEndedSessions,
	nextLiveStep,
	startLiveSession,
	stopLiveSession,
	sweepLiveSessions,
	type LiveDeps,
} from "../src/live/sessions.ts";
import { PhoneUseRequests } from "../src/live/requests.ts";
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

async function sessionDoc(sessionId: string) {
	return (await t.snapshot(LiveSessions))?.sessions[sessionId];
}

async function phoneUseEvents() {
	const events = await chatEvents();
	return events.filter((e) => e.type === "phone_use").map((e) => e.data as Record<string, unknown>);
}

async function rootUserTexts(): Promise<string[]> {
	const page = await t.root.entries({}, 200, undefined, context);
	return page.items
		.map((entry) => entry.model?.[0])
		.filter((message) => message !== undefined && message.role === "user")
		.map((message) => (typeof (message as { content?: unknown }).content === "string" ? ((message as { content: string }).content) : ""));
}

let t: TestHarness;
let deps: LiveDeps;
const seenToolResults: string[] = [];

/** A scripted model: each request returns the next scripted assistant turn; tool results are captured. */
function script(turns: Array<() => ReturnType<typeof fauxAssistantMessage>>): void {
	let index = 0;
	t.faux!.setResponses(
		Array.from({ length: 16 }, () => (request) => {
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
	t = await openTestHarness();
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

async function requestsDoc() {
	return (await t.snapshot(PhoneUseRequests))?.requests ?? {};
}

async function chatEvents() {
	return (await t.commit(async (tx) => (await tx.doc(ChatEvents)).events.map((e) => JSON.parse(JSON.stringify(e)) as Record<string, unknown>)));
}

/** Submit an owner message with a run opened first (what POST /v1/chat does), and wait for the turn to settle. */
async function ownerTurn(text: string, requestId: string, reason: "owner" | "weekly_check" | "phone_result" | "photo_window" = "owner"): Promise<void> {
	const { openRun, closeRun } = await import("../src/run-tracking.ts");
	await t.commit(async (tx) => openRun(tx, t.root.id, requestId, reason, Date.now()));
	const submission = await t.root.submit({ type: "input", content: text, requestId }, context);
	await submission.wait(context);
	await t.commit(async (tx) => closeRun(tx, t.root.id, requestId, Date.now()));
}

describe("phone_use_request (A13.1)", () => {
	it("inside an owner turn it stores a pending request, appends the chat event, writes the ledger row and tells the model to wait", async () => {
		script([
			() => fauxAssistantMessage([fauxToolCall("phone_use_request", { task: "Clear WhatsApp's cache in Settings", why: "Android gives an app no cache API" })], { stopReason: "toolUse" }),
			() => fauxAssistantMessage([fauxText("ok")]),
		]);
		await ownerTurn("please clear WhatsApp's cache", "owner-req-1");
		const requests = await until(async () => {
			const doc = await requestsDoc();
			return Object.keys(doc).length === 1 ? doc : undefined;
		});
		const request = Object.values(requests)[0]!;
		expect(request).toMatchObject({ status: "pending", task: "Clear WhatsApp's cache in Settings", why: "Android gives an app no cache API" });
		expect(String(request.id)).toMatch(/^pu-/);
		// The chat event.
		const events = await chatEvents();
		const phoneUse = events.filter((e) => e.type === "phone_use");
		expect(phoneUse).toHaveLength(1);
		expect(phoneUse[0]!.data).toMatchObject({ kind: "request", requestId: request.id, task: request.task, why: request.why });
		// The ledger row.
		const ledger = await t.commit(async (tx) =>
			(await tx.doc(Decisions, t.root.id)).entries.map((e) => JSON.parse(JSON.stringify(e)) as Record<string, unknown>),
		);
		const row = ledger.find((e) => e.type === "phone_use_request");
		expect(row).toBeDefined();
		// The model is told to wait, not to ask again.
		expect(seenToolResults.join("\n")).toMatch(/Asked the owner in Chat/);
		expect(seenToolResults.join("\n")).toMatch(/do not ask again/i);
	});

	it("a new request supersedes the older pending one, and validation refuses bad arguments without storing", async () => {
		script([
			() => fauxAssistantMessage([fauxToolCall("phone_use_request", { task: "first task", why: "because" })], { stopReason: "toolUse" }),
			() => fauxAssistantMessage([fauxToolCall("phone_use_request", { task: "second task", why: "because" })], { stopReason: "toolUse" }),
			() => fauxAssistantMessage([fauxToolCall("phone_use_request", { task: "", why: "because" })], { stopReason: "toolUse" }),
			() => fauxAssistantMessage([fauxToolCall("phone_use_request", { task: "third", why: "x".repeat(501) })], { stopReason: "toolUse" }),
			() => fauxAssistantMessage([fauxText("done")]),
		]);
		await ownerTurn("do two things", "owner-req-2");
		const requests = await until(async () => {
			const doc = await requestsDoc();
			return Object.keys(doc).length === 2 ? doc : undefined;
		});
		const statuses = Object.values(requests).map((r) => r.status).sort();
		expect(statuses).toEqual(["pending", "superseded"]);
		// Bad arguments: refused, nothing stored (still two requests).
		expect(seenToolResults.join("\n")).toMatch(/refused/);
		expect(Object.keys(await requestsDoc())).toHaveLength(2);
	});

	it("a pending request expires after 10 minutes", async () => {
		const { createRequestTx, pendingRequestTx } = await import("../src/live/requests.ts");
		const now = Date.now();
		const created = await t.commit(async (tx) => createRequestTx(tx, t.root.id, { id: "pu_testexpire01", task: "old task", why: "why" }, now - 11 * 60_000));
		expect(created.status).toBe("pending");
		// Reads expire it lazily.
		const pending = await t.commit(async (tx) => (await pendingRequestTx(tx, now))?.id ?? null);
		expect(pending).toBeNull();
		const doc = await requestsDoc();
		expect(Object.values(doc)[0]!.status).toBe("expired");
	});
});

describe("the tool sets under A13", () => {
	it("the main conversation offers 24 tools including phone_use_request, and no screen tools", async () => {
		const names = await resolveAgentTools(t.root, context);
		expect(names).toHaveLength(24);
		expect(PROTOCOL_TOOL_NAMES).toContain("phone_use_request");
		expect(names).toContain("phone_use_request");
		for (const name of ["screen_observe", "screen_act", "screen_ask", "screen_end"]) expect(names).not.toContain(name);
	});

	it("the live conversation offers ONLY the four screen tools, never phone_use_request", async () => {
		const { liveConversationTools } = await import("../src/live/sessions.ts");
		const { LIVE_SCREEN_TOOLS } = await import("../src/live/state.ts");
		script([() => fauxAssistantMessage([fauxText("ok")])]);
		const { sessionId } = await startLiveSession(deps, { deviceId: DEVICE, task: "t" });
		const live = (await t.snapshot(LiveSessions))?.sessions[sessionId];
		const names = await liveConversationTools(deps, live!.conversationId);
		expect([...names].sort()).toEqual(["screen_act", "screen_ask", "screen_end", "screen_observe"]);
		expect([...LIVE_SCREEN_TOOLS].sort()).toEqual(["screen_act", "screen_ask", "screen_end", "screen_observe"]);
		expect(names).not.toContain("phone_use_request");
	});

	it("a phone_use_request call inside a live conversation is blocked by the live guard", async () => {
		const { liveGuardBlock } = await import("../src/live/tools.ts");
		expect(liveGuardBlock("phone_use_request")).toBeTruthy();
		expect(liveGuardBlock("screen_ask")).toBeUndefined();
		// And at runtime: the scripted live model tries to ask the main conversation's tool.
		script([
			() => fauxAssistantMessage([fauxToolCall("phone_use_request", { task: "t", why: "w" })], { stopReason: "toolUse" }),
			() => fauxAssistantMessage([fauxToolCall("screen_end", { summary: "gave up" })], { stopReason: "toolUse" }),
			() => fauxAssistantMessage([fauxText("done")]),
		]);
		const { sessionId } = await startLiveSession(deps, { deviceId: DEVICE, task: "t" });
		await until(async () => {
			const session = (await t.snapshot(LiveSessions))?.sessions[sessionId];
			return session?.status === "ended" ? session : undefined;
		});
		expect(seenToolResults.join("\n")).toMatch(/not allowed in a phone session|is not available|blocked/i);
		expect(await requestsDoc()).toEqual({});
	});
});

describe("the session is shown in Chat (A13.3)", () => {
	it("emits started, step and ended events with summaries and outcomes, never the tree", async () => {
		script([
			() => fauxAssistantMessage([fauxToolCall("screen_observe", {})], { stopReason: "toolUse" }),
			() => fauxAssistantMessage([fauxToolCall("screen_act", { action: "tap", nodeId: "n2" })], { stopReason: "toolUse" }),
			() => fauxAssistantMessage([fauxToolCall("screen_end", { summary: "Cleared the cache." })], { stopReason: "toolUse" }),
			() => fauxAssistantMessage([fauxText("Done.")]),
		]);
		const { answerLiveStep } = await import("../src/live/sessions.ts");
		const { sessionId } = await startLiveSession(deps, { deviceId: DEVICE, task: "clear one cache" });
		const first = await nextLiveStep(deps, sessionId, DEVICE, 8);
		await answerLiveStep(deps, sessionId, (first as { step: { stepId: string } }).step.stepId, DEVICE, { outcome: "ok", tree });
		const second = await nextLiveStep(deps, sessionId, DEVICE, 8);
		await answerLiveStep(deps, sessionId, (second as { step: { stepId: string } }).step.stepId, DEVICE, {
			outcome: "ok",
			detail: "tapped",
			nodeText: "Clear cache",
		});
		await until(async () => ((await sessionDoc(sessionId))?.status === "ended" ? "ended" : undefined));
		const events = await phoneUseEvents();
		const kinds = events.map((e) => e.kind);
		expect(kinds).toContain("started");
		expect(events.find((e) => e.kind === "started")).toMatchObject({ sessionId, task: "clear one cache" });
		const steps = events.filter((e) => e.kind === "step");
		expect(steps).toHaveLength(2);
		expect(steps[0]).toMatchObject({ sessionId, tool: "screen_observe", summary: "observe com.android.settings (2 items)", outcome: "ok" });
		expect(steps[1]).toMatchObject({ sessionId, tool: "screen_act", summary: 'tap "Clear cache"', outcome: "ok" });
		// Never the tree, never JSON of it.
		for (const step of steps) expect(String(step.summary)).not.toContain("n2");
		const ended = events.find((e) => e.kind === "ended");
		expect(ended).toMatchObject({ sessionId, reason: "finished", summary: "Cleared the cache.", steps: 2 });
	});

	it("a refused action still shows its step with outcome refused, and a failed one outcome failed", async () => {
		script([
			() => fauxAssistantMessage([fauxToolCall("screen_act", { action: "tap", nodeId: "n2" })], { stopReason: "toolUse" }),
			() => fauxAssistantMessage([fauxToolCall("screen_end", { summary: "The app refused." })], { stopReason: "toolUse" }),
			() => fauxAssistantMessage([fauxText("Done.")]),
		]);
		const { answerLiveStep } = await import("../src/live/sessions.ts");
		const { sessionId } = await startLiveSession(deps, { deviceId: DEVICE, task: "t" });
		const step = await nextLiveStep(deps, sessionId, DEVICE, 8);
		await answerLiveStep(deps, sessionId, (step as { step: { stepId: string } }).step.stepId, DEVICE, {
			outcome: "refused",
			detail: "the owner was asked and refused",
			nodeText: "Uninstall",
		});
		await until(async () => ((await sessionDoc(sessionId))?.status === "ended" ? "ended" : undefined));
		const steps = (await phoneUseEvents()).filter((e) => e.kind === "step");
		expect(steps).toHaveLength(1);
		expect(steps[0]).toMatchObject({ tool: "screen_act", summary: 'tap "Uninstall"', outcome: "refused" });
	});

	it("the session model's own text becomes say events, masked as S13", async () => {
		script([
			() => fauxAssistantMessage([fauxText("I see the verification code field. The screen shows 482913 in it.")], { stopReason: "toolUse" }),
			() => fauxAssistantMessage([fauxToolCall("screen_end", { summary: "done" })], { stopReason: "toolUse" }),
			() => fauxAssistantMessage([fauxText("ok")]),
		]);
		const { sessionId } = await startLiveSession(deps, { deviceId: DEVICE, task: "t" });
		await until(async () => ((await sessionDoc(sessionId))?.status === "ended" ? "ended" : undefined));
		const says = (await phoneUseEvents()).filter((e) => e.kind === "say");
		expect(says).toHaveLength(1);
		expect(says[0]).toMatchObject({ sessionId });
		expect(String(says[0]!.text)).not.toContain("482913");
		expect(String(says[0]!.text)).toContain("verification code");
	});

	it("the ended event and the finished line mask a one-time code in the summary (S13, correction 1 item 3)", async () => {
		script([
			() => fauxAssistantMessage([fauxToolCall("screen_end", { summary: "The verification code field showed 482913; I cleared the cache instead." })], { stopReason: "toolUse" }),
			() => fauxAssistantMessage([fauxText("done")]),
		]);
		const { sessionId } = await startLiveSession(deps, { deviceId: DEVICE, task: "t" });
		await until(async () => ((await sessionDoc(sessionId))?.status === "ended" ? "ended" : undefined));
		const events = await chatEvents();
		const blob = JSON.stringify(events);
		expect(blob).not.toContain("482913");
		const ended = (await phoneUseEvents()).find((e) => e.kind === "ended");
		expect(String(ended?.summary)).toContain("verification code field showed \u2022\u2022\u2022\u2022");
	});

	it("a session's end draws one line in Chat: the ended event, no second assistant message (P10-A review finding 4)", async () => {
		script([
			() => fauxAssistantMessage([fauxToolCall("screen_end", { summary: "Cleared one cache." })], { stopReason: "toolUse" }),
			() => fauxAssistantMessage([fauxText("done")]),
		]);
		const { sessionId } = await startLiveSession(deps, { deviceId: DEVICE, task: "t" });
		await until(async () => ((await sessionDoc(sessionId))?.status === "ended" ? "ended" : undefined));
		const events = await chatEvents();
		const endMessages = events.filter((e) => e.type === "message" && /^Phone session (finished|stopped|ended)/.test(String((e.data as Record<string, unknown>).text ?? "")));
		expect(endMessages).toHaveLength(0);
		const ended = (await phoneUseEvents()).filter((e) => e.kind === "ended");
		expect(ended).toHaveLength(1);
		expect(ended[0]).toMatchObject({ sessionId, reason: "finished", summary: "Cleared one cache." });
	});
});

describe("request expiry on the clock (correction 1 item 4)", () => {
	it("the live sweep expires a pending phone-use request past its 10 minutes", async () => {
		const { createRequestTx } = await import("../src/live/requests.ts");
		const { expireLiveSessions } = await import("../src/live/sessions.ts");
		const now = Date.now();
		await t.commit(async (tx) => createRequestTx(tx, t.root.id, { id: "pu_sweepexpire1", task: "old ask", why: "why" }, now - 11 * 60_000));
		expect(await expireLiveSessions(deps, now)).toBe(0);
		const doc = await requestsDoc();
		expect(Object.values(doc)[0]!.status).toBe("expired");
		// A fresh pending request is untouched.
		await t.commit(async (tx) => createRequestTx(tx, t.root.id, { id: "pu_sweepfresh2", task: "fresh ask", why: "why" }, now - 60_000));
		await expireLiveSessions(deps, now);
		expect((await requestsDoc())["pu_sweepfresh2"]!.status).toBe("pending");
	});
});

describe("screen_ask and owner messages (A13.4)", () => {
	it("screen_ask waits for the owner's chat reply and returns it as the tool result", async () => {
		configureLive({ askWaitMs: 2000 });
		script([
			() => fauxAssistantMessage([fauxToolCall("screen_ask", { question: "Which app should I clear?" })], { stopReason: "toolUse" }),
			() => fauxAssistantMessage([fauxToolCall("screen_end", { summary: "Cleared the chosen app." })], { stopReason: "toolUse" }),
			() => fauxAssistantMessage([fauxText("done")]),
		]);
		const { sessionId } = await startLiveSession(deps, { deviceId: DEVICE, task: "clear one app" });
		// No phone step while asking; the app's poll keeps the session alive.
		expect(await nextLiveStep(deps, sessionId, DEVICE, 1)).toMatchObject({ step: null });
		// The owner's reply arrives through the chat path.
		const delivered = await until(async () => {
			const result = await deliverOwnerMessage(deps, "WhatsApp, please");
			return result.kind === "answered_ask" ? result : undefined;
		});
		expect(delivered.sessionId).toBe(sessionId);
		await until(async () => ((await sessionDoc(sessionId))?.status === "ended" ? "ended" : undefined));
		expect(seenToolResults.join("\n")).toContain("WhatsApp, please");
		// The ask is recorded and closed.
		const doc = await sessionDoc(sessionId);
		const asks = Object.values(doc?.asks ?? {});
		expect(asks).toHaveLength(1);
		expect(asks[0]).toMatchObject({ status: "answered", reply: "WhatsApp, please" });
		// The chat log carried the ask and the owner's message.
		const events = await chatEvents();
		expect(events.find((e) => e.type === "phone_use" && (e.data as Record<string, unknown>).kind === "ask")).toBeDefined();
		expect(events.find((e) => e.type === "message" && (e.data as Record<string, unknown>).text === "WhatsApp, please")).toBeDefined();
	});

	it("an owner message with no pending ask is queued and appended to the next screen tool result as [owner says]", async () => {
		script([
			() => fauxAssistantMessage([fauxToolCall("screen_observe", {})], { stopReason: "toolUse" }),
			() => fauxAssistantMessage([fauxToolCall("screen_end", { summary: "done" })], { stopReason: "toolUse" }),
			() => fauxAssistantMessage([fauxText("done")]),
		]);
		const { sessionId } = await startLiveSession(deps, { deviceId: DEVICE, task: "t" });
		const delivered = await deliverOwnerMessage(deps, "check the top item");
		expect(delivered).toMatchObject({ kind: "queued", sessionId });
		const step = await nextLiveStep(deps, sessionId, DEVICE, 8);
		await import("../src/live/sessions.ts").then(({ answerLiveStep }) =>
			answerLiveStep(deps, sessionId, (step as { step: { stepId: string } }).step.stepId, DEVICE, { outcome: "ok", tree }),
		);
		await until(async () => ((await sessionDoc(sessionId))?.status === "ended" ? "ended" : undefined));
		const observeResult = seenToolResults.find((r) => r.includes("outcome: ok") && r.includes("screen"));
		expect(observeResult).toContain("[owner says] check the top item");
		// The note is drained: it is appended once.
		expect(observeResult!.split("[owner says]")).toHaveLength(2);
	});

	it("a waiting ask is released by Stop, and polling during the ask never trips app_lost", async () => {
		configureLive({ askWaitMs: 8000, maxIdleMs: 400 });
		script([
			() => fauxAssistantMessage([fauxToolCall("screen_ask", { question: "Proceed?" })], { stopReason: "toolUse" }),
			() => fauxAssistantMessage([fauxText("ended")]),
		]);
		const { expireLiveSessions } = await import("../src/live/sessions.ts");
		const { sessionId } = await startLiveSession(deps, { deviceId: DEVICE, task: "t" });
		await nextLiveStep(deps, sessionId, DEVICE, 1);
		// The ask is waiting; the app keeps polling (a poll is a sign of life).
		await new Promise((resolve) => setTimeout(resolve, 300));
		await nextLiveStep(deps, sessionId, DEVICE, 1);
		expect(await expireLiveSessions(deps, Date.now())).toBe(0);
		expect((await sessionDoc(sessionId))?.status).toBe("running");
		// Stop releases the waiting ask (the turn is aborted, so the tool result is not
		// delivered to the model; the ask record shows the release).
		await stopLiveSession(deps, sessionId, DEVICE);
		await until(async () => {
			const asks = Object.values((await sessionDoc(sessionId))?.asks ?? {});
			return asks[0] !== undefined && asks[0].status === "no_answer" ? "released" : undefined;
		});
		const doc = await sessionDoc(sessionId);
		expect(doc?.status).toBe("ended");
		expect(doc?.endReason).toBe("stopped");
	});

	it("an ask with no answer returns 'no answer' and the session goes on", async () => {
		configureLive({ askWaitMs: 250 });
		script([
			() => fauxAssistantMessage([fauxToolCall("screen_ask", { question: "Proceed?" })], { stopReason: "toolUse" }),
			() => fauxAssistantMessage([fauxToolCall("screen_end", { summary: "no answer, ending" })], { stopReason: "toolUse" }),
			() => fauxAssistantMessage([fauxText("done")]),
		]);
		const { sessionId } = await startLiveSession(deps, { deviceId: DEVICE, task: "t" });
		await until(async () => ((await sessionDoc(sessionId))?.status === "ended" ? "ended" : undefined));
		expect(seenToolResults.join("\n")).toContain("no answer");
	});

	it("the session limit is 15 minutes", async () => {
		const { liveConfig } = await import("../src/live/state.ts");
		expect(liveConfig.maxDurationMs).toBe(15 * 60_000);
	});
});

describe("run attribution (correction 1: the turn's own input decides, not the stale run mirror)", () => {
	/** Open a run the way each server path does and submit the input it would submit. */
	async function serverTurn(input: { content: string; requestId: string; reason: "owner" | "weekly_check" | "phone_result" | "photo_window" | "phone_use"; close: boolean }): Promise<void> {
		const { openRun, closeRun } = await import("../src/run-tracking.ts");
		await t.commit(async (tx) => openRun(tx, t.root.id, input.requestId, input.reason, Date.now()));
		const submission = await t.root.submit({ type: "input", content: input.content, requestId: input.requestId }, context);
		await submission.wait(context);
		if (input.close) await t.commit(async (tx) => closeRun(tx, t.root.id, input.requestId, Date.now()));
	}

	it("an owner turn may ask even while a phone-result run is still open (the review's false refusal)", async () => {
		script([
			() => fauxAssistantMessage([fauxToolCall("phone_use_request", { task: "Clear one app's cache", why: "no API" })], { stopReason: "toolUse" }),
			() => fauxAssistantMessage([fauxText("ok")]),
		]);
		// A phone result's run stays open across later turns (its settle waits for idle).
		const { openRun } = await import("../src/run-tracking.ts");
		await t.commit(async (tx) => openRun(tx, t.root.id, "phonejob:stale-1", "phone_result", Date.now() - 1000));
		await ownerTurn("please clear one app's cache", "owner-while-open");
		const requests = await until(async () => {
			const doc = await requestsDoc();
			return Object.keys(doc).length === 1 ? doc : undefined;
		});
		expect(Object.values(requests)[0]).toMatchObject({ status: "pending" });
	});

	it("a weekly-check turn is refused even when an owner run was left open (the review's fail-open)", async () => {
		script([
			() => fauxAssistantMessage([fauxToolCall("phone_use_request", { task: "ask from the weekly check", why: "why" })], { stopReason: "toolUse" }),
			() => fauxAssistantMessage([fauxText("ok")]),
		]);
		// An owner run whose detached close was lost: it stays open (only the start-up sweep clears it).
		const { openRun } = await import("../src/run-tracking.ts");
		await t.commit(async (tx) => openRun(tx, t.root.id, "stale-owner-run", "owner", Date.now() - 1000));
		await serverTurn({ content: "[schedule weekly_check 2026-10-09] Run this week's storage check.", requestId: "weekly-check:2026-10-09", reason: "weekly_check", close: false });
		await until(async () => (seenToolResults.some((r) => r.includes("refused")) ? "refused" : undefined));
		expect(await requestsDoc()).toEqual({});
		expect(seenToolResults.join("\n")).toMatch(/scheduled weekly check/);
	});

	it("a phone-result turn, a photo-window turn and a phone_use hand-back turn are each refused", async () => {
		const turns: Array<{ content: string; requestId: string; reason: "phone_result" | "photo_window" | "phone_use"; matches: RegExp; close: boolean }> = [
			{ content: '[phone result pj-abc12345] kind=app_usage status=ok summary={"apps":3}', requestId: "phonejob:pj-abc12345", reason: "phone_result", matches: /a phone result/, close: true },
			// The photo window reaches the model as the media_index result it queued.
			{ content: '[phone result pj-photo999] kind=media_index status=ok summary={"folders":2}', requestId: "phonejob:pj-photo999", reason: "photo_window", matches: /a phone result/, close: true },
			{ content: "[phone use ended] task=clear one cache reason=finished steps=3", requestId: "phoneuse:ls-handback1", reason: "phone_use", matches: /hand-back/, close: true },
		];
		for (const turn of turns) {
			seenToolResults.length = 0;
			script([
				() => fauxAssistantMessage([fauxToolCall("phone_use_request", { task: "ask from a server turn", why: "why" })], { stopReason: "toolUse" }),
				() => fauxAssistantMessage([fauxText("ok")]),
			]);
			await serverTurn(turn);
			await until(async () => (seenToolResults.some((r) => r.includes("refused")) ? "refused" : undefined));
			expect(seenToolResults.join("\n")).toMatch(turn.matches);
			expect(await requestsDoc()).toEqual({});
		}
	});

	it("fail-closed: a turn whose origin cannot be established is refused", async () => {
		const { currentTurnOrigin } = await import("../src/turn-origin.ts");
		const origin = await t.commit(async (tx) => currentTurnOrigin(tx, t.root.id, 999_999 as never));
		expect(origin).toBe("unknown");
		const { phoneUseRequestRefusal } = await import("../src/tools.ts");
		expect(phoneUseRequestRefusal({ task: "t", why: "w" }, "unknown")).toMatch(/unrecognised source/);
	});
});

describe("owner notes routed into a session (correction 1 items 5 and 6)", () => {
	it("queues at most 20 owner notes, each capped at 500 characters, newest kept", async () => {
		script([
			() => fauxAssistantMessage([fauxToolCall("screen_observe", {})], { stopReason: "toolUse" }),
			() => fauxAssistantMessage([fauxToolCall("screen_end", { summary: "done" })], { stopReason: "toolUse" }),
			() => fauxAssistantMessage([fauxText("done")]),
		]);
		const { sessionId } = await startLiveSession(deps, { deviceId: DEVICE, task: "t" });
		for (let i = 1; i <= 21; i++) {
			const text = i === 21 ? `note 21${"x".repeat(600)}` : `note ${i}`;
			const delivered = await deliverOwnerMessage(deps, text);
			expect(delivered).toMatchObject({ kind: "queued" });
		}
		const doc = await sessionDoc(sessionId);
		const notes = doc?.ownerNotes ?? [];
		expect(notes).toHaveLength(20);
		expect(notes[0]).toBe("note 2");
		expect(notes[19]).toMatch(/^note 21x+$/);
		expect(notes[19]!.length).toBe(500);
		await stopLiveSession(deps, sessionId, DEVICE);
	});

	it("notes still queued when the session ends ride with the hand-back, masked and capped", async () => {
		script([
			() => fauxAssistantMessage([fauxToolCall("screen_end", { summary: "finished" })], { stopReason: "toolUse" }),
			() => fauxAssistantMessage([fauxText("done")]),
		]);
		const { sessionId } = await startLiveSession(deps, { deviceId: DEVICE, task: "clear one cache" });
		await deliverOwnerMessage(deps, "stop, that is the wrong app. code 482913");
		await until(async () => ((await sessionDoc(sessionId))?.status === "ended" ? "ended" : undefined));
		const texts = await until(async () => {
			const found = (await rootUserTexts()).filter((text) => text.startsWith("[phone use ended]"));
			return found.length >= 1 ? found : undefined;
		});
		expect(texts).toHaveLength(1);
		expect(texts[0]).toContain("owner said after the last step:");
		expect(texts[0]).toContain("stop, that is the wrong app");
		expect(texts[0]).not.toContain("482913");
	});
});

describe("the hand-back (A13.5)", () => {
	it("a finished session hands back once, as a phone_use run, for the old route too", async () => {
		script([
			() => fauxAssistantMessage([fauxToolCall("screen_end", { summary: "Cleared it." })], { stopReason: "toolUse" }),
			() => fauxAssistantMessage([fauxText("Good.")]),
		]);
		const { sessionId } = await startLiveSession(deps, { deviceId: DEVICE, task: "clear one cache" });
		await until(async () => ((await sessionDoc(sessionId))?.status === "ended" ? "ended" : undefined));
		const texts = await until(async () => {
			const found = (await rootUserTexts()).filter((text) => text.startsWith("[phone use ended]"));
			return found.length >= 1 ? found : undefined;
		});
		expect(texts).toHaveLength(1);
		expect(texts[0]).toContain("task=clear one cache");
		expect(texts[0]).toContain("reason=finished");
		expect(texts[0]).toContain("steps=");
		expect(texts[0]).toContain("summary=Cleared it.");
		// The run is a phone_use run, started and ended once (the run closes when the turn settles).
		const phoneUseStatuses = await until(async () => {
			const runEvents = (await chatEvents()).filter((e) => e.type === "run");
			const statuses = runEvents
				.filter((e) => (e.data as Record<string, unknown>).reason === "phone_use")
				.map((e) => (e.data as Record<string, unknown>).status);
			return statuses.length === 2 && statuses[0] === "started" && statuses[1] === "ended" ? statuses : undefined;
		});
		expect(phoneUseStatuses).toEqual(["started", "ended"]);
		// Asking again changes nothing: exactly once.
		await handbackEndedSessions(deps);
		expect((await rootUserTexts()).filter((text) => text.startsWith("[phone use ended]"))).toHaveLength(1);
	});

	it("an interrupted session hands back once after the restart sweep", async () => {
		script([() => fauxAssistantMessage([fauxToolCall("screen_observe", {})], { stopReason: "toolUse" })]);
		const { sessionId } = await startLiveSession(deps, { deviceId: DEVICE, task: "t" });
		await nextLiveStep(deps, sessionId, DEVICE, 8);
		expect(await sweepLiveSessions(t.harness, context)).toBe(1);
		const texts = await until(async () => {
			const found = (await rootUserTexts()).filter((text) => text.startsWith("[phone use ended]"));
			return found.length >= 1 ? found : undefined;
		});
		expect(texts).toHaveLength(1);
		expect(texts[0]).toContain("reason=interrupted");
		await handbackEndedSessions(deps);
		expect((await rootUserTexts()).filter((text) => text.startsWith("[phone use ended]"))).toHaveLength(1);
	});

	it("a request-started session hands back the same way (A13.2 + A13.5)", async () => {
		const { createRequestTx, answerRequestTx, attachSessionTx } = await import("../src/live/requests.ts");
		const now = Date.now();
		await t.commit(async (tx) => {
			await createRequestTx(tx, t.root.id, { id: "pu_handback01", task: "the requested task", why: "why" }, now);
			await answerRequestTx(tx, t.root.id, "pu_handback01", true, now + 1000);
		});
		script([
			() => fauxAssistantMessage([fauxToolCall("screen_end", { summary: "Requested work done." })], { stopReason: "toolUse" }),
			() => fauxAssistantMessage([fauxText("Done.")]),
		]);
		const { sessionId } = await startLiveSession(deps, { deviceId: DEVICE, task: "the requested task" });
		await t.commit((tx) => attachSessionTx(tx, t.root.id, "pu_handback01", sessionId, Date.now()));
		await until(async () => ((await sessionDoc(sessionId))?.status === "ended" ? "ended" : undefined));
		const texts = await until(async () => {
			const found = (await rootUserTexts()).filter((text) => text.startsWith("[phone use ended]"));
			return found.length >= 1 ? found : undefined;
		});
		expect(texts).toHaveLength(1);
		expect(texts[0]).toContain("task=the requested task");
	});
});

describe("the decline reaches the model through the server note (A13.2, A9.2)", () => {
	it("the note names the declined ask; a fresh note without a decline does not", async () => {
		const { createRequestTx, answerRequestTx } = await import("../src/live/requests.ts");
		const { ownerChatNote } = await import("../src/owner-note.ts");
		const now = Date.now();
		await t.commit(async (tx) => {
			await createRequestTx(tx, t.root.id, { id: "pu_decline01", task: "clear WhatsApp's cache in Settings", why: "no API for it" }, now);
			await answerRequestTx(tx, t.root.id, "pu_decline01", false, now + 1000);
		});
		const requests = Object.values((await t.snapshot((await import("../src/live/requests.ts")).PhoneUseRequests))?.requests ?? {});
		const note = ownerChatNote({ proposals: [], ledger: [], nowMs: now + 2000, phoneUseRequests: requests });
		expect(note).toMatch(/declined your phone-use ask/);
		expect(note).toContain("clear WhatsApp's cache in Settings");
		// No decline: no line.
		const plain = ownerChatNote({ proposals: [], ledger: [], nowMs: now + 2000 });
		expect(plain).not.toMatch(/declined your phone-use ask/);
	});

	it("an owner chat turn after the decline carries the line in the model's copy, and the log shows only the typed text", async () => {
		const { createRequestTx, answerRequestTx } = await import("../src/live/requests.ts");
		const now = Date.now();
		await t.commit(async (tx) => {
			await createRequestTx(tx, t.root.id, { id: "pu_decline02", task: "walk through a setting", why: "asked" }, now);
			await answerRequestTx(tx, t.root.id, "pu_decline02", false, now + 1000);
		});
		const { ownerTurnContent, SERVER_NOTE_MARKER, stripServerNote } = await import("../src/owner-note.ts");
		const content = await ownerTurnContent(t.harness, t.root.id, "what can you do instead?", context, now + 2000);
		expect(content).toMatch(/declined your phone-use ask/);
		expect(stripServerNote(content)).toBe("what can you do instead?");
		expect(content.indexOf("declined your phone-use ask")).toBeGreaterThan(content.indexOf(SERVER_NOTE_MARKER));
		void createRequestTx;
		void answerRequestTx;
	});
});
