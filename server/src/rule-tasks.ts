/**
 * The deterministic rule tasks : `WeeklyCheckTask` and
 * `PhotoWindowTask`. No model. Every step commits a checkpoint, so a restart resumes from
 * the last one. Each task's "open" phase queues its phone jobs and terminates; the job's
 * result (or expiry) creates the phase-2 instance (`start: "settle"` / `start: "remind"`),
 * keyed by `runId`, so a replayed reporter never creates two.
 *
 * Handler pattern: reads go through `runtime.snapshot` (committed state), writes go through
 * `runtime.commit`, and data a later phase needs rides in the checkpoint.
 */
import { defineDoc, defineTask, type ConversationId, type JsonObject, type Tx } from "@earendil-works/pi-durable";
import type { Context } from "@earendil-works/chord";
import type { MediaRef } from "./jobs.ts";
import { Jobs, queueJob } from "./jobs.ts";
import {
	Decisions,
	Device,
	RulesDoc,
	Snapshots,
	executedTrashMediaIds,
	recordDecision,
	restoredMediaIds,
	type Rules,
	type Snapshot,
	type SnapshotApp,
	type SnapshotCategories,
} from "./documents.ts";
import { queueNotify } from "./notify.ts";
import { AUTO_MODE_NOT_YET } from "./rule-guards.ts";
import { batchItemsForRun, reminderItemsForRun, restoredRefsInIndex } from "./photo-window-logic.ts";
import { inFolderScope } from "./folder-scope.ts";
import { ageDays, stewardDate } from "./time.ts";

/** A6: the candidate's folder is inside one of the stamped scopes (subtree match). */
function inStampedFolders(ref: MediaRef, stampedFolders: readonly string[]): boolean {
	return inFolderScope(ref.relativePath, stampedFolders);
}
import { Proposals, createProposal } from "./proposals.ts";
import { shizukuState } from "./shizuku.ts";
import { buildCacheBatch, openProposalLines, weeklyCheckMessage, weeklyResultLines } from "./weekly-cache.ts";
import { canonicalJson } from "./canonical.ts";
import { crashPoint } from "./crash-gate.ts";
import { activeContextControl } from "./context-control.ts";
import { emitStewardEvent } from "./steward-events.ts";
import { closeRun, openRun } from "./run-tracking.ts";
import { sha256Hex } from "./ids.ts";
import { periodicCheckpoint } from "./checkpoint.ts";

/** A full read of the rules document (detached from any draft). */
async function readRulesDoc(snapshot: Rules): Promise<Rules> {
	return JSON.parse(JSON.stringify(snapshot)) as Rules;
}

async function snapshotRules(tx: Tx, conversationId: ConversationId): Promise<Rules> {
	const doc = await tx.doc(RulesDoc, conversationId);
	return readRulesDoc({ ...doc });
}

// ————— photo-window reminder ledger —————

export const PhotoWindowDoc = defineDoc<{
	/** mediaId → the run that reminded about it (a reminder always precedes a cut-off). */
	reminded: Record<string, { runId: string; atMs: number }>;
}>({
	kind: "steward.photo-window",
	version: 1,
	// Pi Durable 1.4.0 requires an explicit checkpoint policy (no default).
	checkpointWhen: periodicCheckpoint(),
	scope: "conversation",
	history: "latest",
	fork: "initial",
	initial: () => ({ reminded: {} }),
});

/** Per-run markers for the weekly check, so parallel settle continuations do not double-work. */
export const WeeklyRunsDoc = defineDoc<{
	snapshotted: Record<string, true>;
	/** S7: runId → the cache-batch proposal the server built in that run's settle commit. */
	cacheBatch?: Record<string, string>;
}>({
	kind: "steward.weekly-runs",
	version: 1,
	// Pi Durable 1.4.0 requires an explicit checkpoint policy (no default).
	checkpointWhen: periodicCheckpoint(),
	scope: "conversation",
	history: "latest",
	fork: "initial",
	initial: () => ({ snapshotted: {} }),
});

// ————— WeeklyCheckTask —————

export type WeeklyCheckInput = { runId: string; start: "open" | "settle" };

type WeeklyState =
	| { phase: "open" }
	| { phase: "settle"; snapshotted?: boolean; submitted?: string };

