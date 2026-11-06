/**
 * The device-facing HTTP API (docs/protocol.md §1–§7) and the local admin channel, on
 * `node:http` — no library: the surface is small, SSE and long-poll need exact control,
 * and zero dependencies keep the supply chain flat.
 *
 * One router serves two listeners: the TCP listener (the device API, bearer-token auth)
 * and a Unix socket at `<stateDir>/admin.sock` (mode 0600) whose `/admin/*` routes are the
 * CLI's channel to a running instance (Pi Durable allows one process per SQLite file).
 */
import { chmodSync, existsSync, mkdirSync, rmSync } from "node:fs";
import { open } from "node:fs/promises";
import { pipeline } from "node:stream/promises";
import { createServer, type IncomingMessage, type Server, type ServerResponse } from "node:http";
import { join } from "node:path";
import { BACKGROUND_CONTEXT } from "@earendil-works/chord/context";
import type { Context } from "@earendil-works/chord";
import type { Conversation, Harness, Submission, TaskId, Tx } from "@earendil-works/pi-durable";
import {
	AUTH,
	activeDevice,
	authenticateTokenState,
	createPairingCode,
	pairRequest,
	revokeDevice,
	type AuthState,
} from "./auth.ts";
import { ChatEvents, chatEventsAfter, type ChatEvent } from "./chat-events.ts";
import { ownerTurnContent } from "./owner-note.ts";
import type { StewardConfig } from "./config.ts";
import {
	Device,
	RulesDoc,
	Snapshots,
	recordDecision,
	recordDeviceStatus,
	touchDeviceSeen,
	updateRules,
} from "./documents.ts";
import { Proposals } from "./proposals.ts";
import { ApiError, badRequest, conflict, notFound, notImplemented, unauthorized } from "./errors.ts";
import { ReleaseStore, type ReleaseState } from "./release.ts";
import { expireDueJobs, jobsOverview, queueJob, queuedJobs, reportPhoneResult } from "./jobs.ts";
import { crashPoint } from "./crash-gate.ts";
import { CRASH_LIMITS, Crashes, crashNotification, parseCrashBody, recordCrash } from "./crash-reports.ts";
import { notifySenderFromConfig, queueNotify, setNotifySender } from "./notify.ts";
import { decideProposal, decisionValid, recordExecution } from "./proposals.ts";
import { Jobs } from "./jobs.ts";
import { recordRequest, resolveRequestId } from "./requests.ts";
import { rateLimitAddress } from "./rate-limit-key.ts";
import { PhotoWindowTask, WeeklyCheckTask, type PhotoWindowInput, type WeeklyCheckInput } from "./rule-tasks.ts";
import { closeRun, closeRunReliably, openRun } from "./run-tracking.ts";
import { formatConversation, summariseSnapshot, usagePerDay } from "./observability.ts";
import { chooseModel, modelsResponse, settingsResponse, currentModelRef, type AgentSettingsDeps } from "./agent-settings.ts";
import { AgentBusyError, type ContextControl } from "./context-control.ts";
import type { ModelCatalogue } from "./model-catalogue.ts";
import { authMtimeMsOf, noteTurnModelFailure } from "./model-failure.ts";
import type { Models } from "@earendil-works/pi-ai";
import { handleLiveRoute } from "./live/http.ts";
import { handleVoiceSpeak, handleVoiceTranscribe, voiceDepsFromConfig } from "./voice/http.ts";
import type { FetchLike } from "./voice/deps.ts";
import { deliverOwnerMessage } from "./live/sessions.ts";
import { LiveSessions, runningSession } from "./live/state.ts";
import { restoredLedgerEntry, validateRestoredItems } from "./restored-files.ts";

const BODY_LIMIT_BYTES = 2 * 1024 * 1024;
/** S10: how long requests in flight may take to finish when the server is closing. */
const SHUTDOWN_GRACE_MS = 1_000;

const ADMIN_SOCKET_NAME = "admin.sock";

export type StewardServers = {
	tcp: Server;
	admin: Server | undefined;
	adminSocketPath: string | undefined;
	close(): Promise<void>;
};

type Route = {
	config: StewardConfig;
	harness: Harness;
	root: Conversation;
	context: Context;
	release: ReleaseStore;
	/** A10.4/A10.9 settings deps; absent when the caller did not wire the catalogue and context control. */
	agent: AgentSettingsDeps | undefined;
	contextControl: ContextControl | undefined;
	/** The chat streams open on this server right now; closing the server ends them (S10). */
	streams: Set<ServerResponse>;
	/** A15.5/A15.6: the injected provider `fetch` (tests use a fake; production uses the global). */
	voiceFetch?: FetchLike;
};

function sendJson(res: ServerResponse, status: number, body: unknown, headers?: Record<string, string | undefined>): void {
	// Snapshots can hold live tracker values that only read cleanly inside their read;
	// round-trip through JSON to detach before serialising.
	const payload = JSON.stringify(body === undefined ? null : JSON.parse(JSON.stringify(body)));
	res.writeHead(status, {
		"content-type": "application/json; charset=utf-8",
		"content-length": Buffer.byteLength(payload),
		...(headers ?? {}),
	});
	res.end(payload);
}

function sendError(res: ServerResponse, error: unknown): void {
	if (error instanceof ApiError) {
		const retryAfter =
			error.status === 429 || error.status === 503
				? { "retry-after": String(error.retryAfterSeconds ?? 1) }
				: {};
		sendJson(res, error.status, error.body(), retryAfter);
		return;
	}
	const message = error instanceof Error ? error.message : String(error);
	console.error(`[steward] internal error: ${message}`);
	sendJson(res, 500, { error: { code: "INTERNAL", message: "internal server error" } });
}

async function readBody(req: IncomingMessage, limitBytes: number = BODY_LIMIT_BYTES): Promise<string> {
	return new Promise((resolve, reject) => {
		let size = 0;
		const chunks: Buffer[] = [];
		req.on("data", (chunk: Buffer) => {
			size += chunk.length;
			if (size > limitBytes) {
				req.removeAllListeners("data");
				req.resume(); // drain the rest so the response can flush
				const limit = limitBytes % (1024 * 1024) === 0 ? `${limitBytes / (1024 * 1024)} MiB` : `${limitBytes / 1024} KiB`;
				reject(new ApiError(413, "BODY_TOO_LARGE", `request bodies are at most ${limit}`));
				return;
			}
			chunks.push(chunk);
		});
		req.on("end", () => resolve(Buffer.concat(chunks).toString("utf8")));
		req.on("error", reject);
	});
}

async function readJsonBody(req: IncomingMessage, limitBytes?: number): Promise<Record<string, unknown>> {
	const raw = await readBody(req, limitBytes);
	if (raw.trim() === "") return {};
	try {
		const parsed: unknown = JSON.parse(raw);
		if (parsed === null || typeof parsed !== "object" || Array.isArray(parsed)) {
			throw new Error("not an object");
		}
		return parsed as Record<string, unknown>;
	} catch {
		throw badRequest("the request body must be a JSON object");
	}
}

function bearerToken(req: IncomingMessage): string | undefined {
	const header = req.headers.authorization;
	if (header === undefined || !header.startsWith("Bearer ")) return undefined;
	return header.slice("Bearer ".length).trim();
}

