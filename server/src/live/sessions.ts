/**
 * Live sessions (protocol A10.3, server half): start, long-poll, answer, stop, sweep.
 *
 * A session is its own Pi Durable conversation whose agent selects ONLY the live-session
 * extension (three screen tools). The phone's foreground service long-polls `next`, runs
 * each step under the app's guards and answers. The session document is the durable
 * record; the in-process hub (`hub.ts`) only wakes waiters.
 */
import { homedir } from "node:os";
import { join } from "node:path";
import type { Context } from "@earendil-works/chord";
import { configure, type Conversation, type Harness, type JsonObject, type Submission } from "@earendil-works/pi-durable";
import { appendChatEvent } from "../chat-events.ts";
import { recordDecision } from "../documents.ts";
import { badRequest, conflict, notFound } from "../errors.ts";
import { newId } from "../ids.ts";
import { authMtimeMsOf, modelFieldFor, modelLabelOf, noteTurnModelFailure, type FailureChannel } from "../model-failure.ts";
import { closeRun, closeRunReliably, openRun } from "../run-tracking.ts";
import { startLiveChatBridge, stopLiveChatBridge } from "./bridge.ts";
import { expireRequestsTx } from "./requests.ts";
import { askAnswered, conversationEnded, endAllWaiters, stepAnswered, waitForPoll, wakePollers } from "./hub.ts";
import {
	LIVE_SCREEN_TOOLS,
	LiveSessions,
	STEWARD_PACKAGE,
	configureLive,
	endSessionTx,
	expireSessionsTx,
	liveConfig,
	ownedSession,
	pruneSessions,
	requireOpenStep,
	resetLiveConfig,
	runningSession,
	stepSummary,
	type LiveEndReason,
	type LiveSession,
} from "./state.ts";
import { liveScreenExtension } from "./tools.ts";
import { MAX_DETAIL, redactCodes, renderTree, sanitiseTree } from "./tree.ts";

export { LIVE_SCREEN_TOOLS, LiveSessions, configureLive, resetLiveConfig };
export { stewardHarnessSettings } from "./tools.ts";

export type LiveDeps = { harness: Harness; root: Conversation; context: Context };

/** A failed steward turn: A15.3's plain line goes to Chat (a session's `say`, or a main `message`). */
async function noteTurnFailure(deps: Pick<LiveDeps, "harness" | "context">, conversation: Conversation, channel: FailureChannel, settled: { status: string; reason?: string; detail?: unknown }): Promise<void> {
	try {
		const agent = await conversation.agent(deps.context);
		const ref = agent.model;
		if (ref === undefined) return;
		await noteTurnModelFailure(
			{
				harness: deps.harness,
				conversationId: conversation.id,
				model: { provider: ref.provider, modelId: ref.modelId },
				label: modelLabelOf(ref),
				channel,
				authMtimeMs: authMtimeMsOf(process.env.PHONE_STEWARD_PI_DIR ?? join(homedir(), ".pi", "agent")),
				context: deps.context,
			},
			settled,
		);
	} catch (error) {
		console.error(`[steward] turn failure note failed: ${error instanceof Error ? error.message : String(error)}`);
	}
}

export const MAX_TASK_CHARS = 2000;
export const MAX_POLL_SECONDS = 30;

