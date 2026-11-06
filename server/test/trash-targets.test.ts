/**
 * S9 (protocol A9.1): a trash_media proposal item is completed in code from the newest
 * reported media_index row with that mediaId. The model wrote `{mediaId, name}` and the
 * phone refused every item (it matches relativePath, displayName and sizeBytes on the live
 * row), so the server now holds the full MediaRef itself. Open proposals stored with such
 * incomplete targets are repaired once, from the same index.
 */
import { describe, expect, it } from "vitest";
import { BACKGROUND_CONTEXT } from "@earendil-works/chord/context";
import type { JsonObject } from "@earendil-works/pi-durable";
import { Jobs, jobIdForDedupeKey, type MediaRef } from "../src/jobs.ts";
import { Proposals, createProposal } from "../src/proposals.ts";
import { proposeCleanup } from "../src/tools.ts";
import { repairOpenTrashTargets } from "../src/media-index.ts";
import { openTestHarness, type TestHarness } from "./helpers.ts";

const context = BACKGROUND_CONTEXT;

function ref(n: number, over: Partial<MediaRef> = {}): MediaRef {
	return {
		mediaId: `external_primary:video:${n}`,
		relativePath: "Movies/Trips/",
		displayName: `clip-${n}.mkv`,
		mimeType: "video/x-matroska",
		sizeBytes: 1_000_000 * n,
		dateTakenMs: 1_700_000_000_000 + n,
		dateAddedMs: 1_700_000_100_000 + n,
		isFavourite: false,
		inKeep: false,
		...over,
	};
}

let seq = 0;
/** Store a reported media_index job straight in the jobs document (no reporter task, no model turn). */
async function reportIndex(
	t: TestHarness,
	mode: "summary" | "rule_candidates",
	refs: MediaRef[],
	reportedAtMs: number,
	status: "ok" | "partial" | "error" = "ok",
): Promise<void> {
	seq += 1;
	const dedupeKey = `s9:index:${seq}`;
	const data = mode === "summary" ? { scope: [], folders: [], largest: refs, topLevelFiles: [], oldApks: [] } : { scope: [], items: refs };
	await t.commit(async (tx) => {
		const doc = await tx.doc(Jobs, t.root.id);
		const jobId = jobIdForDedupeKey(dedupeKey);
		doc.jobs[jobId] = {
			jobId,
			dedupeKey,
			kind: "media_index",
			params: { mode } as JsonObject,
			status: "reported",
			createdAtMs: reportedAtMs - 10,
			expiresAtMs: reportedAtMs + 86_400_000,
			conflictingReports: 0,
			result: { status, data: JSON.parse(JSON.stringify(data)), error: null, reportedAtMs },
		};
	});
}

async function propose(t: TestHarness, items: unknown[]): Promise<string> {
	return t.commit((tx) => proposeCleanup(tx, t.root.id, { title: "Old videos", reason: "r", items }, Date.now()));
}

async function stored(t: TestHarness, proposalId: string) {
	return (await t.harness.snapshot(Proposals, t.root.id, context))?.proposals[proposalId]!;
}

