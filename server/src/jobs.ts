/**
 * `steward.jobs` — the phone job store (plan docs/protocol.md §3).
 *
 * A tool or a rule task commits a job keyed by a `dedupeKey` (the tool call's durable task
 * id, or a rule task's `<taskId>:<slot>`), which is stable across crash-and-resume, and
 * find-before-create means a replay never makes a second job. The first accepted result
 * wins and creates the job's one background reporter task, which submits the result into
 * `main` as a follow-up input with `requestId: "phonejob:<jobId>"`. Jobs expire after 7
 * days; an expired job tells the conversation once, the same way.
 */
import type { Context, JsonValue } from "@earendil-works/chord";
import {
	type Conversation,
	defineDoc,
	defineExtension,
	defineTask,
	type ConversationId,
	type Harness,
	type TaskId,
	type Tx,
} from "@earendil-works/pi-durable";
import type { JsonObject } from "@earendil-works/pi-durable";
import { canonicalJson } from "./canonical.ts";
import { crashPoint } from "./crash-gate.ts";
import { normaliseDiagnosticsResult } from "./diagnostics.ts";
import { ApiError, conflict, notFound } from "./errors.ts";
import { sha256Hex } from "./ids.ts";
import { emitStewardEvent } from "./steward-events.ts";
import { closeRun, openRun } from "./run-tracking.ts";
import { recordRequest } from "./requests.ts";
import { Proposals } from "./proposals.ts";
import { openListText, openProposalLines } from "./weekly-cache.ts";
import { PHONE_SUMMARY_MAX_CHARS, summarisePhoneResult } from "./phone-summary.ts";
import { settleRemovedApps } from "./removed-apps.ts";
import { periodicCheckpoint } from "./checkpoint.ts";

export const JOB_TTL_MS = 7 * 24 * 60 * 60_000;

export type JobKind =
	| "storage_overview"
	| "app_usage"
	| "media_index"
	| "phone_selftest"
	| "rule_batch"
	| "phone_diagnostics"
	/** A10.1 */
	| "files_query"
	/** A10.2 */
	| "shell_info"
	| "force_stop";

export type MediaRef = {
	mediaId: string;
	relativePath: string;
	displayName: string;
	mimeType: string;
	sizeBytes: number;
	dateTakenMs: number | null;
	dateAddedMs: number;
	isFavourite: boolean;
	inKeep: boolean;
};

export type RuleBatchItem = {
	mediaId: string;
	outcome: "trashed" | "skipped_protected" | "missing" | "failed";
	resultCode: number | null;
};

export type PhoneJobResult = {
	status: "ok" | "error" | "partial";
	data: JsonValue;
	error: { code: string; message: string } | null;
};

export type PhoneJob = {
	/** Derived from the dedupe key, so a replay names the same job. */
	jobId: string;
	/** The creator's dedupe key (tool call task id, or rule task `<taskId>:<slot>`). */
	dedupeKey: string;
	kind: JobKind;
	params: JsonObject;
	status: "queued" | "reported" | "expired";
	createdAtMs: number;
	expiresAtMs: number;
	/** The one reporter task serving this job (its durable numeric id); set on the first accepted report. */
	reporterTaskId?: number;
	/** Canonical JSON of the accepted result; equality is decided on this. */
	canonicalResult?: string;
	/** The accepted result. */
	result?: PhoneJobResult & { reportedAtMs: number };
	conflictingReports: number;
	/** Set when a rule task waits for this job's outcome. */
	waiter?: { run: "weekly-check" | "photo-window" | "batch_result"; runId: string };
	/** Dedupe key of the waiter continuation task, once one has been created. */
	continuation?: string;
	/** 04.2: request-time rule version and folders, stamped at queue time. */
	ruleVersion?: number;
	folders?: string[];
	/** 04.3: the expiry reporter's task id, set in the same commit as status = "expired". */
	expiryTaskId?: number;
};

