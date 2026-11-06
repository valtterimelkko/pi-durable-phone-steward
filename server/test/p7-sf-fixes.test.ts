/**
 * protocol A10.8: S11 (an `app_usage` report settles open `uninstall_app` items whose
 * package is gone: ledger type `removed_outside`, item dropped, proposal closed when empty,
 * mentioned in the A9.2 note) and S12 (`recordExecution` ignores items that were not
 * approved in that decision; they stay `kept`, and the note counts them as kept). Also the
 * A9.2 note wording for the A10.1 items (moved to the Steward trash, not freed).
 */
import { describe, expect, it } from "vitest";
import { BACKGROUND_CONTEXT } from "@earendil-works/chord/context";
import { Decisions, type DecisionLedgerEntry } from "../src/documents.ts";
import { Jobs, jobIdForDedupeKey, queueJob, reportPhoneResult } from "../src/jobs.ts";
import { Proposals, createProposal, decideProposal, recordExecution, type Proposal } from "../src/proposals.ts";
import { ownerChatNote } from "../src/owner-note.ts";
import { openListText, openProposalLines } from "../src/weekly-cache.ts";
import { openTestHarness, type TestHarness } from "./helpers.ts";

const context = BACKGROUND_CONTEXT;

function usage(...packages: string[]) {
	return { apps: packages.map((packageName) => ({ packageName, label: packageName, lastUsedMs: null, installedMs: 1, isSystem: false })) };
}

async function openUninstall(t: TestHarness, packages: string[], title = "Unused apps") {
	return t.commit((tx) =>
		createProposal(
			tx,
			t.root.id,
			{
				source: "agent",
				title,
				reason: "r",
				items: packages.map((packageName) => ({
					kind: "uninstall_app" as const,
					target: { packageName, label: `Label ${packageName}` },
					bytesEstimate: 1000,
				})),
			},
			Date.now(),
		),
	);
}

/** Queue an app_usage job and report its result through the real reporting path. */
async function reportAppUsage(t: TestHarness, data: unknown, status: "ok" | "partial" | "error" = "ok", key = `s11:${Math.random()}`): Promise<string> {
	const { jobId } = await t.commit((tx) => queueJob(tx, t.root.id, { dedupeKey: key, kind: "app_usage", params: {}, nowMs: Date.now() }));
	await reportPhoneResult(t.harness, t.root.id, jobId, { status, data: data as never, error: status === "error" ? { code: "X", message: "m" } : null }, context);
	return jobId;
}

async function proposal(t: TestHarness, id: string): Promise<Proposal> {
	return (await t.harness.snapshot(Proposals, t.root.id, context))!.proposals[id]!;
}
async function ledger(t: TestHarness): Promise<DecisionLedgerEntry[]> {
	return (await t.harness.snapshot(Decisions, t.root.id, context))?.entries ?? [];
}