/** The protocol's job shape: ids and timestamps only, no server-side bookkeeping. */
function jobJson(job: {
	jobId: string;
	kind: string;
	params: Record<string, unknown>;
	createdAtMs: number;
	expiresAtMs: number;
}): unknown {
	return {
		jobId: job.jobId,
		kind: job.kind,
		params: job.params,
		createdAtMs: job.createdAtMs,
		expiresAtMs: job.expiresAtMs,
	};
}

function proposalJson(proposal: {
	proposalId: string;
	createdAtMs: number;
	source: string;
	title: string;
	reason: string;
	bytesEstimate: number;
	status: string;
	supersededBy?: string;
	supersededAtMs?: number;
	builtBy?: string;
	items: Array<{ itemId: string; kind: string; target: unknown; bytesEstimate: number; reason: string }>;
}): unknown {
	return {
		proposalId: proposal.proposalId,
		createdAtMs: proposal.createdAtMs,
		source: proposal.source,
		title: proposal.title,
		reason: proposal.reason,
		bytesEstimate: proposal.bytesEstimate,
		status: proposal.status,
		...(proposal.supersededBy === undefined ? {} : { supersededBy: proposal.supersededBy, supersededAtMs: proposal.supersededAtMs }),
		...(proposal.builtBy === undefined ? {} : { builtBy: proposal.builtBy }),
		items: proposal.items.map((item) => ({
			itemId: item.itemId,
			kind: item.kind,
			target: item.target,
			bytesEstimate: item.bytesEstimate,
			reason: item.reason,
		})),
	};
}

/** The chat posts answer 202 (accepted), not 200. */
const CHAT_ROUTES = new Set(["POST /v1/chat", "POST /admin/chat", "POST /v1/chat/compact", "POST /v1/chat/fresh"]);

function requireRequestId(body: Record<string, unknown>): string {
	const requestId = body.requestId;
	if (typeof requestId !== "string" || requestId.length < 1 || requestId.length > 128) {
		throw badRequest("requestId is required (1–128 characters)");
	}
	return requestId;
}

/**
 * Wraps a mutating POST/PUT handler with the protocol §1 requestId idempotency: a repeat
 * with the same route and body returns the first response with `"duplicate": true`.
 */
function deduped(
	routeName: string,
	handler: (route: Route, body: Record<string, unknown>, requestId: string) => Promise<Record<string, unknown>>,
): (route: Route, req: IncomingMessage, res: ServerResponse) => Promise<void> {
	return async (route, req, res) => {
		const body = await readJsonBody(req);
		const requestId = requireRequestId(body);
		const existing = await route.harness.commit(
			(tx) => resolveRequestId(tx, { requestId, route: routeName, body }),
			route.context,
		);
		if (existing !== undefined) {
			sendJson(res, CHAT_ROUTES.has(routeName) ? 202 : 200, {
				...(existing.response as Record<string, unknown>),
				duplicate: true,
			});
			return;
		}
		const response = await handler(route, body, requestId);
		await route.harness.commit(
			(tx) =>
				recordRequest(tx, {
					requestId,
					route: routeName,
					body,
					response: JSON.parse(JSON.stringify(response)) as never,
					nowMs: Date.now(),
				}),
			route.context,
		);
		sendJson(res, CHAT_ROUTES.has(routeName) ? 202 : 200, response);
	};
}

// ————— route handlers —————

/** Read one conversation document detached from its overlay (stringify inside the commit). */
async function readDoc<T extends import("@earendil-works/pi-durable").JsonObject>(
	route: Route,
	token: import("@earendil-works/pi-durable").ConversationDocToken<T>,
): Promise<T | undefined> {
	const read = await route.harness.commit(async (tx) => {
		const doc = await tx.doc(token, route.root.id);
		return doc === undefined ? undefined : JSON.parse(JSON.stringify({ ...doc }));
	}, route.context);
	return read as T | undefined;
}

async function handlePair(route: Route, req: IncomingMessage, res: ServerResponse): Promise<void> {
	const body = await readJsonBody(req);
	const code = body.code;
	const deviceName = body.deviceName;
	if (typeof code !== "string" || typeof deviceName !== "string" || deviceName.length === 0) {
		throw badRequest("code and deviceName are required");
	}
	const result = await pairRequest(
		(change: (tx: Tx) => unknown) => route.harness.commit(change as never, route.context),
		{ code, deviceName, address: rateLimitAddress(req, route.config.trustedProxies), nowMs: Date.now() },
	);
	sendJson(res, 200, { deviceId: result.deviceId, token: result.token });
}

async function handleJobsGet(route: Route, res: ServerResponse, url: URL, deviceId: string): Promise<void> {
	// N9: an authenticated poll proves the phone is alive; the write is throttled to once a minute.
	await route.harness.commit((tx) => touchDeviceSeen(tx, route.root.id, deviceId, Date.now()), route.context);
	const waitSeconds = Math.max(0, Math.min(30, Number(url.searchParams.get("wait") ?? "0") || 0));
	const deadline = Date.now() + waitSeconds * 1000;
	for (;;) {
		await expireDueJobs(route.harness, route.root.id, Date.now(), route.context);
		const pending = await route.harness.commit((tx) => queuedJobs(tx, route.root.id, Date.now()), route.context);
		if (pending.length > 0) {
			sendJson(res, 200, { jobs: pending.map(jobJson) });
			return;
		}
		if (Date.now() >= deadline) {
			sendJson(res, 200, { jobs: [] });
			return;
		}
		await new Promise((resolve) => setTimeout(resolve, 250));
	}
}

async function handleJobResultById(
	route: Route,
	req: IncomingMessage,
	res: ServerResponse,
	jobId: string,
): Promise<void> {
	const body = await readJsonBody(req);
	const requestId = requireRequestId(body);
	const routeName = "POST /v1/jobs/{id}/result";
	const existing = await route.harness.commit(
		(tx) => resolveRequestId(tx, { requestId, route: routeName, body: { ...body, __jobId: jobId } }),
		route.context,
	);
	if (existing !== undefined) {
		sendJson(res, 200, { ...(existing.response as Record<string, unknown>), duplicate: true });
		return;
	}
	const status = body.status;
	if (status !== "ok" && status !== "error" && status !== "partial") {
		throw badRequest("status must be ok, error or partial");
	}
	const error = body.error === null || body.error === undefined ? null : (body.error as { code: string; message: string });
	// item 3: the ledger entry shares the acceptance commit.
	await reportPhoneResult(
		route.harness,
		route.root.id,
		jobId,
		{
			status,
			data: (body.data ?? null) as never,
			error,
			deviceTimeMs: typeof body.deviceTimeMs === "number" ? body.deviceTimeMs : undefined,
			appVersion: typeof body.appVersion === "string" ? body.appVersion : undefined,
		},
		route.context,
		{ requestId, route: routeName, body: { ...body, __jobId: jobId } },
	);
	// 05.3 crash window: the effect + ledger commit has landed; the response has not.
	await crashPoint("http:result-committed");
	sendJson(res, 200, { accepted: true, duplicate: false });
}

// ————— app update (A7) —————

