/**
 * item 5: a `rule_batch` job's items are full MediaRefs (protocol §3.1
 * params, §3.3 MediaRef) — the app checks each item's path, name and size against the
 * live row, so bare media ids would make every auto trash fail.
 */
import { describe, expect, it } from "vitest";
import { BACKGROUND_CONTEXT } from "@earendil-works/chord/context";
import type { MediaRef } from "../src/jobs.ts";
import { Jobs, reportPhoneResult } from "../src/jobs.ts";
import { PhotoWindowTask, PhotoWindowDoc } from "../src/rule-tasks.ts";
import { RulesDoc } from "../src/documents.ts";
import { openTestHarness } from "./helpers.ts";

const context = BACKGROUND_CONTEXT;

const CANDIDATES: MediaRef[] = [
	{
		mediaId: "external_primary:images:101",
		relativePath: "DCIM/Camera/",
		displayName: "IMG_20250101_101010.jpg",
		mimeType: "image/jpeg",
		sizeBytes: 4_200_000,
		dateTakenMs: Date.now() - 125 * 86_400_000,
		dateAddedMs: Date.now() - 125 * 86_400_000,
		isFavourite: false,
		inKeep: false,
	},
	{
		mediaId: "external_primary:images:102",
		relativePath: "DCIM/Camera/",
		displayName: "IMG_20250201_101010.jpg",
		mimeType: "image/jpeg",
		sizeBytes: 3_100_000,
		dateTakenMs: Date.now() - 95 * 86_400_000,
		dateAddedMs: Date.now() - 95 * 86_400_000,
		isFavourite: false,
		inKeep: false,
	},
];

describe("rule_batch jobs carry full MediaRefs", () => {
	it("auto mode queues a rule_batch job whose items are complete MediaRefs", async () => {
		const t = await openTestHarness();
		try {
			// Auto mode unlocked (one batch has already run as one-tap approval) and enabled.
			await t.commit(async (tx) => {
				const rules = await tx.doc(RulesDoc, t.root.id);
				rules.photoWindow.enabled = true;
				rules.photoWindow.autoMode = true;
				rules.batchesRunOneTap = 1;
				return undefined;
			});
			// An earlier run reminded the 125-day item: it is eligible for the batch.
			await t.commit(async (tx) => {
				const photo = await tx.doc(PhotoWindowDoc, t.root.id);
				photo.reminded[CANDIDATES[0]!.mediaId] = { runId: "photo-window:earlier", atMs: Date.now() - 31 * 86_400_000 };
				return undefined;
			});
			const input = { runId: "pw-media", start: "open" } as const;
			await t.root.commit(
				(tx) => tx.createTask(PhotoWindowTask, input, { ownership: { kind: "conversation" }, background: true }),
				context,
			);
			// Answer the media_index job as the phone would.
			let jobId: string | undefined;
			for (let i = 0; i < 200 && jobId === undefined; i++) {
				const stored = await t.harness.snapshot(Jobs, t.root.id, context);
				const job = Object.values(stored?.jobs ?? {}).find((candidate) => candidate.kind === "media_index");
				if (job !== undefined) jobId = job.jobId;
				else await new Promise((resolve) => setTimeout(resolve, 25));
			}
			expect(jobId).toBeDefined();
			await reportPhoneResult(t.harness, t.root.id, jobId!, { status: "ok", data: { scope: [], items: CANDIDATES }, error: null }, context);

			// The rule_batch job appears; its items are full MediaRefs.
			let batch: { params: { items?: unknown; batchId?: string; ruleVersion?: number } } | undefined;
			for (let i = 0; i < 200 && batch === undefined; i++) {
				const stored = await t.harness.snapshot(Jobs, t.root.id, context);
				const job = Object.values(stored?.jobs ?? {}).find((candidate) => candidate.kind === "rule_batch");
				if (job !== undefined) batch = job as never;
				else await new Promise((resolve) => setTimeout(resolve, 25));
			}
			expect(batch).toBeDefined();
			const items = (batch!.params.items ?? []) as Array<Record<string, unknown>>;
			expect(items.length).toBe(1);
			const item = items[0]!;
			// Protocol §3.3 MediaRef: every field present, with the right types.
			expect(item).toEqual({
				mediaId: expect.any(String),
				relativePath: expect.any(String),
				displayName: expect.any(String),
				mimeType: expect.any(String),
				sizeBytes: expect.any(Number),
				dateTakenMs: expect.any(Number),
				dateAddedMs: expect.any(Number),
				isFavourite: expect.any(Boolean),
				inKeep: expect.any(Boolean),
			});
			expect(item.mediaId).toBe(CANDIDATES[0]!.mediaId);
			expect(item.displayName).toBe(CANDIDATES[0]!.displayName);
			expect(batch!.params.batchId).toMatch(/^rb-/);
			expect(typeof batch!.params.ruleVersion).toBe("number");
		} finally {
			await t.close();
		}
	});
});
