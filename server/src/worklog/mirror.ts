/**
 * The worklog mirror (`docs/protocol.md` §10).
 *
 * One mirror per conversation. It appends every committed message entry and every
 * steward event to `<worklogDir>/<sessionStart>_<sessionUuid>.jsonl`, file mode 0600,
 * one JSON object per line, with a linear `parentId` chain.
 *
 * Idempotence: a line's id is a pure function of the session uuid and the mirrored item,
 * so the file itself is the cursor. On start the mirror reads the file, collects the
 * written ids and repairs a truncated tail; on every sync it recomputes the desired line
 * sequence from the conversation's committed entries and the pending-events document and
 * appends only the lines whose ids are missing. A process that dies anywhere — before a
 * write, after it, mid-line — never writes a line twice and never skips one.
 */
import { chmod, mkdir, open, readFile, stat, truncate } from "node:fs/promises";
import { type FileHandle } from "node:fs/promises";
import { join } from "node:path";
import type { Context } from "@earendil-works/chord";
import {
	type CommitChange,
	type Conversation,
	type ConversationId,
	type Cursor,
	type EntryRecord,
	Harness,
} from "@earendil-works/pi-durable";
import { crashPoint } from "../crash-gate.ts";
import {
	StewardPendingEvents,
	StewardSessionInfo,
	PENDING_EVENTS_DOC_KIND,
	type PendingStewardEvent,
} from "./docs.ts";
import {
	messageLineFromEntry,
	modelChangeLine,
	sessionHeaderLine,
	stewardEventLine,
	stewardEventLineId,
	type StewardLine,
} from "./lines.ts";

export type WorklogMirrorOptions = {
	/** Protocol §10 location; production `/var/lib/phone-steward/worklog/`. */
	worklogDir: string;
	/** Header cwd; tests pass a scratch one. */
	cwd?: string;
};

export type WorklogMirrorDeps = {
	/** Clock for model_change timestamps; tests inject a fixed one. */
	now?: () => number;
	/** Prepended to `worklogDir`; scratch roots for tests. */
	fsRoot?: string;
};

type ModelRef = { provider: string; modelId: string };

type FileState = {
	haveHeader: boolean;
	writtenIds: Set<string>;
	lastId: string | undefined;
	modelChangeCount: number;
	lastModel: ModelRef | undefined;
};

/** `<YYYY-MM-DDTHH-mm-ss-SSSZ>_<sessionUuid>.jsonl` — the event-mirror name shape. */
export function mirrorFileName(startedAtMs: number, sessionUuid: string): string {
	const iso = new Date(startedAtMs).toISOString();
	return `${iso.replace(/:/g, "-").replace(/\.(\d{3})Z$/, "-$1Z")}_${sessionUuid}.jsonl`;
}

function isMirrorableEntry(entry: EntryRecord): boolean {
	return entry.kind === "pi.user" || entry.kind === "pi.assistant" || entry.kind === "pi.tool-result";
}

export class WorklogMirror {
	private readonly harness: Harness;
	private readonly conversation: Conversation;
	private readonly conversationId: ConversationId;
	private readonly context: Context;
	private readonly options: WorklogMirrorOptions;
	private readonly now: () => number;
	private readonly dir: string;
	/** The mirror file's absolute path; tests and validation read it. */
	readonly filePath: string;
	private handle: FileHandle | undefined;
	/** The commit observation, detached on close (Pi Durable 1.5.0's `observeCommits()`). */
	private observation: { detach(): void } | undefined;
	private state: FileState;
	private sessionUuid = "";
	private syncChain: Promise<void> = Promise.resolve();
	private closed = false;

	private constructor(init: {
		harness: Harness;
		conversation: Conversation;
		conversationId: ConversationId;
		context: Context;
		options: WorklogMirrorOptions;
		now: () => number;
		dir: string;
		filePath: string;
		state: FileState;
	}) {
		this.harness = init.harness;
		this.conversation = init.conversation;
		this.conversationId = init.conversationId;
		this.context = init.context;
		this.options = init.options;
		this.now = init.now;
		this.dir = init.dir;
		this.filePath = init.filePath;
		this.state = init.state;
	}