/** The verified release, or the A7 error: 404 NO_RELEASE, 500 RELEASE_CORRUPT. */
async function requireRelease(route: Route): Promise<Extract<ReleaseState, { kind: "ok" }>> {
	const state = await route.release.current();
	if (state.kind === "none") throw new ApiError(404, "NO_RELEASE", "no app release is published");
	if (state.kind === "corrupt") {
		console.error(`[steward] release corrupt: ${state.reason} does not match release.json`);
		throw new ApiError(500, "RELEASE_CORRUPT", "the published APK does not match its release record");
	}
	return state;
}

async function handleAppLatest(route: Route, res: ServerResponse): Promise<void> {
	const { record } = await requireRelease(route);
	sendJson(res, 200, {
		versionName: record.versionName,
		versionCode: record.versionCode,
		sha256: record.sha256,
		sizeBytes: record.sizeBytes,
		publishedAtMs: record.publishedAtMs,
	});
}

/** Streams the current release APK. The only file ever opened is the one release.json names. */
async function handleAppApk(route: Route, res: ServerResponse, url: URL): Promise<void> {
	const release = await requireRelease(route);
	const wanted = url.searchParams.get("versionCode");
	if (wanted === null || !/^[0-9]{1,9}$/.test(wanted)) throw badRequest("versionCode is required (an integer)");
	if (Number(wanted) !== release.record.versionCode) {
		throw conflict("RELEASE_CHANGED", "the published release is a different version; ask /v1/app/latest again");
	}
	const handle = await open(release.filePath, "r");
	try {
		const info = await handle.stat();
		if (info.size !== release.record.sizeBytes || info.mtimeMs !== release.mtimeMs) {
			throw conflict("RELEASE_CHANGED", "the release file changed while it was being served; ask again");
		}
		res.writeHead(200, {
			"content-type": "application/vnd.android.package-archive",
			"content-length": info.size,
			"x-sha256": release.record.sha256,
		});
		// Stream from the open handle: the APK is never held in memory.
		await pipeline(handle.createReadStream(), res).catch(() => undefined);
	} finally {
		await handle.close().catch(() => undefined);
	}
}

async function handleProposalsGet(route: Route, res: ServerResponse, url: URL): Promise<void> {
	const status = url.searchParams.get("status") ?? "open";
	const stored = await readDoc(route, Proposals);
	const all = Object.values(stored?.proposals ?? {}).sort((a, b) => a.createdAtMs - b.createdAtMs);
	const filtered = status === "all" ? all : all.filter((proposal) => proposal.status === status);
	sendJson(res, 200, { proposals: filtered.map(proposalJson) });
}

async function handleProposalDecisionById(
	route: Route,
	req: IncomingMessage,
	res: ServerResponse,
	proposalId: string,
): Promise<void> {
	const body = await readJsonBody(req);
	const requestId = requireRequestId(body);
	const routeName = "POST /v1/proposals/{id}/decision";
	const existing = await route.harness.commit(
		(tx) => resolveRequestId(tx, { requestId, route: routeName, body: { ...body, __proposalId: proposalId } }),
		route.context,
	);
	if (existing !== undefined) {
		sendJson(res, 200, { ...(existing.response as Record<string, unknown>), duplicate: true });
		return;
	}
	const decision = body.decision;
	if (decision !== "approve" && decision !== "reject") throw badRequest("decision must be approve or reject");
	const itemIds = body.itemIds === undefined ? undefined : (body.itemIds as string[]);
	if (itemIds !== undefined && !Array.isArray(itemIds)) throw badRequest("itemIds must be a list of item ids");
	const requestRecord = { requestId, route: routeName, body: { ...body, __proposalId: proposalId } };
	const result = await decideProposal(
		route.harness,
		route.root.id,
		proposalId,
		{ decision, itemIds },
		route.context,
		requestRecord,
	);
	// 04.4 crash window: the commit (effect + ledger) has landed; the response has not.
	await crashPoint("http:decision-committed");
	sendJson(res, 200, result);
}

async function handleProposalExecutionById(
	route: Route,
	req: IncomingMessage,
	res: ServerResponse,
	proposalId: string,
): Promise<void> {
	const body = await readJsonBody(req);
	const requestId = requireRequestId(body);
	const routeName = "POST /v1/proposals/{id}/execution";
	const existing = await route.harness.commit(
		(tx) => resolveRequestId(tx, { requestId, route: routeName, body: { ...body, __proposalId: proposalId } }),
		route.context,
	);
	if (existing !== undefined) {
		sendJson(res, 200, { ...(existing.response as Record<string, unknown>), duplicate: true });
		return;
	}
	const decisionId = body.decisionId;
	if (typeof decisionId !== "string") throw badRequest("decisionId is required");
	const items = body.items;
	if (!Array.isArray(items)) throw badRequest("items must be a list");
	for (const item of items) {
		const one = item as { itemId?: unknown; outcome?: unknown };
		if (typeof one.itemId !== "string") throw badRequest("each item needs itemId");
		if (one.outcome !== "done" && one.outcome !== "cancelled" && one.outcome !== "failed" && one.outcome !== "skipped_protected") {
			throw badRequest("item outcome must be done, cancelled, failed or skipped_protected");
		}
	}
	const requestRecord = { requestId, route: routeName, body: { ...body, __proposalId: proposalId } };
	await recordExecution(
		route.harness,
		route.root.id,
		proposalId,
		{
			decisionId,
			items: items.map((item) => {
				const one = item as {
					itemId: string;
					outcome: "done" | "cancelled" | "failed" | "skipped_protected";
					resultCode?: unknown;
					bytesFreed?: unknown;
					trashedBytes?: unknown;
				};
				return {
					itemId: one.itemId,
					outcome: one.outcome,
					resultCode: typeof one.resultCode === "number" ? one.resultCode : null,
					bytesFreed: typeof one.bytesFreed === "number" ? one.bytesFreed : null,
					...(typeof one.trashedBytes === "number" ? { trashedBytes: one.trashedBytes } : {}),
				};
			}),
		},
		route.context,
		requestRecord,
	);
	sendJson(res, 200, { accepted: true });
}

async function handleDecisionGet(route: Route, res: ServerResponse, decisionId: string): Promise<void> {
	const decision = await decisionValid(route.harness, route.root.id, decisionId, route.context);
	if (decision === undefined) throw notFound("no valid decision with this id");
	sendJson(res, 200, {
		valid: true,
		proposalId: decision.proposalId,
		approvedItemIds: decision.approvedItemIds,
		expiresAtMs: decision.expiresAtMs,
	});
}

async function handleRulesGet(route: Route, res: ServerResponse): Promise<void> {
	const stored = await readDoc(route, RulesDoc);
	if (stored !== undefined) {
		sendJson(res, 200, stored);
		return;
	}
	throw notFound("rules are not initialised");
}

const handleRulesPut = deduped("PUT /v1/rules", async (route, body, requestId) => {
	const ruleVersion = body.ruleVersion;
	const photoWindow = body.photoWindow;
	if (typeof ruleVersion !== "number" || photoWindow === null || typeof photoWindow !== "object") {
		throw badRequest("ruleVersion and photoWindow are required");
	}
	// item 4: update + ledger in one commit.
	const rules = await route.harness.commit(
		async (tx) => {
			const updated = await updateRules(tx, route.root.id, { ruleVersion, photoWindow: photoWindow as never });
			const response = JSON.parse(JSON.stringify(updated)) as Record<string, unknown>;
			await recordRequest(tx, {
				requestId,
				route: "PUT /v1/rules",
				body,
				response: JSON.parse(JSON.stringify(response)) as never,
				nowMs: Date.now(),
			});
			return response;
		},
		route.context,
	);
	return rules;
});