export type JobsState = { jobs: Record<string, PhoneJob> };

export const Jobs = defineDoc<JobsState>({
	kind: "steward.jobs",
	version: 2,
	// Pi Durable 1.4.0 requires an explicit checkpoint policy (no default).
	checkpointWhen: periodicCheckpoint(),
	scope: "conversation",
	history: "latest",
	// A fork of the conversation starts with no jobs.
	fork: "initial",
	initial: () => ({ jobs: {} }),
});

/** The job id for a dedupe key: a pure function, so a replay names the same job. */
export function jobIdForDedupeKey(dedupeKey: string): string {
	return `pj-${sha256Hex(dedupeKey).slice(0, 12)}`;
}

export type QueueJobInput = {
	dedupeKey: string;
	kind: JobKind;
	params: JsonObject;
	nowMs: number;
	waiter?: { run: "weekly-check" | "photo-window" | "batch_result"; runId: string };
	/** 04.2: the request-time rule version, stamped for waiter continuations. */
	ruleVersion?: number;
	/** 04.2: the request-time folders, stamped for waiter continuations. */
	folders?: string[];
};

export type QueueJobResult = { jobId: string; created: boolean };

/**
 * Find-or-create the job for `dedupeKey`, inside the caller's commit. The lookup runs
 * before any create: a replayed call finds the job its first attempt made. Emits
 * `job_queued` (and, for a selftest job, `selftest`) inside the same commit.
 */
export async function queueJob(tx: Tx, conversationId: ConversationId, input: QueueJobInput): Promise<QueueJobResult> {
	const doc = await tx.doc(Jobs, conversationId);
	const jobId = jobIdForDedupeKey(input.dedupeKey);
	const existing = doc.jobs[jobId];
	if (existing !== undefined) return { jobId, created: false };
	doc.jobs[jobId] = {
		jobId,
		dedupeKey: input.dedupeKey,
		kind: input.kind,
		params: input.params,
		status: "queued",
		createdAtMs: input.nowMs,
		expiresAtMs: input.nowMs + JOB_TTL_MS,
		conflictingReports: 0,
		...(input.waiter === undefined ? {} : { waiter: input.waiter }),
		...(input.ruleVersion === undefined ? {} : { ruleVersion: input.ruleVersion }),
		...(input.folders === undefined ? {} : { folders: JSON.parse(JSON.stringify(input.folders)) as string[] }),
	};
	await emitStewardEvent(tx, conversationId, {
		kind: input.kind === "phone_selftest" ? "selftest" : "job_queued",
		atMs: input.nowMs,
		data: { jobId, jobKind: input.kind },
	});
	return { jobId, created: true };
}

/**
 * Every job queued but not yet answered, oldest first — what `GET /v1/jobs` delivers.
 * Delivered-once jobs stay `queued` until their result lands (delivery is at least once).
 */
export async function queuedJobs(tx: Tx, conversationId: ConversationId, nowMs: number): Promise<PhoneJob[]> {
	const doc = await tx.doc(Jobs, conversationId);
	// Detach from the document overlay: callers serialise these after the commit.
	return JSON.parse(
		JSON.stringify(
			Object.values(doc.jobs)
				.filter((job) => job.status === "queued" && job.expiresAtMs > nowMs)
				.sort((a, b) => a.createdAtMs - b.createdAtMs),
		),
	) as PhoneJob[];
}

type WaiterJob = Pick<PhoneJob, "waiter" | "continuation">;

/**
 * Create the waiter's continuation task exactly once, inside the caller's commit. Used by
 * the reporter's mark phase and by the expiry sweep: either path must continue the rule
 * run, and the `continuation` marker (set in the same commit) keeps a replay from making
 * two.
 */