	/**
	 * Seed (or reuse) the conversation's session document, prepare the mirror file and
	 * run the first reconcile. The session uuid is minted once per conversation and kept
	 * in the conversation's own document, so a restart reuses the same file.
	 */
	static async start(
		harness: Harness,
		conversationId: ConversationId,
		context: Context,
		options: WorklogMirrorOptions,
		deps: WorklogMirrorDeps = {},
	): Promise<WorklogMirror> {
		const now = deps.now ?? Date.now;
		await harness.commit(async (tx) => {
			// First access mints the uuid + start time; a restart reads what is stored.
			await tx.doc(StewardSessionInfo, conversationId);
		}, context);
		const session = await harness.snapshot(StewardSessionInfo, conversationId, context);
		if (session === undefined) throw new Error("worklog mirror: session document missing after seeding");
		const dir = deps.fsRoot === undefined ? options.worklogDir : join(deps.fsRoot, options.worklogDir);
		await mkdir(dir, { recursive: true, mode: 0o700 });
		const filePath = join(dir, mirrorFileName(session.startedAtMs, session.sessionUuid));
		const conversation = await harness.conversation(conversationId, context);
		if (conversation === undefined) throw new Error(`worklog mirror: no conversation ${conversationId}`);
		const state = await loadFileState(filePath);
		const mirror = new WorklogMirror({
			harness,
			conversation,
			conversationId,
			context,
			options,
			now,
			dir,
			filePath,
			state,
		});
		mirror.sessionUuid = session.sessionUuid;
		mirror.handle = await open(filePath, "a", 0o600);
		// Self-heal the mode in case an older writer or a copy left it wider.
		const stats = await stat(filePath);
		if ((stats.mode & 0o777) !== 0o600) await chmod(filePath, 0o600);
		mirror.observation = harness.observeCommits({
			advance: (publication) => {
				// The mirror reconciles from durable state, so it wants stored commits only (not every
				// streamed partial). `advance` must not throw: Pi Durable 1.5.0 reports a throw, detaches
				// the observer and ends it `listener_error`, which would stop the worklog silently.
				try {
					if (publication.changes.some((change) => mirror.isRelevant(change))) mirror.scheduleSync();
				} catch (error) {
					console.error(`[steward] worklog mirror: commit observation failed: ${error instanceof Error ? error.message : String(error)}`);
				}
			},
			end: (end) => {
				// A close ends the observation `failed` with "session closed" (or the storage error);
				// only a failure while the mirror is still wanted is worth a line in the log.
				if (mirror.closed || end.kind !== "failed") return;
				console.error(`[steward] worklog mirror: commit observation ended: ${end.error instanceof Error ? end.error.message : String(end.error)}`);
			},
		});
		await mirror.sync();
		return mirror;
	}

	/** Commits that can change the desired line sequence for this conversation. */
	private isRelevant(change: CommitChange): boolean {
		if (change.type === "entry") return change.value.conversationId === this.conversationId;
		if (change.type === "document") {
			return (
				change.conversationId === this.conversationId &&
				(change.record.kind === PENDING_EVENTS_DOC_KIND || change.record.kind === "pi.agent")
			);
		}
		return false;
	}

	/** Serialized reconcile: enqueues behind the running one; callers await their turn. */
	sync(): Promise<void> {
		const run = this.syncChain.catch(() => {}).then(() => this.reconcileOnce());
		this.syncChain = run;
		return run;
	}

	private scheduleSync(): void {
		void this.sync().catch(() => {});
	}