export async function startLiveSession(
	deps: LiveDeps,
	input: { deviceId: string; task: string },
): Promise<{ sessionId: string }> {
	const task = input.task.trim();
	if (task === "") throw badRequest("task is required");
	if (task.length > MAX_TASK_CHARS) throw badRequest(`task is at most ${MAX_TASK_CHARS} characters`);
	const sessionId = newId("ls");
	const model = (await deps.root.agent(deps.context)).model;
	// Correction 1 (A15.10/A13.3): the session's first line names the model its turn will run on.
	const modelField = model === undefined ? undefined : modelFieldFor(model);
	const nowMs = Date.now();
	await deps.harness.commit(async (tx) => {
		const doc = await tx.doc(LiveSessions);
		const active = runningSession(doc);
		if (active !== undefined) throw conflict("LIVE_SESSION_ACTIVE", "a phone session is already running; stop it first");
		const created = await tx.createConversation({ ownership: { kind: "ownerless" } });
		// The live conversation selects ONLY the live-session extension: the screen tools.
		await configure(tx, created.id, { ...(model === undefined ? {} : { model }), extensions: [liveScreenExtension] });
		doc.sessions[sessionId] = {
			sessionId,
			conversationId: created.id as unknown as number,
			deviceId: input.deviceId,
			task,
			status: "running",
			startedAtMs: nowMs,
			stepCount: 0,
			consecutiveTimeouts: 0,
			steps: {},
			handback: "pending",
		};
		pruneSessions(doc);
		// A13.3: the session is shown in Chat from its first moment; A15.10/Correction 1: it names its model.
		await appendChatEvent(tx, "phone_use", { kind: "started", sessionId, task, ...(modelField === undefined ? {} : { model: modelField }), atMs: nowMs }, nowMs);
		return undefined;
	}, deps.context);
	const session = (await deps.harness.snapshot(LiveSessions, deps.context))?.sessions[sessionId];
	if (session === undefined) throw new Error("live session record missing after creation");
	let submission: Submission;
	let conversation: Conversation | undefined;
	try {
		conversation = await deps.harness.conversation(session.conversationId as never, deps.context);
		if (conversation === undefined) throw new Error("live conversation missing after creation");
		submission = await conversation.submit(
			{ type: "input", content: `The owner started a phone session. Task: ${task}`, requestId: `live:${sessionId}` },
			deps.context,
		);
	} catch (error) {
		await finishSession(deps, sessionId, "error");
		throw error;
	}
	// When the model's turn settles (it stopped calling tools, or errored), the session is over.
	const sessionConversation: Conversation = conversation;
	void submission
		.wait(deps.context)
		.then(async (settled) => {
			// A15.3: a first turn that failed before any output with an auth/plan error is said plainly.
			if (settled.status !== "done") await noteTurnFailure(deps, sessionConversation, { kind: "say", sessionId }, settled);
			return finishSession(deps, sessionId, settled.status === "done" ? "finished" : "error");
		})
		.catch(() => finishSession(deps, sessionId, "error"));
	// A13.3: the session model's own text becomes `say` events.
	startLiveChatBridge(conversation, sessionId);
	return { sessionId };
}

async function finishSession(deps: LiveDeps, sessionId: string, reason: LiveEndReason, summary?: string): Promise<void> {
	try {
		const result = await deps.harness.commit((tx) => endSessionTx(tx, sessionId, reason, summary, Date.now()), deps.context);
		conversationEnded(result.conversationId);
		wakePollers(sessionId);
	} catch (error) {
		console.error(`[steward] live session ${sessionId} end failed: ${error instanceof Error ? error.message : String(error)}`);
	} finally {
		stopLiveChatBridge(sessionId);
		// A13.5: the end hands back to the main conversation (idempotent, keyed on the
		// session). A harness closed underneath is the shutdown race: the persisted
		// `handback: "pending"` marker survives and the start-up sweep hands back then.
		handbackEndedSessions(deps).catch((error) =>
			console.error(`[steward] phone-use hand-back failed: ${error instanceof Error ? error.message : String(error)}`),
		);
	}
}

export type NextStep =
	| { step: { stepId: string; tool: string; args: JsonObject } }
	| { step: null }
	| { ended: true; reason: LiveEndReason; summary?: string };