async function createWaiterContinuation(tx: Tx, conversationId: ConversationId, job: WaiterJob): Promise<TaskId<never> | undefined> {
	const waiter = job.waiter;
	if (waiter === undefined || job.continuation !== undefined) return undefined;
	job.continuation = `${waiter.run}-settle:${waiter.runId}`;
	const options = { ownership: { kind: "conversation" }, conversationId, background: true } as const;
	if (waiter.run === "weekly-check") {
		const input: WeeklyCheckInput = { runId: waiter.runId, start: "settle" };
		return tx.createTask(WeeklyCheckTask, input, options) as unknown as TaskId<never>;
	}
	if (waiter.run === "photo-window") {
		const job0 = job as PhoneJob;
		const input: PhotoWindowInput = {
			runId: waiter.runId,
			start: "remind",
			...(job0.ruleVersion === undefined ? {} : { ruleVersion: job0.ruleVersion }),
			...(job0.folders === undefined ? {} : { folders: job0.folders }),
		};
		return tx.createTask(PhotoWindowTask, input, options) as unknown as TaskId<never>;
	}
	const input: BatchResultInput = { runId: waiter.runId };
	return tx.createTask(BatchResultTask, input, options) as unknown as TaskId<never>;
}

export type ReportOutcome = { accepted: true; duplicate: boolean };

/**
 * Accept one result report (the HTTP-facing path). First accepted report wins; a later
 * identical report (canonical JSON equality) is a duplicate; a different one is a
 * `409 RESULT_CONFLICT` and only increments `conflictingReports` — no second follow-up
 * ever reaches the conversation.
 */
type Acceptance =
	| { conflict: true }
	| { conflict: false; duplicate: true }
	| { conflict: false; duplicate: false; reporterTaskId: TaskId<null> | undefined };

export async function reportPhoneResult(
	harness: Harness,
	conversationId: ConversationId,
	jobId: string,
	report: PhoneJobResult & { deviceTimeMs?: number; appVersion?: string },
	context: Context,
	request?: { requestId: string; route: string; body: unknown },
): Promise<ReportOutcome> {
	const conversation = await harness.conversation(conversationId, context);
	if (conversation === undefined) throw notFound(`no conversation ${conversationId}`);
	const nowMs = Date.now();
	const known = await harness.snapshot(Jobs, conversationId, context);
	const knownJob = known?.jobs[jobId];
	if (knownJob === undefined) throw notFound(`unknown job ${jobId}`);
	// O6: a diagnostics result is normalised before the canonical form, so duplicate
	// detection and the stored data agree on the capped shape.
	const storedData = knownJob.kind === "phone_diagnostics"
		? normaliseDiagnosticsResult((report.data ?? null) as JsonValue)
		: (report.data ?? null);
	const canonical = canonicalJson({
		status: report.status,
		data: storedData,
		error: report.error,
	});
	if (knownJob.status === "expired") throw conflict("JOB_EXPIRED", "this job expired before its result arrived");
	if (knownJob.status === "reported") {
		if (knownJob.canonicalResult === canonical) return { accepted: true, duplicate: true };
		return reportConflict(jobId, conversation, conversationId, context);
	}
	const committed = await conversation.commit(async (tx): Promise<Acceptance> => {
		const doc = await tx.doc(Jobs, conversationId);
		const job = doc.jobs[jobId];
		if (job === undefined) throw notFound(`unknown job ${jobId}`);
		if (job.status === "expired") throw conflict("JOB_EXPIRED", "this job expired before its result arrived");
		if (job.status === "reported") {
			// Raced with the winning report: return the marker and let the caller persist
			// the counter — a throw inside the commit would roll it back (04.9).
			if (job.canonicalResult !== canonical) return { conflict: true };
			return { conflict: false, duplicate: true };
		}
		job.status = "reported";
		job.canonicalResult = canonical;
		job.result = {
			status: report.status,
			data: JSON.parse(JSON.stringify(storedData)) as JsonValue,
			error: report.error ?? null,
			reportedAtMs: nowMs,
		};
		let createdReporter: TaskId<null> | undefined;
		if (job.reporterTaskId === undefined) {
			const reporterTaskId = await tx.createTask(
				PhoneResultTask,
				{ jobId },
				{ ownership: { kind: "conversation" }, background: true },
			);
			job.reporterTaskId = reporterTaskId as unknown as number;
			createdReporter = reporterTaskId;
		}
		await emitStewardEvent(tx, conversationId, {
			kind: "job_reported",
			atMs: nowMs,
			data: { jobId, jobKind: job.kind, status: report.status },
		});
		// S11 (A10.8): apps removed outside the app leave the open uninstall proposals, in
		// the same commit as the accepted report.
		if (job.kind === "app_usage") await settleRemovedApps(tx, conversationId, job.result, nowMs);
		// item 3: the ledger entry shares the acceptance commit, so a crash
		// before the HTTP response still lets a retry dedupe.
		if (request !== undefined) {
			await recordRequest(tx, {
				requestId: request.requestId,
				route: request.route,
				body: request.body,
				response: { accepted: true, duplicate: false },
				nowMs,
			});
		}
		return { conflict: false, duplicate: false, reporterTaskId: createdReporter };
	}, context);
	if (committed.conflict) {
		return reportConflict(jobId, conversation, conversationId, context);
	}
	if (committed.duplicate) {
		return { accepted: true, duplicate: true };
	}
	// The scheduler dozes when the process has been quiet: only calls that ask for progress
	// wake it. Register (and briefly await) a waiter for the new reporter's terminal
	// receipt; the HTTP answer does not wait for the run itself.
	harness.resume();
	if (committed.reporterTaskId !== undefined) {
		await Promise.race([
			harness.waitForTask(committed.reporterTaskId, context).catch(() => undefined),
			new Promise((resolve) => setTimeout(resolve, 25)),
		]);
	}
	return { accepted: true, duplicate: false };
}