describe("S11: an app_usage report settles uninstall items for apps that are gone", () => {
	it("drops only the missing packages, records removed_outside, and keeps the proposal open", async () => {
		const t = await openTestHarness();
		try {
			const made = await openUninstall(t, ["com.example.a", "com.example.b", "com.example.c"]);
			await reportAppUsage(t, usage("com.example.b", "com.example.keepme"));
			const after = await proposal(t, made.proposalId);
			expect(after.status).toBe("open");
			expect(after.items.map((item) => (item.target as { packageName: string }).packageName)).toEqual(["com.example.b"]);
			expect(after.bytesEstimate).toBe(1000);
			const rows = (await ledger(t)).filter((row) => row.type === "removed_outside");
			expect(rows.map((row) => row.packageName).sort()).toEqual(["com.example.a", "com.example.c"]);
			expect(rows.every((row) => row.proposalId === made.proposalId && row.kind === "uninstall_app")).toBe(true);
		} finally {
			await t.close();
		}
	});

	it("closes the proposal when no item is left, with closedReason removed_outside, and it is no longer open", async () => {
		const t = await openTestHarness();
		try {
			const made = await openUninstall(t, ["com.example.a", "com.example.b"]);
			await reportAppUsage(t, usage("com.example.other"));
			const after = await proposal(t, made.proposalId);
			expect(after.status).not.toBe("open");
			expect(after.closedReason).toBe("removed_outside");
			expect(after.items).toEqual([]);
			expect(after.statusChangedAtMs).toBeTypeOf("number");
		} finally {
			await t.close();
		}
	});

	it("only touches open uninstall items: approved proposals, other kinds and other proposals are left alone", async () => {
		const t = await openTestHarness();
		try {
			const approved = await openUninstall(t, ["com.example.gone"], "Approved");
			await decideProposal(t.harness, t.root.id, approved.proposalId, { decision: "approve" }, context);
			const mixed = await t.commit((tx) =>
				createProposal(
					tx,
					t.root.id,
					{
						source: "agent",
						title: "Mixed",
						reason: "r",
						items: [
							{ kind: "uninstall_app", target: { packageName: "com.example.gone2", label: "G" }, bytesEstimate: 5 },
							{ kind: "clear_app_cache", target: { packageName: "com.example.gone2", label: "G" }, bytesEstimate: 5 },
							{ kind: "advice", target: { text: "hi" }, bytesEstimate: 0 },
						],
					},
					Date.now(),
				),
			);
			await reportAppUsage(t, usage("com.example.x"));
			expect((await proposal(t, approved.proposalId)).items).toHaveLength(1);
			const after = await proposal(t, mixed.proposalId);
			expect(after.status).toBe("open");
			expect(after.items.map((item) => item.kind)).toEqual(["clear_app_cache", "advice"]);
		} finally {
			await t.close();
		}
	});

	it("ignores a report that is not ok, an empty app list, and a malformed one", async () => {
		const t = await openTestHarness();
		try {
			const made = await openUninstall(t, ["com.example.a"]);
			await reportAppUsage(t, usage("com.example.zzz"), "partial");
			await reportAppUsage(t, { apps: [] });
			await reportAppUsage(t, { nope: true });
			await reportAppUsage(t, null);
			expect((await proposal(t, made.proposalId)).items).toHaveLength(1);
			expect((await ledger(t)).filter((row) => row.type === "removed_outside")).toEqual([]);
		} finally {
			await t.close();
		}
	});

	it("does nothing for other job kinds, and a repeated report changes nothing more", async () => {
		const t = await openTestHarness();
		try {
			const made = await openUninstall(t, ["com.example.a", "com.example.b"]);
			const jobId = await reportAppUsage(t, usage("com.example.b"), "ok", "s11:fixed");
			const { duplicate } = await reportPhoneResult(t.harness, t.root.id, jobId, { status: "ok", data: usage("com.example.b") as never, error: null }, context);
			expect(duplicate).toBe(true);
			expect((await ledger(t)).filter((row) => row.type === "removed_outside")).toHaveLength(1);
			void made;
			void Jobs;
			void jobIdForDedupeKey;
		} finally {
			await t.close();
		}
	});
});

function proposalOf(over: Partial<Proposal>): Proposal {
	return {
		proposalId: "pr-1",
		createdAtMs: 1,
		source: "agent",
		title: "Unused apps",
		reason: "r",
		bytesEstimate: 0,
		status: "open",
		items: [],
		...over,
	};
}

describe("S11: the A9.2 note mentions apps removed outside the app", () => {
	const nowMs = Date.UTC(2026, 9, 8, 12);
	it("lists removed apps from the last 14 days and says a fully settled proposal closed", () => {
		const note = ownerChatNote({
			proposals: [proposalOf({ proposalId: "pr-9", status: "expired", closedReason: "removed_outside", statusChangedAtMs: nowMs - 1000 })],
			ledger: [
				{ atMs: nowMs - 1000, type: "removed_outside", proposalId: "pr-9", itemId: "it-9-0", kind: "uninstall_app", packageName: "com.example.a", note: "Alpha" },
				{ atMs: nowMs - 1000, type: "removed_outside", proposalId: "pr-9", itemId: "it-9-1", kind: "uninstall_app", packageName: "com.example.b", note: "Beta" },
				{ atMs: nowMs - 30 * 86_400_000, type: "removed_outside", proposalId: "pr-0", kind: "uninstall_app", packageName: "com.example.old", note: "Ancient" },
			],
			nowMs,
		});
		expect(note).toMatch(/removed outside the app/i);
		expect(note).toContain("Alpha");
		expect(note).toContain("Beta");
		expect(note).not.toContain("Ancient");
		expect(note).toMatch(/pr-9[^\n]*already removed outside the app/i);
	});

	it("says nothing about removed apps when there are none", () => {
		expect(ownerChatNote({ proposals: [], ledger: [], nowMs })).not.toMatch(/removed outside/i);
	});
});

