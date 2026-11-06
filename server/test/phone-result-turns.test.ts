/**
 * S8 : phone-result steward turns.
 *  - During a weekly check the three job results settle silently into the run: no
 *    `[phone result …]` or `[phone expired …]` steward turn per job; the steward speaks
 *    once, from the weekly message, which carries the server's open list (S7) and the
 *    results the run gathered.
 *  - Outside a review, every phone-result turn also carries the server's open-proposal
 *    list, so the commentary cannot narrate stale proposals.
 */
import { describe, expect, it } from "vitest";
import { BACKGROUND_CONTEXT } from "@earendil-works/chord/context";
import type { JsonObject } from "@earendil-works/pi-durable";
import { openTestHarness, type TestHarness } from "./helpers.ts";
import { Jobs, expireDueJobs, queueJob, reportPhoneResult } from "../src/jobs.ts";
import { WeeklyCheckTask, type WeeklyCheckInput } from "../src/rule-tasks.ts";
import { Proposals, createProposal } from "../src/proposals.ts";
import { stewardDate } from "../src/time.ts";

const context = BACKGROUND_CONTEXT;
const CACHE_APP = { packageName: "com.example.chat", label: "Chat", cacheBytes: 1 };

async function userTexts(t: TestHarness): Promise<string[]> {
	const view = await t.root.context(context);
	return view.entries
		.map((entry) => entry.model?.[0])
		.filter((message) => message?.role === "user")
		.map((message) => (typeof message.content === "string" ? message.content : ""));
}

async function waitForText(t: TestHarness, startsWith: string): Promise<string> {
	for (let i = 0; i < 400; i++) {
		const found = (await userTexts(t)).find((text) => text.startsWith(startsWith));
		if (found !== undefined) return found;
		await new Promise((resolve) => setTimeout(resolve, 25));
	}
	throw new Error(`no user message starting ${startsWith}`);
}

async function openWeeklyRun(t: TestHarness): Promise<void> {
	const input: WeeklyCheckInput = { runId: "wc-s8", start: "open" };
	await t.root.commit(
		(tx) => tx.createTask(WeeklyCheckTask, input, { ownership: { kind: "conversation" }, background: true }),
		context,
	);
	for (let i = 0; i < 200; i++) {
		const stored = await t.harness.snapshot(Jobs, t.root.id, context);
		if (Object.keys(stored?.jobs ?? {}).length >= 3) return;
		await new Promise((resolve) => setTimeout(resolve, 25));
	}
	throw new Error("weekly check never queued its three jobs");
}

const dataFor = (kind: string): JsonObject =>
	kind === "storage_overview"
		? { totalBytes: 100, freeBytes: 50, apps: [], categories: {} }
		: kind === "app_usage"
			? { usage: [{ packageName: "com.example.chat", lastUsedMs: 1_790_000_000_000 }] }
			: { scope: [], totals: { images: 3 } };

