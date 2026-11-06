/**
 * S6 (protocol A6.2): a new agent proposal supersedes earlier OPEN agent proposals of the
 * same kind family, and the steward gets a read-only `proposals` tool so it never reports
 * what is open from memory.
 */
import { describe, expect, it } from "vitest";
import { BACKGROUND_CONTEXT } from "@earendil-works/chord/context";
import { Decisions } from "../src/documents.ts";
import { Jobs } from "../src/jobs.ts";
import {
	Proposals,
	createProposal,
	decideProposal,
	listProposalsForSteward,
	recordExecution,
	type CreateProposalInput,
} from "../src/proposals.ts";
import { PROTOCOL_TOOL_NAMES, proposeCleanup, stewardSections } from "../src/tools.ts";
import { openTestHarness, type TestHarness } from "./helpers.ts";

const context = BACKGROUND_CONTEXT;

const cacheItem = { kind: "clear_all_caches" as const, target: { apps: [{ packageName: "com.a", label: "A", cacheBytes: 300 }] }, bytesEstimate: 300 };
const oneCache = { kind: "clear_app_cache" as const, target: { packageName: "com.a", label: "A" }, bytesEstimate: 100 };
const uninstallItem = { kind: "uninstall_app" as const, target: { packageName: "com.u", label: "U" }, bytesEstimate: 50 };
const mediaItem = { kind: "trash_media" as const, target: { mediaId: "m1" }, bytesEstimate: 10 };
/** S9: an agent proposal's trash targets come from a reported media_index, so `m1` is reported once per harness. */
const reportedM1 = new WeakSet<TestHarness>();
async function reportM1(t: TestHarness) {
	if (reportedM1.has(t)) return;
	reportedM1.add(t);
	await t.commit(async (tx) => {
		const doc = await tx.doc(Jobs, t.root.id);
		doc.jobs["pj-s9"] = {
			jobId: "pj-s9", dedupeKey: "s9", kind: "media_index", params: {}, status: "reported", createdAtMs: 1, expiresAtMs: 9e15, conflictingReports: 0,
			result: {
				status: "ok", error: null, reportedAtMs: 2,
				data: { largest: [{ mediaId: "m1", relativePath: "Movies/", displayName: "a.mkv", mimeType: "video/x-matroska", sizeBytes: 10, dateTakenMs: 1, dateAddedMs: 1, isFavourite: false, inKeep: false }] },
			},
		};
	});
}
const adviceItem = { kind: "advice" as const, target: { text: "Open Settings" }, bytesEstimate: 0 };

let clock = 1000;
async function make(t: TestHarness, items: CreateProposalInput["items"], source: "agent" | "rule_batch" = "agent", title = "P") {
	clock += 1000;
	await reportM1(t);
	return t.commit((tx) => createProposal(tx, t.root.id, { source, title, reason: "r", items }, clock));
}
async function statusOf(t: TestHarness, id: string) {
	return (await t.harness.snapshot(Proposals, t.root.id, context))?.proposals[id];
}

