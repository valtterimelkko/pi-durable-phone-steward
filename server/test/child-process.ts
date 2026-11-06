/**
 * Child process for the S1 crash tests. One stage per invocation:
 *
 *     node test/child-process.ts '<json stage>'
 *
 * Stages print `MARKER {…}` lines on stdout; stages that wait to be SIGKILLed also write
 * a marker file (kernel-visible at once) so the test harness can kill at a deterministic point.
 * Every stage opens the SQLite file its parent named, so a reopen stage proves recovery
 * in a fresh process.
 */
import { existsSync, readFileSync } from "node:fs";
import { writeFile } from "node:fs/promises";
import { join } from "node:path";
import { BACKGROUND_CONTEXT } from "@earendil-works/chord/context";
import { type EntryRecord } from "@earendil-works/pi-durable";
import { armCrashGate } from "../src/crash-gate.ts";
import { messageText } from "../src/faux-steward.ts";
import { Jobs, reportPhoneResult } from "../src/jobs.ts";
import { PhotoWindowTask } from "../src/photo-window.ts";
import { openStewardHarness, spikeTestRegistry } from "../src/steward.ts";

const context = BACKGROUND_CONTEXT;

type Stage = {
	stage:
		| "hang-model-turn"
		| "resume-and-settle"
		| "queue-job"
		| "report-and-verify"
		| "replay-gate"
		| "replay-gate-after-job"
		| "report-gate"
		| "report-concurrent-verify"
		| "report-resume-verify"
		| "resume-verify-jobs"
		| "rule-task-queue-jobs"
		| "rule-task-jobs-verify"
		| "rule-task-queue-jobs"
		| "rule-task-jobs-verify"
		| "notify-gate"
		| "notify-verify"
		| "expire-gate"
		| "expire-verify"
		| "decision-crash-server"
		| "decision-retry-server"
		| "route-crash-server"
		| "route-retry-server"
		| "reporter-gap"
		| "reporter-verify"
		| "expired-gap"
		| "expired-verify"
		| "photo-window-batch-gate"
		| "photo-window-batch-verify"
		| "weekly-settle-gate"
		| "weekly-settle-verify";
	db: string;
	gateDir?: string;
	jobId?: string;
	runId?: string;
	proposalId?: string;
	gate?: string;
	route?: string;
	sentLog?: string;
	dedupeKey?: string;
	reports?: number;
	taskId?: string;
	counterPath?: string;
	hang?: boolean;
	pendingMarker?: string;
};

const stage = JSON.parse(process.argv[2]!) as Stage;

function markerLine(payload: Record<string, unknown>): void {
	process.stdout.write(`MARKER ${JSON.stringify(payload)}\n`);
}

async function markerFile(dir: string, name: string): Promise<void> {
	await writeFile(join(dir, name), new Date().toISOString(), "utf8");
}

/** Stay alive until the test parent SIGKILLs us. */
function keepAlive(): void {
	setInterval(() => {}, 1_000_000);
}

async function pollUntil(check: () => Promise<boolean> | boolean, timeoutMs = 20_000): Promise<boolean> {
	const deadline = Date.now() + timeoutMs;
	while (Date.now() < deadline) {
		if (await check()) return true;
		await new Promise((resolve) => setTimeout(resolve, 5));
	}
	return false;
}

/** Transcript summary computed from committed entries — the reopened storage, not memory. */
function summarize(entries: readonly EntryRecord[]) {
	let userEntries = 0;
	let assistantEntries = 0;
	let phonejobDeliveries = 0;
	let queuedToolResults = 0;
	let phoneEchoCalls = 0;
	const assistantTexts: string[] = [];
	for (const record of entries) {
		const message = record.model?.[0];
		if (message === undefined) continue;
		const text = messageText(message);
		if (message.role === "user") {
			userEntries += 1;
			if (text.startsWith("[phone result ") || text.startsWith("[phonejob")) phonejobDeliveries += 1;
		} else if (message.role === "assistant") {
			assistantEntries += 1;
			assistantTexts.push(text);
			for (const part of message.content) {
				if (part.type === "toolCall" && part.name === "phone_echo") phoneEchoCalls += 1;
			}
		} else if (message.role === "toolResult") {
			if (text.includes("queued")) queuedToolResults += 1;
		}
	}
	return { userEntries, assistantEntries, phonejobDeliveries, queuedToolResults, phoneEchoCalls, assistantTexts };
}

/**
 * Wait for a task to reach terminal state. `harness.waitForTask` resolves with the
 * storage record as-is when the resumed scheduler has not registered the task in its
 * live set yet (a reopen race), so loop until the receipt is terminal.
 */
async function waitForTaskTerminal(
	harness: Awaited<ReturnType<typeof openStewardHarness>>["harness"],
	id: Parameters<typeof harness.waitForTask>[0],
	taskContext: typeof context,
): Promise<Awaited<ReturnType<typeof harness.waitForTask>>> {
	for (;;) {
		const receipt = await harness.waitForTask(id, taskContext);
		if (receipt.state.status === "terminal") return receipt;
		await new Promise((resolve) => setTimeout(resolve, 10));
	}
}