/** Persist the conflict counter in its own commit, log one line, then throw the 409. */
async function reportConflict(
	jobId: string,
	conversation: Conversation,
	conversationId: ConversationId,
	context: Context,
): Promise<never> {
	await conversation.commit(async (tx) => {
		const doc = await tx.doc(Jobs, conversationId);
		const job = doc.jobs[jobId];
		if (job !== undefined) job.conflictingReports += 1;
		return { counted: true } as const;
	}, context);
	console.error(`[steward] result conflict on ${jobId}: kept the first result`);
	throw conflict("RESULT_CONFLICT", "this job already has a different accepted result");
}

type ReporterState =
	| { phase: "deliver" }
	| { phase: "mark"; jobId: string }
	| { phase: "settle"; jobId: string; continuationTaskId?: number };

/** S8: a job a weekly check waits on settles silently into its run (no steward turn). */
function settlesSilently(job: { waiter?: { run: string } }): boolean {
	return job.waiter?.run === "weekly-check";
}

/**
 * The job's one background reporter. Phases:
 *  - `deliver`: submit the follow-up (the requestId makes a replayed delivery a no-op),
 *    then open the run (`run_start`, reason `phone_result`) in the same commit.
 *  - `mark`: store nothing new (the result is already in the job) but create the rule-task
 *    continuation exactly once, then hand to `settle`.
 *  - `settle`: wait for the conversation to go idle, then close the run (`run_end`).
 */