/** The app's long-poll: deliver the oldest pending step exactly once, or say the session ended. */
export async function nextLiveStep(
	deps: LiveDeps,
	sessionId: string,
	deviceId: string,
	waitSeconds: number,
	signal?: AbortSignal,
): Promise<NextStep> {
	const deadline = Date.now() + Math.max(0, Math.min(waitSeconds, MAX_POLL_SECONDS)) * 1000;
	for (;;) {
		// Register before reading, so a step queued in between still wakes us.
		const remaining = Math.max(0, deadline - Date.now());
		const waiting = waitForPoll(sessionId, remaining, signal);
		try {
			const outcome = await deps.harness.commit(async (tx): Promise<NextStep | undefined> => {
				const doc = await tx.doc(LiveSessions);
				const session = ownedSession(doc, sessionId, deviceId);
				if (session.status === "running") session.lastPollAtMs = Date.now();
				if (session.status === "ended") {
					return {
						ended: true,
						reason: session.endReason ?? "finished",
						...(session.summary === undefined ? {} : { summary: session.summary }),
					};
				}
				const pending = Object.values(session.steps)
					.filter((step) => step.status === "pending")
					.sort((a, b) => a.createdAtMs - b.createdAtMs)[0];
				if (pending === undefined) return undefined;
				pending.status = "delivered";
				pending.deliveredAtMs = Date.now();
				return { step: { stepId: pending.stepId, tool: pending.tool, args: JSON.parse(JSON.stringify(pending.args)) as JsonObject } };
			}, deps.context);
			if (outcome !== undefined) return outcome;
			if (remaining <= 0 || signal?.aborted) return { step: null };
			await waiting.promise;
			if (signal?.aborted) return { step: null };
		} finally {
			waiting.dispose();
		}
	}
}

export type StepAnswer = {
	outcome: "ok" | "refused" | "failed";
	tree?: unknown;
	detail?: string;
	/** The text of the node an action touched; ledgered, never sent to the model. */
	nodeText?: string;
};

export function parseStepAnswer(body: Record<string, unknown>): StepAnswer {
	const outcome = body.outcome;
	if (outcome !== "ok" && outcome !== "refused" && outcome !== "failed") throw badRequest("outcome must be ok, refused or failed");
	const detail = body.detail;
	if (detail !== undefined && typeof detail !== "string") throw badRequest("detail must be a string");
	const nodeText = body.nodeText;
	if (nodeText !== undefined && typeof nodeText !== "string") throw badRequest("nodeText must be a string");
	return {
		outcome,
		...(body.tree === undefined ? {} : { tree: body.tree }),
		...(detail === undefined ? {} : { detail }),
		...(nodeText === undefined ? {} : { nodeText }),
	};
}

export type StepAck = { accepted: true; late?: true };

/** The decisions-ledger row for one answered screen action (the note is already redacted). */
async function ledgerScreenAction(
	tx: Parameters<typeof recordDecision>[0],
	rootId: Parameters<typeof recordDecision>[1],
	step: LiveSession["steps"][string],
	answer: StepAnswer,
	nodeText: string | undefined,
	detail: string | undefined,
	late: boolean,
	nowMs: number,
): Promise<void> {
	const args = step.args as Record<string, string | undefined>;
	const what = nodeText !== undefined && nodeText !== "" ? ` "${nodeText}"` : args.packageName !== undefined ? ` ${args.packageName}` : "";
	await recordDecision(tx, rootId, {
		atMs: nowMs,
		type: "screen_action",
		kind: args.action ?? "unknown",
		...(args.packageName === undefined ? {} : { packageName: args.packageName }),
		note: `${args.action ?? "action"}${what} -> ${answer.outcome}${late ? " (answered late)" : ""}${detail === undefined || detail === "" ? "" : `: ${detail}`}`,
	});
}