/**
 * WeeklyCheckTask: queues `storage_overview`, `app_usage` and `media_index` (summary),
 * then — once all three are answered — stores the snapshot and submits
 * `[schedule weekly_check <date>]` to `main` with requestId `weekly-check:<date>`.
 * Every step is idempotent: the submit is deduped by requestId, the run events by the runs
 * document, and the snapshot by the `snapshotted` checkpoint flag.
 */
export const WeeklyCheckTask = defineTask<WeeklyCheckInput, WeeklyState, { submitted: boolean }>({
	name: "steward.weekly-check",
	version: 1,
	// A settle continuation instance starts in its phase, not at open.
	initial: (input) => (input.start === "settle" ? { phase: "settle" } : { phase: "open" }),
	phases: {
		open: async (task, runtime, context) => {
			// A10.9: a fresh start before each weekly check, once per run (never throws).
			await activeContextControl()?.freshBeforeWeeklyCheck(task.input.runId);
			const slots = ["storage_overview", "app_usage", "media_index"] as const;
			for (const slot of slots) {
				// One commit per slot with a crash point between them: a kill mid-open
				// replays into find-before-create, never a duplicated job.
				await crashPoint(`rule-task:queued-${slot}`);
				await runtime.commit(
					async (tx) => {
						const nowMs = Date.now();
						const params: JsonObject = slot === "media_index" ? { mode: "summary" } : {};
						await queueJob(tx, runtime.conversationId, {
							// The task id is durable, so a crashed-and-resumed run names the same
							// jobs; the slot suffix keeps the three of one run distinct.
							dedupeKey: `${task.id}:${slot}`,
							kind: slot,
							params,
							nowMs,
							waiter: { run: "weekly-check", runId: task.input.runId },
						});
						return undefined;
					},
					context,
				);
				// 04.6: an after-effect gate too — a kill here has the job persisted; the
				// reopen must show it exactly once.
				await crashPoint(`rule-task:after-${slot}`);
			}
			await runtime.commit(
				() => ({ status: "terminal" as const, outcome: { status: "completed" as const, result: { submitted: false } } }),
				context,
			);
		},
		settle: async (task, runtime, context) => {
			const checkpoint = task.state.checkpoint;
			const state = checkpoint.phase === "settle"
				? checkpoint
				: ({ phase: "settle" } as { phase: "settle"; snapshotted?: boolean; submitted?: string });
			if (state.snapshotted !== true) {
				// Reads come from committed state; only the last continuation to fire finds
				// every job answered and does the work.
				const stored = await runtime.snapshot(Jobs, runtime.conversationId, context);
				const runJobs = Object.values(stored?.jobs ?? {}).filter(
					(job) => job.waiter?.run === "weekly-check" && job.waiter.runId === task.input.runId,
				);
				const allAnswered =
					runJobs.length >= 3 && runJobs.every((job) => job.status === "reported" || job.status === "expired");
				if (!allAnswered) {
					await runtime.commit(
						() => ({ status: "terminal" as const, outcome: { status: "completed" as const, result: { submitted: false } } }),
						context,
					);
					return;
				}
				const overview = runJobs.find((job) => job.kind === "storage_overview");
				const data = overview?.result?.data as
					| { totalBytes?: number; freeBytes?: number; apps?: unknown; categories?: unknown }
					| undefined;
				// S7 crash gates on both sides of the one commit that stores the snapshot, the run
				// marker and the server-built cache batch (AR1).
				await crashPoint("weekly-check:before-settle-commit");
				await runtime.commit(
					async (tx) => {
						const nowMs = Date.now();
						// Several settle continuations can fire for one run; the run marker in the
						// same commit keeps the snapshot to exactly one.
						const weeklyRuns = await tx.doc(WeeklyRunsDoc, runtime.conversationId);
						if (weeklyRuns.snapshotted[task.input.runId] !== true) {
							weeklyRuns.snapshotted[task.input.runId] = true;
							if (overview?.status === "reported" && data !== undefined) {
								const snapshot: Snapshot = {
									capturedAtMs: nowMs,
									totalBytes: Number(data.totalBytes ?? 0),
									freeBytes: Number(data.freeBytes ?? 0),
									apps: (data.apps ?? []) as SnapshotApp[],
									categories: (data.categories ?? {}) as SnapshotCategories,
								};
								const snaps = await tx.doc(Snapshots, runtime.conversationId);
								snaps.snapshots.push(snapshot);
								// S7 (A6.3): the cache batch is built here, in the same commit as the
								// snapshot and the run marker, so a replay (marker already set) can never
								// create a second one. `source: "agent"` lets S6 supersede last week's.
								// A10.2: when the last device status says Shizuku is ready, the batch is a
								// `trim_caches` (one step, no Android dialog) instead of `clear_all_caches`.
								const viaShizuku = shizukuState(await tx.doc(Device, runtime.conversationId)) === "ready";
								const batch = buildCacheBatch(snapshot.apps, stewardDate(nowMs), { viaShizuku });
								if (batch !== undefined && weeklyRuns.cacheBatch?.[task.input.runId] === undefined) {
									const created = await createProposal(tx, runtime.conversationId, {
										source: "agent",
										builtBy: "weekly_check",
										title: batch.title,
										reason: batch.reason,
										items: [
											{
												kind: viaShizuku ? "trim_caches" : "clear_all_caches",
												target: { apps: batch.apps },
												bytesEstimate: batch.bytesEstimate,
												reason: viaShizuku
													? `Trim the caches of ${batch.apps.length} apps in one step, no dialog`
													: `Clear the caches of ${batch.apps.length} apps in one confirmation`,
											},
										],
									}, nowMs);
									weeklyRuns.cacheBatch = { ...(weeklyRuns.cacheBatch ?? {}), [task.input.runId]: created.proposalId };
								}
							}
						}
						return { status: "running" as const, checkpoint: { phase: "settle" as const, snapshotted: true } };
					},
					context,
				);
				await crashPoint("weekly-check:after-settle-commit");
			}
			const date = stewardDate(Date.now());
			// A replayed settle reaches the same submission through the requestId dedupe.
			const requestId = state.submitted ?? `weekly-check:${date}`;
			const conversation = await runtime.conversation(runtime.conversationId, context);
			if (conversation === undefined) throw new Error("weekly check lost its conversation");
			// run_start is emitted BEFORE the scheduled input lands (02-answer note 2).
			await runtime.commit(
				async (tx) => {
					await openRun(tx, runtime.conversationId, requestId, "weekly_check", Date.now());
					return {
						status: "running" as const,
						checkpoint: { phase: "settle" as const, snapshotted: true, submitted: requestId },
					};
				},
				context,
			);
			// S7: the steward is told the server's own open list, read from committed state.
			const proposalsStored = await runtime.snapshot(Proposals, runtime.conversationId, context);
			const runsStored = await runtime.snapshot(WeeklyRunsDoc, runtime.conversationId, context);
			const open = openProposalLines(Object.values(proposalsStored?.proposals ?? {}));
			// S8: the run's phone results settle silently; the one weekly message carries them.
			const jobsStored = await runtime.snapshot(Jobs, runtime.conversationId, context);
			const results = weeklyResultLines(
				Object.values(jobsStored?.jobs ?? {}).filter(
					(job) => job.waiter?.run === "weekly-check" && job.waiter.runId === task.input.runId,
				),
				canonicalJson,
			);
			const submission = await conversation.submit(
				{
					type: "input",
					content: weeklyCheckMessage({
						date,
						open,
						builtCacheProposalId: runsStored?.cacheBatch?.[task.input.runId],
						results,
					}),
					whenBusy: "followUp",
					requestId,
				},
				context,
			);
			await submission.wait(context);
			await runtime.commit(
				async (tx) => {
					await closeRun(tx, runtime.conversationId, requestId, Date.now());
					return { status: "terminal" as const, outcome: { status: "completed" as const, result: { submitted: true } } };
				},
				context,
			);
		},
	},
	abort: async (_task, runtime, context) => {
		await runtime.commit(() => ({ status: "terminal" as const, outcome: { status: "aborted" as const } }), context);
	},
});

