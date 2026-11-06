/**
 * Schedules (R2 decides): the timers exist and are tested with an injected clock, and
 * nothing runs unless config enables them.
 */
import { describe, expect, it } from "vitest";
import { BACKGROUND_CONTEXT } from "@earendil-works/chord/context";
import { loadConfig } from "../src/config.ts";
import { Jobs } from "../src/jobs.ts";
import { RulesDoc } from "../src/documents.ts";
import { SchedulesDoc, startSchedulers } from "../src/schedules.ts";
import { openTestHarness } from "./helpers.ts";

const context = BACKGROUND_CONTEXT;

describe("schedules", () => {
	it("creates nothing while disabled, even when due", async () => {
		const t = await openTestHarness();
		try {
			const config = loadConfig({ stateDir: "/tmp/x", schedules: { enabled: false } });
			const scheduler = startSchedulers({ harness: t.harness, root: t.root, config, clock: () => 1_800_000_000_000, intervalMs: 60_000 });
			const result = await scheduler.tick();
			expect(result).toEqual({ weekly: false, photo: false });
			const jobs = await t.harness.snapshot(Jobs, t.root.id, context);
			expect(Object.keys(jobs?.jobs ?? {})).toHaveLength(0);
			scheduler.stop();
		} finally {
			await t.close();
		}
	});

	it("when enabled, a due tick queues the weekly jobs; the next tick is not due for 7 days", async () => {
		const t = await openTestHarness();
		try {
			let now = 1_800_000_000_000;
			const config = loadConfig({ stateDir: "/tmp/x", schedules: { enabled: true } });
			const scheduler = startSchedulers({ harness: t.harness, root: t.root, config, clock: () => now, intervalMs: 60_000 });
			const first = await scheduler.tick();
			expect(first.weekly).toBe(true);
			// The photo rule is disabled by default: no photo run.
			expect(first.photo).toBe(false);

			// The weekly open phase queues its three jobs (background task: poll).
			let jobCount = 0;
			for (let i = 0; i < 200; i++) {
				const jobs = await t.harness.snapshot(Jobs, t.root.id, context);
				jobCount = Object.keys(jobs?.jobs ?? {}).length;
				if (jobCount >= 3) break;
				await new Promise((resolve) => setTimeout(resolve, 25));
			}
			expect(jobCount).toBe(3);

			// Immediately again: not due (the run marker also blocks the same date).
			now += 60_000;
			const second = await scheduler.tick();
			expect(second.weekly).toBe(false);

			// 7 days later: due again, with a NEW runId (new date → new jobs).
			now += 7 * 24 * 60 * 60_000;
			const third = await scheduler.tick();
			expect(third.weekly).toBe(true);
			scheduler.stop();
		} finally {
			await t.close();
		}
	});

	it("the monthly photo run fires only when the rule is enabled", async () => {
		const t = await openTestHarness();
		try {
			let now = 1_800_000_000_000;
			const config = loadConfig({ stateDir: "/tmp/x", schedules: { enabled: true } });
			const scheduler = startSchedulers({ harness: t.harness, root: t.root, config, clock: () => now, intervalMs: 60_000 });
			// First tick consumes the weekly due-ness; the photo rule is off.
			await scheduler.tick();

			// Enable the rule mid-month: the next tick schedules this month's photo run.
			await t.commit(async (tx) => {
				const rules = await tx.doc(RulesDoc, t.root.id);
				rules.photoWindow.enabled = true;
				return undefined;
			});
			const second = await scheduler.tick();
			expect(second.photo).toBe(true);

			// The photo-window media_index job (mode rule_candidates) was queued; the weekly
			// run's media_index job is mode summary — find the right one.
			let media: { params: Record<string, unknown> } | undefined;
			for (let i = 0; i < 200 && media === undefined; i++) {
				const jobs = await t.harness.snapshot(Jobs, t.root.id, context);
				media = Object.values(jobs?.jobs ?? {}).find(
					(job) => job.kind === "media_index" && (job.params as { mode?: string }).mode === "rule_candidates",
				);
				if (media === undefined) await new Promise((resolve) => setTimeout(resolve, 25));
			}
			expect(media?.params).toMatchObject({ mode: "rule_candidates", olderThanDays: 90 });

			// Next month: due again.
			now += 31 * 24 * 60 * 60_000;
			const third = await scheduler.tick();
			expect(third.photo).toBe(true);
			scheduler.stop();
		} finally {
			await t.close();
		}
	});

	it("records its state so a restart does not double-schedule the same run", async () => {
		const t = await openTestHarness();
		try {
			const config = loadConfig({ stateDir: "/tmp/x", schedules: { enabled: true } });
			let now = 1_800_000_000_000;
			const first = startSchedulers({ harness: t.harness, root: t.root, config, clock: () => now, intervalMs: 60_000 });
			await first.tick();
			first.stop();
			// A "restart": a new scheduler over the same state, same clock.
			const second = startSchedulers({ harness: t.harness, root: t.root, config, clock: () => now + 1000, intervalMs: 60_000 });
			const again = await second.tick();
			// The weekly was marked at the same London date → the run id exists → not re-created.
			expect(again.weekly).toBe(false);
			second.stop();
			const state = await t.harness.snapshot(SchedulesDoc, t.root.id, context);
			expect(Object.keys(state?.createdRuns ?? {}).length).toBe(1);
		} finally {
			await t.close();
		}
	});
});
