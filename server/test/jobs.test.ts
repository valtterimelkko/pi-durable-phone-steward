import { describe, expect, it } from "vitest";
import { BACKGROUND_CONTEXT } from "@earendil-works/chord/context";
import { canonicalJson } from "../src/canonical.ts";
import { JOB_TTL_MS, Jobs, expireDueJobs, queueJob, reportPhoneResult } from "../src/jobs.ts";
import { openTestHarness, type TestHarness } from "./helpers.ts";

const context = BACKGROUND_CONTEXT;

async function openWithJob(t: TestHarness, overrides?: { waiter?: { run: "weekly-check" | "photo-window"; runId: string } }) {
	return t.commit((tx) =>
		queueJob(tx, t.root.id, {
			dedupeKey: "tool-call-1",
			kind: "storage_overview",
			params: {},
			nowMs: 1_790_000_000_000,
			...(overrides?.waiter === undefined ? {} : { waiter: overrides.waiter }),
		}),
	);
}

describe("queueJob (find-before-create)", () => {
	it("creates one job per dedupe key and finds it again on replay", async () => {
		const t = await openTestHarness();
		try {
			const first = await openWithJob(t);
			const replay = await openWithJob(t);
			expect(replay.jobId).toBe(first.jobId);
			expect(replay.created).toBe(false);
			const second = await t.commit((tx) =>
				queueJob(tx, t.root.id, {
					dedupeKey: "tool-call-2",
					kind: "app_usage",
					params: {},
					nowMs: 1_790_000_000_000,
				}),
			);
			expect(second.jobId).not.toBe(first.jobId);
			const stored = await t.harness.snapshot(Jobs, t.root.id, context);
			expect(Object.keys(stored?.jobs ?? {})).toHaveLength(2);
			const job = stored?.jobs[first.jobId]!;
			expect(job.status).toBe("queued");
			expect(job.expiresAtMs - job.createdAtMs).toBe(JOB_TTL_MS);
			expect(jobIdLooksOpaque(job.jobId)).toBe(true);
		} finally {
			await t.close();
		}
	});
});

function jobIdLooksOpaque(jobId: string): boolean {
	return /^pj-[0-9a-f]{12}$/.test(jobId);
}

describe("reportPhoneResult (first result wins)", () => {
	it("accepts the first report, reports duplicate for the same one, and conflicts on a different one", async () => {
		const t = await openTestHarness();
		try {
			const { jobId } = await openWithJob(t);
			const report = { status: "ok" as const, data: { freeBytes: 5 }, error: null };
			const first = await reportPhoneResult(t.harness, t.root.id, jobId, report, context);
			expect(first).toEqual({ accepted: true, duplicate: false });

			const same = await reportPhoneResult(t.harness, t.root.id, jobId, { ...report }, context);
			expect(same).toEqual({ accepted: true, duplicate: true });

			await expect(
				reportPhoneResult(t.harness, t.root.id, jobId, { status: "ok", data: { freeBytes: 6 }, error: null }, context),
			).rejects.toMatchObject({ code: "RESULT_CONFLICT", status: 409 });

			const stored = await t.harness.snapshot(Jobs, t.root.id, context);
			const job = stored?.jobs[jobId]!;
			expect(job.status).toBe("reported");
			expect(job.conflictingReports).toBe(1);
			expect(job.canonicalResult).toBe(canonicalJson(report));
		} finally {
			await t.close();
		}
	});

	it("delivers the result to the conversation exactly once as a phonejob follow-up", async () => {
		const t = await openTestHarness();
		try {
			const { jobId } = await openWithJob(t);
			await reportPhoneResult(
				t.harness,
				t.root.id,
				jobId,
				{ status: "ok", data: { freeBytes: 5 }, error: null },
				context,
			);
			const storedAfter = await t.harness.snapshot(Jobs, t.root.id, context);
			const reporterId = storedAfter?.jobs[jobId]?.reporterTaskId;
			if (reporterId !== undefined) await t.waitTaskTerminal(reporterId);
			// A duplicate report after the first must not re-deliver.
			await reportPhoneResult(
				t.harness,
				t.root.id,
				jobId,
				{ status: "ok", data: { freeBytes: 5 }, error: null },
				context,
			);
			await t.root.waitForIdle(context);
			const view = await t.root.context(context);
			const deliveries = view.entries
				.map((entry) => entry.model?.[0])
				.filter((message) => message?.role === "user")
				.map((message) => (typeof message.content === "string" ? message.content : ""));
			expect(deliveries.filter((text) => text.startsWith(`[phone result ${jobId}]`))).toHaveLength(1);
		} finally {
			await t.close();
		}
	});

	it("gives JOB_EXPIRED for a report on an expired job and NOT_FOUND for an unknown one", async () => {
		const t = await openTestHarness();
		try {
			const { jobId } = await openWithJob(t);
			await expect(
				reportPhoneResult(
					t.harness,
					t.root.id,
					"pj-000000000000",
					{ status: "ok", data: {}, error: null },
					context,
				),
			).rejects.toMatchObject({ code: "NOT_FOUND" });
			await t.commit(async (tx) => {
				const doc = await tx.doc(Jobs, t.root.id);
				const job = doc.jobs[jobId]!;
				job.expiresAtMs = 1_790_000_000_000 - 1; // already due
				return undefined;
			});
			await expireDueJobs(t.harness, t.root.id, 1_790_000_000_000, context);
			await expect(
				reportPhoneResult(t.harness, t.root.id, jobId, { status: "ok", data: {}, error: null }, context),
			).rejects.toMatchObject({ code: "JOB_EXPIRED", status: 409 });
		} finally {
			await t.close();
		}
	});
});

