/**
 * `PhotoWindowTask` — the seed of the monthly photo-age rule task .
 *
 * Three checkpointed steps, deterministic, no model: index the media index snapshot,
 * send the reminder, build the cut-off batch. Each step commits its checkpoint before the
 * next begins, so a kill mid-way resumes from the last checkpoint and never re-runs an
 * earlier step.
 */
import { readFileSync, writeFileSync } from "node:fs";
import { defineTask } from "@earendil-works/pi-durable";
import { crashPoint } from "./crash-gate.ts";

export type PhotoWindowInput = {
	/** Where the step counter lives (a JSON file outside the task, for test assertions). */
	counterPath: string;
};

export type PhotoWindowState =
	| { phase: "index" }
	| { phase: "remind"; indexed: number }
	| { phase: "batch"; reminded: number };

export type PhotoWindowResult = { batched: number };

/**
 * Count one step run in a file outside the task. The task's own checkpoints live in the
 * SQLite storage; this counter is the independent witness a crash test reads to prove
 * earlier steps were not re-run.
 */
function bumpStep(counterPath: string, step: "index" | "remind" | "batch"): number {
	let counter: Record<string, number> = { index: 0, remind: 0, batch: 0 };
	try {
		counter = { ...counter, ...(JSON.parse(readFileSync(counterPath, "utf8")) as Record<string, number>) };
	} catch {
		// First step of the first run: the file does not exist yet.
	}
	counter[step] = (counter[step] ?? 0) + 1;
	writeFileSync(counterPath, JSON.stringify(counter), "utf8");
	return counter[step]!;
}

export const PhotoWindowTask = defineTask<PhotoWindowInput, PhotoWindowState, PhotoWindowResult>({
	name: "steward.photo-window",
	version: 1,
	initial: () => ({ phase: "index" }),
	phases: {
		index: async (task, runtime, context) => {
			const indexed = bumpStep(task.input.counterPath, "index");
			await runtime.commit(() => ({ status: "running", checkpoint: { phase: "remind", indexed } }), context);
		},
		remind: async (task, runtime, context) => {
			const reminded = bumpStep(task.input.counterPath, "remind");
			await runtime.commit(() => ({ status: "running", checkpoint: { phase: "batch", reminded } }), context);
		},
		batch: async (task, runtime, context) => {
			// Test scaffolding: catches a kill between the remind checkpoint and the batch.
			await crashPoint("photo-window-batch");
			const batched = bumpStep(task.input.counterPath, "batch");
			await runtime.commit(
				() => ({ status: "terminal", outcome: { status: "completed", result: { batched } } }),
				context,
			);
		},
	},
	abort: async (_task, runtime, context) => {
		await runtime.commit(() => ({ status: "terminal", outcome: { status: "aborted" } }), context);
	},
});