// ————— PhotoWindowTask —————

export type PhotoWindowInput = {
	runId: string;
	start: "open" | "remind";
	/** Stamped at queue time (04.2): the run discards candidates gathered under an older rule. */
	ruleVersion?: number;
	/** Stamped at queue time: the folders the candidates were gathered for. */
	folders?: string[];
};

type PhotoWindowState =
	| { phase: "open" }
	| { phase: "remind"; ruleVersion?: number; folders?: string[] }
	| { phase: "batch"; ruleVersion?: number; folders?: string[] }
	| { phase: "after-batch"; batched: number };

/**
 * PhotoWindowTask: the monthly photo-age rule. `open` queues the `media_index`
 * `rule_candidates` job (olderThanDays = reminderDays). `remind` records restored items,
 * reminds about 3–4-month items and enters them in the ledger; `batch` builds the cut-off
 * batch from items an earlier run reminded, never touching protected items — a one-tap
 * proposal by default, a `rule_batch` job in auto mode.
 */
export const PhotoWindowTask = defineTask<PhotoWindowInput, PhotoWindowState, { batched: number }>({
	name: "steward.photo-window",
	version: 1,
	initial: (input) =>
		input.start === "remind"
			? {
					phase: "remind",
					// 05.2: carry the request-time stamp into the checkpoint (dropping it made
					// the guards below fall back to nothing and remind out-of-scope items).
					...(input.ruleVersion === undefined ? {} : { ruleVersion: input.ruleVersion }),
					...(input.folders === undefined ? {} : { folders: input.folders }),
				}
			: { phase: "open" },
	phases: {
		open: async (task, runtime, context) => {
			await crashPoint("photo-window:open");
			await runtime.commit(
				async (tx) => {
					const nowMs = Date.now();
					const rules = (await snapshotRules(tx, runtime.conversationId)).photoWindow;
					if (!rules.enabled) {
						return { status: "terminal" as const, outcome: { status: "completed" as const, result: { batched: 0 } } };
					}
					await queueJob(tx, runtime.conversationId, {
						dedupeKey: `${task.id}:media_index`,
						kind: "media_index",
						params: { mode: "rule_candidates", folders: rules.folders, olderThanDays: rules.reminderDays },
						nowMs,
						waiter: { run: "photo-window", runId: task.input.runId },
						// 04.2: stamp the request-time rule version and folders.
						ruleVersion: (await tx.doc(RulesDoc, runtime.conversationId)).ruleVersion,
						folders: rules.folders,
					});
					return { status: "terminal" as const, outcome: { status: "completed" as const, result: { batched: 0 } } };
				},
				context,
			);
		},
		remind: async (task, runtime, context) => {
			const nowMs = Date.now();
			const checkpoint = task.state.checkpoint;
			// 05.2: fall back to the task input when the checkpoint lacks the stamp.
			const stampedVersion = checkpoint.phase === "remind" ? (checkpoint.ruleVersion ?? task.input.ruleVersion) : task.input.ruleVersion;
			const stampedFolders = checkpoint.phase === "remind"
				? (checkpoint.folders ?? task.input.folders)
				: task.input.folders;
			const jobsStored = await runtime.snapshot(Jobs, runtime.conversationId, context);
			const job = Object.values(jobsStored?.jobs ?? {}).find(
				(candidate) => candidate.waiter?.run === "photo-window" && candidate.waiter.runId === task.input.runId,
			);
			const rawItems = ((job?.result?.data as { items?: MediaRef[] } | undefined)?.items ?? []) as MediaRef[];
			// 04.2 second guard: only candidates inside the stamped folders survive (A6 subtrees).
			const items = stampedFolders
				? rawItems.filter((ref: MediaRef) => inStampedFolders(ref, stampedFolders))
				: rawItems;
			const executed = await executedSet(runtime.snapshot, runtime.conversationId, context);
			const restored = await restoredSet(runtime.snapshot, runtime.conversationId, context);
			// 04.2: the rules changed while the job was pending — discard the candidates,
			// record the run as rule_changed, and finish without a reminder or batch.
			const rulesNow = await runtime.snapshot(RulesDoc, runtime.conversationId, context);
			if (stampedVersion !== undefined && rulesNow !== undefined && rulesNow.ruleVersion !== stampedVersion) {
				await runtime.commit(
					async (tx) => {
						await recordDecision(tx, runtime.conversationId, {
							atMs: nowMs,
							type: "rule_changed",
							note: `run ${task.input.runId}: candidates gathered under ruleVersion ${stampedVersion}, current ${rulesNow.ruleVersion} — discarded, the next run starts afresh`,
						});
						return { status: "terminal" as const, outcome: { status: "completed" as const, result: { batched: 0 } } };
					},
					context,
				);
				return;
			}
			await runtime.commit(
				async (tx) => {
					// An owner restore found in a scan is recorded as restored — protected from now on.
					for (const mediaId of restoredRefsInIndex(items, executed)) {
						await recordDecision(tx, runtime.conversationId, {
							atMs: nowMs,
							type: "restored",
							mediaId,
							note: `seen again in ${task.input.runId}`,
						});
					}
					const rules = (await snapshotRules(tx, runtime.conversationId)).photoWindow;
					const photo = await tx.doc(PhotoWindowDoc, runtime.conversationId);
					// 05.2: enforce version + folder scope again inside the reminder-writing
					// commit — a rule change landing mid-phase cannot leak a stale reminder.
					const inScope = stampedFolders
						? items.filter((ref: MediaRef) => inStampedFolders(ref, stampedFolders))
						: items;
					const reminderItems = reminderItemsForRun(inScope, rules, nowMs, { reminded: photo.reminded }, restored);
					for (const ref of reminderItems) {
						photo.reminded[ref.mediaId] = { runId: task.input.runId, atMs: nowMs };
					}
					if (reminderItems.length > 0) {
						const bytes = reminderItems.reduce((sum, ref) => sum + (ref.sizeBytes ?? 0), 0);
						const notifyTaskId = await queueNotify(
							tx,
							runtime.conversationId,
							{
								dedupeKey: `photo-reminder:${task.input.runId}`,
								category: "reminder",
								title: "Photo reminder",
								body: `${reminderItems.length} items (about ${(bytes / 1_000_000_000).toFixed(1)} GB) pass the cut-off next month. Protected: Keep folder and favourites.`,
							},
							nowMs,
						);
						// A waiter registration asks the scheduler to run the notify task.
						await Promise.race([
							runtime.waitForTask(notifyTaskId, context).catch(() => undefined),
							new Promise((resolve) => setTimeout(resolve, 25)),
						]);
					}
					return {
						status: "running" as const,
						checkpoint: {
							phase: "batch" as const,
							...(task.input.ruleVersion === undefined ? {} : { ruleVersion: task.input.ruleVersion }),
							...(task.input.folders === undefined ? {} : { folders: task.input.folders }),
						},
					};
				},
				context,
			);
		},
		batch: async (task, runtime, context) => {
			await crashPoint("photo-window:batch");
			const nowMs = Date.now();
			const batchCheckpoint = task.state.checkpoint;
			const stampedVersion = batchCheckpoint.ruleVersion ?? task.input.ruleVersion;
			const stampedFolders = batchCheckpoint.folders ?? task.input.folders;
			const jobsStored = await runtime.snapshot(Jobs, runtime.conversationId, context);
			const job = Object.values(jobsStored?.jobs ?? {}).find(
				(candidate) => candidate.waiter?.run === "photo-window" && candidate.waiter.runId === task.input.runId,
			);
			const items = ((job?.result?.data as { items?: MediaRef[] } | undefined)?.items ?? []) as MediaRef[];
			const restored = await restoredSet(runtime.snapshot, runtime.conversationId, context);
			const rulesSnapshot = await runtime.snapshot(RulesDoc, runtime.conversationId, context);
			const rules = rulesSnapshot === undefined ? undefined : await readRulesDoc(rulesSnapshot);
			const photoStored = await runtime.snapshot(PhotoWindowDoc, runtime.conversationId, context);
			const reminded = photoStored?.reminded ?? {};
			if (rules === undefined || job?.status !== "reported") {
				await runtime.commit(
					() => ({ status: "terminal" as const, outcome: { status: "completed" as const, result: { batched: 0 } } }),
					context,
				);
				return;
			}
			// 04.2 second guard: filter to the stamped folders before selecting the batch (A6 subtrees).
			const inScope = stampedFolders
				? items.filter((ref: MediaRef) => inStampedFolders(ref, stampedFolders))
				: items;
			const batch = batchItemsForRun(inScope, rules.photoWindow, nowMs, { reminded }, task.input.runId, restored);
			if (stampedVersion !== undefined && rules.ruleVersion !== stampedVersion) {
				// The rules changed after the candidates were gathered: never batch stale ones.
				await runtime.commit(
					async (tx) => {
						await recordDecision(tx, runtime.conversationId, {
							atMs: nowMs,
							type: "rule_changed",
							note: `run ${task.input.runId}: batch discarded (ruleVersion ${stampedVersion} → ${rules.ruleVersion})`,
						});
						return { status: "terminal" as const, outcome: { status: "completed" as const, result: { batched: 0 } } };
					},
					context,
				);
				return;
			}
			if (batch.length === 0) {
				await runtime.commit(
					() => ({ status: "terminal" as const, outcome: { status: "completed" as const, result: { batched: 0 } } }),
					context,
				);
				return;
			}
			AUTO_MODE_NOT_YET({ autoMode: rules.photoWindow.autoMode, batchesRunOneTap: rules.batchesRunOneTap });
			const batchId = `rb-${sha256Hex(task.input.runId).slice(0, 8)}`;
			await runtime.commit(
				async (tx) => {
					await emitStewardEvent(tx, runtime.conversationId, {
						kind: "rule_batch",
						atMs: nowMs,
						data: {
							runId: task.input.runId,
							batchId,
							mode: rules.photoWindow.autoMode ? "job" : "proposal",
							itemCount: batch.length,
							bytesEstimate: batch.reduce((sum, ref) => sum + (ref.sizeBytes ?? 0), 0),
						},
					});
					if (rules.photoWindow.autoMode) {
						await queueJob(tx, runtime.conversationId, {
							dedupeKey: `photo-batch:${task.input.runId}`,
							kind: "rule_batch",
							params: {
								batchId,
								ruleVersion: rules.ruleVersion,
								items: JSON.parse(JSON.stringify(batch)) as MediaRef[],
							},
							nowMs,
							waiter: { run: "batch_result", runId: task.input.runId },
						});
						const notifyTaskId = await queueNotify(
							tx,
							runtime.conversationId,
							{
								dedupeKey: `photo-batch:${task.input.runId}`,
								category: "batch",
								title: "Photo batch queued",
								body: `${batch.length} items went to the trash automatically (auto mode).`,
							},
							nowMs,
						);
						await Promise.race([
							runtime.waitForTask(notifyTaskId, context).catch(() => undefined),
							new Promise((resolve) => setTimeout(resolve, 25)),
						]);
					} else {
						await createProposal(tx, runtime.conversationId, {
							source: "rule_batch",
							title: "Photo cleanup batch",
							reason: `Older than ${rules.photoWindow.cutoffDays} days, each reminded in an earlier month; Keep and favourites are never touched.`,
							items: batch.map((ref) => ({
								kind: "trash_media" as const,
								target: ref as unknown as Record<string, never>,
								bytesEstimate: ref.sizeBytes ?? 0,
								reason: `age ${ageDays(ref.dateTakenMs != null ? ref.dateTakenMs : ref.dateAddedMs, nowMs)} days in ${ref.relativePath}`,
							})),
							batchId,
							ruleVersion: rules.ruleVersion,
						}, nowMs);
						const notifyTaskId = await queueNotify(
							tx,
							runtime.conversationId,
							{
								dedupeKey: `photo-batch:${task.input.runId}`,
								category: "batch",
								title: "Photo batch ready",
								body: `${batch.length} items await your one-tap approval in the app.`,
							},
							nowMs,
						);
						await Promise.race([
							runtime.waitForTask(notifyTaskId, context).catch(() => undefined),
							new Promise((resolve) => setTimeout(resolve, 25)),
						]);
					}
					// The batch (proposal or job) commits first; the after-effect gate below runs
					// between this commit and the terminal one (04.6).
					return {
						status: "running" as const,
						checkpoint: { phase: "after-batch" as const, batched: batch.length },
					};
				},
				context,
			);
			// 04.6: after-effect gate — the batch (proposal or job) is committed; a kill here
			// must still leave exactly one batch after the reopen.
			await crashPoint("photo-window:after-batch");
		},
		"after-batch": async (task, runtime, context) => {
			const checkpoint = task.state.checkpoint;
			const batched = checkpoint.phase === "after-batch" ? checkpoint.batched : 0;
			await runtime.commit(
				() => ({ status: "terminal" as const, outcome: { status: "completed" as const, result: { batched } } }),
				context,
			);
		},
	},
	abort: async (_task, runtime, context) => {
		await runtime.commit(() => ({ status: "terminal" as const, outcome: { status: "aborted" as const } }), context);
	},
});