export async function answerLiveStep(
	deps: LiveDeps,
	sessionId: string,
	stepId: string,
	deviceId: string,
	answer: StepAnswer,
): Promise<StepAck> {
	const tree = answer.tree === undefined ? undefined : sanitiseTree(answer.tree);
	// S13: a one-time code is masked before anything is stored, rendered or returned.
	const detail = answer.detail === undefined ? undefined : redactCodes(answer.detail).slice(0, MAX_DETAIL);
	const nodeText = answer.nodeText === undefined ? undefined : redactCodes(answer.nodeText.replace(/\s+/g, " ").trim()).slice(0, 200);
	const nowMs = Date.now();
	const outcome = await deps.harness.commit(async (tx): Promise<"answered" | "late" | "late_ended"> => {
		const doc = await tx.doc(LiveSessions);
		const session = ownedSession(doc, sessionId, deviceId);
		const found = session.steps[stepId];
		// S15: a step the phone really received, but the server had already given up on (its 60 s
		// timer) or ended with the session, still happened on the phone: record it, once. The
		// model has moved on and is not woken.
		if (found !== undefined && found.status === "abandoned" && found.deliveredAtMs !== undefined && found.lateAnswered !== true) {
			found.lateAnswered = true;
			if (found.tool === "screen_act") await ledgerScreenAction(tx, deps.root.id, found, answer, nodeText, detail, true, nowMs);
			if (session.status === "running") session.lastPollAtMs = nowMs;
			return session.status === "running" ? "late" : "late_ended";
		}
		const step = requireOpenStep(session, stepId);
		step.status = "answered";
		step.outcome = answer.outcome;
		session.lastPollAtMs = nowMs;
		if (detail !== undefined) step.detail = detail;
		if (tree !== undefined) {
			step.rendered = renderTree(tree);
			// A15.2: remember what the tree showed in front — and the last package that is
			// not Phone Steward, which the reopen line names.
			step.frontPackage = tree.package;
			if (tree.package !== STEWARD_PACKAGE) session.lastOtherPackage = tree.package;
		}
		if (step.tool === "screen_act") await ledgerScreenAction(tx, deps.root.id, step, answer, nodeText, detail, false, nowMs);
		// A13.3: the owner sees the step in Chat (never the tree; S13-masked summary).
		await appendChatEvent(
			tx,
			"phone_use",
			{
				kind: "step",
				sessionId,
				tool: step.tool,
				summary: stepSummary(step, {
					nodeCount: tree?.nodes.length,
					...(nodeText === undefined ? {} : { nodeText }),
					...(tree === undefined ? {} : { packageName: tree.package }),
				}),
				outcome: answer.outcome,
				atMs: nowMs,
			},
			nowMs,
		);
		return "answered";
	}, deps.context);
	if (outcome === "late_ended") throw conflict("SESSION_ENDED", "this session has ended");
	if (outcome === "late") return { accepted: true, late: true };
	stepAnswered(stepId);
	return { accepted: true };
}

export async function stopLiveSession(
	deps: LiveDeps,
	sessionId: string,
	deviceId: string,
	reason: LiveEndReason = "stopped",
): Promise<{ stopped: true; alreadyEnded: boolean }> {
	const result = await deps.harness.commit(async (tx) => {
		const doc = await tx.doc(LiveSessions);
		ownedSession(doc, sessionId, deviceId);
		return endSessionTx(tx, sessionId, reason, undefined, Date.now());
	}, deps.context);
	conversationEnded(result.conversationId);
	wakePollers(sessionId);
	if (result.changed) {
		// Stop the model's own turn too, without waiting for it.
		void deps.harness
			.conversation(result.conversationId as never, deps.context)
			.then((conversation) => conversation?.abort(deps.context))
			.catch(() => undefined);
	}
	stopLiveChatBridge(sessionId);
	handbackEndedSessions(deps).catch((error) =>
		console.error(`[steward] phone-use hand-back failed: ${error instanceof Error ? error.message : String(error)}`),
	);
	return { stopped: true, alreadyEnded: !result.changed };
}

/** At start-up: no session survives a restart. Returns how many were running. */
export async function sweepLiveSessions(harness: Harness, context: Context): Promise<number> {
	const swept = await harness.commit(async (tx) => {
		const doc = await tx.doc(LiveSessions);
		const conversations: number[] = [];
		for (const session of Object.values(doc.sessions)) {
			if (session.status !== "running") continue;
			await endSessionTx(tx, session.sessionId, "interrupted", undefined, Date.now());
			conversations.push(session.conversationId);
		}
		return conversations;
	}, context);
	endAllWaiters();
	// Stop the swept sessions' model turns too: resume() may have restarted them.
	for (const conversationId of swept) {
		void harness
			.conversation(conversationId as never, context)
			.then((conversation) => conversation?.abort(context))
			.catch(() => undefined);
	}
	// A13.5: an interrupted session hands back once after restart.
	await handbackEndedSessions({ harness, context });
	return swept.length;
}

/**
 * S16: the session limits run on a clock, not only when a step is queued. Ends every running
 * session past its hard time limit (`budget`) or whose app has shown no sign of life for
 * `maxIdleMs` (`app_lost`), and releases any tool call waiting on an open step so the model's
 * turn continues. Returns how many sessions it ended.
 */