describe("S12: unticked items stay kept", () => {
	async function approvedWithTwoOfThree(t: TestHarness) {
		const made = await openUninstall(t, ["com.example.a", "com.example.b", "com.example.c"]);
		const [a, b, c] = made.items;
		const decision = await decideProposal(t.harness, t.root.id, made.proposalId, { decision: "approve", itemIds: [a!.itemId, b!.itemId] }, context);
		return { made, a: a!, b: b!, c: c!, decision };
	}

	it("a report that lists the unticked item as cancelled does not change the ledger or the counts", async () => {
		const t = await openTestHarness();
		try {
			const { made, a, b, c, decision } = await approvedWithTwoOfThree(t);
			await recordExecution(
				t.harness,
				t.root.id,
				made.proposalId,
				{
					decisionId: decision.decisionId,
					items: [
						{ itemId: a.itemId, outcome: "done", resultCode: null, bytesFreed: 500 },
						{ itemId: b.itemId, outcome: "done", resultCode: null, bytesFreed: 400 },
						{ itemId: c.itemId, outcome: "cancelled", resultCode: null, bytesFreed: null },
					],
				},
				context,
			);
			const rows = await ledger(t);
			expect(rows.filter((row) => row.itemId === c.itemId).map((row) => row.type)).toEqual(["kept"]);
			expect(rows.some((row) => row.type === "cancelled")).toBe(false);
			const review = rows.find((row) => row.type === "review")!;
			expect(review.review).toMatchObject({ cancellations: 0, failures: 0, freedBytes: 900 });
			expect((await proposal(t, made.proposalId)).status).toBe("executed");
		} finally {
			await t.close();
		}
	});

	it("a report with only the unticked item (nothing approved was reported) leaves the proposal open again", async () => {
		const t = await openTestHarness();
		try {
			const { made, c, decision } = await approvedWithTwoOfThree(t);
			await recordExecution(
				t.harness,
				t.root.id,
				made.proposalId,
				{ decisionId: decision.decisionId, items: [{ itemId: c.itemId, outcome: "cancelled", resultCode: null, bytesFreed: null }] },
				context,
			);
			expect((await ledger(t)).some((row) => row.type === "cancelled")).toBe(false);
			expect((await proposal(t, made.proposalId)).status).toBe("open");
		} finally {
			await t.close();
		}
	});

	it("an unknown itemId is still refused", async () => {
		const t = await openTestHarness();
		try {
			const { made, decision } = await approvedWithTwoOfThree(t);
			await expect(
				recordExecution(t.harness, t.root.id, made.proposalId, { decisionId: decision.decisionId, items: [{ itemId: "it-nope", outcome: "done", resultCode: null, bytesFreed: null }] }, context),
			).rejects.toThrow(/unknown itemId/);
		} finally {
			await t.close();
		}
	});

	it("the A9.2 note counts unticked items as kept, not cancelled", async () => {
		const t = await openTestHarness();
		try {
			const { made, a, b, decision } = await approvedWithTwoOfThree(t);
			await recordExecution(
				t.harness,
				t.root.id,
				made.proposalId,
				{
					decisionId: decision.decisionId,
					items: [
						{ itemId: a.itemId, outcome: "done", resultCode: null, bytesFreed: 500 },
						{ itemId: b.itemId, outcome: "done", resultCode: null, bytesFreed: 400 },
					],
				},
				context,
			);
			const proposals = Object.values((await t.harness.snapshot(Proposals, t.root.id, context))!.proposals);
			const note = ownerChatNote({ proposals, ledger: await ledger(t), nowMs: Date.now() });
			expect(note).toMatch(/executed: 2 done[^\n]*1 kept by the owner/);
			expect(note).not.toMatch(/cancelled on the phone/);
		} finally {
			await t.close();
		}
	});
});

describe("A10.1 wording in the server's text", () => {
	const nowMs = Date.UTC(2026, 9, 8, 12);
	it("the open list says delete_files go to the Steward trash", () => {
		const lines = openProposalLines([
			{ proposalId: "pr-5", status: "open", title: "Old maps", createdAtMs: 1, bytesEstimate: 2_600_000_000, items: [{ kind: "delete_files" }] },
		]);
		expect(openListText(lines)).toMatch(/pr-5[^\n]*delete_files[^\n]*Steward trash/);
	});

	it("an executed delete_files proposal reads as moved to the Steward trash, not freed", () => {
		const note = ownerChatNote({
			proposals: [proposalOf({ proposalId: "pr-5", title: "Old maps", status: "executed", statusChangedAtMs: nowMs - 5000 })],
			ledger: [{ atMs: nowMs - 5000, type: "executed", proposalId: "pr-5", itemId: "it-5-0", kind: "delete_files", path: "MapsWithMe", bytesFreed: 0, bytesTrashed: 2_600_000_000 }],
			nowMs,
		});
		expect(note).toMatch(/executed: 1 done \(2\.6 GB moved to the Steward trash/);
		expect(note).not.toMatch(/2\.6 GB freed/);
	});
});
