/**
 * The durable record of live sessions (protocol A10.3): one document, a handful of
 * sessions, at most 40 steps each. Pure commit helpers over `Tx`; no I/O, no waiting.
 */
import type { JsonObject } from "@earendil-works/pi-durable";
import { defineDoc, type Tx } from "@earendil-works/pi-durable";
import { appendChatEvent } from "../chat-events.ts";
import { conflict, notFound } from "../errors.ts";
import { redactCodes } from "./tree.ts";
import { periodicCheckpoint } from "../checkpoint.ts";

export const LIVE_SCREEN_TOOLS = ["screen_observe", "screen_act", "screen_ask", "screen_end"] as const;
export type LiveTool = (typeof LIVE_SCREEN_TOOLS)[number];
/** The tools that round-trip to the phone (the ask is a server-side wait, not a step). */
export type PhoneStepTool = Exclude<LiveTool, "screen_ask" | "screen_end">;

/** Phone Steward's own package (A15.2: the app the session must never operate). */
export const STEWARD_PACKAGE = "com.example.phonesteward";

/** The one reopen line (A15.2), appended to a step result or ask reply that finds the steward's app in front. */
export function reopenLine(session: Pick<LiveSession, "lastOtherPackage">): string {
	const target = session.lastOtherPackage ?? "";
	const reopen = target !== "" ? `Reopen ${target} with open_app before acting.` : "Reopen the app you were using with open_app before acting.";
	return `Phone Steward is in front (the owner answered in Chat). ${reopen}`;
}

/** The app's refusal when the model tried to operate Phone Steward itself (A15.2). */
export function isStewardRefusal(detail: string | undefined): boolean {
	return detail !== undefined && /steward'?s own app/i.test(detail);
}

export type LiveEndReason = "finished" | "stopped" | "budget" | "denied" | "app_lost" | "interrupted" | "error";

export type LiveConfig = {
	/** How long a tool call waits for the phone to answer one step. */
	stepTimeoutMs: number;
	/** Server-side step budget (the app enforces its own 40 as well). */
	maxSteps: number;
	/** Server-side session time budget (A13.4: 15 minutes). */
	maxDurationMs: number;
	/** Unanswered steps in a row before the session ends as `app_lost`. */
	maxTimeouts: number;
	/** S16: a running session whose app has shown no sign of life this long ends as `app_lost`. */
	maxIdleMs: number;
	/** S16: how often the clock-driven sweep looks. */
	sweepIntervalMs: number;
	/** A13.4: how long `screen_ask` waits for the owner's chat reply. */
	askWaitMs: number;
};

export const DEFAULT_LIVE_CONFIG: LiveConfig = {
	stepTimeoutMs: 60_000,
	// A15.1: 60 steps (was 40); the 15 minutes are unchanged.
	maxSteps: 60,
	maxDurationMs: 15 * 60_000,
	maxTimeouts: 2,
	maxIdleMs: 2 * 60_000,
	sweepIntervalMs: 30_000,
	askWaitMs: 3 * 60_000,
};

export let liveConfig: LiveConfig = { ...DEFAULT_LIVE_CONFIG };

/** Tests only: shrink a budget. */
export function configureLive(change: Partial<LiveConfig>): void {
	liveConfig = { ...liveConfig, ...change };
}
export function resetLiveConfig(): void {
	liveConfig = { ...DEFAULT_LIVE_CONFIG };
}

export type LiveStep = {
	stepId: string;
	tool: PhoneStepTool;
	args: JsonObject;
	status: "pending" | "delivered" | "answered" | "abandoned";
	createdAtMs: number;
	deliveredAtMs?: number;
	/** A15.1: the step's 1-based place in the session's budget, for the `step N of M` frame. */
	index?: number;
	/** A15.2: the package the tree showed in front, for the reopen line. */
	frontPackage?: string;
	/** S15: the phone answered after the server gave up on (or ended) this step; ledgered once. */
	lateAnswered?: boolean;
	outcome?: "ok" | "refused" | "failed";
	detail?: string;
	/** The text rendering of the tree the phone sent (the tree itself is not kept). */
	rendered?: string;
};

/** A13.4: one question the session asked the owner in Chat. */
export type LiveAsk = {
	askId: string;
	question: string;
	status: "waiting" | "answered" | "no_answer";
	createdAtMs: number;
	answeredAtMs?: number;
	/** The owner's reply, masked as S13 before it is stored or shown to the model. */
	reply?: string;
};

