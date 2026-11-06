import { describe, expect, it } from "vitest";
import type { ConversationId, Harness, Report, ReportTask } from "@earendil-works/pi-durable";
import { createHarnessReporter, HARNESS_REPORT_KINDS, type NotifyInput } from "../src/harness-reports.ts";

const task = (kind = "steward.weekly-check"): ReportTask => ({
	id: "t-1" as unknown as ReportTask["id"],
	kind,
	conversationId: "c-1" as unknown as ReportTask["conversationId"],
});

function harness(options: { notifyFails?: boolean; sendFails?: boolean } = {}) {
	const sent: NotifyInput[] = [];
	const direct: Array<{ title: string; body: string }> = [];
	const logs: string[] = [];
	const report = createHarnessReporter({
		log: (line) => logs.push(line),
		notify: async (input) => {
			if (options.notifyFails === true) throw new Error("notify exploded");
			sent.push(input);
			return true;
		},
		send: async (title, body) => {
			if (options.sendFails === true) throw new Error("send exploded");
			direct.push({ title, body });
		},
	});
	return { report, sent, direct, logs };
}

/**
 * Pi Durable 1.5.0 reports a faulted task, an interrupted task, a failed migration and a failed
 * Session through `onReport(report)`: one `Report`, tagged by `kind`, with the error inside. The
 * steward turns the four into one owner ping each through the existing durable notify path
 * (which already refuses a second send for a dedupe key), and never lets a report throw into the
 * harness.
 */