describe("S6: supersede per kind family", () => {
	it("a new cache proposal supersedes the earlier open cache proposal, with supersededBy and a timestamp", async () => {
		const t = await openTestHarness();
		try {
			const old = await make(t, [cacheItem]);
			const fresh = await make(t, [oneCache]);
			expect(fresh.superseded).toEqual([old.proposalId]);
			const stored = await statusOf(t, old.proposalId);
			expect(stored?.status).toBe("superseded");
			expect(stored?.supersededBy).toBe(fresh.proposalId);
			expect(typeof stored?.supersededAtMs).toBe("number");
			expect((await statusOf(t, fresh.proposalId))?.status).toBe("open");
		} finally {
			await t.close();
		}
	});

	it("keeps a record in the decisions ledger (one superseded row per replaced proposal)", async () => {
		const t = await openTestHarness();
		try {
			const old = await make(t, [cacheItem]);
			const fresh = await make(t, [cacheItem]);
			const ledger = await t.harness.snapshot(Decisions, t.root.id, context);
			const rows = (ledger?.entries ?? []).filter((e) => e.type === "superseded");
			expect(rows).toHaveLength(1);
			expect(rows[0]).toMatchObject({ proposalId: old.proposalId });
			expect(rows[0]?.note).toContain(fresh.proposalId);
		} finally {
			await t.close();
		}
	});

	it("supersedes only proposals whose families are all covered by the new one", async () => {
		const t = await openTestHarness();
		try {
			const cache = await make(t, [cacheItem]);
			const media = await make(t, [mediaItem]);
			const mixed = await make(t, [cacheItem, uninstallItem]);
			// cache is covered by "mixed" (cache + uninstall); media is not.
			expect((await statusOf(t, cache.proposalId))?.status).toBe("superseded");
			expect((await statusOf(t, media.proposalId))?.status).toBe("open");
			const uninstall = await make(t, [uninstallItem]);
			// "mixed" has cache + uninstall; a lone uninstall proposal does not cover cache.
			expect((await statusOf(t, mixed.proposalId))?.status).toBe("open");
			expect(uninstall.superseded).toEqual([]);
		} finally {
			await t.close();
		}
	});

	it("treats clear_app_cache and clear_all_caches as one family", async () => {
		const t = await openTestHarness();
		try {
			const a = await make(t, [oneCache]);
			const b = await make(t, [cacheItem]);
			expect(b.superseded).toEqual([a.proposalId]);
		} finally {
			await t.close();
		}
	});

	it("ignores advice items when computing families", async () => {
		const t = await openTestHarness();
		try {
			const old = await make(t, [cacheItem, adviceItem]);
			const fresh = await make(t, [cacheItem]);
			expect(fresh.superseded).toEqual([old.proposalId]);
		} finally {
			await t.close();
		}
	});

	it("a proposal of only advice is never superseded and supersedes nothing", async () => {
		const t = await openTestHarness();
		try {
			const advice = await make(t, [adviceItem]);
			const cache = await make(t, [cacheItem]);
			const advice2 = await make(t, [adviceItem]);
			expect(cache.superseded).toEqual([]);
			expect(advice2.superseded).toEqual([]);
			expect((await statusOf(t, advice.proposalId))?.status).toBe("open");
			expect((await statusOf(t, cache.proposalId))?.status).toBe("open");
		} finally {
			await t.close();
		}
	});

	it("never supersedes rule_batch proposals, and a rule batch supersedes nothing", async () => {
		const t = await openTestHarness();
		try {
			const batch = await make(t, [mediaItem], "rule_batch");
			const agent = await make(t, [mediaItem]);
			expect(agent.superseded).toEqual([]);
			expect((await statusOf(t, batch.proposalId))?.status).toBe("open");
			const batch2 = await make(t, [mediaItem], "rule_batch");
			expect(batch2.superseded).toEqual([]);
			expect((await statusOf(t, agent.proposalId))?.status).toBe("open");
		} finally {
			await t.close();
		}
	});

	it("leaves an approved proposal alone (it may be executing), and its execution report still lands", async () => {
		const t = await openTestHarness();
		try {
			const old = await make(t, [cacheItem]);
			const decision = await decideProposal(t.harness, t.root.id, old.proposalId, { decision: "approve" }, context);
			const fresh = await make(t, [cacheItem]);
			expect(fresh.superseded).toEqual([]);
			expect((await statusOf(t, old.proposalId))?.status).toBe("approved");
			await recordExecution(
				t.harness,
				t.root.id,
				old.proposalId,
				{ decisionId: decision.decisionId, items: [{ itemId: old.items[0]!.itemId, outcome: "done", resultCode: null, bytesFreed: 250 }] },
				context,
			);
			expect((await statusOf(t, old.proposalId))?.status).toBe("executed");
		} finally {
			await t.close();
		}
	});

	it("leaves executed, rejected and already superseded proposals alone", async () => {
		const t = await openTestHarness();
		try {
			const rejected = await make(t, [cacheItem]);
			await decideProposal(t.harness, t.root.id, rejected.proposalId, { decision: "reject" }, context);
			const mid = await make(t, [cacheItem]);
			const fresh = await make(t, [cacheItem]);
			expect(fresh.superseded).toEqual([mid.proposalId]);
			expect((await statusOf(t, rejected.proposalId))?.status).toBe("rejected");
			const again = await make(t, [cacheItem]);
			expect(again.superseded).toEqual([fresh.proposalId]);
			expect((await statusOf(t, mid.proposalId))?.supersededBy).toBe(fresh.proposalId);
		} finally {
			await t.close();
		}
	});

	it("refuses to approve a superseded proposal with PROPOSAL_NOT_OPEN naming the newer one", async () => {
		const t = await openTestHarness();
		try {
			const old = await make(t, [cacheItem]);
			const fresh = await make(t, [cacheItem]);
			await expect(
				decideProposal(t.harness, t.root.id, old.proposalId, { decision: "approve" }, context),
			).rejects.toMatchObject({ code: "PROPOSAL_NOT_OPEN", message: expect.stringContaining(fresh.proposalId) });
		} finally {
			await t.close();
		}
	});

	it("propose_cleanup lists the superseded ids in its result", async () => {
		const t = await openTestHarness();
		try {
			const old = await make(t, [cacheItem]);
			const out = await t.commit((tx) =>
				proposeCleanup(tx, t.root.id, { title: "Caches", reason: "r", items: [cacheItem] }, 9000),
			);
			const parsed = JSON.parse(out) as { proposalId: string; superseded?: string[]; note?: string };
			expect(parsed.superseded).toEqual([old.proposalId]);
			expect(JSON.stringify(parsed)).toContain("replaces");
		} finally {
			await t.close();
		}
	});
});