export const PhoneResultTask = defineTask<{ jobId: string }, ReporterState, null>({
	name: "steward.phone-result",
	version: 2,
	initial: () => ({ phase: "deliver" }),
	phases: {
		deliver: async (task, runtime, context) => {
			const { jobId } = task.input;
			const conversation = await runtime.conversation(runtime.conversationId, context);
			if (conversation === undefined) throw new Error("reporter lost its conversation");
			const stored = await runtime.snapshot(Jobs, runtime.conversationId, context);
			const job = stored?.jobs[jobId];
			if (job === undefined) throw new Error(`reporter lost its job ${jobId}`);
			if (job.kind === "phone_diagnostics") {
				// O6: a diagnostics result is for engineers — no follow-up input, no run, no
				// model turn. The stored result under the job is the whole delivery.
				await runtime.commit(
					() => ({ status: "running" as const, checkpoint: { phase: "settle" as const, jobId } }),
					context,
				);
				return;
			}
			if (settlesSilently(job)) {
				// S8: a weekly check's phone results settle silently into the run — no follow-up
				// input, no run, no model turn per result. The weekly message (one per review)
				// carries the results and the server's open list. `mark` still creates the run's
				// continuation exactly once.
				await runtime.commit(
					() => ({ status: "running" as const, checkpoint: { phase: "mark" as const, jobId } }),
					context,
				);
				return;
			}
			// S8: outside a review the turn also carries the server's open-proposal list, read
			// from committed state. The requestId (not the text) is what dedupes a replay.
			const proposalsStored = await runtime.snapshot(Proposals, runtime.conversationId, context);
			const openList = openListText(openProposalLines(Object.values(proposalsStored?.proposals ?? {})));
			// item 1 : submit BEFORE checkpointing. The stable requestId
			// makes Pi Durable deliver the re-submission of a replayed deliver exactly once;
			// checkpointing first would let a crash in the gap lose the follow-up entirely
			// (the resume would skip past the submit). Supersedes the 02-answer ordering.
			await crashPoint("reporter:before-submit");
			const submission = await conversation.submit(
				{
					type: "input",
					content: `${phoneResultText(jobId, job.kind, job.result ?? { status: "ok", data: null, error: null })}\n${openList}`,
					whenBusy: "followUp",
					requestId: `phonejob:${jobId}`,
				},
				context,
			);
			// Gap B: submitted but not yet checkpointed — a kill here replays the submit;
			// the requestId dedupes it to exactly one delivery.
			await crashPoint("reporter:after-submit");
			await runtime.commit(
				async (tx) => {
					await openRun(tx, runtime.conversationId, `phonejob:${jobId}`, "phone_result", Date.now());
					return { status: "running", checkpoint: { phase: "mark", jobId } };
				},
				context,
			);
			void submission;
		},
		mark: async (task, runtime, context) => {
			const { jobId } = task.input;
			await crashPoint("reporter:before-mark");
			await runtime.commit(
				async (tx) => {
					const doc = await tx.doc(Jobs, runtime.conversationId);
					const job = doc.jobs[jobId];
					// The waiter continuation is created exactly once, keyed on the job, so a
					// replayed mark (crash between submit and this checkpoint) cannot make two.
					let continuationTaskId: number | undefined;
					if (job !== undefined) {
						const created = await createWaiterContinuation(tx, runtime.conversationId, job);
						if (created !== undefined) continuationTaskId = created as unknown as number;
					}
					return {
						status: "running",
						checkpoint: { phase: "settle", jobId, ...(continuationTaskId === undefined ? {} : { continuationTaskId }) },
					};
				},
				context,
			);
		},
		settle: async (task, runtime, context) => {
			const { jobId } = task.input;
			const conversation = await runtime.conversation(runtime.conversationId, context);
			if (conversation === undefined) throw new Error("reporter lost its conversation");
			// Background tasks are outside the ordinary scope waitForIdle watches, so this
			// resolves when the phone-result run has finished answering.
			await conversation.waitForIdle(context);
			// The rule-run continuation created in `mark` needs this task's waiter
			// registration to be scheduled (a quiet host's scheduler works only for tasks
			// something waits on); awaiting it also orders run_end after its work.
			const checkpoint = task.state.checkpoint;
			const continuationTaskId = checkpoint.phase === "settle" ? checkpoint.continuationTaskId : undefined;
			if (continuationTaskId !== undefined) {
				await runtime
					.waitForTask(continuationTaskId as unknown as Parameters<typeof runtime.waitForTask>[0], context)
					.catch(() => undefined);
			}
			await runtime.commit(
				async (tx) => {
					await closeRun(tx, runtime.conversationId, `phonejob:${jobId}`, Date.now());
					return { status: "terminal", outcome: { status: "completed", result: null } };
				},
				context,
			);
		},
	},
	abort: async (_task, runtime, context) => {
		await runtime.commit(() => ({ status: "terminal", outcome: { status: "aborted" } }), context);
	},
});