const open = () =>
	openStewardHarness({
		dbPath: join(stage.db, "steward.sqlite"),
		// The S1 spike's phone_echo is test-only and not on the protocol §8 allowlist, so
		// these children run the spike registry (no guard extension).
		registry: spikeTestRegistry(),
		// Every child gets the (free) scripted faux provider; stages that prove mid-turn
		// recovery set hang: true with a pending marker path.
		fauxPlan: {
			hang: stage.hang === true,
			...(stage.pendingMarker === undefined ? {} : { pendingMarker: stage.pendingMarker }),
		},
	});

try {
	switch (stage.stage) {
		case "hang-model-turn": {
			const { root } = await open();
			await root.submit({ type: "input", content: "Hello there" }, context);
			markerLine({ event: "submitted" });
			keepAlive();
			break;
		}
		case "resume-and-settle": {
			const { harness, root } = await open();
			harness.resume();
			await root.waitForIdle(context);
			const view = await root.context(context);
			markerLine({ event: "result", ...summarize(view.entries) });
			await harness.close(context);
			break;
		}
		case "queue-job": {
			const { harness, root } = await open();
			const submission = await root.submit({ type: "input", content: "Please check my phone" }, context);
			const settled = await submission.wait(context);
			if (settled.status !== "done") throw new Error(`run did not finish: ${settled.status}`);
			let jobId: string | undefined;
			const found = await pollUntil(async () => {
				const jobs = await harness.snapshot(Jobs, root.id, context);
				const list = Object.values(jobs?.jobs ?? {});
				if (list.length === 0) return false;
				jobId = list[0]?.jobId;
				return true;
			});
			if (!found || jobId === undefined) throw new Error("no queued job after the run");
			await markerFile(stage.db, "job-queued");
			markerLine({ event: "job-queued", jobId });
			keepAlive();
			break;
		}
		case "report-and-verify": {
			const { harness, root } = await open();
			harness.resume();
			const stored = await harness.snapshot(Jobs, root.id, context);
			const job = stored?.jobs[stage.jobId ?? ""];
			if (job === undefined) throw new Error(`job ${stage.jobId} not found in reopened storage`);
			const reports = stage.reports ?? 1;
			for (let index = 0; index < reports; index += 1) {
				await reportPhoneResult(
					harness,
					root.id,
					stage.jobId!,
					{ status: "ok", data: { note: "fake phone result" }, error: null },
					context,
				);
			}
			// The reporter is a background task; wait for it explicitly, then for the run.
			const storedNow = await harness.snapshot(Jobs, root.id, context);
			const reporterId = storedNow?.jobs[stage.jobId ?? ""]?.reporterTaskId;
			if (reporterId !== undefined) {
				await waitForTaskTerminal(harness, reporterId as unknown as Parameters<typeof harness.waitForTask>[0], context);
			}
			await root.waitForIdle(context);
			const view = await root.context(context);
			const after = await harness.snapshot(Jobs, root.id, context);
			markerLine({
				event: "result",
				jobCount: Object.keys(stored?.jobs ?? {}).length,
				jobStatus: job.status,
				jobStatusAfter: after?.jobs[stage.jobId ?? ""]?.status,
				...summarize(view.entries),
			});
			await harness.close(context);
			break;
		}
		case "replay-gate": {
			armCrashGate(
				"phone_echo",
				join(stage.gateDir ?? stage.db, "phone-echo-marker"),
				join(stage.gateDir ?? stage.db, "phone-echo-release"),
			);
			const { root } = await open();
			await root.submit({ type: "input", content: "Please check my phone" }, context);
			markerLine({ event: "submitted" });
			keepAlive();
			break;
		}
		case "replay-gate-after-job": {
			armCrashGate(
				"phone_echo:after-job",
				join(stage.gateDir ?? stage.db, "phone-echo-after-job-marker"),
				join(stage.gateDir ?? stage.db, "phone-echo-after-job-release"),
			);
			const { root } = await open();
			await root.submit({ type: "input", content: "Please check my phone" }, context);
			markerLine({ event: "submitted" });
			keepAlive();
			break;
		}
		case "report-gate": {
			armCrashGate(
				"reporter:after-submit",
				join(stage.gateDir ?? stage.db, "reporter-after-submit-marker"),
				join(stage.gateDir ?? stage.db, "reporter-after-submit-release"),
			);
			const { harness, root } = await open();
			harness.resume();
			await reportPhoneResult(
				harness,
				root.id,
				stage.jobId!,
				{ status: "ok", data: { note: "fake phone result" }, error: null },
				context,
			);
			// The reporter's task id (stored on the job) goes to a file: the harness kills
			// this process at the gate marker, possibly before stdout would flush.
			const stored = await harness.snapshot(Jobs, root.id, context);
			const reporterId = stored?.jobs[stage.jobId ?? ""]?.reporterTaskId ?? "";
			await writeFile(join(stage.gateDir ?? stage.db, "reporter-id"), String(reporterId ?? ""), "utf8");
			markerLine({ event: "report-started" });
			keepAlive();
			break;
		}
		case "report-concurrent-verify": {
			const { harness, root } = await open();
			harness.resume();
			const reports = stage.reports ?? 2;
			// The realistic retry case: two result POSTs for one job arriving at once.
			await Promise.all(
				Array.from({ length: reports }, () =>
					reportPhoneResult(
						harness,
						root.id,
						stage.jobId!,
						{ status: "ok", data: { note: "fake phone result" }, error: null },
						context,
					)),
			);
			const storedNow = await harness.snapshot(Jobs, root.id, context);
			const reporterId = storedNow?.jobs[stage.jobId ?? ""]?.reporterTaskId;
			if (reporterId !== undefined) {
				await waitForTaskTerminal(harness, reporterId as unknown as Parameters<typeof harness.waitForTask>[0], context);
			}
			await root.waitForIdle(context);
			const stored = await harness.snapshot(Jobs, root.id, context);
			const job = stored?.jobs[stage.jobId ?? ""];
			const view = await root.context(context);
			markerLine({
				event: "result",
				jobCount: Object.keys(stored?.jobs ?? {}).length,
				jobStatusAfter: job?.status,
				reporterCount: job?.reporterTaskId !== undefined ? 1 : 0,
				reporterRecorded: job?.reporterTaskId !== undefined,
				...summarize(view.entries),
			});
			await harness.close(context);
			break;
		}
		case "report-resume-verify": {
			const { harness, root } = await open();
			harness.resume();
			const receipt = await waitForTaskTerminal(
				harness,
				stage.taskId as unknown as Parameters<typeof harness.waitForTask>[0],
				context,
			);
			await root.waitForIdle(context);
			const stored = await harness.snapshot(Jobs, root.id, context);
			const view = await root.context(context);
			markerLine({
				event: "result",
				jobCount: Object.keys(stored?.jobs ?? {}).length,
				jobStatusAfter: stored?.jobs[stage.jobId ?? ""]?.status,
				outcome: receipt.state.outcome.status,
				...summarize(view.entries),
			});
			await harness.close(context);
			break;
		}
		case "resume-verify-jobs": {
			const { harness, root } = await open();
			harness.resume();
			await root.waitForIdle(context);
			const stored = await harness.snapshot(Jobs, root.id, context);
			const view = await root.context(context);
			markerLine({
				event: "result",
				jobCount: Object.keys(stored?.jobs ?? {}).length,
				jobStatuses: Object.values(stored?.jobs ?? {}).map((job) => job.status),
				...summarize(view.entries),
			});
			await harness.close(context);
			break;
		}
		case "rule-task-queue-jobs": {
			// `gate: "after"` arms the after-final-job gate; the default arms mid-open.
			const gate = stage.gate === "after" ? "rule-task:after-media_index" : "rule-task:queued-app_usage";
			armCrashGate(
				gate,
				join(stage.gateDir ?? stage.db, "rule-task-queue-marker"),
				join(stage.gateDir ?? stage.db, "rule-task-queue-release"),
			);
			const { harness, root } = await open();
			harness.resume();
			const { WeeklyCheckTask } = await import("../src/rule-tasks.ts");
			const input: import("../src/rule-tasks.ts").WeeklyCheckInput = {
				runId: stage.runId ?? "wc-crash",
				start: "open",
			};
			await root.commit(
				(tx) => tx.createTask(WeeklyCheckTask, input, { ownership: { kind: "conversation" }, background: true }),
				context,
			);
			markerLine({ event: "task-created" });
			keepAlive();
			break;
		}
		case "expire-gate": {
			// Crash window: the expiry commit (status + the notice task) has landed; the
			// notice submit has not happened yet.
			const { harness, root } = await open();
			harness.resume();
			const { queueJob, expireDueJobs } = await import("../src/jobs.ts");
			await root.commit(
				(tx) =>
					queueJob(tx, root.id, {
						dedupeKey: "exp-1",
						kind: "storage_overview",
						params: {},
						nowMs: 1000,
						waiter: undefined,
					}),
				context,
			);
			armCrashGate(
				"expiry:committed",
				join(stage.gateDir ?? stage.db, "expiry-committed-marker"),
				join(stage.gateDir ?? stage.db, "expiry-committed-release"),
			);
			const { crashPoint } = await import("../src/crash-gate.ts");
			const originalCommit = harness.commit.bind(harness);
			void originalCommit;
			// The gate sits right after the expiry commit inside expireDueJobs.
			const notices = await expireDueJobs(harness, root.id, 1000 + 7 * 24 * 60 * 60_000 + 1, context);
			if (notices.length !== 1) throw new Error(`expected 1 expiry notice, got ${notices.length}`);
			// The gate marker appears inside expireDueJobs after the expiry commit.
			markerLine({ event: "expiry-committed" });
			keepAlive();
			break;
		}
		case "expire-verify": {
			const { harness, root } = await open();
			harness.resume();
			// The reopened process delivers exactly one expiry follow-up, read from storage.
			let texts: string[] = [];
			for (let i = 0; i < 400; i++) {
				await root.waitForIdle(context);
				const view = await root.context(context);
				texts = view.entries
					.map((entry) => entry.model?.[0])
					.filter((message) => message?.role === "user")
					.map((message) => (typeof message.content === "string" ? message.content : ""));
				if (texts.some((text) => text.startsWith("[phone expired pj-"))) break;
				await new Promise((resolve) => setTimeout(resolve, 25));
			}
			markerLine({
				event: "result",
				expiryDeliveries: texts.filter((text) => text.startsWith("[phone expired pj-")).length,
			});
			await harness.close(context);
			break;
		}
		case "route-crash-server": {
			// 05.3: a generic single-route crash server. stage.route picks the effect;
			// stage.gate names the crash gate placed after the effect commit.
			const { BACKGROUND_CONTEXT: ctx } = await import("@earendil-works/chord/context");
			const { loadConfig } = await import("../src/config.ts");
			const opened = await openStewardHarness({ dbPath: join(stage.db, "s.sqlite"), fauxPlan: { hang: false } });
			opened.harness.resume();
			const { startStewardServers } = await import("../src/http.ts");
			const config = loadConfig({ stateDir: stage.db, port: 0, notify: {}, schedules: { enabled: false } });
			const servers = startStewardServers({ config, harness: opened.harness, root: opened.root });
			await new Promise<void>((resolve) => servers.tcp.listen(0, "127.0.0.1", resolve));
			const port = (servers.tcp.address() as { port: number }).port;
			const gateName = stage.gate ?? "unknown-gate";
			armCrashGate(gateName, join(stage.db, `${gateName.replaceAll(":", "-")}-marker`), join(stage.db, `${gateName.replaceAll(":", "-")}-release`));
			const code = await opened.harness.commit((tx) => import("../src/auth.ts").then((m) => m.createPairingCode(tx, Date.now())), ctx);
			markerLine({ event: "ready", port, code: code.code });
			void (async () => {
				const base = `http://127.0.0.1:${port}`;
				const pair = await fetch(`${base}/v1/pair`, {
					method: "POST",
					headers: { "content-type": "application/json" },
					body: JSON.stringify({ code: code.code, deviceName: "crash" }),
				});
				const { token } = (await pair.json()) as { token: string };
				if (stage.route === "result") {
					// Queue a job, then report it: the gate sits after the acceptance commit.
					const { queueJob } = await import("../src/jobs.ts");
					const { jobId } = await opened.harness.commit(
						(tx) => queueJob(tx, opened.root.id, { dedupeKey: "crash-result", kind: "storage_overview", params: {}, nowMs: Date.now() }),
						ctx,
					);
					markerLine({ event: "job-queued", jobId });
					await fetch(`${base}/v1/jobs/${jobId}/result`, {
						method: "POST",
						headers: { "content-type": "application/json", authorization: `Bearer ${token}` },
						body: JSON.stringify({ requestId: "result-crash-1", status: "ok", data: { a: 1 }, error: null }),
					}).catch(() => undefined);
				} else {
					await fetch(`${base}/v1/device/status`, {
						method: "POST",
						headers: { "content-type": "application/json", authorization: `Bearer ${token}` },
						body: JSON.stringify({
							requestId: "device-crash-1",
							appVersion: "0.2.0",
							permissions: { usageAccess: true, allFiles: true, mediaRead: true, manageMedia: false, batteryUnrestricted: true },
							lastWorkerRunMs: 1_234_567_890,
						}),
					}).catch(() => undefined);
				}
			})().catch(() => undefined);
			keepAlive();
			break;
		}
		case "route-retry-server": {
			const { BACKGROUND_CONTEXT: ctx } = await import("@earendil-works/chord/context");
			const { loadConfig } = await import("../src/config.ts");
			const opened = await openStewardHarness({ dbPath: join(stage.db, "s.sqlite"), fauxPlan: { hang: false } });
			opened.harness.resume();
			const { startStewardServers } = await import("../src/http.ts");
			const config = loadConfig({ stateDir: stage.db, port: 0, notify: {}, schedules: { enabled: false } });
			const servers = startStewardServers({ config, harness: opened.harness, root: opened.root });
			await new Promise<void>((resolve) => servers.tcp.listen(0, "127.0.0.1", resolve));
			const port = (servers.tcp.address() as { port: number }).port;
			const code = await opened.harness.commit((tx) => import("../src/auth.ts").then((m) => m.createPairingCode(tx, Date.now())), ctx);
			const pair = await fetch(`http://127.0.0.1:${port}/v1/pair`, {
				method: "POST",
				headers: { "content-type": "application/json" },
				body: JSON.stringify({ code: code.code, deviceName: "retry" }),
			});
			const { token } = (await pair.json()) as { token: string };
			let retry: { status: number; body: Record<string, unknown> };
			if (stage.route === "result") {
				const { Jobs } = await import("../src/jobs.ts");
				const stored = await opened.harness.snapshot(Jobs, opened.root.id, ctx);
				const jobId = Object.values(stored?.jobs ?? {})[0]?.jobId;
				const r = await fetch(`http://127.0.0.1:${port}/v1/jobs/${jobId}/result`, {
					method: "POST",
					headers: { "content-type": "application/json", authorization: `Bearer ${token}` },
					body: JSON.stringify({ requestId: "result-crash-1", status: "ok", data: { a: 1 }, error: null }),
				});
				retry = { status: r.status, body: (await r.json()) as Record<string, unknown> };
			} else {
				const r = await fetch(`http://127.0.0.1:${port}/v1/device/status`, {
					method: "POST",
					headers: { "content-type": "application/json", authorization: `Bearer ${token}` },
					body: JSON.stringify({
						requestId: "device-crash-1",
						appVersion: "0.2.0",
						permissions: { usageAccess: true, allFiles: true, mediaRead: true, manageMedia: false, batteryUnrestricted: true },
						lastWorkerRunMs: 1_234_567_890,
					}),
				});
				retry = { status: r.status, body: (await r.json()) as Record<string, unknown> };
			}
			markerLine({ event: "result", status: retry.status, duplicate: retry.body.duplicate, accepted: retry.body.accepted, error: JSON.stringify(retry.body.error ?? null) });
			await servers.close();
			await opened.close();
			break;
		}
		case "decision-crash-server": {
			// A full server in this child: pair, seed a proposal, arm the gate, POST the
			// decision (self-fetch); the gate blocks after the commit, before the response.
			const { BACKGROUND_CONTEXT: ctx } = await import("@earendil-works/chord/context");
			const { loadConfig } = await import("../src/config.ts");
			const opened = await openStewardHarness({ dbPath: join(stage.db, "s.sqlite"), fauxPlan: { hang: false } });
			opened.harness.resume();
			const { startStewardServers } = await import("../src/http.ts");
			const config = loadConfig({ stateDir: stage.db, port: 0, notify: {}, schedules: { enabled: false } });
			const servers = startStewardServers({ config, harness: opened.harness, root: opened.root });
			await new Promise<void>((resolve) => servers.tcp.listen(0, "127.0.0.1", resolve));
			const port = (servers.tcp.address() as { port: number }).port;
			armCrashGate("http:decision-committed", join(stage.db, "decision-committed-marker"), join(stage.db, "decision-committed-release"));
			const code = await opened.harness.commit((tx) => import("../src/auth.ts").then((m) => m.createPairingCode(tx, Date.now())), ctx);
			const { createProposal } = await import("../src/proposals.ts");
			const proposal = await opened.harness.commit(
				(tx) =>
					createProposal(
						tx,
						opened.root.id,
						{ source: "agent", title: "t", reason: "r", items: [{ kind: "advice", target: { text: "x" } }] },
						Date.now(),
					),
				ctx,
			);
			markerLine({ event: "ready", port, code: code.code, proposalId: proposal.proposalId });
			// Self-fetch the decision; the gate inside the handler blocks after the commit.
			void fetch(`http://127.0.0.1:${port}/v1/proposals/${proposal.proposalId}/decision`, {
				method: "POST",
				headers: { "content-type": "application/json", authorization: "Bearer x" },
			}).catch(() => undefined);
			void (async () => {
				// The decision needs a paired device: pair through the API first.
				const pair = await fetch(`http://127.0.0.1:${port}/v1/pair`, {
					method: "POST",
					headers: { "content-type": "application/json" },
					body: JSON.stringify({ code: code.code, deviceName: "crash" }),
				});
				const { token } = (await pair.json()) as { token: string };
				await fetch(`http://127.0.0.1:${port}/v1/proposals/${proposal.proposalId}/decision`, {
					method: "POST",
					headers: { "content-type": "application/json", authorization: `Bearer ${token}` },
					body: JSON.stringify({ requestId: "decision-crash-1", decision: "approve" }),
				}).catch(() => undefined);
			})().catch(() => undefined);
			keepAlive();
			break;
		}
		case "decision-retry-server": {
			const { BACKGROUND_CONTEXT: ctx } = await import("@earendil-works/chord/context");
			const { loadConfig } = await import("../src/config.ts");
			const opened = await openStewardHarness({ dbPath: join(stage.db, "s.sqlite"), fauxPlan: { hang: false } });
			opened.harness.resume();
			const { startStewardServers } = await import("../src/http.ts");
			const config = loadConfig({ stateDir: stage.db, port: 0, notify: {}, schedules: { enabled: false } });
			const servers = startStewardServers({ config, harness: opened.harness, root: opened.root });
			await new Promise<void>((resolve) => servers.tcp.listen(0, "127.0.0.1", resolve));
			const port = (servers.tcp.address() as { port: number }).port;
			// Pair a fresh device (the killed run's device is still the active one — this
			// child pairs AFTER it, so it revokes it; that is fine: the retry uses ours).
			const code = await opened.harness.commit((tx) => import("../src/auth.ts").then((m) => m.createPairingCode(tx, Date.now())), ctx);
			const pair = await fetch(`http://127.0.0.1:${port}/v1/pair`, {
				method: "POST",
				headers: { "content-type": "application/json" },
				body: JSON.stringify({ code: code.code, deviceName: "retry" }),
			});
			const { token } = (await pair.json()) as { token: string };
			const retry = await fetch(`http://127.0.0.1:${port}/v1/proposals/${stage.proposalId}/decision`, {
				method: "POST",
				headers: { "content-type": "application/json", authorization: `Bearer ${token}` },
				body: JSON.stringify({ requestId: "decision-crash-1", decision: "approve" }),
			});
			const body = (await retry.json()) as { decisionId?: string; duplicate?: boolean; error?: { code: string } };
			markerLine({ event: "result", status: retry.status, decisionId: body.decisionId, duplicate: body.duplicate, error: body.error?.code });
			await servers.close();
			await opened.close();
			break;
		}
		case "reporter-gap": {
			// item 1: SIGKILL at the named gap (before-submit or after-submit,
			// before the checkpoint). The reopen must show exactly one follow-up and a
			// terminal reporter, read from storage.
			const gate = stage.gate ?? "reporter:before-submit";
			armCrashGate(
				gate,
				join(stage.gateDir ?? stage.db, `${gate.replaceAll(":", "-")}-marker`),
				join(stage.gateDir ?? stage.db, `${gate.replaceAll(":", "-")}-release`),
			);
			const { harness, root } = await open();
			harness.resume();
			const { queueJob, reportPhoneResult } = await import("../src/jobs.ts");
			const queued = await root.commit(
				(tx) => queueJob(tx, root.id, { dedupeKey: "gap-1", kind: "storage_overview", params: {}, nowMs: 1000 }),
				context,
			);
			await reportPhoneResult(harness, root.id, queued.jobId, { status: "ok", data: { a: 1 }, error: null }, context);
			// The reporter starts (kicked by reportPhoneResult) and blocks at the gate.
			markerLine({ event: "reporter-started", jobId: queued.jobId });
			keepAlive();
			break;
		}
		case "reporter-verify": {
			const { harness, root } = await open();
			harness.resume();
			const { Jobs } = await import("../src/jobs.ts");
			let texts: string[] = [];
			let reporterTerminal = false;
			let jobStatus = "";
			// Count only after everything settles AND the count is stable for ~1.5s, so a
			// late duplicate delivery cannot hide behind an early break.
			let stable = 0;
			for (let i = 0; i < 600; i++) {
				await root.waitForIdle(context);
				const stored = await harness.snapshot(Jobs, root.id, context);
				const job = Object.values(stored?.jobs ?? {})[0];
				jobStatus = job?.status ?? "";
				const rid = job?.reporterTaskId;
				if (rid !== undefined) {
					const rec = await harness.getTask(rid as unknown as Parameters<typeof harness.getTask>[0], context);
					reporterTerminal = rec === undefined || rec.state.status === "terminal" || rec.state.status === "completing";
				}
				const view = await root.context(context);
				const next = view.entries
					.map((entry) => entry.model?.[0])
					.filter((message) => message?.role === "user")
					.map((message) => (typeof message.content === "string" ? message.content : ""));
				if (reporterTerminal && next.length === texts.length) {
					stable += 1;
					if (stable >= 60) { texts = next; break; }
				} else {
					stable = 0;
				}
				texts = next;
				await new Promise((resolve) => setTimeout(resolve, 25));
			}
			markerLine({
				event: "result",
				deliveries: texts.filter((text) => text.startsWith("[phone result pj-")).length,
				reporterTerminal,
				jobStatus,
			});
			await harness.close(context);
			break;
		}
		case "expired-gap": {
			const gate = stage.gate ?? "expired:before-submit";
			armCrashGate(
				gate,
				join(stage.gateDir ?? stage.db, `${gate.replaceAll(":", "-")}-marker`),
				join(stage.gateDir ?? stage.db, `${gate.replaceAll(":", "-")}-release`),
			);
			const { harness, root } = await open();
			harness.resume();
			const { queueJob, expireDueJobs } = await import("../src/jobs.ts");
			await root.commit(
				(tx) => queueJob(tx, root.id, { dedupeKey: "gap-exp", kind: "storage_overview", params: {}, nowMs: 1000 }),
				context,
			);
			const notices = await expireDueJobs(harness, root.id, 1000 + 7 * 24 * 60 * 60_000 + 1, context);
			if (notices.length !== 1) throw new Error(`expected 1 expiry notice, got ${notices.length}`);
			markerLine({ event: "expired-started" });
			keepAlive();
			break;
		}
		case "expired-verify": {
			const { harness, root } = await open();
			harness.resume();
			const { Jobs } = await import("../src/jobs.ts");
			let texts: string[] = [];
			let reporterTerminal = false;
			for (let i = 0; i < 400; i++) {
				await root.waitForIdle(context);
				const stored = await harness.snapshot(Jobs, root.id, context);
				const job = Object.values(stored?.jobs ?? {})[0];
				const eid = job?.expiryTaskId;
				if (eid !== undefined) {
					const rec = await harness.getTask(eid as unknown as Parameters<typeof harness.getTask>[0], context);
					reporterTerminal = rec === undefined || rec.state.status === "terminal" || rec.state.status === "completing";
				}
				const view = await root.context(context);
				texts = view.entries
					.map((entry) => entry.model?.[0])
					.filter((message) => message?.role === "user")
					.map((message) => (typeof message.content === "string" ? message.content : ""));
				if (reporterTerminal && texts.some((text) => text.startsWith("[phone expired pj-"))) break;
				await new Promise((resolve) => setTimeout(resolve, 25));
			}
			markerLine({
				event: "result",
				deliveries: texts.filter((text) => text.startsWith("[phone expired pj-")).length,
				reporterTerminal,
			});
			await harness.close(context);
			break;
		}
		case "notify-gate": {
			const { setNotifySender } = await import("../src/notify.ts");
			setNotifySender(async (title, body) => {
				await import("node:fs").then((fs) => {
					fs.appendFileSync(stage.sentLog ?? join(stage.db, "sent.log"), `${title}\t${body}\n`);
					fs.writeFileSync(join(stage.db, "notify-sent-marker"), new Date().toISOString(), "utf8");
				});
			});
			armCrashGate(
				stage.gate ?? "notify:after-send",
				join(stage.gateDir ?? stage.db, `${(stage.gate ?? "notify:after-send").replaceAll(":", "-")}-marker`),
				join(stage.gateDir ?? stage.db, `${(stage.gate ?? "notify:after-send").replaceAll(":", "-")}-release`),
			);
			const { harness, root } = await open();
			harness.resume();
			const { queueNotify } = await import("../src/notify.ts");
			await root.commit(
				(tx) =>
					queueNotify(tx, root.id, { dedupeKey: stage.dedupeKey ?? "k", category: "reminder", title: "T", body: "B" }, Date.now()),
				context,
			);
			markerLine({ event: "notify-queued" });
			keepAlive();
			break;
		}
		case "notify-verify": {
			const { setNotifySender, Notifications } = await import("../src/notify.ts");
			setNotifySender(async (title, body) => {
				await import("node:fs").then((fs) => fs.appendFileSync(stage.sentLog ?? join(stage.db, "sent.log"), `${title}\t${body}\n`));
			});
			const { harness, root } = await open();
			harness.resume();
			// Wait for the replayed notify task itself to settle (background work is outside
			// waitForIdle), then count the real sends.
			let taskId: string | undefined;
			await pollUntil(async () => {
				const stored = await harness.snapshot(Notifications, context);
				taskId = stored?.queued[stage.dedupeKey ?? "k"];
				return taskId !== undefined;
			});
			await pollUntil(async () => {
				if (taskId === undefined) return true;
				const record = await harness.getTask(taskId as unknown as Parameters<typeof harness.getTask>[0], context);
				return record === undefined || record.state.status === "terminal" || record.state.status === "completing";
			});
			const sent = existsSync(stage.sentLog ?? join(stage.db, "sent.log"))
				? readFileSync(stage.sentLog ?? join(stage.db, "sent.log"), "utf8").trim().split("\n").filter((line) => line !== "").length
				: 0;
			markerLine({ event: "result", sends: sent });
			await harness.close(context);
			break;
		}
		case "photo-window-batch-gate": {
			// `gate: "after"` arms the after-batch-commit gate; the default arms pre-batch.
			const pwGate = stage.gate === "after" ? "photo-window:after-batch" : "photo-window:batch";
			armCrashGate(
				pwGate,
				join(stage.gateDir ?? stage.db, stage.gate === "after" ? "photo-window-after-batch-marker" : "photo-window-batch-marker"),
				join(stage.gateDir ?? stage.db, stage.gate === "after" ? "photo-window-after-batch-release" : "photo-window-batch-release"),
			);
			const { harness, root } = await open();
			harness.resume();
			// Enable the rule so the open phase queues its job.
			const { RulesDoc } = await import("../src/documents.ts");
			await root.commit(
				async (tx) => {
					const rules = await tx.doc(RulesDoc, root.id);
					rules.photoWindow.enabled = true;
					rules.photoWindow.reminderDays = 90;
					rules.photoWindow.cutoffDays = 120;
					return undefined;
				},
				context,
			);
			const ruleTasks = await import("../src/rule-tasks.ts");
			// Pre-seed the reminder ledger: m-1 was reminded in an EARLIER run, so the batch
			// step (after the crash) has an eligible item.
			await root.commit(
				async (tx) => {
					const photo = await tx.doc(ruleTasks.PhotoWindowDoc, root.id);
					photo.reminded["m-1"] = { runId: "photo-window:earlier", atMs: Date.now() - 31 * 86_400_000 };
					return undefined;
				},
				context,
			);
			const input: import("../src/rule-tasks.ts").PhotoWindowInput = { runId: stage.runId ?? "pw-crash", start: "open" };
			const PhotoWindowTask = ruleTasks.PhotoWindowTask;
			await root.commit(
				(tx) => tx.createTask(PhotoWindowTask, input, { ownership: { kind: "conversation" }, background: true }),
				context,
			);
			// Answer the media_index job as the phone would (rule candidates incl. a 121-day item).
			const { Jobs, reportPhoneResult } = await import("../src/jobs.ts");
			let jobId: string | undefined;
			await pollUntil(async () => {
				const stored = await harness.snapshot(Jobs, root.id, context);
				const job = Object.values(stored?.jobs ?? {}).find((candidate) => candidate.kind === "media_index");
				if (job !== undefined) {
					jobId = job.jobId;
					return true;
				}
				return false;
			});
			if (jobId === undefined) throw new Error("media_index job never queued");
			const now = Date.now();
			const items = [
				{ mediaId: "m-1", relativePath: "DCIM/Camera/", displayName: "IMG_0001.jpg", mimeType: "image/jpeg", sizeBytes: 4_000_000, dateTakenMs: now - 121 * 86_400_000, dateAddedMs: now - 121 * 86_400_000, isFavourite: false, inKeep: false },
				{ mediaId: "m-2", relativePath: "DCIM/Camera/", displayName: "IMG_0002.jpg", mimeType: "image/jpeg", sizeBytes: 3_000_000, dateTakenMs: now - 95 * 86_400_000, dateAddedMs: now - 95 * 86_400_000, isFavourite: false, inKeep: false },
			];
			await reportPhoneResult(harness, root.id, jobId, { status: "ok", data: { scope: [], items }, error: null }, context);
			markerLine({ event: "media-answered", jobId });
			keepAlive();
			break;
		}
		case "photo-window-batch-verify": {
			const { harness, root } = await open();
			harness.resume();
			const { Proposals } = await import("../src/proposals.ts");
			let proposals: Array<{ source: string }> = [];
			await pollUntil(async () => {
				const stored = await harness.snapshot(Proposals, root.id, context);
				proposals = Object.values(stored?.proposals ?? {});
				return proposals.length > 0;
			});
			markerLine({
				event: "result",
				proposals: proposals.length,
				proposalSource: proposals[0]?.source ?? "",
			});
			await harness.close(context);
			break;
		}
		case "weekly-settle-gate": {
			// S7: `gate: "after"` arms the gate after the settle commit (snapshot + run marker +
			// server-built cache batch); the default arms it before.
			const wsGate = stage.gate === "after" ? "weekly-check:after-settle-commit" : "weekly-check:before-settle-commit";
			armCrashGate(wsGate, join(stage.db, "weekly-settle-marker"), join(stage.db, "weekly-settle-release"));
			const { harness, root } = await open();
			harness.resume();
			const { WeeklyCheckTask } = await import("../src/rule-tasks.ts");
			const input: import("../src/rule-tasks.ts").WeeklyCheckInput = { runId: stage.runId ?? "wc-settle", start: "open" };
			await root.commit(
				(tx) => tx.createTask(WeeklyCheckTask, input, { ownership: { kind: "conversation" }, background: true }),
				context,
			);
			let jobIds: Array<{ jobId: string; kind: string }> = [];
			await pollUntil(async () => {
				const stored = await harness.snapshot(Jobs, root.id, context);
				jobIds = Object.values(stored?.jobs ?? {}).map((job) => ({ jobId: job.jobId, kind: job.kind }));
				return jobIds.length >= 3;
			});
			if (jobIds.length < 3) throw new Error("weekly check never queued its three jobs");
			const bigApp = { packageName: "com.big", label: "Big", appBytes: 1, dataBytes: 1, cacheBytes: 500 * 1024 * 1024, isSystem: false };
			for (const job of jobIds) {
				const data: import("@earendil-works/pi-durable").JsonObject = job.kind === "storage_overview" ? { totalBytes: 100, freeBytes: 50, apps: [bigApp], categories: {} } : { scope: [] };
				await reportPhoneResult(harness, root.id, job.jobId, { status: "ok", data, error: null }, context);
			}
			markerLine({ event: "answered" });
			keepAlive();
			break;
		}
		case "weekly-settle-verify": {
			const { harness, root } = await open();
			harness.resume();
			const { Proposals } = await import("../src/proposals.ts");
			const { Snapshots } = await import("../src/documents.ts");
			const readSchedules = async (): Promise<string[]> => {
				const view = await root.context(context);
				return view.entries
					.map((entry) => entry.model?.[0])
					.filter((message) => message?.role === "user")
					.map((message) => (typeof message.content === "string" ? message.content : ""))
					.filter((text) => text.startsWith("[schedule weekly_check"));
			};
			let schedules: string[] = [];
			await pollUntil(async () => {
				schedules = await readSchedules();
				return schedules.length > 0;
			});
			await root.waitForIdle(context);
			const proposals = Object.values((await harness.snapshot(Proposals, root.id, context))?.proposals ?? {});
			const snaps = await harness.snapshot(Snapshots, root.id, context);
			markerLine({
				event: "result",
				snapshots: snaps?.snapshots.length ?? 0,
				builtProposals: proposals.filter((proposal) => proposal.builtBy === "weekly_check").length,
				openProposals: proposals.filter((proposal) => proposal.status === "open").length,
				schedules: schedules.length,
				scheduleMentionsBatch: proposals.length === 1 && schedules[0]?.includes(proposals[0]!.proposalId) === true,
			});
			await harness.close(context);
			break;
		}
		case "rule-task-jobs-verify": {
			const { harness, root } = await open();
			harness.resume();
			// The weekly task is a background task: poll for its three jobs (waitForIdle does
			// not cover background work).
			let jobs: Array<{ kind: string; status: string }> = [];
			await pollUntil(async () => {
				const stored = await harness.snapshot(Jobs, root.id, context);
				jobs = Object.values(stored?.jobs ?? {});
				return jobs.length >= 3;
			});
			await root.waitForIdle(context);
			markerLine({
				event: "result",
				jobCount: jobs.length,
				jobKinds: jobs.map((job) => job.kind).sort(),
				jobStatuses: jobs.map((job) => job.status).sort(),
			});
			await harness.close(context);
			break;
		}
	}
} catch (error) {
	console.error(String(error));
	process.exit(1);
}