export type LiveSession = {
	sessionId: string;
	conversationId: number;
	deviceId: string;
	task: string;
	status: "running" | "ended";
	startedAtMs: number;
	/** S16: the last time the app polled or answered (a sign of life); the start time until then. */
	lastPollAtMs?: number;
	endedAtMs?: number;
	endReason?: LiveEndReason;
	summary?: string;
	stepCount: number;
	consecutiveTimeouts: number;
	steps: Record<string, LiveStep>;
	/** A13.4: the session's asks, by askId. */
	asks?: Record<string, LiveAsk>;
	/** A13.4: owner messages queued while no ask was waiting, drained onto the next tool result. */
	ownerNotes?: string[];
	/** A15.2: the last package observed in front that is not Phone Steward; the reopen line names it. */
	lastOtherPackage?: string;
	/** A13.5: set at creation; the hand-back to the main conversation is owed exactly once. */
	handback?: "pending" | "done";
	/** A13.5: when the hand-back input was submitted. */
	handbackAtMs?: number;
};

export type LiveSessionsState = { sessions: Record<string, LiveSession> };

export const LiveSessions = defineDoc<LiveSessionsState>({
	kind: "steward.live",
	version: 1,
	// Pi Durable 1.4.0 requires an explicit checkpoint policy (no default).
	checkpointWhen: periodicCheckpoint(),
	scope: "session",
	initial: (): LiveSessionsState => ({ sessions: {} }),
});

const KEEP_SESSIONS = 10;

export function runningSession(doc: LiveSessionsState): LiveSession | undefined {
	return Object.values(doc.sessions).find((session) => session.status === "running");
}

export function sessionForConversation(doc: LiveSessionsState, conversationId: number): LiveSession | undefined {
	return Object.values(doc.sessions).find((session) => session.conversationId === conversationId);
}

/** The session, if it exists and belongs to this device (else 404: never reveal other devices' ids). */
export function ownedSession(doc: LiveSessionsState, sessionId: string, deviceId: string): LiveSession {
	const session = doc.sessions[sessionId];
	if (session === undefined || session.deviceId !== deviceId) throw notFound(`no live session ${sessionId}`);
	return session;
}

export function pruneSessions(doc: LiveSessionsState): void {
	const all = Object.values(doc.sessions).sort((a, b) => b.startedAtMs - a.startedAtMs);
	for (const old of all.slice(KEEP_SESSIONS)) {
		if (old.status === "ended") delete doc.sessions[old.sessionId];
	}
}

/**
 * A13.3: the one-line summary of an answered step for the `phone_use` step event, in the
 * A8 style: what was done (`tap "Clear cache"`, `open com.app`, `observe com.pkg (34
 * items)`), never the tree, one-time codes masked as S13.
 */
export function stepSummary(
	step: Pick<LiveStep, "tool" | "args">,
	context: { nodeCount?: number; nodeText?: string; packageName?: string } = {},
): string {
	const args = step.args as Record<string, string | undefined>;
	let summary: string;
	switch (step.tool) {
		case "screen_observe":
			summary = `observe ${context.packageName ?? "the screen"} (${context.nodeCount ?? 0} items)`;
			break;
		case "screen_act": {
			const action = args.action ?? "act";
			if (action === "tap" || action === "type") {
				const nodeText = context.nodeText ?? "";
				summary = nodeText === "" ? action : `${action} "${nodeText}"`;
			} else if (action === "open_app") {
				summary = `open ${args.packageName ?? ""}`.trim();
			} else if (action === "scroll") {
				summary = `scroll ${args.direction ?? ""}`.trim();
			} else {
				summary = action;
			}
			break;
		}
		default:
			summary = step.tool;
	}
	return redactCodes(summary).slice(0, 300);
}

/**
 * End a running session inside the caller's commit and leave the owner one line in Chat
 * plus the A13.3 `ended` event. Returns changed=false (changing nothing) if it had
 * already ended: ending is idempotent.
 */
export async function endSessionTx(
	tx: Tx,
	sessionId: string,
	reason: LiveEndReason,
	summary: string | undefined,
	nowMs: number,
): Promise<{ changed: boolean; conversationId: number }> {
	const doc = await tx.doc(LiveSessions);
	const session = doc.sessions[sessionId];
	if (session === undefined) throw notFound(`no live session ${sessionId}`);
	if (session.status === "ended") return { changed: false, conversationId: session.conversationId };
	session.status = "ended";
	session.endReason = reason;
	session.endedAtMs = nowMs;
	if (summary !== undefined) session.summary = summary.slice(0, 600);
	for (const step of Object.values(session.steps)) {
		if (step.status === "pending" || step.status === "delivered") step.status = "abandoned";
	}
	for (const ask of Object.values(session.asks ?? {})) {
		if (ask.status === "waiting") ask.status = "no_answer";
	}
	// S13 (correction 1 item 3): the session model's summary is masked everywhere it
	// leaves the session — the say events, the hand-back input, and this event.
	// The `ended` event is the session's one end line in Chat (A13.6); no second
	// assistant message (a review finding).
	const safeSummary = summary !== undefined && summary.trim() !== "" ? redactCodes(summary.trim()).slice(0, 600) : undefined;
	await appendChatEvent(
		tx,
		"phone_use",
		{
			kind: "ended",
			sessionId: session.sessionId,
			reason,
			...(safeSummary !== undefined ? { summary: safeSummary } : {}),
			steps: session.stepCount,
			atMs: nowMs,
		},
		nowMs,
	);
	return { changed: true, conversationId: session.conversationId };
}