/**
 * One owner chat message into the conversation: the single path behind the phone's
 * `POST /v1/chat` and the admin `POST /admin/chat` (S7, `bin/steward say`).
 */
/**
 * A15.3: one failed owner turn (chat or phone-use hand-back) whose model call failed
 * before any output with an auth/plan error says so plainly in Chat and marks the model.
 * Everything else is today's behaviour. Needs the agent deps (the model registry); in the
 * 501 mode without them there is no catalogue to mark either.
 */
async function noteOwnerTurnFailure(
	deps: { harness: Route["harness"]; root: Route["root"]; context: Route["context"]; config: Route["config"]; agent: AgentSettingsDeps | undefined },
	settled: { status: string; reason?: string; detail?: unknown },
): Promise<void> {
	if (deps.agent === undefined) return;
	const model = await currentModelRef(deps.agent);
	const found = deps.agent.models.getModel(model.provider, model.id);
	await noteTurnModelFailure(
		{
			harness: deps.harness,
			conversationId: deps.root.id,
			model: { provider: model.provider, modelId: model.id },
			label: found?.name ?? `${model.provider}/${model.id}`,
			channel: { kind: "message" },
			authMtimeMs: authMtimeMsOf(deps.config.piAgentDir),
			context: deps.context,
		},
		settled,
	);
}

async function submitOwnerChat(route: Route, text: string, requestId: string): Promise<Record<string, unknown>> {
	// Emit run_start BEFORE the input is submitted (02-answer note 2): the integration
	// reads the conversation inside this commit, so the event must precede the entry.
	const nowMs = Date.now();
	await route.harness.commit(
		async (tx) => {
			await openRun(tx, route.root.id, requestId, "owner", nowMs);
			return undefined;
		},
		route.context,
	);
	let submission: Submission;
	try {
		// S9 (A9.2): the model's copy carries the server's open list and recent outcomes.
		const content = await ownerTurnContent(route.harness, route.root.id, text, route.context, Date.now());
		submission = await route.root.submit({ type: "input", content, requestId }, route.context);
	} catch (error) {
		await route.harness.commit(async (tx) => closeRun(tx, route.root.id, requestId, Date.now()), route.context);
		throw error;
	}
	// Close the run when the answer settles; detached, idempotent through the runs doc.
	// a failed close is retried and logged, never silently swallowed.
	// A15.3: a turn that failed before any output with an auth/plan error says so plainly.
	void submission
		.wait(route.context)
		.then(async (settled) => {
			await noteOwnerTurnFailure(route, settled);
			return closeRunReliably(route.harness, route.root.id, requestId);
		})
		.catch((error) => {
			console.error(`[steward] owner turn ${requestId} wait failed: ${error instanceof Error ? error.message : String(error)}`);
			return closeRunReliably(route.harness, route.root.id, requestId);
		});
	return { accepted: true, conversationId: route.config.conversationId };
}

function chatTextFrom(body: Record<string, unknown>): string {
	const text = body.text;
	if (typeof text !== "string" || text.trim().length === 0) throw badRequest("text is required");
	if (text.length > 32_000) throw badRequest("text is too long");
	return text;
}

const handleChatPost = deduped("POST /v1/chat", async (route, body, requestId) => {
	const text = chatTextFrom(body);
	// A13.4: while a phone session runs, the owner's message goes to that session — the
	// reply to a waiting screen_ask answers it, anything else is queued for the session's
	// next screen tool result. The main conversation gets it in neither case.
	const live = await route.harness.commit(async (tx) => {
		const doc = await tx.doc(LiveSessions);
		const session = runningSession(doc);
		return session === undefined ? undefined : { sessionId: session.sessionId };
	}, route.context);
	if (live !== undefined) {
		const delivered = await deliverOwnerMessage(
			{ harness: route.harness, root: route.root, context: route.context },
			text,
		);
		if (delivered.kind !== "session_ended") {
			return { accepted: true, conversationId: `live:${delivered.sessionId}` };
		}
		// The session ended between the read and the delivery: the message belongs in main.
	}
	return submitOwnerChat(route, text, requestId);
});

/** S7 (A6.3): the server marks what did not come from the phone, so the record shows it. */
export const VIA_SERVER_PREFIX = "[via server] ";

const handleAdminChatPost = deduped("POST /admin/chat", async (route, body, requestId) =>
	submitOwnerChat(route, `${VIA_SERVER_PREFIX}${chatTextFrom(body)}`, requestId),
);

async function handleChatStream(route: Route, req: IncomingMessage, res: ServerResponse, url: URL): Promise<void> {
	const afterParam = url.searchParams.get("after") ?? req.headers["last-event-id"];
	const after = Number(Array.isArray(afterParam) ? afterParam[0] : afterParam) || 0;
	res.writeHead(200, {
		"content-type": "text/event-stream; charset=utf-8",
		"cache-control": "no-cache",
		connection: "keep-alive",
	});
	const send = (event: ChatEvent | { heartbeat: true; seq: number }): void => {
		if ("heartbeat" in event) {
			res.write(`id: ${event.seq}\nevent: heartbeat\ndata: {}\n\n`);
			return;
		}
		res.write(`id: ${event.seq}\nevent: ${event.type}\ndata: ${JSON.stringify(event.data)}\n\n`);
	};
	// Replay from the server's store, then live.
	const history = await route.harness.commit((tx) => chatEventsAfter(tx, after), route.context);
	let lastSeq = after;
	for (const event of history) {
		send(event);
		lastSeq = event.seq;
	}
	const deliver = (value: Readonly<{ seq: number; events: ChatEvent[] }>): void => {
		for (const event of value.events) {
			if (event.seq > lastSeq) {
				lastSeq = event.seq;
				send(event);
			}
		}
	};
	// Pi Durable 1.5.0: `documentState()` is gone; a document watch follows the address. Its first
	// value also closes the gap between the store replay above and the subscription.
	const watch = await route.harness.watchDoc(ChatEvents, route.context);
	if (watch !== undefined) {
		deliver(watch.value);
		watch.start((call) => {
			if (call.kind !== "value" || res.writableEnded) return Promise.resolve();
			deliver(call.value);
			return Promise.resolve();
		});
	}
	const heartbeat = setInterval(() => send({ heartbeat: true, seq: lastSeq }), 20_000);
	route.streams.add(res);
	req.on("close", () => {
		clearInterval(heartbeat);
		// A document watch lives until the Session ends unless it is stopped: one leak per reconnect.
		if (watch !== undefined) void watch.stop();
		route.streams.delete(res);
	});
}

async function handleChatHistory(route: Route, res: ServerResponse, url: URL): Promise<void> {
	const limit = Math.max(1, Math.min(Number(url.searchParams.get("limit") ?? 50) || 50, 200));
	const before = Number(url.searchParams.get("before") ?? Number.MAX_SAFE_INTEGER) || Number.MAX_SAFE_INTEGER;
	const events = await route.harness.commit((tx) => chatEventsAfter(tx, 0), route.context);
	// A4: the envelope is {id, type, data} — the same objects the stream sends (id = seq),
	// oldest first; heartbeat events are never stored.
	sendJson(
		res,
		200,
		{
			events: events
				.filter((event) => event.seq < before)
				.slice(-limit)
				.map((event) => ({ id: event.seq, type: event.type, data: event.data })),
		},
	);
}

