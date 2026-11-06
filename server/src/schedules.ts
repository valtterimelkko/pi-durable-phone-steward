/**
 * Schedules for the two rule tasks : OFF by default. The timers
 * exist, run on an injected clock, and only create work when `config.schedules.enabled`.
 * Each tick checks due-ness against a per-run ledger, and the create and the marker share
 * one commit, so a crash cannot double-schedule a run.
 */
import { BACKGROUND_CONTEXT } from "@earendil-works/chord/context";
import type { Context } from "@earendil-works/chord";
import { defineDoc, type Conversation, type Harness, type Tx } from "@earendil-works/pi-durable";
import type { StewardConfig } from "./config.ts";
import { RulesDoc } from "./documents.ts";
import { PhotoWindowTask, WeeklyCheckTask, type PhotoWindowInput, type WeeklyCheckInput } from "./rule-tasks.ts";
import { stewardDate } from "./time.ts";
import { periodicCheckpoint } from "./checkpoint.ts";

export const SchedulesDoc = defineDoc<{
	weeklyLastRunAtMs: number | null;
	photoLastRunAtMs: number | null;
	/** Runs already created, by runId — the find-before-create guard. */
	createdRuns: Record<string, true>;
}>({
	kind: "steward.schedules",
	version: 1,
	// Pi Durable 1.4.0 requires an explicit checkpoint policy (no default).
	checkpointWhen: periodicCheckpoint(),
	scope: "conversation",
	history: "latest",
	fork: "initial",
	initial: () => ({ weeklyLastRunAtMs: null, photoLastRunAtMs: null, createdRuns: {} }),
});

const WEEK_MS = 7 * 24 * 60 * 60_000;

async function createIfDue(
	tx: Tx,
	conversationId: import("@earendil-works/pi-durable").ConversationId,
	runId: string,
	kind: "weekly" | "photo",
	nowMs: number,
): Promise<boolean> {
	const schedules = await tx.doc(SchedulesDoc, conversationId);
	if (schedules.createdRuns[runId] === true) return false;
	const rules = await tx.doc(RulesDoc, conversationId);
	if (kind === "photo" && !rules.photoWindow.enabled) return false;
	schedules.createdRuns[runId] = true;
	if (kind === "weekly") {
		schedules.weeklyLastRunAtMs = nowMs;
		const input: WeeklyCheckInput = { runId, start: "open" };
		await tx.createTask(WeeklyCheckTask, input, { ownership: { kind: "conversation" }, conversationId, background: true });
	} else {
		schedules.photoLastRunAtMs = nowMs;
		const input: PhotoWindowInput = { runId, start: "open" };
		await tx.createTask(PhotoWindowTask, input, { ownership: { kind: "conversation" }, conversationId, background: true });
	}
	return true;
}

export type Scheduler = { stop(): void; tick(): Promise<{ weekly: boolean; photo: boolean }> };

/**
 * Start the schedule timers. `clock` and `intervalMs` are injectable for tests; nothing
 * runs unless `config.schedules.enabled` is true.
 */
export function startSchedulers(options: {
	harness: Harness;
	root: Conversation;
	config: StewardConfig;
	clock?: () => number;
	intervalMs?: number;
	context?: Context;
}): Scheduler {
	const clock = options.clock ?? (() => Date.now());
	const intervalMs = options.intervalMs ?? 60_000;
	const context = options.context ?? BACKGROUND_CONTEXT;
	let stopped = false;
	const tick = async (): Promise<{ weekly: boolean; photo: boolean }> => {
		if (!options.config.schedules.enabled || stopped) return { weekly: false, photo: false };
		const nowMs = clock();
		const result = { weekly: false, photo: false };
		await options.harness.commit(
			async (tx) => {
				// Weekly: due when 7 days have passed since the last run (or immediately on
				// the first tick after enabling).
				const schedules = await tx.doc(SchedulesDoc, options.root.id);
				const weeklyDue =
					schedules.weeklyLastRunAtMs === null || nowMs - schedules.weeklyLastRunAtMs >= WEEK_MS;
				if (weeklyDue) {
					result.weekly = await createIfDue(tx, options.root.id, `weekly-check:${stewardDate(nowMs)}`, "weekly", nowMs);
				}
				// Photo window: monthly, on the London calendar month boundary.
				const rules = await tx.doc(RulesDoc, options.root.id);
				if (rules.photoWindow.enabled) {
					const lastPhotoDate =
						schedules.photoLastRunAtMs === null ? null : stewardDate(schedules.photoLastRunAtMs);
					const thisMonth = stewardDate(nowMs).slice(0, 7);
					if (lastPhotoDate === null || lastPhotoDate.slice(0, 7) !== thisMonth) {
						result.photo = await createIfDue(tx, options.root.id, `photo-window:${thisMonth}`, "photo", nowMs);
					}
				}
				return undefined;
			},
			context,
		);
		options.harness.resume();
		return result;
	};
	const timer = setInterval(() => void tick().catch(() => undefined), intervalMs);
	return {
		stop() {
			stopped = true;
			clearInterval(timer);
		},
		tick,
	};
}
