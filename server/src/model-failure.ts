/**
 * A15.3: model failures are said plainly. When a steward turn (an owner chat turn, a
 * phone session's turn, or a phone-use hand-back) fails before any output with a provider
 * auth or plan/quota error, the owner gets one plain assistant line in Chat naming the
 * model and the reason in words (never the provider's text, a status code, a URL or a
 * stack), and the model catalogue marks that model with an additive `problem` field for
 * one hour or until `auth.json` changes — whichever comes first.
 *
 * Ordinary transient failures (a network blip, a rate limit) keep today's behaviour:
 * nothing extra is said and nothing is marked.
 *
 * The problem markers are a session-scoped document, so they survive a restart within
 * their hour. Credential values never enter this module: only the file's modification
 * time is read.
 */
import { statSync } from "node:fs";
import { join } from "node:path";
import { defineDoc, type ConversationId, type EntryId, type Tx } from "@earendil-works/pi-durable";
import type { Context } from "@earendil-works/chord";
import { appendChatEvent } from "./chat-events.ts";
import { catalogueLabelOf } from "./model-catalogue.ts";
import { periodicCheckpoint } from "./checkpoint.ts";

export type ModelFailureKind = "auth" | "plan";

/**
 * Classify a failed turn's error text (pi-ai's composed provider error: status and body).
 * Returns the failure kind, or undefined for a transient or ordinary failure, which keeps
 * today's behaviour. The plan patterns are pi-ai's own non-retryable limit errors
 * (`pi-ai dist/utils/retry.js` NON_RETRYABLE_PROVIDER_LIMIT_ERROR_PATTERN) plus a few
 * plain-language extras; an explicit rate-limit/429 text reads as transient, matching
 * pi-ai's retryable classification.
 */
export function classifyModelFailure(text: string): ModelFailureKind | undefined {
	if (text === "") return undefined;
	if (PLAN_PATTERN.test(text)) return "plan";
	if (AUTH_PATTERN.test(text)) return "auth";
	return undefined;
}

const PLAN_PATTERN =
	/(GoUsageLimitError|FreeUsageLimitError|usage limit|available balance|insufficient_quota|out of budget|quota exceeded|quota for this project|billing|subscription_sharing_usage_limit_exceeded|out of credits|no credits left)/i;

const AUTH_PATTERN =
	/(authentication token has expired|token has expired|token expired|invalid[_ ]api[_ ]key|incorrect api key|invalid api key|unauthorized|authentication|not authenticated|sign[ -]?in (required|again|expired)|permission denied|api key not valid|api key (has )?expired)/i;

/** The plain words for a failure kind (British English; no provider text). */
export function plainModelFailure(kind: ModelFailureKind): string {
	return kind === "auth" ? "its sign-in on the server has expired" : "its plan on the server has no use left for it right now";
}

/** The one assistant line Chat shows for a failed model (the A15.3 shape). */
export function failureLine(label: string, kind: ModelFailureKind): string {
	return `${label} could not run: ${plainModelFailure(kind)}. Pick another model in Settings.`;
}

/** How long a model's `problem` marker lasts (A15.3: one hour). */
const HOUR_MS = 60 * 60_000;

let ttlMs = HOUR_MS;

/** Tests only: shrink the marker's time-to-live. */
export function configureModelFailure(change: { ttlMs?: number }): { ttlMs: number } {
	if (change.ttlMs !== undefined) ttlMs = change.ttlMs;
	return { ttlMs };
}
export function resetModelFailureConfig(): void {
	ttlMs = HOUR_MS;
}

// ————— the problem markers (the catalogue's `problem` field) —————

export type ModelProblem = {
	/** The plain words shown under the model in the app's picker. */
	problem: string;
	markedAtMs: number;
	untilMs: number;
	/** auth.json's mtime when the failure was seen; a different mtime clears the marker. */
	authMtimeMs?: number;
};

export type ModelProblemsState = { problems: Record<string, ModelProblem> };