describe("S8: weekly-check results settle silently", () => {
	it("sends no steward turn per phone result; one weekly message carries the open list and the gathered results", async () => {
		const t = await openTestHarness();
		try {
			const stale = await t.root.commit(
				(tx) =>
					createProposal(tx, t.root.id, {
						source: "agent",
						title: "Remove unused apps",
						reason: "x",
						items: [{ kind: "uninstall_app", target: { packageName: "com.example.old", label: "Old app" }, bytesEstimate: 5 }],
					}, Date.now()),
				context,
			);
			await openWeeklyRun(t);
			const jobs = Object.values((await t.harness.snapshot(Jobs, t.root.id, context))?.jobs ?? {});
			for (const job of jobs) {
				await reportPhoneResult(t.harness, t.root.id, job.jobId, { status: "ok", data: dataFor(job.kind), error: null }, context);
			}
			const date = stewardDate(Date.now());
			const weekly = await waitForText(t, `[schedule weekly_check ${date}]`);
			await t.root.waitForIdle(context);
			const reporterIds = Object.values((await t.harness.snapshot(Jobs, t.root.id, context))?.jobs ?? {}).map((job) => job.reporterTaskId);
			for (const id of reporterIds) if (id !== undefined) await t.waitTaskTerminal(id);

			const texts = await userTexts(t);
			expect(texts.filter((text) => text.startsWith("[phone result"))).toEqual([]);
			expect(texts.filter((text) => text.startsWith("[schedule weekly_check"))).toHaveLength(1);
			// The steward still gets what the run gathered, inside the one message.
			expect(weekly).toContain(stale.proposalId);
			expect(weekly).toMatch(/kind=app_usage status=ok/);
			expect(weekly).toContain("com.example.chat");
			expect(weekly).toMatch(/kind=media_index status=ok/);
			// The overview is already the stored snapshot: not repeated in full.
			expect(weekly).not.toContain("totalBytes");
		} finally {
			await t.close();
		}
	});

	it("an expired job of the run does not start its own steward turn; the weekly message names it", async () => {
		const t = await openTestHarness();
		try {
			await openWeeklyRun(t);
			const jobs = Object.values((await t.harness.snapshot(Jobs, t.root.id, context))?.jobs ?? {});
			for (const job of jobs.filter((job) => job.kind !== "media_index")) {
				await reportPhoneResult(t.harness, t.root.id, job.jobId, { status: "ok", data: dataFor(job.kind), error: null }, context);
			}
			await expireDueJobs(t.harness, t.root.id, Date.now() + 8 * 86_400_000, context);
			const date = stewardDate(Date.now());
			const weekly = await waitForText(t, `[schedule weekly_check ${date}]`);
			await t.root.waitForIdle(context);
			const texts = await userTexts(t);
			expect(texts.filter((text) => text.startsWith("[phone result") || text.startsWith("[phone expired"))).toEqual([]);
			expect(weekly).toMatch(/kind=media_index status=expired/);
		} finally {
			await t.close();
		}
	});
});

describe("S8: outside a review, a phone-result turn carries the server's open list", () => {
	async function oneResult(t: TestHarness, kind: "app_usage" | "storage_overview"): Promise<string> {
		const { jobId } = await t.commit((tx) =>
			queueJob(tx, t.root.id, { dedupeKey: `s8:${kind}:${Math.random()}`, kind, params: {}, nowMs: Date.now() }),
		);
		await reportPhoneResult(t.harness, t.root.id, jobId, { status: "ok", data: dataFor(kind), error: null }, context);
		const text = await waitForText(t, `[phone result ${jobId}]`);
		await t.root.waitForIdle(context);
		return text;
	}

	it("lists exactly the open proposals (not superseded or closed ones) as authoritative", async () => {
		const t = await openTestHarness();
		try {
			const older = await t.root.commit(
				(tx) =>
					createProposal(tx, t.root.id, {
						source: "agent",
						title: "Older cache batch",
						reason: "x",
						items: [{ kind: "clear_all_caches", target: { apps: [CACHE_APP] }, bytesEstimate: 1 }],
					}, Date.now() - 1000),
				context,
			);
			const newer = await t.root.commit(
				(tx) =>
					createProposal(tx, t.root.id, {
						source: "agent",
						title: "Newer cache batch",
						reason: "x",
						items: [{ kind: "clear_all_caches", target: { apps: [CACHE_APP] }, bytesEstimate: 2 }],
					}, Date.now()),
				context,
			);
			const stored = await t.harness.snapshot(Proposals, t.root.id, context);
			expect(stored?.proposals[older.proposalId]?.status).toBe("superseded");
			const text = await oneResult(t, "app_usage");
			expect(text).toContain("kind=app_usage status=ok");
			expect(text).toContain(newer.proposalId);
			expect(text).toContain("Newer cache batch");
			expect(text).not.toContain(older.proposalId);
			expect(text).toMatch(/authoritative/i);
			expect(text).toMatch(/no other proposal is waiting for the owner/i);
		} finally {
			await t.close();
		}
	});

	it("says plainly when nothing is open", async () => {
		const t = await openTestHarness();
		try {
			const text = await oneResult(t, "storage_overview");
			expect(text).toMatch(/no proposal is waiting for the owner/i);
		} finally {
			await t.close();
		}
	});
});
