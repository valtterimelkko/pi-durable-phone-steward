import { describe, expect, it } from "vitest";
import { BACKGROUND_CONTEXT } from "@earendil-works/chord/context";
import { openTestHarness, type TestHarness } from "./helpers.ts";
import type { JsonObject } from "@earendil-works/pi-durable";
import { Jobs, jobIdForDedupeKey, reportPhoneResult } from "../src/jobs.ts";
import { WeeklyCheckTask, type WeeklyCheckInput } from "../src/rule-tasks.ts";
import { Snapshots, recordDeviceStatus } from "../src/documents.ts";
import { Proposals, createProposal, type Proposal } from "../src/proposals.ts";
import { Runs } from "../src/run-tracking.ts";
import { stewardDate } from "../src/time.ts";

const context = BACKGROUND_CONTEXT;

async function openWeeklyRun(t: TestHarness): Promise<void> {
	const openInput: WeeklyCheckInput = { runId: "wc-e2e", start: "open" };
	await t.root.commit(
		(tx) => tx.createTask(WeeklyCheckTask, openInput, { ownership: { kind: "conversation" }, background: true }),
		context,
	);
	// The open phase queues the three jobs; wait for them.
	for (let i = 0; i < 200; i++) {
		const stored = await t.harness.snapshot(Jobs, t.root.id, context);
		if (Object.keys(stored?.jobs ?? {}).length >= 3) return;
		await new Promise((resolve) => setTimeout(resolve, 25));
	}
	throw new Error("weekly check open never queued its three jobs");
}