type DocumentSnapshotFn = <T extends import("@earendil-works/pi-durable").JsonObject>(
	token: import("@earendil-works/pi-durable").ConversationDocToken<T>,
	conversationId: ConversationId,
	context: Context,
) => Promise<T | undefined>;

async function executedSet(snapshot: DocumentSnapshotFn, conversationId: ConversationId, context: Context): Promise<Set<string>> {
	const stored = await snapshot(Decisions, conversationId, context);
	const ids = new Set<string>();
	for (const entry of stored?.entries ?? []) {
		if (entry.type === "executed" && entry.kind === "trash_media" && entry.mediaId !== undefined) ids.add(entry.mediaId);
	}
	return ids;
}

async function restoredSet(snapshot: DocumentSnapshotFn, conversationId: ConversationId, context: Context): Promise<Set<string>> {
	const stored = await snapshot(Decisions, conversationId, context);
	const ids = new Set<string>();
	for (const entry of stored?.entries ?? []) {
		if (entry.type === "restored" && entry.mediaId !== undefined) ids.add(entry.mediaId);
	}
	return ids;
}

// ————— BatchResultTask —————

export type BatchResultInput = { runId: string };

type BatchResultState = { phase: "settle" };

/**
 * BatchResultTask: the deterministic continuation of an auto-mode `rule_batch` job. When
 * the phone reports the per-item outcomes, it writes the ledger entries and the review
 * line (failures, cancellations, protected skips; the job result carries no freed bytes).
 */