	/**
	 * Recompute the desired line sequence from durable state and append the missing
	 * lines. Failures leave the file and the state consistent for the next sync: the
	 * file's line ids are the cursor, so a retry never duplicates and never skips.
	 */
	private async reconcileOnce(): Promise<void> {
		if (this.closed) return;
		try {
			const entries = await this.fetchEntriesAscending();
			const pending = await this.harness.snapshot(StewardPendingEvents, this.conversationId, this.context);
			const events = [...(pending?.events ?? [])].sort(
				(a, b) => a.anchorEntryId - b.anchorEntryId || a.seq - b.seq,
			);
			const model = await this.currentModel();

			type Wanted = { line: StewardLine };
			const wanted: Wanted[] = [];
			if (!this.state.haveHeader) {
				const session = await this.harness.snapshot(StewardSessionInfo, this.conversationId, this.context);
				if (session !== undefined) {
					wanted.push({
						line: sessionHeaderLine({
							sessionUuid: session.sessionUuid,
							startedAtMs: session.startedAtMs,
							cwd: this.options.cwd,
						}),
					});
				}
			}
			if (model !== undefined && !sameModel(this.state.lastModel, model)) {
				wanted.push({
					line: modelChangeLine({
						sessionUuid: this.sessionUuid,
						provider: model.provider,
						modelId: model.modelId,
						atMs: this.now(),
						parentId: null, // replaced at write time with the real chain position
						ordinal: this.state.modelChangeCount,
					}),
				});
			}
			let eventIndex = 0;
			const pushEvent = (event: PendingStewardEvent) => {
				wanted.push({
					line: stewardEventLine({
						sessionUuid: this.sessionUuid,
						seq: event.seq,
						kind: event.kind,
						atMs: event.atMs,
						data: event.data,
						parentId: null,
					}),
				});
			};
			for (const entry of entries) {
				// An event lands after every entry up to its anchor and before later ones;
				// equal anchors keep commit (seq) order. This reproduces the fixture's
				// interleaving and survives a crash between commit and write.
				while (eventIndex < events.length && events[eventIndex]!.anchorEntryId < Number(entry.id)) {
					pushEvent(events[eventIndex]!);
					eventIndex += 1;
				}
				if (!isMirrorableEntry(entry)) continue;
				const line = messageLineFromEntry({
					sessionUuid: this.sessionUuid,
					entry,
					parentId: null,
				});
				if (line !== undefined) wanted.push({ line });
			}
			while (eventIndex < events.length) {
				pushEvent(events[eventIndex]!);
				eventIndex += 1;
			}

			const missing = wanted.filter((item) => !this.state.writtenIds.has(String(item.line.id)));
			if (missing.length > 0) await this.appendLines(missing.map((item) => item.line));

			// Trim drained events: safe to repeat, safe to skip (ids filter re-drains).
			let drainedThrough = 0;
			for (const event of events) {
				if (this.state.writtenIds.has(stewardEventLineId(this.sessionUuid, event.seq))) {
					drainedThrough = Math.max(drainedThrough, event.seq);
				}
			}
			if (drainedThrough > 0) await this.trimDrained(drainedThrough);
		} catch {
			// Never fail a run for mirror reasons: the next sync retries from durable state.
		}
	}

	private async currentModel(): Promise<ModelRef | undefined> {
		const agent = await this.conversation.agent(this.context);
		const model = agent.model;
		if (model === undefined) return undefined;
		return { provider: model.provider, modelId: model.modelId };
	}

	/** Full conversation history, ascending. Newest-first pages, reversed. */
	private async fetchEntriesAscending(): Promise<EntryRecord[]> {
		const out: EntryRecord[] = [];
		let cursor: Cursor | undefined;
		for (;;) {
			const page = await this.conversation.entries({}, 200, cursor, this.context);
			out.push(...page.items);
			if (page.next === undefined) break;
			cursor = page.next;
		}
		out.reverse();
		return out;
	}

