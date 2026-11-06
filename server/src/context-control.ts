/**
 * A10.9 server half: the steward's context control. One object per process owns
 * - the compaction policy Pi Durable reads at every resolution (working budget mapped
 *   against the current model's window, recomputed when the model changes),
 * - manual compaction and fresh starts (`POST /v1/chat/compact|fresh`, `bin/steward
 *   compact|fresh`), and the automatic fresh start before a weekly check,
 * - the `context` block of `GET /v1/agent/settings`,
 * - the threshold-compaction summary, written with the steward's own instructions.
 *
 * The chat log is never touched by compaction or a fresh start: these change the MODEL's
 * context only. A fresh start adds one `context` chat event (the app's divider).
 */
import type { Context } from "@earendil-works/chord";
import { BACKGROUND_CONTEXT } from "@earendil-works/chord/context";
import type { AssistantMessage, Message, Models } from "@earendil-works/pi-ai";
import {
	CompactionEntry,
	ResetEntry,
	defineDoc,
	type CompactionPolicy,
	type Conversation,
	type ContextView,
	type Cursor,
	type EntryRecord,
	type Harness,
} from "@earendil-works/pi-durable";
import { appendChatEvent } from "./chat-events.ts";
import {
	STEWARD_SUMMARY_INSTRUCTIONS,
	COMPACT_AT_DEFAULT,
	PREFERENCES_HEADING,
	THREADS_HEADING,
	clampCompactAt,
	compactAtTokensFor,
	compactionPolicyFor,
	openThreadsFrom,
} from "./context-policy.ts";
import { Decisions, RulesDoc, Snapshots, type Rules } from "./documents.ts";
import { summariseSnapshot } from "./observability.ts";
import { ownerChatNote } from "./owner-note.ts";
import { Proposals } from "./proposals.ts";
import { Runs } from "./run-tracking.ts";
import { SERVER_NOTE_MARKER } from "./server-note.ts";
import { buildStateBrief } from "./state-brief.ts";
import { formatBytes } from "./weekly-cache.ts";
import { periodicCheckpoint } from "./checkpoint.ts";

const SCAN_PAGE = 200;
const FIRST_SCAN_PAGES = 8;

/** The divider text the app shows (A10.9). */
export const FRESH_START_DIVIDER = "Fresh start: the steward now works from its records, not this conversation.";

export type ContextStatus = {
	tokens: number;
	/** A11.1: the model's window, so "Context NN %" is the share of the window. */
	budget: number;
	/** A11.1: the token count at which background compaction starts. */
	compactAtTokens: number;
	lastCompactionMs: number | null;
	lastFreshStartMs: number | null;
};

export type ContextControlOptions = {
	/**
	 * A11.1: the fraction of the model's window at which background compaction starts
	 * (`PHONE_STEWARD_COMPACT_AT`, 0.5–0.9, default 0.75).
	 */
	compactAt?: number;
	/** Reads the project memory file ("" when the file is absent). */
	projectMemory?: () => Promise<string>;
	/** How long a fresh start waits for its pre-compaction before going ahead without it. */
	freshCompactionTimeoutMs?: number;
	now?: () => number;
};

/** Per-run record that the automatic fresh start already happened (replay-safe across restarts). */
export const FreshStartsDoc = defineDoc<{ forRun: Record<string, number> }>({
	kind: "steward.fresh-starts",
	version: 1,
	// Pi Durable 1.4.0 requires an explicit checkpoint policy (no default).
	checkpointWhen: periodicCheckpoint(),
	scope: "conversation",
	history: "latest",
	fork: "initial",
	initial: () => ({ forRun: {} }),
});

export class AgentBusyError extends Error {
	constructor() {
		super("the steward is working; try again when it is idle");
		this.name = "AgentBusyError";
	}
}

type Attached = { harness: Harness; root: Conversation; models: Models };

export class ContextControl {
	readonly #compactAt: number;
	readonly #projectMemory: () => Promise<string>;
	readonly #freshTimeoutMs: number;
	readonly #now: () => number;
	#contextWindow = 0;
	#model: { provider: string; modelId: string } | undefined;
	#attached: Attached | undefined;
	#inFlight = false;
	#scan: { newestId: number; lastCompactionMs: number | null; lastFreshStartMs: number | null } | undefined;

