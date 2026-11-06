/**
 * The phone-use requests of protocol A13.1: the record behind the `phone_use_request`
 * tool. The steward asks (only inside an owner run — the tool checks that itself, through
 * `currentRunReason`); the owner answers from Chat; a request is pending for 10 minutes, a
 * newer request supersedes an older pending one, and the answer (or expiry) closes it.
 *
 * Pure commit helpers over `Tx`; no I/O, no waiting. The routes live in `live/http.ts`
 * and the sessions they start in `live/sessions.ts`.
 */
import { defineDoc, type ConversationId, type Tx } from "@earendil-works/pi-durable";
import { appendChatEvent } from "../chat-events.ts";
import { recordDecision } from "../documents.ts";
import { periodicCheckpoint } from "../checkpoint.ts";

export const PHONE_USE_REQUEST_TTL_MS = 10 * 60_000;
/** A13.1 argument limits: task 1–2 000 characters, why 1–500. */
export const MAX_REQUEST_TASK_CHARS = 2000;
export const MAX_REQUEST_WHY_CHARS = 500;
/** Requests kept in the document (the newest ones). */
const KEEP_REQUESTS = 10;

export type PhoneUseRequestStatus = "pending" | "allowed" | "declined" | "expired" | "superseded";

export type PhoneUseRequest = {
	id: string;
	task: string;
	why: string;
	status: PhoneUseRequestStatus;
	createdAtMs: number;
	answeredAtMs?: number;
	/** Set when an Allow started the session (A13.2). */
	sessionId?: string;
};

export type PhoneUseRequestsState = { requests: Record<string, PhoneUseRequest> };

export const PhoneUseRequests = defineDoc<PhoneUseRequestsState>({
	kind: "steward.phone-use",
	version: 1,
	// Pi Durable 1.4.0 requires an explicit checkpoint policy (no default).
	checkpointWhen: periodicCheckpoint(),
	scope: "session",
	initial: (): PhoneUseRequestsState => ({ requests: {} }),
});

function pruneRequests(doc: PhoneUseRequestsState): void {
	const all = Object.values(doc.requests).sort((a, b) => b.createdAtMs - a.createdAtMs);
	for (const old of all.slice(KEEP_REQUESTS)) {
		if (old.status !== "pending") delete doc.requests[old.id];
	}
}

/**
 * Store one pending request: a newer pending request supersedes an older one. Appends the
 * `phone_use` request chat event and the ledger row in the same commit.
 */
export async function createRequestTx(
	tx: Tx,
	rootId: ConversationId,
	input: { id: string; task: string; why: string },
	nowMs: number,
): Promise<PhoneUseRequest> {
	const doc = await tx.doc(PhoneUseRequests);
	for (const request of Object.values(doc.requests)) {
		if (request.status === "pending") request.status = "superseded";
	}
	const request: PhoneUseRequest = { id: input.id, task: input.task, why: input.why, status: "pending", createdAtMs: nowMs };
	doc.requests[request.id] = request;
	pruneRequests(doc);
	await appendChatEvent(tx, "phone_use", { kind: "request", requestId: request.id, task: request.task, why: request.why, atMs: nowMs }, nowMs);
	await recordDecision(tx, rootId, {
		atMs: nowMs,
		type: "phone_use_request",
		kind: "request",
		note: `phone use requested: ${request.task.slice(0, 200)}`,
	});
	return JSON.parse(JSON.stringify(request)) as PhoneUseRequest;
}

/** A pending request older than the TTL reads as expired. */
export function requestStatusAt(request: PhoneUseRequest, nowMs: number): PhoneUseRequestStatus {
	if (request.status === "pending" && nowMs - request.createdAtMs > PHONE_USE_REQUEST_TTL_MS) return "expired";
	return request.status;
}

/** Mark every past-TTL pending request expired (the clock sweep's share). */
export async function expireRequestsTx(tx: Tx, nowMs: number): Promise<number> {
	const doc = await tx.doc(PhoneUseRequests);
	let expired = 0;
	for (const request of Object.values(doc.requests)) {
		if (request.status === "pending" && nowMs - request.createdAtMs > PHONE_USE_REQUEST_TTL_MS) {
			request.status = "expired";
			expired += 1;
		}
	}
	return expired;
}

/** The one request the app should still show a card for, or undefined. Lazily expires. */
export async function pendingRequestTx(tx: Tx, nowMs: number): Promise<PhoneUseRequest | undefined> {
	const doc = await tx.doc(PhoneUseRequests);
	const pending = Object.values(doc.requests)
		.filter((request) => request.status === "pending")
		.sort((a, b) => b.createdAtMs - a.createdAtMs)[0];
	if (pending === undefined) return undefined;
	if (nowMs - pending.createdAtMs > PHONE_USE_REQUEST_TTL_MS) {
		pending.status = "expired";
		return undefined;
	}
	return JSON.parse(JSON.stringify(pending)) as PhoneUseRequest;
}

export type AnswerOutcome =
	| { kind: "answered"; status: "allowed" | "declined"; task: string; request: PhoneUseRequest }
	| { kind: "not_pending"; status: PhoneUseRequestStatus }
	| { kind: "unknown" };

/**
 * Apply the owner's answer inside the caller's commit: the status must still be pending
 * (else `not_pending` with the current status — the route answers 409). Declines get their
 * ledger row here; an Allow's row and the answer chat event are written with the session
 * id once the session has started (A13.2).
 */
export async function answerRequestTx(tx: Tx, rootId: ConversationId, requestId: string, allow: boolean, nowMs: number): Promise<AnswerOutcome> {
	const doc = await tx.doc(PhoneUseRequests);
	const request = doc.requests[requestId];
	if (request === undefined) return { kind: "unknown" };
	const status = requestStatusAt(request, nowMs);
	if (status !== "pending") return { kind: "not_pending", status };
	request.status = allow ? "allowed" : "declined";
	request.answeredAtMs = nowMs;
	if (!allow) {
		await recordDecision(tx, rootId, {
			atMs: nowMs,
			type: "phone_use_request",
			kind: "declined",
			note: `owner declined phone use: ${request.task.slice(0, 200)}`,
		});
	}
	return { kind: "answered", status: request.status, task: request.task, request: JSON.parse(JSON.stringify(request)) as PhoneUseRequest };
}

/** Attach the started session to an allowed request and record the answer (A13.2). */
export async function attachSessionTx(tx: Tx, rootId: ConversationId, requestId: string, sessionId: string, nowMs: number): Promise<void> {
	const doc = await tx.doc(PhoneUseRequests);
	const request = doc.requests[requestId];
	if (request === undefined) return;
	request.sessionId = sessionId;
	await appendChatEvent(tx, "phone_use", { kind: "answer", requestId, allowed: true, sessionId, atMs: nowMs }, nowMs);
	await recordDecision(tx, rootId, {
		atMs: nowMs,
		type: "phone_use_request",
		kind: "allowed",
		note: `owner allowed phone use: ${request.task.slice(0, 200)}`,
	});
}

/** The newest declined request, for the A9.2 server note (A13.2: it tells the model). */
export function declinedRequest(entries: PhoneUseRequest[], nowMs: number, windowMs: number): PhoneUseRequest | undefined {
	const declined = entries
		.filter((request) => request.status === "declined" && request.answeredAtMs !== undefined && nowMs - request.answeredAtMs <= windowMs)
		.sort((a, b) => (b.answeredAtMs ?? 0) - (a.answeredAtMs ?? 0));
	return declined[0];
}