type ExpiredState = { phase: "deliver" } | { phase: "settle"; jobId: string };

/**
 * The job's one expiry reporter (04.3), created in the same commit that marks the job
 * expired. `deliver` submits the notice with requestId `phonejob:<jobId>` — a replayed
 * delivery is a no-op — and `settle` closes the run the notice starts.
 */
export const PhoneExpiredTask = defineTask<{ jobId: string; kind: JobKind }, ExpiredState, null>({
	name: "steward.phone-expired",
	version: 1,
	initial: () => ({ phase: "deliver" }),
	phases: {
		deliver: async (task, runtime, context) => {
			const { jobId, kind } = task.input;
			const conversation = await runtime.conversation(runtime.conversationId, context);
			if (conversation === undefined) throw new Error("expiry reporter lost its conversation");
			// item 1 : submit BEFORE checkpointing (see PhoneResultTask).
			await crashPoint("expired:before-submit");
			const submission = await conversation.submit(
				{
					type: "input",
					content: `[phone expired ${jobId}] kind=${kind} status=expired`,
					whenBusy: "followUp",
					requestId: `phonejob:${jobId}`,
				},
				context,
			);
			await crashPoint("expired:after-submit");
			await runtime.commit(
				async (tx) => {
					await openRun(tx, runtime.conversationId, `phonejob:${jobId}`, "phone_result", Date.now());
					return { status: "running", checkpoint: { phase: "settle", jobId } };
				},
				context,
			);
			void submission;
		},
		settle: async (task, runtime, context) => {
			const { jobId } = task.input;
			const conversation = await runtime.conversation(runtime.conversationId, context);
			if (conversation === undefined) throw new Error("expiry reporter lost its conversation");
			await conversation.waitForIdle(context);
			await runtime.commit(
				async (tx) => {
					await closeRun(tx, runtime.conversationId, `phonejob:${jobId}`, Date.now());
					return { status: "terminal", outcome: { status: "completed", result: null } };
				},
				context,
			);
		},
	},
	abort: async (_task, runtime, context) => {
		await runtime.commit(() => ({ status: "terminal", outcome: { status: "aborted" } }), context);
	},
});

/** The one-line, deterministic text a phone result delivers into the conversation. */
export function phoneResultText(jobId: string, kind: JobKind, result: PhoneJobResult): string {
	if (result.status === "ok") {
		const data = result.data ?? null;
		const full = canonicalJson(data);
		// A11.2: a result that already fits the conversation's budget goes whole, as before;
		// a bigger one enters as a deterministic per-kind digest, and the model fetches the
		// rest with the `phone_result` tool rather than the whole result being pasted in.
		if (full.length <= PHONE_SUMMARY_MAX_CHARS) {
			return `[phone result ${jobId}] kind=${kind} status=ok data=${full}`;
		}
		return `[phone result ${jobId}] kind=${kind} status=ok summary=${canonicalJson(summarisePhoneResult(kind, data))} (full result: phone_result {"jobId":"${jobId}"})`;
	}
	const code = result.error?.code ?? "UNKNOWN";
	const message = result.error?.message ?? "";
	return `[phone result ${jobId}] kind=${kind} status=${result.status} error=${code} ${message}`.trimEnd();
}

export type ExpiryNotice = { jobId: string; kind: JobKind };