	constructor(options: ContextControlOptions = {}) {
		this.#compactAt = clampCompactAt(options.compactAt ?? COMPACT_AT_DEFAULT);
		this.#projectMemory = options.projectMemory ?? (async () => "");
		this.#freshTimeoutMs = options.freshCompactionTimeoutMs ?? 120_000;
		this.#now = options.now ?? Date.now;
	}

	/** Called once the harness exists. */
	attach(attached: Attached): void {
		this.#attached = attached;
	}

	get compactAt(): number {
		return this.#compactAt;
	}

	/** The policy Pi Durable reads (a getter on the harness settings). */
	policy(): CompactionPolicy {
		return compactionPolicyFor(this.#contextWindow, this.#compactAt);
	}

	/** The model the conversation now uses: recomputes the window the policy is measured against. */
	setModel(model: { provider: string; modelId: string }, contextWindow: number): void {
		this.#model = model;
		this.#contextWindow = contextWindow;
	}

	/** A11.1: the model's window (the denominator the app's "Context NN %" uses). */
	windowTokens(): number {
		return this.#contextWindow;
	}

	/** A11.1: the token count at which background compaction starts. */
	compactAtTokens(): number {
		return compactAtTokensFor(this.#contextWindow, this.policy());
	}

	get operationInFlight(): boolean {
		return this.#inFlight;
	}

	/** True while a run is open or a compaction/fresh start is under way. */
	async isBusy(): Promise<boolean> {
		return this.#inFlight || (await this.#runsOpen());
	}

	async #runsOpen(): Promise<boolean> {
		const { harness, root } = this.#must();
		const runs = await harness.snapshot(Runs, root.id, BACKGROUND_CONTEXT);
		return Object.keys(runs?.open ?? {}).length > 0;
	}

	#must(): Attached {
		if (this.#attached === undefined) throw new Error("context control is not attached to a harness");
		return this.#attached;
	}

	// ————— status —————

	async status(): Promise<ContextStatus> {
		const { root } = this.#must();
		const view = await root.context(BACKGROUND_CONTEXT);
		const times = await this.#timestamps();
		return {
			tokens: estimateContextTokens(view),
			budget: this.#contextWindow,
			compactAtTokens: this.compactAtTokens(),
			lastCompactionMs: times.lastCompactionMs,
			lastFreshStartMs: times.lastFreshStartMs,
		};
	}

	/**
	 * Newest compaction and reset times, from the transcript. Incremental: only the entries
	 * newer than the last look are read (the transcript is never rescanned whole), and a first
	 * look reads at most `FIRST_SCAN_PAGES` pages, newest first.
	 */
	async #timestamps(): Promise<{ lastCompactionMs: number | null; lastFreshStartMs: number | null }> {
		const { root } = this.#must();
		const newest = (await root.entries({}, 1, undefined, BACKGROUND_CONTEXT)).items[0]?.id ?? 0;
		const known = this.#scan;
		if (known !== undefined && known.newestId === newest) return known;
		const floor = known?.newestId ?? 0;
		let lastCompactionMs: number | null = null;
		let lastFreshStartMs: number | null = null;
		let cursor: Cursor | undefined;
		scan: for (let pageNo = 0; pageNo < FIRST_SCAN_PAGES; pageNo++) {
			const page = await root.entries({}, SCAN_PAGE, cursor, BACKGROUND_CONTEXT);
			for (const entry of page.items) {
				if (entry.id <= floor) break scan;
				if (lastCompactionMs === null && CompactionEntry.is(entry)) lastCompactionMs = messageTime(entry);
				if (lastFreshStartMs === null && ResetEntry.is(entry)) lastFreshStartMs = messageTime(entry);
				if (lastCompactionMs !== null && lastFreshStartMs !== null) break scan;
			}
			if (page.next === undefined) break;
			cursor = page.next;
		}
		// What the incremental look did not find stays what an earlier look found.
		this.#scan = {
			newestId: newest,
			lastCompactionMs: lastCompactionMs ?? known?.lastCompactionMs ?? null,
			lastFreshStartMs: lastFreshStartMs ?? known?.lastFreshStartMs ?? null,
		};
		return this.#scan;
	}

	// ————— compaction —————

	/**
	 * Start a manual compaction or a fresh start (A10.9) if the steward is idle: refuses
	 * with `AgentBusyError` while a run is open or another operation is under way. Returns
	 * once the operation has begun; `done` settles when it has finished (the HTTP routes
	 * answer 202 at once, the CLI and tests await `done`).
	 */
	async start(operation: "compact" | "fresh"): Promise<{ done: Promise<boolean> }> {
		if (this.#inFlight) throw new AgentBusyError();
		this.#inFlight = true;
		try {
			if (await this.#runsOpen()) throw new AgentBusyError();
		} catch (error) {
			this.#inFlight = false;
			throw error;
		}
		const done = (async () => {
			try {
				if (operation === "compact") return await this.#compactAndWait(this.#freshTimeoutMs);
				await this.#freshStart();
				return true;
			} finally {
				this.#inFlight = false;
			}
		})();
		return { done };
	}

	/** Manual compaction, awaited. */
	async compact(): Promise<{ compacted: boolean }> {
		const { done } = await this.start("compact");
		return { compacted: await done };
	}

	async #compactAndWait(timeoutMs: number): Promise<boolean> {
		const { harness, root } = this.#must();
		const before = (await root.context(BACKGROUND_CONTEXT)).head?.id;
		const taskId = await root.compact(STEWARD_SUMMARY_INSTRUCTIONS, BACKGROUND_CONTEXT);
		const deadline = this.#now() + timeoutMs;
		for (;;) {
			const record = await harness.getTask(taskId, BACKGROUND_CONTEXT);
			if (record === undefined || record.state.status === "terminal") break;
			if (this.#now() >= deadline) return false;
			await new Promise((resolve) => setTimeout(resolve, 25));
		}
		// The summary lands through a write submission: wait for the conversation to settle too.
		await root.waitForIdle(BACKGROUND_CONTEXT).catch(() => undefined);
		const after = (await root.context(BACKGROUND_CONTEXT)).head?.id;
		return after !== before;
	}

	/**
	 * The summary for a threshold compaction, written with the steward's instructions
	 * (Pi Durable's own threshold compaction passes none). `undefined` means "use Pi
	 * Durable's default summary": any failure falls back to it, never to a failed run.
	 */
	async thresholdSummary(messages: readonly Message[]): Promise<string | undefined> {
		const attached = this.#attached;
		const model = this.#model === undefined ? undefined : attached?.models.getModel(this.#model.provider, this.#model.modelId);
		if (attached === undefined || model === undefined) return undefined;
		try {
			const now = this.#now();
			const message: AssistantMessage = await attached.models.completeSimple(
				model,
				{
					messages: [
						{
							role: "system",
							content:
								"You are a context summarization assistant. Read the conversation and write a structured summary that another assistant will use to continue it. Do not answer or continue the conversation. If it starts with an earlier summary, keep its information and fold the newer messages into it.",
							timestamp: now,
						},
						{
							role: "user",
							content: [
								{
									type: "text",
									text: `<conversation>\n${serializeMessages(messages)}\n</conversation>\n\nWrite the summary as markdown with these headings: ## Goal, ${PREFERENCES_HEADING}, ${THREADS_HEADING}, ## Progress, ## Where to look.\n\n${STEWARD_SUMMARY_INSTRUCTIONS}`,
								},
							],
							timestamp: now,
						},
					],
				},
				{ maxTokens: Math.min(model.maxTokens > 0 ? model.maxTokens : 8_000, 8_000) },
			);
			if (message.stopReason !== "stop") return undefined;
			const text = message.content
				.flatMap((part) => (part.type === "text" ? [part.text] : []))
				.join("\n")
				.trim();
			return text.length === 0 ? undefined : text;
		} catch {
			return undefined;
		}
	}

	// ————— fresh start —————

	/** Fresh start on demand, awaited. */
	async fresh(): Promise<void> {
		const { done } = await this.start("fresh");
		await done;
	}

	/**
	 * The automatic fresh start before a weekly check: once per `runId`, whatever restarts.
	 * Runs inside the weekly task, so it must not throw and does not require an idle run
	 * list (the weekly run is not open yet).
	 */
	async freshBeforeWeeklyCheck(runId: string): Promise<boolean> {
		const { harness, root } = this.#must();
		const done = await harness.snapshot(FreshStartsDoc, root.id, BACKGROUND_CONTEXT);
		if (done?.forRun[runId] !== undefined) return false;
		if (this.#inFlight) return false;
		this.#inFlight = true;
		try {
			await this.#freshStart();
			await harness.commit(async (tx) => {
				const doc = await tx.doc(FreshStartsDoc, root.id);
				doc.forRun[runId] = this.#now();
				// Keep the record small.
				const keys = Object.keys(doc.forRun);
				for (const key of keys.slice(0, Math.max(0, keys.length - 20))) delete doc.forRun[key];
				return undefined;
			}, BACKGROUND_CONTEXT);
			return true;
		} catch (error) {
			console.error(`[steward] automatic fresh start skipped: ${error instanceof Error ? error.message : String(error)}`);
			return false;
		} finally {
			this.#inFlight = false;
		}
	}

	async #freshStart(): Promise<void> {
		const { harness, root } = this.#must();
		// The summary of the old conversation carries the owner's preferences and open threads
		// into the brief: make one now, if the transcript is long enough to have anything to cut.
		await this.#compactAndWait(this.#freshTimeoutMs).catch(() => false);
		const brief = await this.#buildBrief();
		await root.reset(`${SERVER_NOTE_MARKER}${brief}`, BACKGROUND_CONTEXT);
		const nowMs = this.#now();
		await harness.commit(
			(tx) => appendChatEvent(tx, "context", { kind: "fresh_start", text: FRESH_START_DIVIDER, atMs: nowMs }, nowMs),
			BACKGROUND_CONTEXT,
		);
	}

	async #buildBrief(): Promise<string> {
		const { harness, root } = this.#must();
		const nowMs = this.#now();
		const proposals = await harness.snapshot(Proposals, root.id, BACKGROUND_CONTEXT);
		const ledger = await harness.snapshot(Decisions, root.id, BACKGROUND_CONTEXT);
		const rules = await harness.snapshot(RulesDoc, root.id, BACKGROUND_CONTEXT);
		const snaps = await harness.snapshot(Snapshots, root.id, BACKGROUND_CONTEXT);
		const view = await root.context(BACKGROUND_CONTEXT);
		const projectMemory = await this.#projectMemory().catch(() => "");
		return buildStateBrief({
			nowMs,
			serverNote: ownerChatNote({
				proposals: Object.values(proposals?.proposals ?? {}),
				ledger: ledger?.entries ?? [],
				nowMs,
			}),
			rulesText: rules === undefined ? "" : rulesText(rules),
			snapshotText: snapshotText(snaps?.snapshots.at(-1)),
			summaryThreads: threadsFromView(view),
			projectMemory,
		});
	}
}