describe("harness reports → owner notification", () => {
	it("covers exactly the four kinds the steward acts on", () => {
		expect([...HARNESS_REPORT_KINDS].sort()).toEqual(["migration_failed", "storage_failed", "task_faulted", "task_interrupted"]);
	});

	it("pings the owner once for a faulted task, naming its kind and not the stack", () => {
		const { report, sent } = harness();
		const error = new Error("boom: sqlite is locked");
		error.stack = "Error: boom: sqlite is locked\n    at somewhere (file.ts:1:1)";
		report({ kind: "task_faulted", task: task(), reason: "threw", error });
		expect(sent).toHaveLength(1);
		expect(sent[0]?.category).toBe("crash");
		expect(sent[0]?.body).toContain("steward.weekly-check");
		expect(sent[0]?.body).toContain("boom: sqlite is locked");
		expect(sent[0]?.body).not.toContain("file.ts");
		expect(sent[0]?.dedupeKey).toBe("harness:task_faulted:steward.weekly-check:boom: sqlite is locked");
	});

	it("pings once when crashes ended a task: repeated cut-off restarts, or a run stopped while answering", () => {
		// 1.5.0's new kind: the work stopped for good, so the owner must hear about it — parked
		// tasks no longer exist and nothing silently waits for a retry.
		const { report, sent } = harness();
		report({ kind: "task_interrupted", task: task("steward.batch"), reason: "restart_limit", restarts: 5, error: new Error("cut off") });
		report({ kind: "task_interrupted", task: task("pi.generation"), reason: "interrupted", restarts: 2, error: new Error("host stopped") });
		expect(sent.map((n) => n.dedupeKey)).toEqual([
			"harness:task_interrupted:steward.batch:cut off",
			"harness:task_interrupted:pi.generation:host stopped",
		]);
		expect(sent[0]?.body).toContain("steward.batch");
		expect(sent[0]?.body).toMatch(/repeated|repeatedly|cut off/);
		expect(sent[0]?.body).toContain("cut off");
		expect(sent[0]?.body).not.toContain("retry");
	});

	it("pings for a failed migration through the durable queue", () => {
		const { report, sent, direct } = harness();
		report({ kind: "migration_failed", task: task("steward.jobs"), error: new Error("cannot migrate") });
		expect(sent.map((n) => n.dedupeKey)).toEqual(["harness:migration_failed:steward.jobs:cannot migrate"]);
		expect(direct).toHaveLength(0);
	});

	it("sends a failed Session straight to the process notify path, not through the dead queue", async () => {
		// Review F2: a failed Session closes itself and rejects every commit, so queueing on that
		// harness can never work. This goes to the installed sender (notify.sh in production).
		const { report, sent, direct } = harness();
		report({ kind: "storage_failed", error: new Error("disk I/O error") });
		await new Promise((resolve) => setTimeout(resolve, 0));
		expect(sent).toHaveLength(0);
		expect(direct).toHaveLength(1);
		expect(direct[0]?.title).toBe("Phone Steward harness failed");
		expect(direct[0]?.body).toContain("disk I/O error");
	});

	it("logs, and never throws, when the direct send for a failed Session fails", async () => {
		const { report, logs } = harness({ sendFails: true });
		expect(() => report({ kind: "storage_failed", error: new Error("disk I/O error") })).not.toThrow();
		await new Promise((resolve) => setTimeout(resolve, 0));
		expect(logs.some((l) => l.includes("could not send the owner notification"))).toBe(true);
	});

	it("logs the other report kinds without pinging the owner", () => {
		const { report, sent, logs } = harness();
		const others: Report[] = [
			{ kind: "extension_error", extension: "steward-web", error: new Error("x extension_error") },
			{ kind: "listener_error", error: new Error("x listener_error") },
			{ kind: "task_outdated", task: task(), reason: "task_too_old", error: new Error("x task_outdated") },
			{ kind: "host_error", callback: "now", error: new Error("x host_error") },
			{ kind: "task_error", task: task(), error: new Error("x task_error") },
		];
		for (const info of others) report(info);
		expect(sent).toHaveLength(0);
		expect(logs.filter((l) => l.includes("[steward] harness report"))).toHaveLength(5);
	});

	it("de-duplicates the same report in this process", () => {
		const { report, sent } = harness();
		const report_: Report = { kind: "task_faulted", task: task(), reason: "threw", error: new Error("same") };
		report(report_);
		report(report_);
		report({ kind: "task_faulted", task: task(), reason: "restart_limit", error: new Error("same") });
		expect(sent).toHaveLength(1);
	});

	it("never throws, even when the notify path does", async () => {
		const { report, logs } = harness({ notifyFails: true });
		expect(() => report({ kind: "task_faulted", task: task(), reason: "threw", error: new Error("boom") })).not.toThrow();
		// The rejection is swallowed on its own turn, not left unhandled.
		await new Promise((resolve) => setTimeout(resolve, 0));
		expect(logs.some((l) => l.includes("could not queue the owner notification"))).toBe(true);
	});

	it("bounds the message it copies into a ping", () => {
		const { report, sent } = harness();
		report({ kind: "task_faulted", task: task(), reason: "threw", error: new Error("x".repeat(5_000)) });
		expect(sent[0]!.body.length).toBeLessThanOrEqual(600);
	});

	it("holds a report raised while the harness is still opening, and delivers it on flush", async () => {
		// Pi Durable reports a failed migration INSIDE Harness.open, before the steward has a
		// conversation to queue into: dropping it would mean the owner never hears about a task
		// that cannot ever run.
		const sent: NotifyInput[] = [];
		const logs: string[] = [];
		let open = false;
		const report = createHarnessReporter({
			log: (line) => logs.push(line),
			ready: () => open,
			notify: async (input) => {
				sent.push(input);
				return true;
			},
		});
		report({ kind: "migration_failed", task: task(), error: new Error("cannot migrate during open") });
		expect(sent).toHaveLength(0);
		expect(logs.some((l) => l.includes("held until the harness is open"))).toBe(true);

		open = true;
		await report.flush();
		expect(sent).toHaveLength(1);
		expect(sent[0]?.dedupeKey).toBe("harness:migration_failed:steward.weekly-check:cannot migrate during open");

		// A flush with nothing held does nothing, and the same report is never pinged twice.
		await report.flush();
		report({ kind: "migration_failed", task: task(), error: new Error("cannot migrate during open") });
		await new Promise((resolve) => setTimeout(resolve, 0));
		expect(sent).toHaveLength(1);
	});

	it("keeps only the newest held reports, so a broken open cannot grow memory", async () => {
		const sent: NotifyInput[] = [];
		const report = createHarnessReporter({
			log: () => undefined,
			ready: () => false,
			notify: async (input) => {
				sent.push(input);
				return true;
			},
		});
		for (let i = 0; i < 40; i++) report({ kind: "task_faulted", task: task(), reason: "threw", error: new Error(`fault ${i}`) });
		await report.flush();
		expect(sent).toHaveLength(20);
	});
});