const handleDeviceStatus = deduped("POST /v1/device/status", async (route, body, requestId) => {
	const authState = await route.harness.commit(async (tx) => {
		const doc = await tx.doc(AUTH);
		return doc === undefined ? undefined : (JSON.parse(JSON.stringify({ ...doc })) as AuthState);
	}, route.context);
	const deviceId = authState !== undefined ? activeDevice(authState, Date.now())?.deviceId : undefined;
	// item 3: the ledger entry shares the status commit.
	const response = { accepted: true };
	await route.harness.commit(
		async (tx) => {
			await recordDeviceStatus(tx, route.root.id, {
				deviceId: deviceId ?? "unknown",
				appVersion: typeof body.appVersion === "string" ? body.appVersion : undefined,
				permissions: (body.permissions ?? undefined) as never,
				lastWorkerRunMs: typeof body.lastWorkerRunMs === "number" ? body.lastWorkerRunMs : undefined,
				stewardTrash: (body.stewardTrash ?? undefined) as never,
				nowMs: Date.now(),
			});
			await recordRequest(tx, {
				requestId,
				route: "POST /v1/device/status",
				body,
				response,
				nowMs: Date.now(),
			});
		},
		route.context,
	);
	// 05.3 crash window: the effect + ledger commit has landed; the response has not.
	await crashPoint("http:device-committed");
	return response;
});

/**
 * A11.3: the app reports the items it put back from the Steward trash. One `restored`
 * ledger entry per item, in the same commit as the request record, so a crash before the
 * response still lets a retry dedupe. Paths are validated like `delete_files` targets; a
 * proposal id the server does not know is still recorded.
 */
const handleFilesRestored = deduped("POST /v1/files/restored", async (route, body, requestId) => {
	const proposalId = body.proposalId;
	if (typeof proposalId !== "string" || proposalId.length === 0 || proposalId.length > 128) {
		throw badRequest("proposalId is required (1–128 characters)");
	}
	const items = validateRestoredItems(body.items);
	const nowMs = Date.now();
	const response = { recorded: items.length };
	await route.harness.commit(async (tx) => {
		for (const item of items) {
			await recordDecision(tx, route.root.id, restoredLedgerEntry(item, proposalId, nowMs));
		}
		await recordRequest(tx, { requestId, route: "POST /v1/files/restored", body, response, nowMs });
	}, route.context);
	return response;
});

async function handleStatusGet(route: Route, res: ServerResponse): Promise<void> {
	const now = Date.now();
	const authState = await route.harness.commit(async (tx) => {
		const doc = await tx.doc(AUTH);
		return doc === undefined ? undefined : (JSON.parse(JSON.stringify({ ...doc })) as AuthState);
	}, route.context);
	const device = authState !== undefined ? activeDevice(authState, now) : undefined;
	const deviceDoc = await readDoc(route, Device);
	const counts = await route.harness.commit(async (tx) => {
		const jobs = await tx.doc(Jobs, route.root.id);
		const proposals = await tx.doc(Proposals, route.root.id);
		const snaps = await tx.doc(Snapshots, route.root.id);
		return {
			pendingJobs: Object.values(jobs.jobs).filter((job) => job.status === "queued" && job.expiresAtMs > now).length,
			openProposals: Object.values(proposals.proposals).filter((proposal) => proposal.status === "open").length,
			lastSnapshotMs: snaps.snapshots[snaps.snapshots.length - 1]?.capturedAtMs ?? null,
		};
	}, route.context);
	const agent = await route.root.agent(route.context);
	sendJson(res, 200, {
		serverTimeMs: now,
		device: {
			deviceId: device?.deviceId ?? deviceDoc?.deviceId ?? null,
			lastSeenMs: deviceDoc?.lastSeenMs ?? null,
			permissions: deviceDoc?.permissions ?? null,
			stewardTrash: deviceDoc?.stewardTrash ?? null,
		},
		pendingJobs: counts.pendingJobs,
		openProposals: counts.openProposals,
		lastSnapshotMs: counts.lastSnapshotMs,
		agent: {
			model: {
				provider: agent.model?.provider ?? route.config.model.provider,
				id: agent.model?.modelId ?? route.config.model.modelId,
			},
		},
	});
}

function requireAgent(route: Route): AgentSettingsDeps {
	if (route.agent === undefined) throw notImplemented("the model catalogue is not wired on this instance");
	return route.agent;
}

async function handleAgentSettingsGet(route: Route, res: ServerResponse): Promise<void> {
	if (route.agent === undefined) {
		// An instance without the catalogue (a bare test harness): the MVP shape.
		const agent = await route.root.agent(route.context);
		sendJson(res, 200, {
			conversationId: route.config.conversationId,
			model: {
				provider: agent.model?.provider ?? route.config.model.provider,
				id: agent.model?.modelId ?? route.config.model.modelId,
			},
			allowlist: [{ provider: route.config.model.provider, id: route.config.model.modelId, metered: false }],
			usage: { inputTokens: 0, outputTokens: 0 },
		});
		return;
	}
	sendJson(res, 200, await settingsResponse(route.agent));
}

async function handleAgentModelsGet(route: Route, res: ServerResponse, url: URL): Promise<void> {
	const availableOnly = ["1", "true"].includes(url.searchParams.get("availableOnly") ?? "");
	sendJson(res, 200, await modelsResponse(requireAgent(route), { availableOnly }));
}

const handleAgentSettingsPut = deduped("PUT /v1/agent/settings", async (route, body) => chooseModel(requireAgent(route), body));

/**
 * A10.9: manual compaction / fresh start. Answers 202 once the operation has begun (the
 * model may take a minute to summarise); 409 AGENT_BUSY during a run.
 */
async function startContextOperation(route: Route, operation: "compact" | "fresh"): Promise<Record<string, unknown>> {
	if (route.contextControl === undefined) throw notImplemented("context control is not wired on this instance");
	try {
		const { done } = await route.contextControl.start(operation);
		done.catch((error) => console.error(`[steward] ${operation} failed: ${error instanceof Error ? error.message : String(error)}`));
	} catch (error) {
		if (error instanceof AgentBusyError) throw conflict("AGENT_BUSY", error.message);
		throw error;
	}
	return { accepted: true };
}

const handleChatCompact = deduped("POST /v1/chat/compact", async (route) => startContextOperation(route, "compact"));
const handleChatFresh = deduped("POST /v1/chat/fresh", async (route) => startContextOperation(route, "fresh"));

/**
 * A5: the app's crash self-report. No token required (a crash can come before pairing);
 * a valid token attributes the report, anything else is ignored rather than refused, so a
 * report is never lost to auth state. Report, owner ping and request ledger share a commit.
 */