export const ModelProblems = defineDoc<ModelProblemsState>({
	kind: "steward.model-problems",
	version: 1,
	// Pi Durable 1.4.0 requires an explicit checkpoint policy (no default).
	checkpointWhen: periodicCheckpoint(),
	scope: "session",
	initial: (): ModelProblemsState => ({ problems: {} }),
});

const KEEP_PROBLEMS = 50;

function pruneProblems(doc: ModelProblemsState, nowMs: number, authMtimeMs: number | undefined): void {
	for (const [key, marker] of Object.entries(doc.problems)) {
		if (marker.untilMs <= nowMs || marker.authMtimeMs !== authMtimeMs) delete doc.problems[key];
	}
	const all = Object.entries(doc.problems).sort((a, b) => b[1].markedAtMs - a[1].markedAtMs);
	for (const [key] of all.slice(KEEP_PROBLEMS)) delete doc.problems[key];
}

/** Record one failed model inside the caller's commit. Returns the plain words stored. */
export async function noteModelFailureTx(
	tx: Tx,
	model: { provider: string; modelId: string },
	kind: ModelFailureKind,
	nowMs: number,
	authMtimeMs: number | undefined,
): Promise<string> {
	const doc = await tx.doc(ModelProblems);
	pruneProblems(doc, nowMs, authMtimeMs);
	const problem = plainModelFailure(kind);
	doc.problems[`${model.provider}/${model.modelId}`] = {
		problem,
		markedAtMs: nowMs,
		untilMs: nowMs + ttlMs,
		...(authMtimeMs === undefined ? {} : { authMtimeMs }),
	};
	return problem;
}

/** The live `problem` markers for the catalogue: within their hour and auth.json unchanged. */
export async function liveModelProblemsTx(tx: Tx, nowMs: number, authMtimeMs: number | undefined): Promise<Map<string, string>> {
	const doc = await tx.doc(ModelProblems);
	const live = new Map<string, string>();
	for (const [key, marker] of Object.entries(doc.problems)) {
		if (marker.untilMs > nowMs && marker.authMtimeMs === authMtimeMs) live.set(key, marker.problem);
	}
	return live;
}

/**
 * auth.json's modification time, read the way the registry reads the file (mtime only; the
 * file itself is never opened here). Undefined when there is no file.
 */
export function authMtimeMsOf(agentDir: string): number | undefined {
	try {
		return statSync(join(agentDir, "auth.json")).mtimeMs;
	} catch {
		return undefined;
	}
}

// ————— the one line per failed turn —————

/**
 * The model registry the label lookup reads, set once by `openStewardHarness` (the same
 * set-active pattern as the context control). Only `getModel` is needed; a missing entry
 * or no registry at all degrades the label to `provider/id`.
 */
type LabelModels = { getModel(provider: string, id: string): { name?: string } | undefined };
let activeModels: LabelModels | undefined;

/** Tests and the harness set the registry the labels come from. */
export function setActiveModels(models: LabelModels | undefined): void {
	activeModels = models;
}

/** The model's display name, or `provider/id` when unknown. */
export function modelLabelOf(ref: { provider: string; modelId: string }): string {
	return activeModels?.getModel(ref.provider, ref.modelId)?.name ?? `${ref.provider}/${ref.modelId}`;
}

/**
 * A15.10: the additive `model` field of a steward message event, from the assistant
 * message's own provider/model fields — the model that actually produced it.
 */
export function modelFieldOf(message: { provider?: unknown; model?: unknown }): { provider: string; id: string; label: string } | undefined {
	const provider = typeof message.provider === "string" && message.provider !== "" ? message.provider : undefined;
	const id = typeof message.model === "string" && message.model !== "" ? message.model : undefined;
	if (provider === undefined || id === undefined) return undefined;
	return { provider, id, label: modelLabelOf({ provider, modelId: id }) };
}

/**
 * Correction 1 (A15.10/A13.3): the additive `model` field a phone session's `phone_use`
 * `started` event carries — the steward's current model setting, which is the model the
 * session's turn will run on, with the catalogue's own label (an alias id says what it
 * follows). The same three keys a steward message carries, and no more.
 */
