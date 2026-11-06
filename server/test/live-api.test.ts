/**
 * Protocol A10.3 over HTTP: the /v1/live/* routes on a real server (temporary port and
 * state dir, faux model, no real phone). The test plays the phone.
 */
import { BACKGROUND_CONTEXT } from "@earendil-works/chord/context";
import { connect } from "node:net";
import { mkdtemp, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { fauxAssistantMessage, fauxText, fauxToolCall } from "@earendil-works/pi-ai/providers/faux";
import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it } from "vitest";
import { loadConfig } from "../src/config.ts";
import { openStewardHarness } from "../src/steward.ts";
import { startStewardServers, routeTemplateFor, type StewardServers } from "../src/http.ts";
import { configureLive, resetLiveConfig } from "../src/live/sessions.ts";

let dir: string;
let port: number;
let servers: StewardServers;
let opened: Awaited<ReturnType<typeof openStewardHarness>>;
let token: string;

async function adminPair(): Promise<string> {
	return new Promise((resolve, reject) => {
		const socket = connect(join(dir, "admin.sock"), () => {
			socket.write("POST /admin/pair HTTP/1.1\r\nhost: admin\r\ncontent-length: 0\r\nconnection: close\r\n\r\n");
		});
		let raw = "";
		socket.on("data", (chunk: Buffer) => (raw += chunk.toString("utf8")));
		socket.on("error", reject);
		socket.on("close", () => resolve((JSON.parse(raw.slice(raw.indexOf("\r\n\r\n") + 4)) as { code: string }).code));
	});
}

function call(path: string, init: { method?: string; token?: string | null; body?: unknown } = {}): Promise<{ status: number; body: any }> {
	const headers: Record<string, string> = { "content-type": "application/json" };
	const bearer = init.token === undefined ? token : init.token;
	if (bearer !== null) headers.authorization = `Bearer ${bearer}`;
	return fetch(`http://127.0.0.1:${port}${path}`, {
		method: init.method ?? "GET",
		headers,
		...(init.body === undefined ? {} : { body: JSON.stringify(init.body) }),
	}).then(async (response) => ({ status: response.status, body: await response.json().catch(() => null) }));
}

let n = 0;
const rid = (label: string) => `${label}-${(n += 1)}-${Date.now()}`;

/** Poll an HTTP read until it satisfies `when` (the steward's turn runs asynchronously). */
async function untilHttp<T>(read: () => Promise<T>, when: (value: T) => boolean, ms = 8000): Promise<T> {
	const deadline = Date.now() + ms;
	for (;;) {
		const value = await read();
		if (when(value)) return value;
		if (Date.now() > deadline) throw new Error("timed out waiting for the steward's turn");
		await new Promise((resolve) => setTimeout(resolve, 25));
	}
}

const tree = {
	package: "com.android.settings",
	activity: "Storage",
	nodes: [{ id: "n1", role: "button", text: "Clear cache", clickable: true, editable: false, bounds: [0, 0, 5, 5] }],
	truncated: false,
};

beforeAll(async () => {
	dir = await mkdtemp(join(tmpdir(), "p7sc-api-"));
	const config = loadConfig({ stateDir: dir, port: 0, notify: {}, schedules: { enabled: false } });
	opened = await openStewardHarness({ dbPath: join(dir, "steward.sqlite"), fauxPlan: { hang: false } });
	servers = startStewardServers({ config, harness: opened.harness, root: opened.root });
	await new Promise<void>((resolve) => servers.tcp.listen(0, "127.0.0.1", resolve));
	port = (servers.tcp.address() as { port: number }).port;
	const code = await adminPair();
	const paired = await call("/v1/pair", { method: "POST", token: null, body: { code, deviceName: "Test Lab phone" } });
	token = paired.body.token;
});
beforeEach(() => resetLiveConfig());
afterEach(async () => {
	// A13.5: a session ended by a test hands back to the main conversation; that turn
	// consumes a scripted model response. Drain it here (idempotent) and wait for the main
	// conversation to settle, so the next test's response queue is not eaten mid-flight.
	const { handbackEndedSessions } = await import("../src/live/sessions.ts");
	await handbackEndedSessions({ harness: opened.harness, context: BACKGROUND_CONTEXT });
	await opened.root.waitForIdle(BACKGROUND_CONTEXT).catch(() => undefined);
});
afterAll(async () => {
	await servers.close();
	await opened.close();
	await rm(dir, { recursive: true, force: true });
});