export const BatchResultTask = defineTask<BatchResultInput, BatchResultState, null>({
	name: "steward.batch-result",
	version: 1,
	initial: () => ({ phase: "settle" }),
	phases: {
		settle: async (task, runtime, context) => {
			const nowMs = Date.now();
			const jobsStored = await runtime.snapshot(Jobs, runtime.conversationId, context);
			const job = Object.values(jobsStored?.jobs ?? {}).find(
				(candidate) => candidate.waiter?.run === "batch_result" && candidate.waiter.runId === task.input.runId,
			);
			if (job === undefined || job.status !== "reported") {
				await runtime.commit(
					() => ({ status: "terminal" as const, outcome: { status: "completed" as const, result: null } }),
					context,
				);
				return;
			}
			const data = job.result?.data as
				| { batchId?: string; items?: Array<{ mediaId: string; outcome: string; resultCode: number | null }> }
				| undefined;
			const items = data?.items ?? [];
			const params = job.params as { items?: Array<{ mediaId: string }> };
			const batchMediaIds = new Set((params.items ?? []).map((item) => item.mediaId));
			const decisionsStored = await runtime.snapshot(Decisions, runtime.conversationId, context);
			let estimatedBytes = 0;
			for (const entry of decisionsStored?.entries ?? []) {
				if (entry.kind === "trash_media" && entry.mediaId !== undefined && batchMediaIds.has(entry.mediaId)) {
					estimatedBytes += entry.bytesEstimate ?? 0;
				}
			}
			await runtime.commit(
				async (tx) => {
					let failures = 0;
					let cancellations = 0;
					let skippedProtected = 0;
					for (const item of items) {
						const type: "executed" | "failed" | "cancelled" | "skipped_protected" =
							item.outcome === "trashed"
								? "executed"
								: item.outcome === "failed"
									? "failed"
									: item.outcome === "cancelled"
										? "cancelled"
										: "skipped_protected";
						if (item.outcome === "failed") failures += 1;
						if (item.outcome === "cancelled") cancellations += 1;
						if (item.outcome === "skipped_protected") skippedProtected += 1;
						await recordDecision(tx, runtime.conversationId, {
							atMs: nowMs,
							type,
							mediaId: item.mediaId,
							kind: "trash_media",
							proposalId: data?.batchId,
							note: `rule batch ${data?.batchId ?? task.input.runId}`,
						});
					}
					await emitStewardEvent(tx, runtime.conversationId, {
						kind: "review_line",
						atMs: nowMs,
						data: {
							batchId: data?.batchId ?? "",
							source: "rule_batch",
							estimatedBytes,
							freedBytes: null,
							failures,
							cancellations,
							skippedProtected,
						},
					});
					await recordDecision(tx, runtime.conversationId, {
						atMs: nowMs,
						type: "review",
						proposalId: data?.batchId,
						review: { estimatedBytes, freedBytes: null, failures, cancellations, skippedProtected },
					});
					return { status: "terminal" as const, outcome: { status: "completed" as const, result: null } };
				},
				context,
			);
		},
	},
	abort: async (_task, runtime, context) => {
		await runtime.commit(() => ({ status: "terminal" as const, outcome: { status: "aborted" as const } }), context);
	},
});