// ————— helpers —————

function messageTime(entry: EntryRecord): number | null {
	const stamp = (entry.model?.[0] as { timestamp?: unknown } | undefined)?.timestamp;
	return typeof stamp === "number" && Number.isFinite(stamp) ? stamp : null;
}

function textOf(message: Message | undefined): string {
	if (message === undefined) return "";
	const content = (message as { content?: unknown }).content;
	if (typeof content === "string") return content;
	if (!Array.isArray(content)) return "";
	return content.flatMap((part: { type?: string; text?: string }) => (part.type === "text" && typeof part.text === "string" ? [part.text] : [])).join("\n");
}

function approxTokens(message: Message): number {
	let chars = 0;
	const content = (message as { content?: unknown }).content;
	if (typeof content === "string") chars = content.length;
	else if (Array.isArray(content)) {
		for (const part of content as Array<{ type?: string; text?: string; arguments?: unknown; thinking?: string }>) {
			if (part.type === "text") chars += part.text?.length ?? 0;
			else if (part.type === "toolCall") chars += JSON.stringify(part.arguments ?? {}).length + 20;
			else if (part.type === "thinking") chars += part.thinking?.length ?? 0;
			else chars += 200;
		}
	}
	return Math.ceil(chars / 4);
}

/** Size of the model's next request: the newest assistant usage, plus an estimate of what follows it. */
export function estimateContextTokens(view: Pick<ContextView, "messages">): number {
	const messages = view.messages;
	let measuredAt = -1;
	let tokens = 0;
	for (let index = messages.length - 1; index >= 0; index--) {
		const message = messages[index] as { role?: string; usage?: { input?: number; output?: number; cacheRead?: number; cacheWrite?: number; totalTokens?: number } };
		if (message.role !== "assistant" || message.usage === undefined) continue;
		const usage = message.usage;
		const total = usage.totalTokens ?? (usage.input ?? 0) + (usage.output ?? 0) + (usage.cacheRead ?? 0) + (usage.cacheWrite ?? 0);
		if (total > 0) {
			measuredAt = index;
			tokens = total;
			break;
		}
	}
	for (const message of messages.slice(measuredAt + 1)) tokens += approxTokens(message);
	return tokens;
}