describe("WeeklyCheckTask", () => {
	it("queues storage_overview, app_usage and media_index with waiters", async () => {
		const t = await openTestHarness();
		try {
			await openWeeklyRun(t);
			const stored = await t.harness.snapshot(Jobs, t.root.id, context);
			const jobs = Object.values(stored?.jobs ?? {});
			expect(jobs.map((job) => job.kind).sort()).toEqual(["app_usage", "media_index", "storage_overview"]);
			for (const job of jobs) {
				expect(job.waiter).toEqual({ run: "weekly-check", runId: "wc-e2e" });
				expect(job.params).toEqual(job.kind === "media_index" ? { mode: "summary" } : {});
			}
		} finally {
			await t.close();
		}
	});

	it("stores the snapshot and submits the dated schedule input only when all three are answered", async () => {
		const t = await openTestHarness();
		try {
			await openWeeklyRun(t);
			const stored = await t.harness.snapshot(Jobs, t.root.id, context);
			const jobs = Object.values(stored?.jobs ?? {});
			// Answer only two: nothing may happen yet.
			for (const job of jobs.filter((job) => job.kind !== "media_index")) {
				await reportPhoneResult(t.harness, t.root.id, job.jobId, { status: "ok", data: { a: 1 }, error: null }, context);
			}
			await t.root.waitForIdle(context);
			const snaps0 = await t.harness.snapshot(Snapshots, t.root.id, context);
			expect(snaps0?.snapshots.length ?? 0).toBe(0);

			// The third result completes the run.
			const media = jobs.find((job) => job.kind === "media_index")!;
			await reportPhoneResult(t.harness, t.root.id, media.jobId, { status: "ok", data: { scope: [] }, error: null }, context);
			const stored2 = await t.harness.snapshot(Jobs, t.root.id, context);
			const rid = stored2?.jobs[media.jobId]?.reporterTaskId;
			if (rid !== undefined) await t.waitTaskTerminal(rid);
			// The settle continuation is a background task: poll for its effect.
			let snaps: { snapshots: unknown[] } | undefined;
			for (let i = 0; i < 200; i++) {
				snaps = await t.harness.snapshot(Snapshots, t.root.id, context);
				if ((snaps?.snapshots.length ?? 0) >= 1) break;
				await new Promise((resolve) => setTimeout(resolve, 25));
			}
			await t.root.waitForIdle(context);
			expect(snaps?.snapshots.length).toBe(1);
			const date = stewardDate(Date.now());
			const view = await t.root.context(context);
			const schedule = view.entries
				.map((entry) => entry.model?.[0])
				.filter((message) => message?.role === "user")
				.map((message) => (typeof message.content === "string" ? message.content : ""))
				.find((text) => text.startsWith(`[schedule weekly_check ${date}]`));
			expect(schedule).toBeDefined();
			const runs = await t.harness.snapshot(Runs, t.root.id, context);
			// The weekly run opened and closed again.
			expect(runs === undefined || runs.open[`weekly-check:${date}`] === undefined).toBe(true);
		} finally {
			await t.close();
		}
	});

	it("the three jobs of one run share the run's waiter and distinct dedupe keys", async () => {
		const t = await openTestHarness();
		try {
			await openWeeklyRun(t);
			const stored = await t.harness.snapshot(Jobs, t.root.id, context);
			const jobs = Object.values(stored?.jobs ?? {});
			const ids = new Set(jobs.map((job) => job.jobId));
			expect(ids.size).toBe(3);
			for (const job of jobs) {
				expect(job.jobId).toBe(jobIdForDedupeKey(job.dedupeKey));
			}
		} finally {
			await t.close();
		}
	});

	describe("S7: the server builds the cache batch", () => {
		const MIB = 1024 * 1024;
		const bigApps = [
			{ packageName: "com.a", label: "Alpha", appBytes: 1, dataBytes: 1, cacheBytes: 2000 * MIB, isSystem: false },
			{ packageName: "com.b", label: "Beta", appBytes: 1, dataBytes: 1, cacheBytes: 300 * MIB, isSystem: false },
			{ packageName: "com.c", label: "Gamma", appBytes: 1, dataBytes: 1, cacheBytes: 1 * MIB, isSystem: false },
		];

		async function answerAll(t: TestHarness, apps: unknown[]): Promise<void> {
			const stored = await t.harness.snapshot(Jobs, t.root.id, context);
			const jobs = Object.values(stored?.jobs ?? {});
			for (const job of jobs) {
				const data: JsonObject = job.kind === "storage_overview" ? { totalBytes: 100, freeBytes: 50, apps: apps as never, categories: {} } : { scope: [] };
				await reportPhoneResult(t.harness, t.root.id, job.jobId, { status: "ok", data, error: null }, context);
			}
		}

		async function scheduleText(t: TestHarness): Promise<string> {
			const date = stewardDate(Date.now());
			for (let i = 0; i < 400; i++) {
				const view = await t.root.context(context);
				const found = view.entries
					.map((entry) => entry.model?.[0])
					.filter((message) => message?.role === "user")
					.map((message) => (typeof message.content === "string" ? message.content : ""))
					.find((text) => text.startsWith(`[schedule weekly_check ${date}]`));
				if (found !== undefined) return found;
				await new Promise((resolve) => setTimeout(resolve, 25));
			}
			throw new Error("no weekly_check message arrived");
		}

		async function proposals(t: TestHarness): Promise<Proposal[]> {
			const stored = await t.harness.snapshot(Proposals, t.root.id, context);
			return Object.values(stored?.proposals ?? {}).sort((a, b) => a.createdAtMs - b.createdAtMs);
		}

		it("creates ONE agent clear_all_caches proposal from the fresh snapshot, supersedes last week's, and tells the steward", async () => {
			const t = await openTestHarness();
			try {
				const old = await t.root.commit(
					(tx) =>
						createProposal(tx, t.root.id, {
							source: "agent",
							title: "Last week's caches",
							reason: "old",
							items: [{ kind: "clear_all_caches", target: { apps: [{ packageName: "com.a", label: "Alpha", cacheBytes: 1 }] }, bytesEstimate: 1 }],
						}, Date.now() - 7 * 86_400_000),
					context,
				);
				await openWeeklyRun(t);
				await answerAll(t, bigApps);
				const text = await scheduleText(t);
				await t.root.waitForIdle(context);
				const all = await proposals(t);
				const built = all.filter((proposal) => proposal.builtBy === "weekly_check");
				expect(built).toHaveLength(1);
				const batch = built[0]!;
				expect(batch.source).toBe("agent");
				expect(batch.status).toBe("open");
				expect(batch.title).toBe("Clear app caches (~2.4 GB)");
				expect(batch.bytesEstimate).toBe(2300 * MIB);
				expect(batch.items).toHaveLength(1);
				expect(batch.items[0]!.kind).toBe("clear_all_caches");
				expect(batch.items[0]!.target).toEqual({
					apps: [
						{ packageName: "com.a", label: "Alpha", cacheBytes: 2000 * MIB },
						{ packageName: "com.b", label: "Beta", cacheBytes: 300 * MIB },
					],
				});
				expect(all.find((proposal) => proposal.proposalId === old.proposalId)?.status).toBe("superseded");
				expect(all.find((proposal) => proposal.proposalId === old.proposalId)?.supersededBy).toBe(batch.proposalId);
				// The steward is told the server's truth: the new batch, nothing else.
				expect(text).toContain(batch.proposalId);
				expect(text).toMatch(/just created by the server/i);
				expect(text).not.toContain(old.proposalId);
				expect(text).toMatch(/no other proposal is waiting for the owner/i);
			} finally {
				await t.close();
			}
		});

		it("creates no proposal when the caches total under 100 MiB, and says so", async () => {
			const t = await openTestHarness();
			try {
				await openWeeklyRun(t);
				await answerAll(t, [{ packageName: "com.a", label: "Alpha", appBytes: 1, dataBytes: 1, cacheBytes: 50 * MIB, isSystem: false }]);
				const text = await scheduleText(t);
				await t.root.waitForIdle(context);
				expect(await proposals(t)).toHaveLength(0);
				expect(text).toMatch(/no proposal is waiting for the owner/i);
				expect(text).toMatch(/built no cache batch/i);
			} finally {
				await t.close();
			}
		});

		it("A10.2: with Shizuku ready in the last device status the batch is one trim_caches item (no Android dialog)", async () => {
			const t = await openTestHarness();
			try {
				await t.commit((tx) =>
					recordDeviceStatus(tx, t.root.id, {
						deviceId: "dev_x",
						permissions: { usageAccess: true, allFiles: true, mediaRead: true, manageMedia: false, batteryUnrestricted: true, shizuku: "ready" },
						nowMs: Date.now(),
					}),
				);
				await openWeeklyRun(t);
				await answerAll(t, bigApps);
				await scheduleText(t);
				await t.root.waitForIdle(context);
				const built = (await proposals(t)).filter((proposal) => proposal.builtBy === "weekly_check");
				expect(built).toHaveLength(1);
				expect(built[0]!.items).toHaveLength(1);
				expect(built[0]!.items[0]!.kind).toBe("trim_caches");
				expect(built[0]!.items[0]!.target).toEqual({
					apps: [
						{ packageName: "com.a", label: "Alpha", cacheBytes: 2000 * MIB },
						{ packageName: "com.b", label: "Beta", cacheBytes: 300 * MIB },
					],
				});
				expect(built[0]!.reason).toMatch(/no Android dialog/i);
			} finally {
				await t.close();
			}
		});

		it("A10.2: Shizuku not running or no permission keeps the clear_all_caches batch", async () => {
			const t = await openTestHarness();
			try {
				await t.commit((tx) =>
					recordDeviceStatus(tx, t.root.id, {
						deviceId: "dev_x",
						permissions: { usageAccess: true, allFiles: true, mediaRead: true, manageMedia: false, batteryUnrestricted: true, shizuku: "not_running" },
						nowMs: Date.now(),
					}),
				);
				await openWeeklyRun(t);
				await answerAll(t, bigApps);
				await scheduleText(t);
				await t.root.waitForIdle(context);
				const built = (await proposals(t)).filter((proposal) => proposal.builtBy === "weekly_check");
				expect(built[0]!.items[0]!.kind).toBe("clear_all_caches");
			} finally {
				await t.close();
			}
		});

		it("a second settle for the same run (replay) does not create a second proposal", async () => {
			const t = await openTestHarness();
			try {
				await openWeeklyRun(t);
				await answerAll(t, bigApps);
				await scheduleText(t);
				await t.root.waitForIdle(context);
				expect((await proposals(t)).filter((proposal) => proposal.builtBy === "weekly_check")).toHaveLength(1);
				const settleInput: WeeklyCheckInput = { runId: "wc-e2e", start: "settle" };
				const replay = await t.root.commit(
					(tx) => tx.createTask(WeeklyCheckTask, settleInput, { ownership: { kind: "conversation" }, background: true }),
					context,
				);
				await t.waitTaskTerminal(replay);
				const after = await proposals(t);
				expect(after.filter((proposal) => proposal.builtBy === "weekly_check")).toHaveLength(1);
				expect(after.filter((proposal) => proposal.status === "open")).toHaveLength(1);
			} finally {
				await t.close();
			}
		});
	});
});