async function handleCrashPost(route: Route, req: IncomingMessage, res: ServerResponse): Promise<void> {
	const routeName = "POST /v1/crash";
	const body = await readJsonBody(req, CRASH_LIMITS.bodyBytes);
	const requestId = requireRequestId(body);
	const input = parseCrashBody(body);
	const existing = await route.harness.commit(
		(tx) => resolveRequestId(tx, { requestId, route: routeName, body }),
		route.context,
	);
	if (existing !== undefined) {
		sendJson(res, 200, { ...(existing.response as Record<string, unknown>), duplicate: true });
		return;
	}
	const token = bearerToken(req);
	const authState = await route.harness.commit(async (tx) => {
		const doc = await tx.doc(AUTH);
		return doc === undefined ? undefined : (JSON.parse(JSON.stringify({ ...doc })) as AuthState);
	}, route.context);
	let deviceId: string | undefined;
	if (token !== undefined && authState !== undefined) {
		try {
			deviceId = authenticateTokenState(authState, token, Date.now());
		} catch {
			deviceId = undefined;
		}
	}
	const nowMs = Date.now();
	const response = await route.harness.commit(async (tx) => {
		const report = await recordCrash(tx, input, {
			address: rateLimitAddress(req, route.config.trustedProxies),
			deviceId,
			nowMs,
		});
		// A5/O5: only a `crash` pings the owner; an `error` report is recorded only.
		const ping = crashNotification(report);
		if (ping !== undefined) await queueNotify(tx, route.root.id, { ...ping, category: "crash" }, nowMs);
		const answer = { accepted: true, reportId: report.reportId };
		await recordRequest(tx, { requestId, route: routeName, body, response: answer, nowMs });
		return answer;
	}, route.context);
	console.error(`[steward] crash report ${response.reportId}: ${input.exception} (${input.appVersion})`);
	sendJson(res, 200, response);
}

// ————— admin channel (the CLI's route to a running instance) —————

async function handleAdmin(route: Route, req: IncomingMessage, res: ServerResponse, url: URL): Promise<void> {
	const path = url.pathname;
	if (req.method === "POST" && path === "/admin/pair") {
		const result = await route.harness.commit((tx) => createPairingCode(tx, Date.now()), route.context);
		sendJson(res, 200, { code: result.code, expiresAtMs: result.expiresAtMs });
		return;
	}
	if (req.method === "POST" && path === "/admin/revoke") {
		const body = await readJsonBody(req).catch(() => ({}) as Record<string, unknown>);
		const result = await route.harness.commit(
			(tx) => revokeDevice(tx, typeof body.deviceId === "string" ? body.deviceId : undefined, Date.now()),
			route.context,
		);
		sendJson(res, 200, result);
		return;
	}
	if (req.method === "GET" && path === "/admin/status") {
		await handleStatusGet(route, res);
		return;
	}
	if (req.method === "GET" && path === "/admin/jobs") {
		const jobs = await jobsOverview(route.harness, route.root.id, route.context);
		// The admin view carries the job's status too (the device-facing §3.1 shape does not).
		sendJson(res, 200, { jobs: jobs.map((job) => ({ ...(jobJson(job) as Record<string, unknown>), status: job.status })) });
		return;
	}
	const adminJobMatch = /^\/admin\/jobs\/([^/]+)$/.exec(path);
	if (req.method === "GET" && adminJobMatch !== null) {
		// O2: the full stored job — params, status, timestamps and the whole result data.
		const jobs = await jobsOverview(route.harness, route.root.id, route.context);
		const job = jobs.find((candidate) => candidate.jobId === adminJobMatch[1]);
		if (job === undefined) throw notFound(`unknown job ${adminJobMatch[1]}`);
		sendJson(res, 200, {
			...(jobJson(job) as Record<string, unknown>),
			status: job.status,
			result: job.result ?? null,
			conflictingReports: job.conflictingReports,
		});
		return;
	}
	if (req.method === "GET" && path === "/admin/proposals") {
		await handleProposalsGet(route, res, new URL("/v1/proposals?status=all", "http://admin"));
		return;
	}
	if (req.method === "GET" && path === "/admin/rules") {
		await handleRulesGet(route, res);
		return;
	}
	if (req.method === "POST" && path === "/admin/selftest") {
		const checks = ["trash_dialog", "manage_media_skip", "trash_restorable", "clear_cache_intent", "workmanager_ran"];
		const { jobId } = await route.harness.commit(
			(tx) =>
				queueJob(tx, route.root.id, {
					dedupeKey: `selftest:${Date.now()}`,
					kind: "phone_selftest",
					params: { checks },
					nowMs: Date.now(),
				}),
			route.context,
		);
		sendJson(res, 200, { queued: true, jobId });
		return;
	}
	if (req.method === "POST" && path === "/admin/diagnostics") {
		// O6: queue a phone_diagnostics job; the CLI's `diag` polls for the result. Crash
		// gates on both sides of the job-creating commit (AR1).
		await crashPoint("admin:diagnostics:before-queue");
		const { jobId } = await route.harness.commit(
			(tx) =>
				queueJob(tx, route.root.id, {
					// A fresh key each call: an engineer asks again after a change and wants a
					// fresh answer, not yesterday's replay.
					dedupeKey: `diagnostics:${Date.now()}`,
					kind: "phone_diagnostics",
					params: {},
					nowMs: Date.now(),
				}),
			route.context,
		);
		await crashPoint("admin:diagnostics:after-queue");
		route.harness.resume();
		sendJson(res, 200, { queued: true, jobId });
		return;
	}
	if (req.method === "POST" && (path === "/admin/run/weekly-check" || path === "/admin/run/photo-window")) {
		const nowMs = Date.now();
		const isWeekly = path === "/admin/run/weekly-check";
		const runId = isWeekly ? `weekly-check:manual:${nowMs}` : `photo-window:manual:${nowMs}`;
		if (isWeekly) {
			const input: WeeklyCheckInput = { runId, start: "open" };
			await route.root.commit(
				(tx) => tx.createTask(WeeklyCheckTask, input, { ownership: { kind: "conversation" }, background: true }),
				route.context,
			);
		} else {
			const input: PhotoWindowInput = { runId, start: "open" };
			await route.root.commit(
				(tx) => tx.createTask(PhotoWindowTask, input, { ownership: { kind: "conversation" }, background: true }),
				route.context,
			);
		}
		// resume() kicks the scheduler for the new background task.
		route.harness.resume();
		sendJson(res, 200, { queued: true, runId });
		return;
	}
	if (req.method === "GET" && path === "/admin/crashes") {
		const state = await route.harness.commit(async (tx) => {
			const doc = await tx.doc(Crashes);
			return JSON.parse(JSON.stringify({ ...doc })) as { reports: unknown[] };
		}, route.context);
		sendJson(res, 200, { crashes: [...state.reports].reverse() });
		return;
	}
	if (req.method === "GET" && path === "/admin/chat") {
		// O1: the conversation in order, thinking omitted, arguments and data truncated.
		const last = Math.max(1, Math.min(Number(url.searchParams.get("last") ?? 20) || 20, 500));
		const page = await route.root.entries({}, 1000, undefined, route.context);
		sendJson(res, 200, { lines: formatConversation([...page.items].reverse(), last) });
		return;
	}
	if (req.method === "POST" && path === "/admin/chat") {
		// S7: `bin/steward say` — an owner message through the phone's /v1/chat path, marked
		// `[via server]`. Socket-only like every /admin/* route.
		await handleAdminChatPost(route, req, res);
		return;
	}
	if (req.method === "POST" && (path === "/admin/compact" || path === "/admin/fresh")) {
		// A10.9: `bin/steward compact|fresh`.
		sendJson(res, 202, await startContextOperation(route, path === "/admin/compact" ? "compact" : "fresh"));
		return;
	}
	if (req.method === "GET" && path === "/admin/snapshot") {
		// O3: the latest stored storage snapshot, summarised.
		const snaps = await readDoc(route, Snapshots);
		sendJson(res, 200, { snapshot: summariseSnapshot(snaps?.snapshots.at(-1)) });
		return;
	}
	if (req.method === "GET" && path === "/admin/usage") {
		// O3: model tokens per day, from what the harness records on assistant messages.
		const page = await route.root.entries({}, 2000, undefined, route.context);
		sendJson(res, 200, { days: usagePerDay(page.items) });
		return;
	}
	if (req.method === "GET" && path === "/admin/doctor") {
		sendJson(res, 200, { ok: true, note: "the running instance answered the admin socket" });
		return;
	}
	throw notFound(`no admin route ${req.method} ${path}`);
}