export type QueueResult =
	| { kind: "queued"; sessionId: string }
	| { kind: "refused"; detail: string; sessionId?: string; endedNow?: boolean };

/** Queue one step for the phone, or refuse (session over, budget out). Idempotent on `stepId`. */
export async function queueStepTx(
	tx: Tx,
	conversationId: number,
	step: { stepId: string; tool: LiveStep["tool"]; args: JsonObject },
	nowMs: number,
): Promise<QueueResult> {
	const doc = await tx.doc(LiveSessions);
	const session = sessionForConversation(doc, conversationId);
	if (session === undefined || session.status !== "running") {
		return { kind: "refused", detail: "this phone session is over; call screen_end or stop" };
	}
	if (session.steps[step.stepId] !== undefined) return { kind: "queued", sessionId: session.sessionId };
	if (session.stepCount >= liveConfig.maxSteps || nowMs - session.startedAtMs > liveConfig.maxDurationMs) {
		await endSessionTx(tx, session.sessionId, "budget", undefined, nowMs);
		return { kind: "refused", detail: "step or time budget used up: the session has ended", sessionId: session.sessionId, endedNow: true };
	}
	session.stepCount += 1;
	session.steps[step.stepId] = {
		stepId: step.stepId,
		tool: step.tool,
		args: JSON.parse(JSON.stringify(step.args)) as JsonObject,
		status: "pending",
		createdAtMs: nowMs,
		index: session.stepCount,
	};
	return { kind: "queued", sessionId: session.sessionId };
}

/**
 * S16: end every running session past its hard time limit (`budget`) or with no sign of life
 * from the app for `maxIdleMs` (`app_lost`). Pure over `Tx`; the caller releases the waiters.
 */
export async function expireSessionsTx(tx: Tx, nowMs: number): Promise<Array<{ sessionId: string; conversationId: number }>> {
	const doc = await tx.doc(LiveSessions);
	const ended: Array<{ sessionId: string; conversationId: number }> = [];
	for (const session of Object.values(doc.sessions)) {
		if (session.status !== "running") continue;
		let reason: LiveEndReason | undefined;
		if (nowMs - session.startedAtMs > liveConfig.maxDurationMs) reason = "budget";
		else if (nowMs - (session.lastPollAtMs ?? session.startedAtMs) > liveConfig.maxIdleMs) reason = "app_lost";
		if (reason === undefined) continue;
		await endSessionTx(tx, session.sessionId, reason, undefined, nowMs);
		ended.push({ sessionId: session.sessionId, conversationId: session.conversationId });
	}
	return ended;
}

export function requireOpenStep(session: LiveSession, stepId: string): LiveStep {
	const step = session.steps[stepId];
	if (step === undefined) throw notFound(`no step ${stepId}`);
	if (session.status !== "running") throw conflict("SESSION_ENDED", "this session has ended");
	if (step.status !== "delivered") throw conflict("STEP_NOT_OPEN", `step ${stepId} is ${step.status}, not waiting for an answer`);
	return step;
}

/**
 * A13.4: record a `screen_ask` (a server-side wait, not a phone step) and append the
 * `ask` chat event. Idempotent on askId.
 */
export async function queueAskTx(
	tx: Tx,
	conversationId: number,
	ask: { askId: string; question: string },
	nowMs: number,
): Promise<QueueResult> {
	const doc = await tx.doc(LiveSessions);
	const session = sessionForConversation(doc, conversationId);
	if (session === undefined || session.status !== "running") {
		return { kind: "refused", detail: "this phone session is over; call screen_end or stop" };
	}
	if (session.asks?.[ask.askId] !== undefined) return { kind: "queued", sessionId: session.sessionId };
	// Two statements: the ??= form would mutate a raw temporary, not the tracked draft.
	if (session.asks === undefined) session.asks = {};
	session.asks[ask.askId] = { askId: ask.askId, question: ask.question, status: "waiting", createdAtMs: nowMs };
	await appendChatEvent(tx, "phone_use", { kind: "ask", sessionId: session.sessionId, question: ask.question, askId: ask.askId, atMs: nowMs }, nowMs);
	return { kind: "queued", sessionId: session.sessionId };
}

/** A13.4: take (and clear) the owner messages queued for the session's next tool result. */
export async function drainOwnerNotesTx(tx: Tx, conversationId: number): Promise<string[]> {
	const doc = await tx.doc(LiveSessions);
	const session = sessionForConversation(doc, conversationId);
	const notes = session?.ownerNotes ?? [];
	if (session !== undefined && notes.length > 0) session.ownerNotes = [];
	// Copy: the caller may only use committed values, never the draft overlay.
	return JSON.parse(JSON.stringify(notes)) as string[];
}