	/** Append lines with crash gates on both sides of every write. */
	private async appendLines(lines: StewardLine[]): Promise<void> {
		if (this.handle === undefined) throw new Error("worklog mirror: file not open");
		for (const line of lines) {
			await crashPoint("orchestration-mirror:before-write");
			// The chain position is only known now: after every earlier line of this batch.
			line.parentId = this.state.lastId ?? null;
			const bytes = Buffer.from(`${JSON.stringify(line)}\n`, "utf8");
			// Two writes with the gate between them: armed, the process dies holding half a
			// line (the truncated-tail repair case); unarmed, this is a plain write.
			const split = Math.floor(bytes.length / 2);
			await this.handle.write(bytes.subarray(0, split));
			await crashPoint("orchestration-mirror:truncated-write");
			if (split < bytes.length) await this.handle.write(bytes.subarray(split));
			this.rememberAppended(line);
		}
		await crashPoint("orchestration-mirror:after-write");
	}

	private rememberAppended(line: StewardLine): void {
		this.state.haveHeader = this.state.haveHeader || line.type === "session";
		if (line.type === "session") {
			// The header itself is not in the id chain; its `id` is the session uuid.
			this.state.writtenIds.add(`header:${String(line.id)}`);
		} else {
			const id = String(line.id);
			this.state.writtenIds.add(id);
			this.state.lastId = id;
			if (line.type === "model_change") {
				this.state.modelChangeCount += 1;
				this.state.lastModel = { provider: String(line.provider), modelId: String(line.modelId) };
			}
		}
	}

	private async trimDrained(throughSeq: number): Promise<void> {
		try {
			await this.harness.commit(async (tx) => {
				const doc = await tx.doc(StewardPendingEvents, this.conversationId);
				doc.events = doc.events.filter((event) => event.seq > throughSeq);
			}, this.context);
		} catch {
			// Trim is an optimisation; the written-ids filter makes re-draining harmless.
		}
	}

	async close(): Promise<void> {
		this.closed = true;
		this.observation?.detach();
		this.observation = undefined;
		await this.syncChain.catch(() => {});
		await this.handle?.close();
		this.handle = undefined;
	}
}

function sameModel(a: ModelRef | undefined, b: ModelRef | undefined): boolean {
	if (a === undefined || b === undefined) return false;
	return a.provider === b.provider && a.modelId === b.modelId;
}

/**
 * Read the mirror file, collect the written ids and repair a truncated tail: everything
 * from the first incomplete or unparseable line onwards is cut, and the next sync
 * rewrites those lines with their original ids.
 */
async function loadFileState(filePath: string): Promise<FileState> {
	const state: FileState = {
		haveHeader: false,
		writtenIds: new Set(),
		lastId: undefined,
		modelChangeCount: 0,
		lastModel: undefined,
	};
	let content: string;
	try {
		content = await readFile(filePath, "utf8");
	} catch {
		return state;
	}
	let goodEnd = 0;
	let position = 0;
	while (position < content.length) {
		const lineEnd = content.indexOf("\n", position);
		if (lineEnd === -1) break; // partial tail line
		const segment = content.slice(position, lineEnd);
		let parsed: unknown;
		try {
			parsed = JSON.parse(segment);
		} catch {
			break; // garbage tail (a crash mid-write that still flushed a newline)
		}
		if (parsed === null || typeof parsed !== "object") break;
		const line = parsed as StewardLine;
		if (line.type === "session") {
			state.haveHeader = true;
			state.writtenIds.add(`header:${String(line.id)}`);
		} else {
			const id = String(line.id);
			state.writtenIds.add(id);
			state.lastId = id;
			if (line.type === "model_change") {
				state.modelChangeCount += 1;
				state.lastModel = { provider: String(line.provider), modelId: String(line.modelId) };
			}
		}
		position = lineEnd + 1;
		goodEnd = lineEnd + 1;
	}
	if (goodEnd < content.length) await truncate(filePath, goodEnd);
	return state;
}