export async function expireLiveSessions(deps: Pick<LiveDeps, "harness" | "context">, nowMs: number = Date.now()): Promise<number> {
	const ended = await deps.harness.commit((tx) => expireSessionsTx(tx, nowMs), deps.context);
	// Correction 1 item 4: the same clock sweep expires pending phone-use requests, so the
	// record and the code agree (previously only lazily expired on read).
	const expiredRequests = await deps.harness.commit((tx) => expireRequestsTx(tx, nowMs), deps.context);
	if (expiredRequests > 0) console.error(`[steward] expired ${expiredRequests} phone-use request(s) past their 10 minutes`);
	for (const { sessionId, conversationId } of ended) {
		conversationEnded(conversationId);
		wakePollers(sessionId);
	}
	if (ended.length > 0) {
		handbackEndedSessions(deps).catch((error) =>
			console.error(`[steward] phone-use hand-back failed: ${error instanceof Error ? error.message : String(error)}`),
		);
	}
	return ended.length;
}

export type LiveSweeper = { stop(): void };

/** Run `expireLiveSessions` on a timer (default every 30 s). The timer never keeps the process alive. */
export function startLiveSweeper(
	deps: Pick<LiveDeps, "harness" | "context">,
	options: { intervalMs?: number; now?: () => number } = {},
): LiveSweeper {
	let running = false;
	const timer = setInterval(() => {
		if (running) return;
		running = true;
		void expireLiveSessions(deps, (options.now ?? Date.now)())
			.then((count) => {
				if (count > 0) console.error(`[steward] ended ${count} phone session(s) past their limit`);
			})
			.catch((error) => console.error(`[steward] live session sweep failed: ${error instanceof Error ? error.message : String(error)}`))
			.finally(() => {
				running = false;
			});
	}, options.intervalMs ?? liveConfig.sweepIntervalMs);
	timer.unref();
	return { stop: () => clearInterval(timer) };
}

/** The tool names a live conversation really offers (the safety test's subject). */
export async function liveConversationTools(deps: LiveDeps, conversationId: number): Promise<string[]> {
	const conversation = await deps.harness.conversation(conversationId as never, deps.context);
	if (conversation === undefined) throw notFound(`no conversation ${conversationId}`);
	const agent = await conversation.agent(deps.context);
	return agent.tools.map((tool) => tool.name);
}

export type { LiveSession };


// ————— A13.4: the owner's messages during a session —————

export type OwnerDelivery =
	| { kind: "answered_ask"; sessionId: string; askId: string }
	| { kind: "queued"; sessionId: string }
	| { kind: "session_ended" };

/**
 * Deliver one owner chat message to the running phone session (A13.4): the reply to a
 * waiting `screen_ask` answers it; anything else is queued for the session's next screen
 * tool result as `[owner says] …`. The chat log shows the message as usual. Refuses
 * nothing: the caller routes here only while a session runs, and re-checks inside the
 * commit (a session that ended between read and commit answers `session_ended`).
 */
export async function deliverOwnerMessage(deps: LiveDeps, text: string): Promise<OwnerDelivery> {
	const nowMs = Date.now();
	const result = await deps.harness.commit(async (tx) => {
		const doc = await tx.doc(LiveSessions);
		const session = runningSession(doc);
		if (session === undefined) return { kind: "session_ended" } as const;
		// The chat log shows what the owner typed.
		await appendChatEvent(tx, "message", { role: "user", text, atMs: nowMs }, nowMs);
		const asks = Object.values(session.asks ?? {})
			.filter((ask) => ask.status === "waiting")
			.sort((a, b) => b.createdAtMs - a.createdAtMs);
		const waiting = asks[0];
		if (waiting !== undefined) {
			// S13: a one-time code never reaches the session.
			waiting.status = "answered";
			waiting.reply = redactCodes(text).slice(0, 500);
			waiting.answeredAtMs = nowMs;
			return { kind: "answered_ask", sessionId: session.sessionId, askId: waiting.askId } as const;
		}
		const notes = session.ownerNotes ?? [];
		// Two statements: ??= would push into a raw temporary, not the tracked draft.
		if (session.ownerNotes === undefined) session.ownerNotes = [];
		session.ownerNotes.push(redactCodes(text).slice(0, 500));
		if (session.ownerNotes.length > 20) session.ownerNotes.splice(0, session.ownerNotes.length - 20);
		return { kind: "queued", sessionId: session.sessionId } as const;
	}, deps.context);
	if (result.kind === "answered_ask") askAnswered(result.askId);
	return result;
}

