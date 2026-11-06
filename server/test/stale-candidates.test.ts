/**
 * item 2: a rule run must never batch candidates gathered under an older
 * rule. The open phase stamps the request-time ruleVersion and folders; when the result
 * arrives under a different version, the candidates are discarded, the ledger records
 * `rule_changed`, and no batch (proposal or job) is created.
 */
import { describe, expect, it } from "vitest";
import { BACKGROUND_CONTEXT } from "@earendil-works/chord/context";
import { Jobs, reportPhoneResult } from "../src/jobs.ts";
import { Decisions, RulesDoc } from "../src/documents.ts";
import { PhotoWindowTask, PhotoWindowDoc } from "../src/rule-tasks.ts";
import { Notifications } from "../src/notify.ts";
import { Proposals } from "../src/proposals.ts";
import { openTestHarness } from "./helpers.ts";

const context = BACKGROUND_CONTEXT;

const CANDIDATES = [
	{
		mediaId: "external_primary:images:201",
		relativePath: "DCIM/Camera/",
		displayName: "IMG_OLD.jpg",
		mimeType: "image/jpeg",
		sizeBytes: 4_000_000,
		dateTakenMs: Date.now() - 130 * 86_400_000,
		dateAddedMs: Date.now() - 130 * 86_400_000,
		isFavourite: false,
		inKeep: false,
	},
];

describe("reminder scope (correction 05.2)", () => {
	it("a 95-day candidate in an excluded folder gets no reminder and no notification", async () => {
		const t = await openTestHarness();
		try {
			await t.commit(async (tx) => {
				const rules = await tx.doc(RulesDoc, t.root.id);
				rules.photoWindow.enabled = true;
				return undefined;
			});
			const input = { runId: "pw-scope", start: "open" } as const;
			await t.root.commit(
				(tx) => tx.createTask(PhotoWindowTask, input, { ownership: { kind: "conversation" }, background: true }),
				context,
			);
			let jobId: string | undefined;
			for (let i = 0; i < 200 && jobId === undefined; i++) {
				const stored = await t.harness.snapshot(Jobs, t.root.id, context);
				const job = Object.values(stored?.jobs ?? {}).find((candidate) => candidate.kind === "media_index");
				if (job !== undefined) jobId = job.jobId;
				else await new Promise((resolve) => setTimeout(resolve, 25));
			}
			expect(jobId).toBeDefined();
			// The rules change while the job is pending: a NEW folder scope and version.
			await t.commit(async (tx) => {
				const rules = await tx.doc(RulesDoc, t.root.id);
				rules.ruleVersion += 1;
				rules.photoWindow.folders = ["Pictures/NewPlace/"];
				return undefined;
			});
			// A 95-day candidate (inside the reminder window) from the OLD folder scope.
			const candidate = {
				mediaId: "external_primary:images:301",
				relativePath: "DCIM/Camera/",
				displayName: "IMG_MID.jpg",
				mimeType: "image/jpeg",
				sizeBytes: 3_000_000,
				dateTakenMs: Date.now() - 95 * 86_400_000,
				dateAddedMs: Date.now() - 95 * 86_400_000,
				isFavourite: false,
				inKeep: false,
			};
			await reportPhoneResult(t.harness, t.root.id, jobId!, { status: "ok", data: { scope: [], items: [candidate] }, error: null }, context);
			await new Promise((resolve) => setTimeout(resolve, 1500));
			// No reminder notification queued, no batch proposal, no rule_batch job.
			const notifications = await t.harness.snapshot(Notifications, context);
			expect(Object.keys(notifications?.queued ?? {}).some((key) => key.startsWith("photo-reminder:"))).toBe(false);
			const proposals = await t.harness.snapshot(Proposals, t.root.id, context);
			expect(Object.keys(proposals?.proposals ?? {})).toHaveLength(0);
			const jobs = await t.harness.snapshot(Jobs, t.root.id, context);
			expect(Object.values(jobs?.jobs ?? {}).some((job) => job.kind === "rule_batch")).toBe(false);
		} finally {
			await t.close();
		}
	});
});