/** A transcript as plain text for the summariser (system messages omitted, long results clipped). */
export function serializeMessages(messages: readonly Message[]): string {
	const lines: string[] = [];
	for (const message of messages) {
		const role = (message as { role: string }).role;
		if (role === "system") continue;
		if (role === "user") lines.push(`[User]: ${textOf(message)}`);
		else if (role === "assistant") {
			const content = (message as { content?: Array<{ type?: string; text?: string; name?: string; arguments?: unknown }> }).content ?? [];
			const text = content.flatMap((part) => (part.type === "text" && part.text ? [part.text] : [])).join("\n");
			if (text.length > 0) lines.push(`[Assistant]: ${text}`);
			const calls = content.filter((part) => part.type === "toolCall");
			if (calls.length > 0) lines.push(`[Assistant tool calls]: ${calls.map((call) => `${call.name}(${JSON.stringify(call.arguments ?? {}).slice(0, 300)})`).join("; ")}`);
		} else if (role === "toolResult") {
			const text = textOf(message);
			lines.push(`[Tool result]: ${text.length > 1500 ? `${text.slice(0, 1500)}…` : text}`);
		}
	}
	return lines.join("\n\n");
}

const SUMMARY_OPEN = "<summary>\n";
const SUMMARY_CLOSE = "\n</summary>";