/**
 * The wiring itself, proved end to end: a task whose phase throws reaches a real Harness
 * (`onReport` → durable notify path → `Notifications.queued`), and the ping is queued once
 * even when the report arrives twice.
 */
describe("the steward's report wiring on a real harness", () => {
	it("queues one owner ping when a task faults", async () => {
		const { BACKGROUND_CONTEXT } = await import("@earendil-works/chord/context");
		const { Harness, MemoryStorage, defineExtension, defineTask } = await import("@earendil-works/pi-durable");
		const { createModels } = await import("@earendil-works/pi-ai/models");
		const { stewardRegistry } = await import("../src/steward.ts");
		const { Notifications } = await import("../src/notify.ts");
		const { durableReportNotifier } = await import("../src/harness-reports.ts");

		const FaultyTask = defineTask<null, { phase: "run" }, null>({
			name: "test.faulty",
			version: 1,
			initial: () => ({ phase: "run" }),
			phases: {
				run: async () => {
					throw new Error("phase exploded");
				},
			},
			abort: async (_task, runtime, context) => {
				await runtime.commit(() => ({ status: "terminal", outcome: { status: "aborted" } }), context);
			},
		});

		const registry = stewardRegistry();
		registry.install(defineExtension({ name: "test-faulty", tasks: [FaultyTask] }));
		const target: { harness?: import("@earendil-works/pi-durable").Harness; conversationId?: never } = {};
		const logs: string[] = [];
		const onReport = createHarnessReporter({
			log: (line) => logs.push(line),
			notify: durableReportNotifier(() => (target.harness === undefined ? undefined : { harness: target.harness, conversationId: target.conversationId as never }), BACKGROUND_CONTEXT),
		});
		const harness = await Harness.open(new MemoryStorage(), { models: createModels(), registry, onReport }, BACKGROUND_CONTEXT);
		const root = await harness.root(BACKGROUND_CONTEXT);
		target.harness = harness;
		target.conversationId = root.id as never;
		harness.resume();

		await harness.commit(
			(tx) =>
				tx.createTask(FaultyTask, null, {
					ownership: { kind: "conversation" },
					conversationId: root.id,
					background: true,
				}),
			BACKGROUND_CONTEXT,
		);

		const dedupeKey = "harness:task_faulted:test.faulty:phase exploded";
		let queued: string | undefined;
		for (let i = 0; i < 200; i++) {
			const notifications = await harness.snapshot(Notifications, BACKGROUND_CONTEXT);
			queued = notifications?.queued[dedupeKey];
			if (queued !== undefined) break;
			await new Promise((resolve) => setTimeout(resolve, 25));
		}
		await harness.close(BACKGROUND_CONTEXT);
		expect(logs.some((l) => l.includes("task_faulted"))).toBe(true);
		expect(queued).toBeDefined();
	});
});

/**
 * Review F1 (re-targeted for 1.5.0): Pi Durable decides and reports a restart-limited task INSIDE
 * `Harness.open` — before the steward has a conversation to queue into — and reports it exactly
 * once. 1.5.0 no longer parks such a task: a stored running task one restart below
 * `settings.restartLimit` ends instead, and a task of an extension's kind ends `faulted` with
 * detail `{ reason: "restart_limit" }`, reported `task_faulted` with reason `restart_limit`. The
 * report must be held during open and delivered by `flush()` once the target exists — the owner
 * otherwise never hears that the work stopped.
 */