export function modelFieldFor(ref: { provider: string; modelId: string }): { provider: string; id: string; label: string } {
	return { provider: ref.provider, id: ref.modelId, label: catalogueLabelOf(modelLabelOf(ref), ref.modelId) };
}

/** The shape of a settled submission this module needs (pi Durable's `SubmissionRecord`). */
export type SettledLike = { status: string; reason?: string; detail?: unknown; entry?: unknown };

/** The chat-event kinds a failed turn's line is appended as. */
export type FailureChannel = { kind: "message" } | { kind: "say"; sessionId: string };

export type TurnFailureDeps = {
	harness: { commit<T>(change: (tx: Tx) => T | Promise<T>, context: Context): Promise<T> };
	conversationId: ConversationId;
	/** The conversation's model at failure time (provider + modelId). */
	model: { provider: string; modelId: string };
	/** The model's display name from the registry; the id is used when unknown. */
	label: string;
	/** `message` for a main-conversation turn, `say` for a phone session's turn. */
	channel: FailureChannel;
	authMtimeMs?: number;
	context: Context;
};

function detailText(detail: unknown): string {
	if (typeof detail === "string") return detail;
	if (detail === undefined || detail === null) return "";
	try {
		return JSON.stringify(detail) ?? "";
	} catch {
		return "";
	}
}

/**
 * Whether the failed turn already showed the model any text (A15.3: only a failure BEFORE
 * any output speaks). Scoped to the turn: entries from the turn's own input entry onwards,
 * newest first; a turn whose entries we cannot scope reads from the newest page only.
 */
async function turnHasAssistantText(tx: Tx, conversationId: ConversationId, afterEntry: unknown): Promise<boolean> {
	const query: { conversationId: ConversationId; minEntryId?: EntryId } = { conversationId };
	if (typeof afterEntry === "number" && Number.isFinite(afterEntry)) query.minEntryId = afterEntry as EntryId;
	else if (typeof afterEntry === "string" && afterEntry !== "") query.minEntryId = afterEntry as unknown as EntryId;
	const page = await tx.scanEntries(query, 30, undefined);
	for (const entry of page.items) {
		for (const message of entry.model ?? []) {
			if (message.role !== "assistant") continue;
			const text = (typeof message.content === "string" ? message.content : message.content.map((part) => (part.type === "text" && typeof part.text === "string" ? part.text : "")).join("")).trim();
			if (text !== "") return true;
		}
	}
	return false;
}

/**
 * One failed steward turn: when it settled `unanswered` with `model_error` and the error
 * classifies as an auth or plan failure, append the one plain line to Chat and mark the
 * model. Everything else is a no-op (transient failures keep today's behaviour). Returns
 * the line appended, if any.
 */
export async function noteTurnModelFailure(deps: TurnFailureDeps, settled: SettledLike): Promise<string | undefined> {
	if (settled.status !== "unanswered" || settled.reason !== "model_error") return undefined;
	const kind = classifyModelFailure(detailText(settled.detail));
	if (kind === undefined) return undefined;
	const line = failureLine(deps.label, kind);
	const nowMs = Date.now();
	let said = false;
	await deps.harness.commit(async (tx) => {
		// A15.3 scopes the line (and the mark) to a failure before any output: a turn that
		// already showed the model's words keeps today's silence.
		if (await turnHasAssistantText(tx, deps.conversationId, settled.entry)) return undefined;
		const modelField = { provider: deps.model.provider, id: deps.model.modelId, label: deps.label };
		if (deps.channel.kind === "message") {
			await appendChatEvent(tx, "message", { role: "assistant", text: line, model: modelField, atMs: nowMs }, nowMs);
		} else {
			await appendChatEvent(tx, "phone_use", { kind: "say", sessionId: deps.channel.sessionId, text: line, model: modelField, atMs: nowMs }, nowMs);
		}
		await noteModelFailureTx(tx, deps.model, kind, nowMs, deps.authMtimeMs);
		said = true;
		return undefined;
	}, deps.context);
	return said ? line : undefined;
}