/** The summary text inside a compaction entry's wrapped user message. */
function summaryOf(entry: EntryRecord | undefined): string | undefined {
	if (entry === undefined || !CompactionEntry.is(entry)) return undefined;
	const text = textOf(entry.model?.[0]);
	const start = text.indexOf(SUMMARY_OPEN);
	if (start < 0) return text;
	const end = text.lastIndexOf(SUMMARY_CLOSE);
	return text.slice(start + SUMMARY_OPEN.length, end > start ? end : undefined);
}

const BRIEF_THREADS_LABEL = "OWNER PREFERENCES AND OPEN THREADS";

/** Preferences and open threads: from the active summary, else from the last brief (the chain across fresh starts). */
function threadsFromView(view: ContextView): string {
	const fromSummary = openThreadsFrom(summaryOf(view.head));
	if (fromSummary !== "") return fromSummary;
	if (view.head !== undefined && ResetEntry.is(view.head)) {
		const text = textOf(view.head.model?.[0]);
		const at = text.indexOf(BRIEF_THREADS_LABEL);
		if (at >= 0) {
			const afterLabel = text.slice(at);
			const body = afterLabel.slice(afterLabel.indexOf("\n") + 1);
			const next = body.search(/\n\n[A-Z][A-Z ]+:/);
			return (next >= 0 ? body.slice(0, next) : body).trim();
		}
	}
	return "";
}

function rulesText(rules: Rules): string {
	const w = rules.photoWindow;
	return [
		`Photo window: ${w.enabled ? "on" : "off"}; reminder after ${w.reminderDays} days, cut-off at ${w.cutoffDays} days; auto mode ${w.autoMode ? "on" : "off"}; Keep marker "${w.keepMarker}"; rule version ${rules.ruleVersion}.`,
		`Folders: ${w.folders.join(", ")}.`,
	].join("\n");
}

function snapshotText(snapshot: Parameters<typeof summariseSnapshot>[0]): string {
	const summary = summariseSnapshot(snapshot);
	if ("status" in summary) return "No snapshot has been stored yet.";
	const day = new Date(summary.capturedAtMs).toISOString().slice(0, 10);
	const apps = summary.topAppsBySize.slice(0, 5).map((app) => `${app.label} ${formatBytes(app.totalBytes)}`).join(", ");
	return `Captured ${day}: ${formatBytes(summary.freeBytes)} free of ${formatBytes(summary.totalBytes)}. Biggest apps: ${apps}.`;
}

// ————— the active control (tasks reach it without a handle) —————

let active: ContextControl | undefined;

export function setActiveContextControl(control: ContextControl | undefined): void {
	active = control;
}

export function activeContextControl(): ContextControl | undefined {
	return active;
}

export type { Context };