describe("expireDueJobs", () => {
	it("expires due jobs and tells the conversation once", async () => {
		const t = await openTestHarness();
		try {
			const { jobId } = await openWithJob(t);
			const now = 1_790_000_000_000 + JOB_TTL_MS + 1;
			const expired = await expireDueJobs(t.harness, t.root.id, now, context);
			expect(expired.map((notice) => notice.jobId)).toEqual([jobId]);
			const stored = await t.harness.snapshot(Jobs, t.root.id, context);
			expect(stored?.jobs[jobId]!.status).toBe("expired");
			// The notice is delivered by a background reporter: poll for it.
			let texts: string[] = [];
			for (let i = 0; i < 200; i++) {
				await t.root.waitForIdle(context);
				const view = await t.root.context(context);
				texts = view.entries
					.map((entry) => entry.model?.[0])
					.filter((message) => message?.role === "user")
					.map((message) => (typeof message.content === "string" ? message.content : ""));
				if (texts.some((text) => text.startsWith(`[phone expired ${jobId}]`))) break;
				await new Promise((resolve) => setTimeout(resolve, 25));
			}
			expect(texts.filter((text) => text.startsWith(`[phone expired ${jobId}]`))).toHaveLength(1);

			// A second sweep must not tell the conversation again.
			const again = await expireDueJobs(t.harness, t.root.id, now + 1000, context);
			expect(again.map((notice) => notice.jobId)).toEqual([]);
		} finally {
			await t.close();
		}
	});
});

describe("concurrent conflicting reports (correction 04.9)", () => {
	it("one accepted, one 409, and conflictingReports exactly 1", async () => {
		const t = await openTestHarness();
		try {
			const { jobId } = await openWithJob(t);
			const report = (n: number) => ({ status: "ok" as const, data: { n }, error: null });
			const outcomes = await Promise.allSettled([
				reportPhoneResult(t.harness, t.root.id, jobId, report(1), context),
				reportPhoneResult(t.harness, t.root.id, jobId, report(2), context),
			]);
			const states = outcomes.map((o) => o.status);
			expect(states.sort()).toEqual(["fulfilled", "rejected"]);
			await expect(
				reportPhoneResult(t.harness, t.root.id, jobId, report(1), context),
			).resolves.toMatchObject({ accepted: true, duplicate: true });
			const stored = await t.harness.snapshot(Jobs, t.root.id, context);
			expect(stored?.jobs[jobId]!.conflictingReports).toBe(1);
		} finally {
			await t.close();
		}
	});
});

describe("rule-task waiters", () => {
	it("creates the continuation task exactly once when the result lands", async () => {
		const t = await openTestHarness();
		try {
			const { jobId } = await openWithJob(t, { waiter: { run: "weekly-check", runId: "wc-1" } });
			const report = { status: "ok" as const, data: { freeBytes: 5 }, error: null };
			await reportPhoneResult(t.harness, t.root.id, jobId, report, context);
			const storedMid = await t.harness.snapshot(Jobs, t.root.id, context);
			const rid = storedMid?.jobs[jobId]?.reporterTaskId;
			if (rid !== undefined) await t.waitTaskTerminal(rid);
			await t.root.waitForIdle(context);
			const stored = await t.harness.snapshot(Jobs, t.root.id, context);
			const job = stored?.jobs[jobId]!;
			expect(job.continuation).toBe(`weekly-check-settle:wc-1`);
			// A duplicate report must not create a second continuation.
			await reportPhoneResult(t.harness, t.root.id, jobId, report, context);
			const storedAgain = await t.harness.snapshot(Jobs, t.root.id, context);
			expect(storedAgain?.jobs[jobId]!.continuation).toBe(`weekly-check-settle:wc-1`);
		} finally {
			await t.close();
		}
	});

	it("creates the continuation when a waited job expires, exactly once", async () => {
		const t = await openTestHarness();
		try {
			const { jobId } = await openWithJob(t, { waiter: { run: "weekly-check", runId: "wc-2" } });
			await expireDueJobs(t.harness, t.root.id, 1_790_000_000_000 + JOB_TTL_MS + 1, context);
			const stored = await t.harness.snapshot(Jobs, t.root.id, context);
			// The continuation marker is set in the same commit as the expiry and guards the
			// exactly-once creation; the created settle task drains quickly (one answered
			// job → quiet terminal), so the marker is the deterministic evidence.
			expect(stored?.jobs[jobId]!.continuation).toBe("weekly-check-settle:wc-2");
		} finally {
			await t.close();
		}
	});
});