describe("/v1/live/* (A10.3)", () => {
	it("needs a device token", async () => {
		const response = await call("/v1/live/sessions", { method: "POST", token: null, body: { requestId: rid("s"), task: "x" } });
		expect(response.status).toBe(401);
	});

	it("rejects a missing task, an unknown route and a missing requestId", async () => {
		expect((await call("/v1/live/sessions", { method: "POST", body: { requestId: rid("s") } })).status).toBe(400);
		expect((await call("/v1/live/sessions", { method: "POST", body: { task: "x" } })).status).toBe(400);
		expect((await call("/v1/live/nope", { method: "POST", body: {} })).status).toBe(404);
		expect((await call("/v1/live/sessions/ls-none/next")).status).toBe(404);
	});

	it("runs a session over HTTP: start, long-poll, answer, end", async () => {
		opened.faux!.setResponses([
			() => fauxAssistantMessage([fauxToolCall("screen_observe", {})], { stopReason: "toolUse" }),
			() => fauxAssistantMessage([fauxToolCall("screen_act", { action: "tap", nodeId: "n1" })], { stopReason: "toolUse" }),
			() => fauxAssistantMessage([fauxToolCall("screen_end", { summary: "Cache cleared." })], { stopReason: "toolUse" }),
			() => fauxAssistantMessage([fauxText("done")]),
		]);
		const requestId = rid("start");
		const started = await call("/v1/live/sessions", { method: "POST", body: { requestId, task: "clear one cache" } });
		expect(started.status).toBe(200);
		const sessionId: string = started.body.sessionId;
		expect(sessionId).toMatch(/^ls-/);
		// The same request id gives the same session, flagged as a duplicate.
		const again = await call("/v1/live/sessions", { method: "POST", body: { requestId, task: "clear one cache" } });
		expect(again.body).toMatchObject({ sessionId, duplicate: true });
		// A second, different start is refused while one runs.
		const second = await call("/v1/live/sessions", { method: "POST", body: { requestId: rid("start"), task: "other" } });
		expect(second.status).toBe(409);
		expect(second.body.error.code).toBe("LIVE_SESSION_ACTIVE");

		const first = await call(`/v1/live/sessions/${sessionId}/next?wait=10`);
		expect(first.status).toBe(200);
		expect(first.body.step.tool).toBe("screen_observe");
		const badOutcome = await call(`/v1/live/sessions/${sessionId}/steps/${first.body.step.stepId}`, { method: "POST", body: { requestId: rid("a"), outcome: "weird" } });
		expect(badOutcome.status).toBe(400);
		const answerBody = { requestId: rid("a"), outcome: "ok", tree };
		const answered = await call(`/v1/live/sessions/${sessionId}/steps/${first.body.step.stepId}`, { method: "POST", body: answerBody });
		expect(answered.status).toBe(200);
		expect(answered.body.accepted).toBe(true);
		const dup = await call(`/v1/live/sessions/${sessionId}/steps/${first.body.step.stepId}`, { method: "POST", body: answerBody });
		expect(dup.body).toMatchObject({ accepted: true, duplicate: true });

		const act = await call(`/v1/live/sessions/${sessionId}/next?wait=10`);
		expect(act.body.step).toMatchObject({ tool: "screen_act", args: { action: "tap", nodeId: "n1" } });
		await call(`/v1/live/sessions/${sessionId}/steps/${act.body.step.stepId}`, { method: "POST", body: { requestId: rid("a"), outcome: "ok", detail: "tapped", nodeText: "Clear cache" } });

		const end = await call(`/v1/live/sessions/${sessionId}/next?wait=10`);
		expect(end.body).toMatchObject({ ended: true, summary: "Cache cleared." });
	});

	it("an empty long-poll answers {step: null} after the wait, and Stop ends the session", async () => {
		opened.faux!.setResponses([() => fauxAssistantMessage([fauxText("waiting")])]);
		// The faux model ends its turn at once, so use a model that waits: observe, then no answer.
		opened.faux!.setResponses([() => fauxAssistantMessage([fauxToolCall("screen_observe", {})], { stopReason: "toolUse" })]);
		const started = await call("/v1/live/sessions", { method: "POST", body: { requestId: rid("start"), task: "wait" } });
		const sessionId: string = started.body.sessionId;
		const step = await call(`/v1/live/sessions/${sessionId}/next?wait=5`);
		expect(step.body.step.tool).toBe("screen_observe");
		const t0 = Date.now();
		const idle = await call(`/v1/live/sessions/${sessionId}/next?wait=1`);
		expect(idle.body).toEqual({ step: null });
		expect(Date.now() - t0).toBeGreaterThanOrEqual(900);
		const stop = await call(`/v1/live/sessions/${sessionId}/stop`, { method: "POST", body: { requestId: rid("stop"), reason: "stopped" } });
		expect(stop.status).toBe(200);
		const ended = await call(`/v1/live/sessions/${sessionId}/next?wait=1`);
		expect(ended.body).toMatchObject({ ended: true, reason: "stopped" });
		// Stop is idempotent.
		const stopAgain = await call(`/v1/live/sessions/${sessionId}/stop`, { method: "POST", body: { requestId: rid("stop") } });
		expect(stopAgain.status).toBe(200);
	});

	it("S15 over HTTP: a late answer is 200 {accepted, late}, a repeat is STEP_NOT_OPEN, and after Stop it is SESSION_ENDED", async () => {
		configureLive({ stepTimeoutMs: 300 });
		opened.faux!.setResponses([
			() => fauxAssistantMessage([fauxToolCall("screen_act", { action: "tap", nodeId: "n1" })], { stopReason: "toolUse" }),
			() => fauxAssistantMessage([fauxToolCall("screen_observe", {})], { stopReason: "toolUse" }),
		]);
		const started = await call("/v1/live/sessions", { method: "POST", body: { requestId: rid("start"), task: "late" } });
		const sessionId: string = started.body.sessionId;
		const act = await call(`/v1/live/sessions/${sessionId}/next?wait=10`);
		expect(act.body.step.tool).toBe("screen_act");
		await new Promise((resolve) => setTimeout(resolve, 450));
		const path = `/v1/live/sessions/${sessionId}/steps/${act.body.step.stepId}`;
		const late = await call(path, { method: "POST", body: { requestId: rid("late"), outcome: "ok", nodeText: "Clear cache" } });
		expect(late.status).toBe(200);
		expect(late.body).toMatchObject({ accepted: true, late: true });
		const again = await call(path, { method: "POST", body: { requestId: rid("late"), outcome: "ok" } });
		expect(again.status).toBe(409);
		expect(again.body.error.code).toBe("STEP_NOT_OPEN");
		await call(`/v1/live/sessions/${sessionId}/stop`, { method: "POST", body: { requestId: rid("stop") } });
		const afterStop = await call(path, { method: "POST", body: { requestId: rid("late"), outcome: "ok" } });
		expect(afterStop.status).toBe(409);
		expect(afterStop.body.error.code).toBe("SESSION_ENDED");
	});

	it("names the live routes in the request log, never the ids", () => {
		expect(routeTemplateFor("POST", "/v1/live/sessions")).toBe("/v1/live/sessions");
		expect(routeTemplateFor("GET", "/v1/live/sessions/ls-abc/next")).toBe("/v1/live/sessions/:id/next");
		expect(routeTemplateFor("POST", "/v1/live/sessions/ls-abc/steps/st-9")).toBe("/v1/live/sessions/:id/steps/:step");
		expect(routeTemplateFor("POST", "/v1/live/sessions/ls-abc/stop")).toBe("/v1/live/sessions/:id/stop");
		expect(routeTemplateFor("POST", "/v1/live/requests/pu-abc/answer")).toBe("/v1/live/requests/:id/answer");
		expect(routeTemplateFor("GET", "/v1/live/requests/pending")).toBe("/v1/live/requests/pending");
	});

	it("answers a pending request: Allow starts the session, a repeat dedupes, a second request is declined without a session (A13.2)", async () => {
		// The steward asks (root turn), then the request-answer flow runs; finally the live
		// session's turn ends at once.
		opened.faux!.setResponses([
			() => fauxAssistantMessage([fauxToolCall("phone_use_request", { task: "Clear the cache of one app in Settings", why: "no API for it" })], { stopReason: "toolUse" }),
			() => fauxAssistantMessage([fauxText("Asked the owner.")]),
			() => fauxAssistantMessage([fauxText("session over")]),
			() => fauxAssistantMessage([fauxText("second ask")]),
		]);
		await call("/v1/chat", { method: "POST", body: { requestId: rid("chat"), text: "please clear one app's cache" } });
		// The app redraws the card after a restart (the steward's turn runs asynchronously).
		const pending = await untilHttp(
			() => call("/v1/live/requests/pending"),
			(response) => response.body.request !== null,
		);
		expect(pending.status).toBe(200);
		const request = pending.body.request;
		expect(request).toMatchObject({ status: "pending", task: "Clear the cache of one app in Settings", why: "no API for it" });
		expect(String(request.id)).toMatch(/^pu-/);
		// Allow starts the session through the same code as POST /v1/live/sessions.
		const answerId = rid("answer");
		const allowed = await call(`/v1/live/requests/${request.id}/answer`, { method: "POST", body: { requestId: answerId, allow: true } });
		expect(allowed.status).toBe(200);
		expect(allowed.body.status).toBe("allowed");
		expect(String(allowed.body.sessionId)).toMatch(/^ls-/);
		// The same answer replays the same session, flagged as a duplicate.
		const again = await call(`/v1/live/requests/${request.id}/answer`, { method: "POST", body: { requestId: answerId, allow: true } });
		expect(again.body).toMatchObject({ status: "allowed", sessionId: allowed.body.sessionId, duplicate: true });
		// An answered request is no longer pending and cannot be answered again.
		const after = await call("/v1/live/requests/pending");
		expect(after.body.request).toBeNull();
		const repeat = await call(`/v1/live/requests/${request.id}/answer`, { method: "POST", body: { requestId: rid("answer"), allow: true } });
		expect(repeat.status).toBe(409);
		expect(repeat.body.error.code).toBe("REQUEST_NOT_PENDING");
		// The chat stream carried the request and the answer.
		const history = await call("/v1/chat/history?limit=200");
		const phoneUse = history.body.events.filter((e: { type: string }) => e.type === "phone_use");
		const kinds = phoneUse.map((e: { data: { kind: string } }) => e.data.kind);
		expect(kinds).toContain("request");
		expect(kinds).toContain("answer");
		const answerEvent = phoneUse.find((e: { data: { kind: string } }) => e.data.kind === "answer");
		expect(answerEvent.data).toMatchObject({ requestId: request.id, allowed: true, sessionId: allowed.body.sessionId });
		// Stop the session so the next request can be answered.
		await call(`/v1/live/sessions/${allowed.body.sessionId}/stop`, { method: "POST", body: { requestId: rid("stop") } });
	});

	it("a decline answers {status: declined}, starts no session and is a 409 when repeated (A13.2)", async () => {
		opened.faux!.setResponses([
			() => fauxAssistantMessage([fauxToolCall("phone_use_request", { task: "Walk through a setting", why: "the owner asked" })], { stopReason: "toolUse" }),
			() => fauxAssistantMessage([fauxText("Asked.")]),
		]);
		await call("/v1/chat", { method: "POST", body: { requestId: rid("chat"), text: "walk me through it" } });
		const pending = await untilHttp(
			() => call("/v1/live/requests/pending"),
			(response) => response.body.request !== null,
		);
		const request = pending.body.request;
		const declined = await call(`/v1/live/requests/${request.id}/answer`, { method: "POST", body: { requestId: rid("answer"), allow: false } });
		expect(declined.status).toBe(200);
		expect(declined.body).toEqual({ status: "declined" });
		// No session was started.
		expect(await call("/v1/live/requests/pending")).toMatchObject({ body: { request: null } });
		const repeat = await call(`/v1/live/requests/${request.id}/answer`, { method: "POST", body: { requestId: rid("answer"), allow: false } });
		expect(repeat.status).toBe(409);
		expect(repeat.body.error.code).toBe("REQUEST_NOT_PENDING");
		expect(repeat.body.status).toBe("declined");
		const answerEvent = (await call("/v1/chat/history?limit=200")).body.events
			.filter((e: { type: string; data: { kind: string; requestId?: string } }) => e.type === "phone_use" && e.data.kind === "answer" && e.data.requestId === request.id)
			.at(-1);
		expect(answerEvent.data).toMatchObject({ requestId: request.id, allowed: false });
	});

	it("an unknown request is 404, and a bad body is 400 (A13.2)", async () => {
		const missing = await call("/v1/live/requests/pu-nope/answer", { method: "POST", body: { requestId: rid("answer"), allow: true } });
		expect(missing.status).toBe(404);
		expect((await call("/v1/live/requests/pu-nope/answer", { method: "POST", body: { requestId: rid("answer") } })).status).toBe(400);
		expect((await call("/v1/live/requests/pu-nope/answer", { method: "POST", body: { requestId: rid("answer"), allow: "yes" } })).status).toBe(400);
	});

	it("while a session runs the owner's chat goes to the session, never to the main conversation (A13.4)", async () => {
		// Ask, allow, then the session asks the owner a question (answered in Chat), then
		// observes (an ask-free window for a queued message), then ends.
		opened.faux!.setResponses([
			() => fauxAssistantMessage([fauxToolCall("phone_use_request", { task: "Clear one cache", why: "no API" })], { stopReason: "toolUse" }),
			() => fauxAssistantMessage([fauxText("Asked.")]),
			() => fauxAssistantMessage([fauxToolCall("screen_ask", { question: "Which app?" })], { stopReason: "toolUse" }),
			() => fauxAssistantMessage([fauxToolCall("screen_observe", {})], { stopReason: "toolUse" }),
			() => fauxAssistantMessage([fauxToolCall("screen_end", { summary: "Chose WhatsApp." })], { stopReason: "toolUse" }),
			() => fauxAssistantMessage([fauxText("Done.")]),
		]);
		await call("/v1/chat", { method: "POST", body: { requestId: rid("chat"), text: "clear a cache for me" } });
		const pending = await untilHttp(
			() => call("/v1/live/requests/pending"),
			(response) => response.body.request !== null,
		);
		const allowed = await call(`/v1/live/requests/${pending.body.request.id}/answer`, { method: "POST", body: { requestId: rid("answer"), allow: true } });
		expect(allowed.status).toBe(200);
		const sessionId: string = allowed.body.sessionId;
		// The ask is waiting; the owner's chat reply answers it.
		await untilHttp(
			async () => {
				const events = (await call("/v1/chat/history?limit=50")).body.events;
				return events.some((e: { type: string; data: { kind?: string } }) => e.type === "phone_use" && e.data.kind === "ask");
			},
			(found) => found,
		);
		const before = (await opened.root.entries({}, 500, undefined, BACKGROUND_CONTEXT)).items.length;
		const replied = await call("/v1/chat", { method: "POST", body: { requestId: rid("chat"), text: "WhatsApp" } });
		expect(replied.status).toBe(202);
		expect(replied.body).toEqual({ accepted: true, conversationId: `live:${sessionId}` });
		// The main conversation gained no entries: the routed reply never reached it.
		const between = (await opened.root.entries({}, 500, undefined, BACKGROUND_CONTEXT)).items.length;
		expect(between).toBe(before);
		// The session carries on with an observe step (no ask waiting): a further message is
		// queued for the next screen tool result.
		const step = await untilHttp(
			() => call(`/v1/live/sessions/${sessionId}/next?wait=10`),
			(response) => response.body.step !== undefined && response.body.step !== null,
		);
		const queued = await call("/v1/chat", { method: "POST", body: { requestId: rid("chat"), text: "and be careful" } });
		expect(queued.status).toBe(202);
		expect(queued.body).toEqual({ accepted: true, conversationId: `live:${sessionId}` });
		await call(`/v1/live/sessions/${sessionId}/steps/${step.body.step.stepId}`, {
			method: "POST",
			body: { requestId: rid("a"), outcome: "ok" },
		});
		// The session ends with the reply and the note in its transcript.
		await untilHttp(
			async () => {
				const events = (await call("/v1/chat/history?limit=50")).body.events;
				return events.some((e: { type: string; data: { kind?: string } }) => e.type === "phone_use" && e.data.kind === "ended");
			},
			(found) => found,
		);
		// After the session ended, chat goes to the main conversation again.
		const backToMain = await call("/v1/chat", { method: "POST", body: { requestId: rid("chat"), text: "thanks" } });
		expect(backToMain.body.conversationId).toBe("main");
	});
});