// ————— router —————

/**
 * item 2: the log line's route is the matched TEMPLATE, never the raw
 * path — path segments are caller-controlled. Anything without a route is `unmatched`.
 */
export function routeTemplateFor(method: string, path: string): string {
	const route = `${method} ${path}`;
	const templates: Array<[RegExp, string]> = [
		[/^POST \/v1\/pair$/, "/v1/pair"],
		[/^POST \/v1\/crash$/, "/v1/crash"],
		[/^GET \/v1\/jobs$/, "/v1/jobs"],
		[/^POST \/v1\/jobs\/[^/]+\/result$/, "/v1/jobs/:id/result"],
		[/^GET \/v1\/proposals$/, "/v1/proposals"],
		[/^POST \/v1\/proposals\/[^/]+\/decision$/, "/v1/proposals/:id/decision"],
		[/^POST \/v1\/proposals\/[^/]+\/execution$/, "/v1/proposals/:id/execution"],
		[/^GET \/v1\/decisions\/[^/]+$/, "/v1/decisions/:id"],
		[/^GET \/v1\/rules$/, "/v1/rules"],
		[/^PUT \/v1\/rules$/, "/v1/rules"],
		[/^POST \/v1\/chat$/, "/v1/chat"],
		[/^GET \/v1\/chat\/stream$/, "/v1/chat/stream"],
		[/^GET \/v1\/chat\/history$/, "/v1/chat/history"],
		[/^POST \/v1\/device\/status$/, "/v1/device/status"],
		[/^POST \/v1\/files\/restored$/, "/v1/files/restored"],
		[/^GET \/v1\/status$/, "/v1/status"],
		[/^GET \/v1\/app\/latest$/, "/v1/app/latest"],
		[/^GET \/v1\/app\/apk$/, "/v1/app/apk"],
		[/^GET \/v1\/agent\/models$/, "/v1/agent/models"],
		[/^POST \/v1\/chat\/compact$/, "/v1/chat/compact"],
		[/^POST \/v1\/chat\/fresh$/, "/v1/chat/fresh"],
		[/^POST \/v1\/voice\/transcribe$/, "/v1/voice/transcribe"],
		[/^POST \/v1\/voice\/speak$/, "/v1/voice/speak"],
		[/^POST \/admin\/compact$/, "/admin/compact"],
		[/^POST \/admin\/fresh$/, "/admin/fresh"],
		[/^GET \/v1\/agent\/settings$/, "/v1/agent/settings"],
		[/^PUT \/v1\/agent\/settings$/, "/v1/agent/settings"],
		[/^POST \/v1\/live\/sessions$/, "/v1/live/sessions"],
		[/^GET \/v1\/live\/sessions\/[^/]+\/next$/, "/v1/live/sessions/:id/next"],
		[/^POST \/v1\/live\/sessions\/[^/]+\/steps\/[^/]+$/, "/v1/live/sessions/:id/steps/:step"],
		[/^POST \/v1\/live\/sessions\/[^/]+\/stop$/, "/v1/live/sessions/:id/stop"],
		[/^POST \/v1\/live\/requests\/[^/]+\/answer$/, "/v1/live/requests/:id/answer"],
		[/^GET \/v1\/live\/requests\/pending$/, "/v1/live/requests/pending"],
		[/^POST \/admin\/pair$/, "/admin/pair"],
		[/^POST \/admin\/revoke$/, "/admin/revoke"],
		[/^GET \/admin\/status$/, "/admin/status"],
		[/^GET \/admin\/jobs$/, "/admin/jobs"],
		[/^GET \/admin\/jobs\/[^/]+$/, "/admin/jobs/:id"],
		[/^GET \/admin\/proposals$/, "/admin/proposals"],
		[/^GET \/admin\/rules$/, "/admin/rules"],
		[/^POST \/admin\/selftest$/, "/admin/selftest"],
		[/^POST \/admin\/diagnostics$/, "/admin/diagnostics"],
		[/^POST \/admin\/run\/weekly-check$/, "/admin/run/weekly-check"],
		[/^POST \/admin\/run\/photo-window$/, "/admin/run/photo-window"],
		[/^GET \/admin\/crashes$/, "/admin/crashes"],
		[/^GET \/admin\/doctor$/, "/admin/doctor"],
		[/^GET \/admin\/chat$/, "/admin/chat"],
		[/^POST \/admin\/chat$/, "/admin/chat"],
		[/^GET \/admin\/snapshot$/, "/admin/snapshot"],
		[/^GET \/admin\/usage$/, "/admin/usage"],
	];
	for (const [pattern, template] of templates) {
		if (pattern.test(route)) return template;
	}
	return "unmatched";
}

/**
 * O4: one structured line per HTTP request on stderr — method, matched route template
 * (never the raw path or query), status, duration, attributed device. Never a body, a
 * token or a file name. `logSink` is injectable so tests can capture the lines.
 */
export function httpRequestLogLine(parts: { method: string; route: string; status: number; ms: number; device: string }, logSink: (line: string) => void = (line) => console.error(line)): void {
	logSink(`[steward] http ${parts.method} ${parts.route} ${parts.status} ${parts.ms}ms device=${parts.device}`);
}