describe("S9 defect 1: propose_cleanup completes trash_media targets from the index", () => {
	it("an incomplete target {mediaId, name} becomes the full MediaRef; bytesEstimate comes from sizeBytes", async () => {
		const t = await openTestHarness();
		try {
			await reportIndex(t, "summary", [ref(1), ref(2)], 1000);
			const out = await propose(t, [
				{ kind: "trash_media", target: { mediaId: ref(1).mediaId, name: "clip-1.mkv" } },
				{ kind: "trash_media", target: { mediaId: ref(2).mediaId }, bytesEstimate: 2_000_000 },
			]);
			const proposal = await stored(t, (JSON.parse(out) as { proposalId: string }).proposalId);
			expect(proposal.items[0]!.target).toEqual(ref(1));
			expect(proposal.items[1]!.target).toEqual(ref(2));
			expect(proposal.items[0]!.bytesEstimate).toBe(1_000_000);
			expect(proposal.items[1]!.bytesEstimate).toBe(2_000_000);
			expect(proposal.bytesEstimate).toBe(3_000_000);
		} finally {
			await t.close();
		}
	});

	it("model-supplied fields that disagree with the index lose to the index", async () => {
		const t = await openTestHarness();
		try {
			await reportIndex(t, "summary", [ref(3)], 1000);
			const out = await propose(t, [
				{
					kind: "trash_media",
					target: { ...ref(3), displayName: "invented.mkv", relativePath: "Elsewhere/", sizeBytes: 5, isFavourite: false },
				},
			]);
			const proposal = await stored(t, (JSON.parse(out) as { proposalId: string }).proposalId);
			expect(proposal.items[0]!.target).toEqual(ref(3));
		} finally {
			await t.close();
		}
	});

	it("finds rows in rule_candidates results too, and the newest report wins", async () => {
		const t = await openTestHarness();
		try {
			await reportIndex(t, "summary", [ref(4, { sizeBytes: 111 })], 1000);
			await reportIndex(t, "rule_candidates", [ref(4, { sizeBytes: 222 }), ref(5)], 2000);
			await reportIndex(t, "summary", [ref(4, { sizeBytes: 333 })], 500);
			const out = await propose(t, [
				{ kind: "trash_media", target: { mediaId: ref(4).mediaId } },
				{ kind: "trash_media", target: { mediaId: ref(5).mediaId } },
			]);
			const proposal = await stored(t, (JSON.parse(out) as { proposalId: string }).proposalId);
			expect((proposal.items[0]!.target as { sizeBytes: number }).sizeBytes).toBe(222);
			expect(proposal.items[1]!.target).toEqual(ref(5));
		} finally {
			await t.close();
		}
	});

	it("refuses a mediaId that is in no reported index, naming it, and stores nothing", async () => {
		const t = await openTestHarness();
		try {
			await reportIndex(t, "summary", [ref(6)], 1000);
			await expect(
				propose(t, [
					{ kind: "trash_media", target: { mediaId: ref(6).mediaId } },
					{ kind: "trash_media", target: { mediaId: "external_primary:video:999", name: "ghost.mkv" } },
				]),
			).rejects.toThrow(/external_primary:video:999.*phone_media_index/s);
			expect(Object.keys((await t.harness.snapshot(Proposals, t.root.id, context))?.proposals ?? {})).toEqual([]);
		} finally {
			await t.close();
		}
	});

	it("ignores an index that errored (no data to trust)", async () => {
		const t = await openTestHarness();
		try {
			await reportIndex(t, "summary", [ref(7)], 1000, "error");
			await expect(propose(t, [{ kind: "trash_media", target: { mediaId: ref(7).mediaId } }])).rejects.toThrow(/external_primary:video:7/);
		} finally {
			await t.close();
		}
	});

	it("keeps refusing protected rows (favourite, Keep folder), naming the item", async () => {
		const t = await openTestHarness();
		try {
			await reportIndex(t, "summary", [ref(8, { isFavourite: true }), ref(9, { inKeep: true, relativePath: "Pictures/Keep/" })], 1000);
			await expect(propose(t, [{ kind: "trash_media", target: { mediaId: ref(8).mediaId } }])).rejects.toThrow(/video:8.*protected/s);
			await expect(propose(t, [{ kind: "trash_media", target: { mediaId: ref(9).mediaId } }])).rejects.toThrow(/video:9.*protected/s);
		} finally {
			await t.close();
		}
	});

	it("a rule_batch proposal of full MediaRefs is stored as given (it already comes from the index)", async () => {
		const t = await openTestHarness();
		try {
			const created = await t.commit((tx) =>
				createProposal(
					tx,
					t.root.id,
					{
						source: "rule_batch",
						title: "Photo cleanup batch",
						reason: "r",
						items: [{ kind: "trash_media", target: ref(10) as unknown as never, bytesEstimate: ref(10).sizeBytes }],
					},
					Date.now(),
				),
			);
			expect((await stored(t, created.proposalId)).items[0]!.target).toEqual(ref(10));
		} finally {
			await t.close();
		}
	});
});

describe("S9 defect 1: repair of already-stored open proposals", () => {
	async function seed(t: TestHarness, source: "agent" | "rule_batch", target: unknown, bytesEstimate?: number) {
		// Written straight into the document: the state the live server holds today.
		return t.commit(async (tx) => {
			const doc = await tx.doc(Proposals, t.root.id);
			const proposalId = `pr-seed${Object.keys(doc.proposals).length}`;
			doc.proposals[proposalId] = {
				proposalId,
				createdAtMs: 1,
				source,
				title: "Old videos",
				reason: "r",
				bytesEstimate: bytesEstimate ?? 0,
				status: "open",
				items: [{ itemId: `it-${proposalId}-0`, kind: "trash_media", target: JSON.parse(JSON.stringify(target)), bytesEstimate: bytesEstimate ?? 0, reason: "" }],
			};
			return proposalId;
		});
	}

	it("completes sparse targets from the index once, then changes nothing (idempotent)", async () => {
		const t = await openTestHarness();
		try {
			await reportIndex(t, "summary", [ref(11)], 1000);
			const id = await seed(t, "agent", { mediaId: ref(11).mediaId, name: "clip-11.mkv" });
			expect(await t.commit((tx) => repairOpenTrashTargets(tx, t.root.id))).toBe(1);
			const repaired = await stored(t, id);
			expect(repaired.items[0]!.target).toEqual(ref(11));
			expect(repaired.items[0]!.bytesEstimate).toBe(ref(11).sizeBytes);
			expect(repaired.bytesEstimate).toBe(ref(11).sizeBytes);
			expect(await t.commit((tx) => repairOpenTrashTargets(tx, t.root.id))).toBe(0);
			expect(await stored(t, id)).toEqual(repaired);
		} finally {
			await t.close();
		}
	});

	it("leaves an item alone when its mediaId is in no index, and leaves closed and complete ones untouched", async () => {
		const t = await openTestHarness();
		try {
			await reportIndex(t, "summary", [ref(12)], 1000);
			const ghost = await seed(t, "agent", { mediaId: "external_primary:video:404", name: "ghost.mkv" });
			const complete = await seed(t, "rule_batch", ref(12), ref(12).sizeBytes);
			const closed = await seed(t, "agent", { mediaId: ref(12).mediaId });
			await t.commit(async (tx) => {
				(await tx.doc(Proposals, t.root.id)).proposals[closed]!.status = "executed";
			});
			const before = JSON.stringify(await t.harness.snapshot(Proposals, t.root.id, context));
			expect(await t.commit((tx) => repairOpenTrashTargets(tx, t.root.id))).toBe(0);
			expect(JSON.stringify(await t.harness.snapshot(Proposals, t.root.id, context))).toBe(before);
			expect((await stored(t, ghost)).items[0]!.target).toEqual({ mediaId: "external_primary:video:404", name: "ghost.mkv" });
			expect((await stored(t, complete)).items[0]!.target).toEqual(ref(12));
		} finally {
			await t.close();
		}
	});
});