describe("S6: the proposals tool", () => {
	it("is one of the protocol tools and is offered to the agent", async () => {
		expect(PROTOCOL_TOOL_NAMES).toContain("proposals");
		const t = await openTestHarness();
		try {
			const agent = await t.root.agent(context);
			expect(agent.tools.map((tool) => tool.name)).toContain("proposals");
		} finally {
			await t.close();
		}
	});

	it("lists open and approved proposals in full summary, and the 10 most recent closed ones", async () => {
		const t = await openTestHarness();
		try {
			const open = await make(t, [mediaItem, adviceItem], "agent", "Media batch");
			const approvedP = await make(t, [uninstallItem], "agent", "Uninstall");
			await decideProposal(t.harness, t.root.id, approvedP.proposalId, { decision: "approve" }, context);
			// 12 closed proposals: rejected, each in a unique family-free way (rule batches are never superseded).
			const closedIds: string[] = [];
			for (let i = 0; i < 12; i += 1) {
				const p = await make(t, [mediaItem], "rule_batch", `Batch ${i}`);
				await decideProposal(t.harness, t.root.id, p.proposalId, { decision: "reject" }, context);
				closedIds.push(p.proposalId);
			}
			const view = await t.commit((tx) => listProposalsForSteward(tx, t.root.id));
			expect(view.openAndApproved.map((p) => p.proposalId).sort()).toEqual([open.proposalId, approvedP.proposalId].sort());
			const row = view.openAndApproved.find((p) => p.proposalId === open.proposalId)!;
			expect(row).toMatchObject({
				title: "Media batch",
				status: "open",
				itemCount: 2,
				itemKinds: ["trash_media", "advice"],
				bytesEstimate: 10,
				source: "agent",
			});
			expect(typeof row.createdAt).toBe("string");
			expect(view.openAndApproved.find((p) => p.proposalId === approvedP.proposalId)?.status).toBe("approved");
			expect(view.recentClosed).toHaveLength(10);
			expect(view.recentClosed.every((p) => p.status === "rejected")).toBe(true);
			expect(view.recentClosed[0]?.proposalId).toBe(closedIds[11]);
			expect(view.recentClosed.map((p) => p.proposalId)).not.toContain(closedIds[0]);
		} finally {
			await t.close();
		}
	});

	it("shows superseded proposals with supersededBy and when", async () => {
		const t = await openTestHarness();
		try {
			const old = await make(t, [cacheItem]);
			const fresh = await make(t, [cacheItem]);
			const view = await t.commit((tx) => listProposalsForSteward(tx, t.root.id));
			expect(view.openAndApproved.map((p) => p.proposalId)).toEqual([fresh.proposalId]);
			expect(view.recentClosed).toHaveLength(1);
			expect(view.recentClosed[0]).toMatchObject({ proposalId: old.proposalId, status: "superseded", supersededBy: fresh.proposalId });
			expect(typeof view.recentClosed[0]?.closedAt).toBe("string");
		} finally {
			await t.close();
		}
	});

	it("an empty store lists nothing", async () => {
		const t = await openTestHarness();
		try {
			const view = await t.commit((tx) => listProposalsForSteward(tx, t.root.id));
			expect(view).toEqual({ openAndApproved: [], recentClosed: [] });
		} finally {
			await t.close();
		}
	});
});

describe("S6: the standing prompt", () => {
	it("tells the steward to re-propose per kind, replace the earlier open one, and call proposals before saying what is open", () => {
		const extension = stewardSections() as unknown as { sections: Array<{ render: (input?: unknown) => string }> };
		const text = extension.sections.map((s) => s.render({} as never)).join("\n");
		expect(text).toMatch(/proposals/);
		expect(text).toMatch(/call `?proposals`? before/i);
		expect(text).toMatch(/one batch per kind/i);
		expect(text).toMatch(/replaces the earlier open/i);
		expect(text).toMatch(/never (call|describe|say)[^.]*pending[^.]*unless/i);
	});
});

describe("S6 investigation: the decisions ledger after an execution report", () => {
	it("is not empty after approve and a one-item done report: kept, executed and review rows exist", async () => {
		const t = await openTestHarness();
		try {
			const p = await make(t, [cacheItem]);
			const decision = await decideProposal(t.harness, t.root.id, p.proposalId, { decision: "approve" }, context);
			await recordExecution(
				t.harness,
				t.root.id,
				p.proposalId,
				{ decisionId: decision.decisionId, items: [{ itemId: p.items[0]!.itemId, outcome: "done", resultCode: null, bytesFreed: 1 }] },
				context,
			);
			const ledger = await t.harness.snapshot(Decisions, t.root.id, context);
			const types = (ledger?.entries ?? []).map((e) => e.type);
			expect(types).toContain("kept");
			expect(types).toContain("executed");
			expect(types).toContain("review");
		} finally {
			await t.close();
		}
	});
});