async function handleRoute(route: Route, req: IncomingMessage, res: ServerResponse, isAdmin: boolean): Promise<void> {
	const startedAtMs = Date.now();
	// The device id resolved from the bearer token, for the O4 log line only.
	let logDevice = "-";
	const url = new URL(req.url ?? "/", isAdmin ? "http://admin" : "http://device");
	const path = url.pathname;
	// The log line carries the matched route template, never the raw path (correction 01
	// item 2: path segments are caller-controlled).
	const routeTemplate = routeTemplateFor(req.method ?? "?", path);
	try {
		if (isAdmin) {
			await handleAdmin(route, req, res, url);
			return;
		}
		if (req.method === "POST" && path === "/v1/pair") {
			await handlePair(route, req, res);
			return;
		}
		if (req.method === "POST" && path === "/v1/crash") {
			await handleCrashPost(route, req, res);
			return;
		}
		// Everything below needs a valid bearer token — including the /v1/live/* 501.
		const token = bearerToken(req);
		if (token === undefined) throw unauthorized();
		const authState = await route.harness.commit(async (tx) => {
			const doc = await tx.doc(AUTH);
			return doc === undefined ? undefined : (JSON.parse(JSON.stringify({ ...doc })) as AuthState);
		}, route.context);
		if (authState === undefined) throw unauthorized();
		logDevice = authenticateTokenState(authState, token, Date.now());

		if (path.startsWith("/v1/live/")) {
			// (A10.3): the live session channel. A poll is cancelled when the phone hangs up.
			const hangUp = new AbortController();
			res.on("close", () => hangUp.abort());
			const live = await handleLiveRoute(
				{ harness: route.harness, root: route.root, context: route.context },
				{ method: req.method ?? "?", path, url, deviceId: logDevice, readBody: () => readJsonBody(req), signal: hangUp.signal },
			);
			if (live === undefined) throw notFound(`no route ${req.method} ${path}`);
			sendJson(res, live.status, live.body);
			return;
		}
		if (req.method === "GET" && path === "/v1/jobs") {
			await handleJobsGet(route, res, url, logDevice);
			return;
		}
		const resultMatch = /^\/v1\/jobs\/([^/]+)\/result$/.exec(path);
		if (req.method === "POST" && resultMatch !== null) {
			await handleJobResultById(route, req, res, resultMatch[1]!);
			return;
		}
		if (req.method === "GET" && path === "/v1/proposals") {
			await handleProposalsGet(route, res, url);
			return;
		}
		const decisionMatch = /^\/v1\/proposals\/([^/]+)\/decision$/.exec(path);
		if (req.method === "POST" && decisionMatch !== null) {
			await handleProposalDecisionById(route, req, res, decisionMatch[1]!);
			return;
		}
		const executionMatch = /^\/v1\/proposals\/([^/]+)\/execution$/.exec(path);
		if (req.method === "POST" && executionMatch !== null) {
			await handleProposalExecutionById(route, req, res, executionMatch[1]!);
			return;
		}
		const decisionGet = /^\/v1\/decisions\/([^/]+)$/.exec(path);
		if (req.method === "GET" && decisionGet !== null) {
			await handleDecisionGet(route, res, decisionGet[1]!);
			return;
		}
		if (req.method === "GET" && path === "/v1/rules") {
			await handleRulesGet(route, res);
			return;
		}
		if (req.method === "PUT" && path === "/v1/rules") {
			await handleRulesPut(route, req, res);
			return;
		}
		if (req.method === "POST" && path === "/v1/chat") {
			await handleChatPost(route, req, res);
			return;
		}
		if (req.method === "GET" && path === "/v1/chat/stream") {
			await handleChatStream(route, req, res, url);
			return;
		}
		if (req.method === "GET" && path === "/v1/chat/history") {
			await handleChatHistory(route, res, url);
			return;
		}
		if (req.method === "POST" && path === "/v1/device/status") {
			await handleDeviceStatus(route, req, res);
			return;
		}
		if (req.method === "POST" && path === "/v1/files/restored") {
			await handleFilesRestored(route, req, res);
			return;
		}
		if (req.method === "GET" && path === "/v1/status") {
			await handleStatusGet(route, res);
			return;
		}
		if (req.method === "GET" && path === "/v1/app/latest") {
			await handleAppLatest(route, res);
			return;
		}
		if (req.method === "GET" && path === "/v1/app/apk") {
			await handleAppApk(route, res, url);
			return;
		}
		if (req.method === "GET" && path === "/v1/agent/settings") {
			await handleAgentSettingsGet(route, res);
			return;
		}
		if (req.method === "GET" && path === "/v1/agent/models") {
			await handleAgentModelsGet(route, res, url);
			return;
		}
		if (req.method === "PUT" && path === "/v1/agent/settings") {
			await handleAgentSettingsPut(route, req, res);
			return;
		}
		if (req.method === "POST" && path === "/v1/chat/compact") {
			await handleChatCompact(route, req, res);
			return;
		}
		if (req.method === "POST" && path === "/v1/chat/fresh") {
			await handleChatFresh(route, req, res);
			return;
		}
		if (req.method === "POST" && path === "/v1/voice/transcribe") {
			await handleVoiceTranscribe(req, res, voiceDepsFromConfig(route.config, route.voiceFetch));
			return;
		}
		if (req.method === "POST" && path === "/v1/voice/speak") {
			await handleVoiceSpeak(req, res, voiceDepsFromConfig(route.config, route.voiceFetch));
			return;
		}
		throw notFound(`no route ${req.method} ${path}`);
	} catch (error) {
		sendError(res, error);
	} finally {
		httpRequestLogLine({
			method: req.method ?? "?",
			route: routeTemplate,
			status: res.statusCode,
			ms: Date.now() - startedAtMs,
			device: logDevice,
		});
	}
}

/** Start the device API (TCP) and the admin socket. */
export function startStewardServers(options: {
	config: StewardConfig;
	harness: Harness;
	root: Conversation;
	notifySender?: (title: string, body: string) => Promise<void>;
	/** A10.4: the model catalogue and registry. Without them the model routes answer 501. */
	catalogue?: ModelCatalogue;
	models?: Models;
	/** A10.9: compaction and fresh starts. */
	contextControl?: ContextControl;
	/** A15.5/A15.6: a provider `fetch` for the voice chains (tests inject a fake). */
	voiceFetch?: FetchLike;
}): StewardServers {
	const { config, harness, root } = options;
	const context: Context = BACKGROUND_CONTEXT;
	const agent: AgentSettingsDeps | undefined =
		options.catalogue !== undefined && options.contextControl !== undefined && options.models !== undefined
			? {
					harness,
					root,
					models: options.models,
					catalogue: options.catalogue,
					context: options.contextControl,
					conversationId: config.conversationId,
					defaultModel: config.model,
				}
			: undefined;
	const route: Route = {
		config,
		harness,
		root,
		context,
		release: new ReleaseStore(config.releaseDir),
		agent,
		contextControl: options.contextControl,
		streams: new Set(),
		voiceFetch: options.voiceFetch,
	};
	setNotifySender(options.notifySender ?? notifySenderFromConfig(config.stateDir, config.notify.command));

	const tcp: Server = createServer((req, res) => {
		void handleRoute(route, req, res, false);
	});
	let admin: Server | undefined;
	let adminSocketPath: string | undefined;
	if (config.stateDir !== ":memory:") {
		mkdirSync(config.stateDir, { recursive: true });
		adminSocketPath = join(config.stateDir, ADMIN_SOCKET_NAME);
		if (existsSync(adminSocketPath)) rmSync(adminSocketPath);
		admin = createServer((req, res) => {
			void handleRoute(route, req, res, true);
		});
		admin.listen(adminSocketPath);
		chmodSync(adminSocketPath, 0o600);
	}

	return {
		tcp,
		admin,
		adminSocketPath,
		close: async () => {
			// S10: stop accepting, end the open chat streams, give requests in flight a moment to
			// finish, then destroy whatever is left (idle keep-alive connections included).
			// Without this, `server.close()` waits for every connection and an open stream stalls it.
			const listeners = [tcp, ...(admin === undefined ? [] : [admin])];
			const closed = listeners.map((server) => new Promise<void>((resolve) => server.close(() => resolve())));
			for (const response of route.streams) response.end();
			for (const server of listeners) server.closeIdleConnections();
			const grace = setTimeout(() => {
				for (const server of listeners) server.closeAllConnections();
			}, SHUTDOWN_GRACE_MS);
			await Promise.all(closed);
			clearTimeout(grace);
			if (adminSocketPath !== undefined) rmSync(adminSocketPath, { force: true });
		},
	};
}

export type { TaskId };