describe("a restart-limited task during Harness.open reaches the owner", () => {
	it("holds the faulted report raised during open and queues one ping once the harness is open", async () => {
		const { BACKGROUND_CONTEXT } = await import("@earendil-works/chord/context");
		const { Harness, MemoryStorage, createRegistry, defineExtension, defineTask } = await import("@earendil-works/pi-durable");
		const { commitWrites } = await import("@earendil-works/pi-durable/testing");
		const { createModels } = await import("@earendil-works/pi-ai/models");
		const { durableReportNotifier, createHarnessReporter } = await import("../src/harness-reports.ts");
		const { NotifyTask, Notifications, queueNotify } = await import("../src/notify.ts");

		// A task definition that never actually completes: the stored record below is what counts.
		const SleepyTask = defineTask<null, { phase: "wait" }, null>({
			name: "test.sleepy",
			version: 1,
			initial: () => ({ phase: "wait" }),
			phases: {
				wait: async (_task, runtime, context) => {
					await runtime.commit(() => undefined, context);
				},
			},
			abort: async (_task, runtime, context) => {
				await runtime.commit(() => ({ status: "terminal", outcome: { status: "aborted" } }), context);
			},
		});
		const registry = createRegistry();
		registry.install(defineExtension({ name: "test-sleepy", tasks: [SleepyTask, NotifyTask] }));
		const storage = new MemoryStorage();

		// Store a task the way a crashed process leaves it: running, restarts one below the limit.
		const first = await Harness.open(storage, { models: createModels(), registry }, BACKGROUND_CONTEXT);
		const conversation = await first.root(BACKGROUND_CONTEXT);
		const created = await first.commit(
			(tx) =>
				tx.createTask(SleepyTask, null, {
					ownership: { kind: "conversation" },
					conversationId: conversation.id,
					background: true,
				}),
			BACKGROUND_CONTEXT,
		);
		const record = await first.getTask(created, BACKGROUND_CONTEXT);
		expect(record).toBeDefined();
		// `first` is left open on purpose: closing it also closes the storage, and a close writes the
		// tasks it stopped back as pending, uncounted — neither is the crash state under test.
		// 1.5.0's `Storage.commit()` takes batches; a test that seeds a storage uses commitWrites.
		await commitWrites(
			storage,
			[{ type: "task", value: { ...record!, state: { status: "running", checkpoint: { phase: "wait" } }, restarts: 4 } as never }],
			BACKGROUND_CONTEXT,
		);

		// The next open finds it running; once the harness resumes, the scheduler counts the fifth
		// restart and ends it: a task of an extension's kind ends `faulted` with reason
		// `restart_limit`, reported as `task_faulted`. (1.5.0 makes that decision in the scheduler,
		// not synchronously inside open, so the report arrives after resume())
		let target: { harness: Harness; conversationId: ConversationId } | undefined;
		const logs: string[] = [];
		const reports = createHarnessReporter({
			log: (line) => logs.push(line),
			ready: () => target !== undefined,
			notify: durableReportNotifier(() => target, BACKGROUND_CONTEXT),
		});
		// Capture the reports themselves, so the reason the task ended is asserted, not inferred.
		const seen: Array<import("@earendil-works/pi-durable").Report> = [];
		const second = await Harness.open(
			storage,
			{
				models: createModels(),
				registry,
				settings: { restartLimit: 5 },
				onReport: (incoming: import("@earendil-works/pi-durable").Report) => {
					seen.push(incoming);
					reports(incoming);
				},
			},
			BACKGROUND_CONTEXT,
		);
		// The steward's own order: the root conversation and the report target exist before resume,
		// so this report takes the durable path directly rather than the held one (that path is
		// covered above by a report raised inside open).
		const root = await second.root(BACKGROUND_CONTEXT);
		target = { harness: second, conversationId: root.id };
		await reports.flush();
		second.resume();

		let queued: string | undefined;
		for (let i = 0; i < 200; i++) {
			const notifications = await second.snapshot(Notifications, BACKGROUND_CONTEXT);
			queued = Object.keys(notifications?.queued ?? {}).find((key) => key.startsWith("harness:task_faulted:test.sleepy:"));
			if (queued !== undefined) break;
			await new Promise((resolve) => setTimeout(resolve, 25));
		}
		expect(logs.some((line) => line.includes("task_faulted") && line.includes("test.sleepy"))).toBe(true);
		const ended = await second.getTask(created, BACKGROUND_CONTEXT);
		await second.close(BACKGROUND_CONTEXT);
		expect(queued).toBeDefined();
		// The task ended rather than parking (1.4.0 would have left it blocked), and the report says
		// why: a task of an extension's kind ends `faulted` with reason `restart_limit`.
		expect(ended?.state.status === "terminal" || ended?.state.status === "completing").toBe(true);
		expect(seen.map((report) => ({ kind: report.kind, reason: "reason" in report ? report.reason : undefined, task: ("task" in report ? report.task?.kind : undefined) }))).toContainEqual({
			kind: "task_faulted",
			reason: "restart_limit",
			task: "test.sleepy",
		});
	});
});