// ————— A13.5: the hand-back to the main conversation —————

export const PHONE_USE_ENDED_PREFIX = "[phone use ended] ";

/** The one-line, deterministic text a finished session delivers into the main conversation. */
export function handbackText(session: {
	task: string;
	endReason?: LiveEndReason;
	stepCount: number;
	summary?: string;
	ownerNotes?: string[];
}): string {
	// The summary and the notes are the session model's/owner's words: masked as S13 before
	// they leave. Notes still queued at the end were never delivered to the session model
	// (correction 1 item 5), so the hand-back carries them.
	const summary = session.summary !== undefined && session.summary.trim() !== "" ? ` summary=${redactCodes(session.summary.trim()).slice(0, 400)}` : "";
	const notes = (session.ownerNotes ?? []).map((note) => redactCodes(note)).join(" | ");
	const ownerSaid = notes !== "" ? ` owner said after the last step: ${notes.slice(0, 400)}` : "";
	return `${PHONE_USE_ENDED_PREFIX}task=${session.task} reason=${session.endReason} steps=${session.stepCount}${summary}${ownerSaid}`;
}

/**
 * Hand every ended, not-yet-handed-back session to the main conversation exactly once
 * (A13.5): submit the `[phone use ended]` input first — the stable requestId
 * `phoneuse:<sessionId>` dedupes a replayed delivery to exactly one input — then mark and
 * open the `phone_use` run. Returns how many sessions were handed back now.
 */
export async function handbackEndedSessions(deps: Pick<LiveDeps, "harness" | "context">): Promise<number> {
	const pending = await deps.harness.commit(async (tx) => {
		const doc = await tx.doc(LiveSessions);
		return Object.values(doc.sessions)
			.filter((session) => session.status === "ended" && session.handback === "pending")
			.map((session) => ({ sessionId: session.sessionId, text: handbackText({ ...session, ownerNotes: session.ownerNotes ?? [] }) }));
	}, deps.context);
	if (pending.length === 0) return 0;
	const root = await deps.harness.root(deps.context);
	let handed = 0;
	for (const session of pending) {
		let submission: Submission;
		try {
			submission = await root.submit(
				{ type: "input", content: session.text, requestId: `phoneuse:${session.sessionId}`, whenBusy: "followUp" },
				deps.context,
			);
		} catch (error) {
			// Not marked: the next sweep retries; the requestId keeps it exactly once.
			console.error(`[steward] phone-use hand-back submit failed (${session.sessionId}): ${error instanceof Error ? error.message : String(error)}`);
			continue;
		}
		await deps.harness.commit(
			async (tx) => {
				const doc = await tx.doc(LiveSessions);
				const record = doc.sessions[session.sessionId];
				if (record !== undefined && record.handback === "pending") {
					record.handback = "done";
					record.handbackAtMs = Date.now();
				}
				await openRun(tx, root.id, `phoneuse:${session.sessionId}`, "phone_use", Date.now());
				return undefined;
			},
			deps.context,
		);
		handed += 1;
		void submission
			.wait(deps.context)
			.then(async (settled) => {
				// A15.3: the hand-back turn's auth/plan failure is said plainly in Chat, like any
				// other owner-visible turn; the run closes either way.
				if (settled.status !== "done") {
					await noteTurnFailure(deps, root, { kind: "message" }, settled);
				}
				return closeRunReliably(deps.harness, root.id, `phoneuse:${session.sessionId}`);
			})
			.catch((error) => {
				console.error(`[steward] phone-use run close failed (phoneuse:${session.sessionId}): ${error instanceof Error ? error.message : String(error)}`);
				return closeRunReliably(deps.harness, root.id, `phoneuse:${session.sessionId}`);
			});
	}
	return handed;
}
