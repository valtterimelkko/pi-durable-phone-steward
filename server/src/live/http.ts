/**
 * The /v1/live/* routes (protocol A10.3). Called by `http.ts` after bearer authentication
 * with the device id the token belongs to; returns the status and body to send, or
 * undefined for a path that is not a live route (the router then answers 404).
 */
import { badRequest, conflict, conflictWith, notFound } from "../errors.ts";
import { appendChatEvent } from "../chat-events.ts";
import { recordRequest, resolveRequestId } from "../requests.ts";
import {
	answerRequestTx,
	attachSessionTx,
	pendingRequestTx,
} from "./requests.ts";
import {
	answerLiveStep,
	nextLiveStep,
	parseStepAnswer,
	startLiveSession,
	stopLiveSession,
	type LiveDeps,
} from "./sessions.ts";
import { LiveSessions, runningSession } from "./state.ts";
import type { LiveEndReason } from "./state.ts";

export type LiveRequest = {
	method: string;
	path: string;
	url: URL;
	deviceId: string;
	readBody: () => Promise<Record<string, unknown>>;
	signal: AbortSignal;
};

export type LiveResponse = { status: number; body: Record<string, unknown> };

function requireRequestId(body: Record<string, unknown>): string {
	const requestId = body.requestId;
	if (typeof requestId !== "string" || requestId.length < 1 || requestId.length > 128) {
		throw badRequest("requestId is required (1–128 characters)");
	}
	return requestId;
}

/** Protocol §1 requestId idempotency around one mutating live route. */
async function dedupe(
	deps: LiveDeps,
	routeName: string,
	body: Record<string, unknown>,
	requestId: string,
	run: () => Promise<Record<string, unknown>>,
): Promise<LiveResponse> {
	const existing = await deps.harness.commit((tx) => resolveRequestId(tx, { requestId, route: routeName, body }), deps.context);
	if (existing !== undefined) return { status: 200, body: { ...(existing.response as Record<string, unknown>), duplicate: true } };
	const response = await run();
	await deps.harness.commit(
		(tx) => recordRequest(tx, { requestId, route: routeName, body, response: JSON.parse(JSON.stringify(response)) as never, nowMs: Date.now() }),
		deps.context,
	);
	return { status: 200, body: response };
}

const STOP_REASONS: readonly LiveEndReason[] = ["stopped", "denied", "budget", "error"];

/**
 * A13.2: the owner's answer to a phone-use request. Allow starts the session through the
 * same code as `POST /v1/live/sessions` (the tap is the owner's start); a decline starts
 * no run. Deduped by the body's requestId like every mutating route.
 */
async function answerRequest(deps: LiveDeps, deviceId: string, requestId: string, allow: boolean): Promise<Record<string, unknown>> {
	// Commit A: the request must still be pending, and no session may be running (A10.3).
	const applied = await deps.harness.commit(
		async (tx) => {
			const sessions = await tx.doc(LiveSessions);
			if (runningSession(sessions) !== undefined) throw conflict("LIVE_SESSION_ACTIVE", "a phone session is already running; stop it first");
			const outcome = await answerRequestTx(tx, deps.root.id, requestId, allow, Date.now());
			if (outcome.kind === "unknown") throw notFound(`no phone-use request ${requestId}`);
			if (outcome.kind === "not_pending") {
				throw conflictWith("REQUEST_NOT_PENDING", `this request is ${outcome.status}, not pending`, { status: outcome.status });
			}
			if (outcome.status === "declined") {
				await appendChatEvent(tx, "phone_use", { kind: "answer", requestId, allowed: false, atMs: Date.now() }, Date.now());
			}
			return outcome;
		},
		deps.context,
	);
	if (applied.status === "declined") return { status: "declined" };
	// Allow: the tap is the owner's start; the session code is startLiveSession's.
	const { sessionId } = await startLiveSession(deps, { deviceId, task: applied.task });
	await deps.harness.commit((tx) => attachSessionTx(tx, deps.root.id, requestId, sessionId, Date.now()), deps.context);
	return { status: "allowed", sessionId };
}

export async function handleLiveRoute(deps: LiveDeps, req: LiveRequest): Promise<LiveResponse | undefined> {
	if (req.method === "POST" && req.path === "/v1/live/sessions") {
		const body = await req.readBody();
		const requestId = requireRequestId(body);
		const task = body.task;
		if (typeof task !== "string" || task.trim() === "") throw badRequest("task is required");
		return dedupe(deps, "POST /v1/live/sessions", body, requestId, async () => {
			const { sessionId } = await startLiveSession(deps, { deviceId: req.deviceId, task });
			return { sessionId };
		});
	}
	const next = /^\/v1\/live\/sessions\/([^/]+)\/next$/.exec(req.path);
	if (req.method === "GET" && next !== null) {
		const wait = Number(req.url.searchParams.get("wait") ?? 0);
		const result = await nextLiveStep(deps, next[1]!, req.deviceId, Number.isFinite(wait) ? wait : 0, req.signal);
		return { status: 200, body: result as Record<string, unknown> };
	}
	const step = /^\/v1\/live\/sessions\/([^/]+)\/steps\/([^/]+)$/.exec(req.path);
	if (req.method === "POST" && step !== null) {
		const body = await req.readBody();
		const requestId = requireRequestId(body);
		const answer = parseStepAnswer(body);
		return dedupe(deps, "POST /v1/live/sessions/{id}/steps/{step}", { ...body, __sessionId: step[1], __stepId: step[2] }, requestId, () =>
			answerLiveStep(deps, step[1]!, step[2]!, req.deviceId, answer),
		);
	}
	const stop = /^\/v1\/live\/sessions\/([^/]+)\/stop$/.exec(req.path);
	if (req.method === "POST" && stop !== null) {
		const body = await req.readBody();
		const reason = body.reason === undefined ? "stopped" : body.reason;
		if (!(STOP_REASONS as readonly unknown[]).includes(reason)) throw badRequest(`reason must be one of ${STOP_REASONS.join(", ")}`);
		const result = await stopLiveSession(deps, stop[1]!, req.deviceId, reason as LiveEndReason);
		return { status: 200, body: result };
	}
	// A13.2: the owner's answer to a phone-use request, and the pending card the app redraws.
	const answer = /^\/v1\/live\/requests\/([^/]+)\/answer$/.exec(req.path);
	if (req.method === "POST" && answer !== null) {
		const body = await req.readBody();
		const requestId = requireRequestId(body);
		if (typeof body.allow !== "boolean") throw badRequest("allow must be true or false");
		return dedupe(deps, "POST /v1/live/requests/{id}/answer", { ...body, __requestId: answer[1] }, requestId, () =>
			answerRequest(deps, req.deviceId, answer[1]!, body.allow as boolean),
		);
	}
	if (req.method === "GET" && req.path === "/v1/live/requests/pending") {
		const request = await deps.harness.commit((tx) => pendingRequestTx(tx, Date.now()), deps.context);
		return { status: 200, body: { request: request ?? null } };
	}
	return undefined;
}