describe("stale rule candidates (correction 04.2)", () => {
	it("discards candidates when the rules changed while the job was pending", async () => {
		const t = await openTestHarness();
		try {
			await t.commit(async (tx) => {
				const rules = await tx.doc(RulesDoc, t.root.id);
				rules.photoWindow.enabled = true;
				return undefined;
			});
			// An earlier run reminded the candidate, so without the guard it WOULD batch.
			await t.commit(async (tx) => {
				const photo = await tx.doc(PhotoWindowDoc, t.root.id);
				photo.reminded[CANDIDATES[0]!.mediaId] = { runId: "photo-window:earlier", atMs: Date.now() - 31 * 86_400_000 };
				return undefined;
			});

			const input = { runId: "pw-stale", start: "open" } as const;
			await t.root.commit(
				(tx) => tx.createTask(PhotoWindowTask, input, { ownership: { kind: "conversation" }, background: true }),
				context,
			);
			let jobId: string | undefined;
			for (let i = 0; i < 200 && jobId === undefined; i++) {
				const stored = await t.harness.snapshot(Jobs, t.root.id, context);
				const job = Object.values(stored?.jobs ?? {}).find((candidate) => candidate.kind === "media_index");
				if (job !== undefined) jobId = job.jobId;
				else await new Promise((resolve) => setTimeout(resolve, 25));
			}
			expect(jobId).toBeDefined();

			// The rules change while the job is pending (a newer version, new folders).
			await t.commit(async (tx) => {
				const rules = await tx.doc(RulesDoc, t.root.id);
				rules.ruleVersion += 1;
				rules.photoWindow.folders = ["Pictures/Keep/"];
				return undefined;
			});

			await reportPhoneResult(t.harness, t.root.id, jobId!, { status: "ok", data: { scope: [], items: CANDIDATES }, error: null }, context);

			// The continuation runs; give it a moment, then assert nothing batched.
			await new Promise((resolve) => setTimeout(resolve, 1500));
			const proposals = await t.harness.snapshot(Proposals, t.root.id, context);
			expect(Object.keys(proposals?.proposals ?? {})).toHaveLength(0);
			const jobs = await t.harness.snapshot(Jobs, t.root.id, context);
			expect(Object.values(jobs?.jobs ?? {}).some((job) => job.kind === "rule_batch")).toBe(false);

			// The ledger records the rule change for this run.
			const ledger = await t.harness.snapshot(Decisions, t.root.id, context);
			const ruleChanged = (ledger?.entries ?? []).filter(
				(entry) => (entry as { type?: string }).type === "rule_changed",
			);
			expect(ruleChanged.length).toBeGreaterThanOrEqual(1);
			expect((ruleChanged[0] as { note?: string }).note).toContain("pw-stale");
		} finally {
			await t.close();
		}
	});

	it("folders changed under the same version still discard out-of-scope candidates", async () => {
		const t = await openTestHarness();
		try {
			await t.commit(async (tx) => {
				const rules = await tx.doc(RulesDoc, t.root.id);
				rules.photoWindow.enabled = true;
				rules.photoWindow.folders = ["DCIM/Keep/"];
				return undefined;
			});
			const input = { runId: "pw-folders", start: "open" } as const;
			await t.root.commit(
				(tx) => tx.createTask(PhotoWindowTask, input, { ownership: { kind: "conversation" }, background: true }),
				context,
			);
			let jobId: string | undefined;
			for (let i = 0; i < 200 && jobId === undefined; i++) {
				const stored = await t.harness.snapshot(Jobs, t.root.id, context);
				const job = Object.values(stored?.jobs ?? {}).find((candidate) => candidate.kind === "media_index");
				if (job !== undefined) jobId = job.jobId;
				else await new Promise((resolve) => setTimeout(resolve, 25));
			}
			await reportPhoneResult(t.harness, t.root.id, jobId!, { status: "ok", data: { scope: [], items: CANDIDATES }, error: null }, context);
			await new Promise((resolve) => setTimeout(resolve, 1200));
			// The candidate's folder (DCIM/Camera/) is outside the stamped scope.
			const proposals = await t.harness.snapshot(Proposals, t.root.id, context);
			expect(Object.keys(proposals?.proposals ?? {})).toHaveLength(0);
		} finally {
			await t.close();
		}
	});
});