/**
 * Expire every queued job past its `expiresAtMs` and tell the conversation once for each,
 * in the same follow-up way as a result. Safe to run repeatedly: an already-expired job is
 * never re-noticed, and the notice input's requestId dedupes across crash retries.
 */
export async function expireDueJobs(
	harness: Harness,
	conversationId: ConversationId,
	nowMs: number,
	context: Context,
): Promise<ExpiryNotice[]> {
	const conversation = await harness.conversation(conversationId, context);
	if (conversation === undefined) return [];
	const due = await conversation.commit(async (tx) => {
		const doc = await tx.doc(Jobs, conversationId);
		const expired: ExpiryNotice[] = [];
		const continuations: TaskId<never>[] = [];
		const expiryTasks: TaskId<null>[] = [];
		for (const job of Object.values(doc.jobs)) {
			if (job.status === "queued" && job.expiresAtMs <= nowMs) {
				job.status = "expired";
				// A rule run waiting on this job must still continue: the same commit marks
				// the expiry and creates the continuation, exactly once.
				const created = await createWaiterContinuation(tx, conversationId, job);
				if (created !== undefined) continuations.push(created);
				// 04.3: the notice is a replayable task created in the SAME commit as the
				// status change, so a crash before or after the submit still delivers
				// exactly one expiry follow-up. A phone_diagnostics expiry tells no one (O6):
				// its result never reaches the conversation, and neither does its expiry.
				// S8: nor does an expired job of a weekly check (the run's message names it).
				if (job.expiryTaskId === undefined && job.kind !== "phone_diagnostics" && !settlesSilently(job)) {
					const expiryTaskId = await tx.createTask(
						PhoneExpiredTask,
						{ jobId: job.jobId, kind: job.kind },
						{ ownership: { kind: "conversation" }, background: true },
					);
					job.expiryTaskId = expiryTaskId as unknown as number;
					expiryTasks.push(expiryTaskId);
				}
				expired.push({ jobId: job.jobId, kind: job.kind });
			}
		}
		return { expired, continuations, expiryTasks };
	}, context).then(async (result) => {
		// Kick each new continuation and expiry reporter: a waiter registration asks the
		// (possibly dozing) scheduler to work on them; awaited briefly so expiry returns
		// at once.
		for (const id of [...result.continuations, ...result.expiryTasks]) {
			await Promise.race([
				harness.waitForTask(id, context).catch(() => undefined),
				new Promise((resolve) => setTimeout(resolve, 25)),
			]);
		}
		return result.expired;
	});
	// 04.3 crash window: the expiry commit has landed and the notice task exists; a kill
	// here must still yield exactly one follow-up after reopen.
	await crashPoint("expiry:committed");
	harness.resume();
	return due;
}

/** Jobs the device may still fetch (queued, not expired), newest last — read-only view. */
export async function jobsOverview(
	harness: Harness,
	conversationId: ConversationId,
	context: Context,
): Promise<PhoneJob[]> {
	const stored = await harness.commit(async (tx) => {
		const doc = await tx.doc(Jobs, conversationId);
		return doc === undefined ? [] : (JSON.parse(JSON.stringify(Object.values(doc.jobs))) as PhoneJob[]);
	}, context);
	return stored.sort((a, b) => a.createdAtMs - b.createdAtMs);
}

// Late import to avoid a cycle: the reporter creates the rule tasks' continuations.
import type { BatchResultInput, PhotoWindowInput, WeeklyCheckInput } from "./rule-tasks.ts";
import { BatchResultTask, PhotoWindowTask, WeeklyCheckTask } from "./rule-tasks.ts";

/**
 * The steward's job plumbing as an extension: the reporter task. The phone tools
 * themselves live in tools.ts; this keeps every task that touches the jobs document
 * registered in one place.
 */
export const jobsExtension = defineExtension({
	name: "steward-jobs",
	tasks: [PhoneResultTask, PhoneExpiredTask],
});

export type { TaskId };
