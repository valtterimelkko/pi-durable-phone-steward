import { describe, expect, it } from "vitest";
import { BACKGROUND_CONTEXT } from "@earendil-works/chord/context";
import { PROTOCOL_TOOL_NAMES } from "../src/tools.ts";
import { allowedToolNames, resolveAgentTools } from "../src/tools.ts";
import { openTestHarness } from "./helpers.ts";
import { Jobs } from "../src/jobs.ts";
import { Proposals, createProposal, decideProposal, recordExecution } from "../src/proposals.ts";
import { Snapshots, storeSnapshot } from "../src/documents.ts";

const context = BACKGROUND_CONTEXT;

/**
 * A10.7: the exact tool set. Three tools (A10.1, A10.2) are named in the
 * contract and in the allowlist but live in their own extension: this
 * worktree does not offer them, so only THOSE names may be absent. The moment they are
 * registered the test is exact. The screen tools (A10.3) exist only in a live-session turn,
 * so a normal turn must never offer them.
 */
const SF_TOOLS = ["phone_files", "phone_shell_info", "phone_force_stop"] as const;
const SCREEN_TOOLS = ["screen_observe", "screen_act", "screen_end"] as const;
const READ_ONLY_SHELL_TOOL = "phone_shell_info"; // fixed read templates; no shell text from the model

describe("the model's tool set (protocol §8 as amended by A10.7,  safety gate)", () => {
	it("is exactly the protocol list: phone, record, web and memory/session tools — nothing that can delete, move, uninstall, clear, change rules, run shell text or write files", async () => {
		const t = await openTestHarness();
		try {
			const names = await resolveAgentTools(t.root, context);
			// An item-level enumeration, reconciled against the protocol list. Only 's
			// tools may be absent (merge-time reconciliation).
			const expected = PROTOCOL_TOOL_NAMES.filter((name) => names.includes(name) || !(SF_TOOLS as readonly string[]).includes(name));
			expect([...names].sort()).toEqual([...expected].sort());
			// A11.2 added phone_result; A13.1 added phone_use_request, so the set is 24 names.
			expect(names).toHaveLength(PROTOCOL_TOOL_NAMES.length);
			expect(PROTOCOL_TOOL_NAMES).toContain("phone_result");
			expect(PROTOCOL_TOOL_NAMES).toContain("phone_use_request");
			const forbidden = [
				"bash",
				"write",
				"edit",
				"read",
				"powershell",
				"phone_echo",
				"uninstall_app",
				"clear_app_cache",
				"trash_media",
				"delete",
				"move",
				"set_rules",
				"capture",
				"memory_capture",
				"presence_declare",
				...SCREEN_TOOLS,
			];
			for (const name of forbidden) expect(names).not.toContain(name);
			// A10.2: `phone_shell_info` only queues a fixed, read-only Shizuku template (the model
			// never sends shell text), and `phone_force_stop` deletes nothing; both are named here.
			const a10Allowed = ["phone_shell_info"];
			for (const name of names) {
				expect(PROTOCOL_TOOL_NAMES).toContain(name);
				// A tool whose name suggests destruction must never be offered; the allowlist
				// is the only source of tools.
				if (name !== READ_ONLY_SHELL_TOOL) expect(name).not.toMatch(/delete|uninstall|trash|clear|shell|exec|write|move/i);
			}
		} finally {
			await t.close();
		}
	});

	it("the allowed set is frozen to the protocol list (bite probe: remove a tool from the extension and the first test fails)", () => {
		expect([...allowedToolNames()].sort()).toEqual([...PROTOCOL_TOOL_NAMES].sort());
		expect(allowedToolNames()).toHaveLength(24);
		expect(allowedToolNames()).toContain("phone_use_request");
		for (const name of SCREEN_TOOLS) expect(allowedToolNames()).not.toContain(name);
	});

	it("every tool name is unique and the web and memory/session tools are all in the list", () => {
		expect(new Set(PROTOCOL_TOOL_NAMES).size).toBe(PROTOCOL_TOOL_NAMES.length);
		for (const name of ["web_search", "web_read", "web_browse", "memory_recall", "memory_find", "memory_survey", "session_find", "session_handoff", "session_log", "presence_board", "phone_result", "phone_use_request"]) {
			expect(PROTOCOL_TOOL_NAMES).toContain(name);
		}
	});
});

describe("guard hook", () => {
	it("blocks every tool outside the allowlist, and allows the listed ones", async () => {
		const { guardBlock } = await import("../src/tools.ts");
		expect(guardBlock("bash")).toBeTruthy();
		expect(guardBlock("phone_echo")).toBeTruthy();
		expect(guardBlock("write")).toBeTruthy();
		expect(guardBlock("screen_act")).toBeTruthy();
		expect(guardBlock("memory_capture")).toBeTruthy();
		expect(guardBlock("phone_storage_overview")).toBeUndefined();
		expect(guardBlock("phone_use_request")).toBeUndefined();
		expect(guardBlock("propose_cleanup")).toBeUndefined();
		expect(guardBlock("web_read")).toBeUndefined();
		expect(guardBlock("session_handoff")).toBeUndefined();
	});
});

describe("server-only tools", () => {
	it("storage_trend diffs the two latest stored snapshots", async () => {
		const t = await openTestHarness();
		try {
			await t.commit((tx) =>
				storeSnapshot(
					tx,
					t.root.id,
					{
						capturedAtMs: 1000,
						totalBytes: 100,
						freeBytes: 10,
						apps: [{ packageName: "com.a", label: "A", appBytes: 5, dataBytes: 20, cacheBytes: 3, isSystem: false }],
						categories: { imagesBytes: 30, videoBytes: 0, audioBytes: 0, documentsBytes: 0, appsBytes: 25, systemBytes: 30, otherBytes: 5 },
					},
				),
			);
			await t.commit((tx) =>
				storeSnapshot(
					tx,
					t.root.id,
					{
						capturedAtMs: 2000,
						totalBytes: 100,
						freeBytes: 4,
						apps: [{ packageName: "com.a", label: "A", appBytes: 5, dataBytes: 30, cacheBytes: 1, isSystem: false }],
						categories: { imagesBytes: 35, videoBytes: 0, audioBytes: 0, documentsBytes: 0, appsBytes: 36, systemBytes: 30, otherBytes: 0 },
					},
				),
			);
			const trend = await t.commit(async (tx) => {
				const { storageTrendFromTx } = await import("../src/documents.ts");
				return storageTrendFromTx(tx, t.root.id);
			});
			expect(trend!.fromCapturedAtMs).toBe(1000);
			expect(trend!.toCapturedAtMs).toBe(2000);
			expect(trend!.freeBytesDelta).toBe(-6);
			expect(trend!.apps[0]).toMatchObject({ packageName: "com.a", dataBytesDelta: 10, cacheBytesDelta: -2 });
		} finally {
			await t.close();
		}
	});

	it("propose_cleanup writes a proposal and executes nothing; its items cannot contain an acting kind outside the allowed four", async () => {
		const t = await openTestHarness();
		try {
			const proposal = await t.commit((tx) =>
				createProposal(
					tx,
					t.root.id,
					{
						source: "agent",
						title: "Clear caches",
						reason: "Caches regrow",
						items: [
							{ kind: "clear_app_cache", target: { packageName: "com.a", label: "A" }, bytesEstimate: 400, reason: "cache" },
							{ kind: "advice", target: { text: "Open Settings" }, bytesEstimate: 0, reason: "manual" },
						],
					},
					1000,
				),
			);
			expect(proposal.proposalId).toMatch(/^pr-/);
			expect(proposal.status).toBe("open");
			const stored = await t.harness.snapshot(Proposals, t.root.id, context);
			expect(stored?.proposals[proposal.proposalId]).toBeDefined();
		} finally {
			await t.close();
		}
	});
});

describe("clear_all_caches (A6)", () => {
	it("is an accepted item kind whose target is the app list shown to the owner", async () => {
		const t = await openTestHarness();
		try {
			const proposal = await t.commit((tx) =>
				createProposal(
					tx,
					t.root.id,
					{
						source: "agent",
						title: "Clear every app's cache in one go",
						reason: "Several large caches; Android clears them all after one confirmation",
						items: [
							{
								kind: "clear_all_caches",
								target: {
									apps: [
										{ packageName: "com.a", label: "A", cacheBytes: 400_000_000 },
										{ packageName: "com.b", label: "B", cacheBytes: 120_000_000 },
									],
								},
								bytesEstimate: 520_000_000,
							},
						],
					},
					1000,
				),
			);
			expect(proposal.items[0]!.kind).toBe("clear_all_caches");
			expect(proposal.status).toBe("open");
		} finally {
			await t.close();
		}
	});

	it("rejects an unknown apps shape with BAD_REQUEST", async () => {
		const t = await openTestHarness();
		try {
			for (const target of [
				{ apps: "everything" },
				{ apps: [] },
				{ apps: [{ packageName: "com.a" }] },
				{ apps: [{ packageName: "com.a", label: "A", cacheBytes: "big" }] },
				{},
			]) {
				await expect(
					t.commit((tx) =>
						createProposal(
							tx,
							t.root.id,
							{
								source: "agent",
								title: "t",
								reason: "r",
								items: [{ kind: "clear_all_caches", target }],
							},
							1000,
						),
					),
				).rejects.toMatchObject({ status: 400 });
			}
		} finally {
			await t.close();
		}
	});

	it("executes as one item: done frees the summed bytes and the review line records it", async () => {
		const t = await openTestHarness();
		try {
			const proposal = await t.commit((tx) =>
				createProposal(
					tx,
					t.root.id,
					{
						source: "agent",
						title: "Clear every app's cache",
						reason: "r",
						items: [
							{
								kind: "clear_all_caches",
								target: { apps: [{ packageName: "com.a", label: "A", cacheBytes: 300 }, { packageName: "com.b", label: "B", cacheBytes: 200 }] },
								bytesEstimate: 500,
							},
						],
					},
					1000,
				),
			);
			const decision = await decideProposal(t.harness, t.root.id, proposal.proposalId, { decision: "approve" }, context);
			await recordExecution(
				t.harness,
				t.root.id,
				proposal.proposalId,
				{ decisionId: decision.decisionId, items: [{ itemId: proposal.items[0]!.itemId, outcome: "done", resultCode: -1, bytesFreed: 480 }] },
				context,
			);
			const stored = await t.harness.snapshot(Proposals, t.root.id, context);
			expect(stored?.proposals[proposal.proposalId]?.status).toBe("executed");
			const { Decisions } = await import("../src/documents.ts");
			const ledger = await t.harness.snapshot(Decisions, t.root.id, context);
			const review = (ledger?.entries ?? []).filter((e: { type: string }) => e.type === "review").at(-1) as
				| { review: { freedBytes: number | null; failures: number } }
				| undefined;
			expect(review?.review.freedBytes).toBe(480);
			expect(review?.review.failures).toBe(0);
		} finally {
			await t.close();
		}
	});

	describe("propose_cleanup tool schema (A6)", () => {
		it("offers clear_all_caches as an item kind", async () => {
			const t = await openTestHarness();
			try {
				const agent = await t.root.agent(context);
				const tool = agent.tools.find((candidate) => candidate.name === "propose_cleanup");
				expect(tool).toBeDefined();
				const schema = JSON.stringify((tool as { parameters: unknown }).parameters);
				expect(schema).toContain("clear_all_caches");
			} finally {
				await t.close();
			}
		});
	});
});

describe("phone tools queue jobs", () => {
	it("phone_media_index commits a job with protocol params and returns queued at once", async () => {
		const t = await openTestHarness();
		try {
			const { queuePhoneJobForCall } = await import("../src/tools.ts");
			const out = await t.commit((tx) =>
				queuePhoneJobForCall(
					tx,
					t.root.id,
					{ callTaskId: "task-7", kind: "media_index", params: { mode: "summary" } },
					1000,
				),
			);
			expect(out).toMatchObject({ status: "queued" });
			expect(out.jobId).toMatch(/^pj-/);
			const stored = await t.harness.snapshot(Jobs, t.root.id, context);
			const job = stored?.jobs[out.jobId]!;
			expect(job.kind).toBe("media_index");
			expect(job.params).toEqual({ mode: "summary" });
		} finally {
			await t.close();
		}
	});
});

describe("S4: a report with no done item does not close the proposal", () => {
	async function openProposal(t: Awaited<ReturnType<typeof openTestHarness>>, source: "agent" | "rule_batch" = "agent") {
		return t.commit((tx) =>
			createProposal(
				tx,
				t.root.id,
				{
					source,
					title: "Clear caches",
					reason: "r",
					items: [
						{ kind: "clear_app_cache", target: { packageName: "com.a", label: "A" }, bytesEstimate: 300 },
						{ kind: "clear_app_cache", target: { packageName: "com.b", label: "B" }, bytesEstimate: 200 },
					],
				},
				1000,
			),
		);
	}

	it("returns to open with the decision spent when every item is cancelled, and the ledger still records each item", async () => {
		const t = await openTestHarness();
		try {
			const proposal = await openProposal(t);
			const decision = await decideProposal(t.harness, t.root.id, proposal.proposalId, { decision: "approve" }, context);
			await recordExecution(
				t.harness,
				t.root.id,
				proposal.proposalId,
				{
					decisionId: decision.decisionId,
					items: proposal.items.map((item) => ({ itemId: item.itemId, outcome: "cancelled" as const, resultCode: null, bytesFreed: null })),
				},
				context,
			);
			const stored = await t.harness.snapshot(Proposals, t.root.id, context);
			expect(stored?.proposals[proposal.proposalId]?.status).toBe("open");
			const { decisionValid } = await import("../src/proposals.ts");
			expect(await decisionValid(t.harness, t.root.id, decision.decisionId, context)).toBeUndefined();
			const { Decisions } = await import("../src/documents.ts");
			const ledger = await t.harness.snapshot(Decisions, t.root.id, context);
			const cancelled = (ledger?.entries ?? []).filter((e: { type: string }) => e.type === "cancelled");
			expect(cancelled).toHaveLength(2);
			const review = (ledger?.entries ?? []).filter((e: { type: string }) => e.type === "review").at(-1) as
				| { note?: string; review: { cancellations: number; freedBytes: number | null } }
				| undefined;
			expect(review?.review.cancellations).toBe(2);
			expect(review?.note).toMatch(/nothing was done/i);
		} finally {
			await t.close();
		}
	});

	it("the review_line event says plainly that nothing was done", async () => {
		const t = await openTestHarness();
		try {
			const proposal = await openProposal(t);
			const decision = await decideProposal(t.harness, t.root.id, proposal.proposalId, { decision: "approve" }, context);
			const { setActiveIntegrations, activeIntegrations } = await import("../src/steward-events.ts");
			const previous = activeIntegrations();
			const seen: Array<{ kind: string; data: Record<string, unknown> }> = [];
			setActiveIntegrations({ extensions: [], stewardEvent: async (_tx, _id, event) => void seen.push(event) });
			try {
				await recordExecution(
					t.harness,
					t.root.id,
					proposal.proposalId,
					{ decisionId: decision.decisionId, items: [{ itemId: proposal.items[0]!.itemId, outcome: "failed", resultCode: 1, bytesFreed: null }] },
					context,
				);
			} finally {
				setActiveIntegrations(previous);
			}
			const line = seen.filter((e) => e.kind === "review_line").at(-1);
			expect(line?.data.nothingDone).toBe(true);
			expect(String(line?.data.line)).toMatch(/nothing was done/i);
		} finally {
			await t.close();
		}
	});

	it("lets the owner approve the re-opened proposal again, and a successful report then executes it", async () => {
		const t = await openTestHarness();
		try {
			const proposal = await openProposal(t);
			const first = await decideProposal(t.harness, t.root.id, proposal.proposalId, { decision: "approve" }, context);
			await recordExecution(
				t.harness,
				t.root.id,
				proposal.proposalId,
				{ decisionId: first.decisionId, items: [{ itemId: proposal.items[0]!.itemId, outcome: "cancelled", resultCode: null, bytesFreed: null }] },
				context,
			);
			const second = await decideProposal(t.harness, t.root.id, proposal.proposalId, { decision: "approve" }, context);
			expect(second.decisionId).not.toBe(first.decisionId);
			// The spent decision cannot execute anything.
			await expect(
				recordExecution(
					t.harness,
					t.root.id,
					proposal.proposalId,
					{ decisionId: first.decisionId, items: [{ itemId: proposal.items[0]!.itemId, outcome: "done", resultCode: 0, bytesFreed: 300 }] },
					context,
				),
			).rejects.toMatchObject({ status: 400 });
			await recordExecution(
				t.harness,
				t.root.id,
				proposal.proposalId,
				{ decisionId: second.decisionId, items: [{ itemId: proposal.items[0]!.itemId, outcome: "done", resultCode: 0, bytesFreed: 300 }] },
				context,
			);
			const stored = await t.harness.snapshot(Proposals, t.root.id, context);
			expect(stored?.proposals[proposal.proposalId]?.status).toBe("executed");
		} finally {
			await t.close();
		}
	});

	it("a partial report (one done) still executes the proposal", async () => {
		const t = await openTestHarness();
		try {
			const proposal = await openProposal(t);
			const decision = await decideProposal(t.harness, t.root.id, proposal.proposalId, { decision: "approve" }, context);
			await recordExecution(
				t.harness,
				t.root.id,
				proposal.proposalId,
				{
					decisionId: decision.decisionId,
					items: [
						{ itemId: proposal.items[0]!.itemId, outcome: "done", resultCode: 0, bytesFreed: 300 },
						{ itemId: proposal.items[1]!.itemId, outcome: "cancelled", resultCode: null, bytesFreed: null },
					],
				},
				context,
			);
			const stored = await t.harness.snapshot(Proposals, t.root.id, context);
			expect(stored?.proposals[proposal.proposalId]?.status).toBe("executed");
		} finally {
			await t.close();
		}
	});

	it("a no-op report on a rule batch does not advance the one-tap count", async () => {
		const t = await openTestHarness();
		try {
			const { RulesDoc } = await import("../src/documents.ts");
			const before = (await t.harness.snapshot(RulesDoc, t.root.id, context))?.batchesRunOneTap ?? 0;
			const proposal = await openProposal(t, "rule_batch");
			const decision = await decideProposal(t.harness, t.root.id, proposal.proposalId, { decision: "approve" }, context);
			await recordExecution(
				t.harness,
				t.root.id,
				proposal.proposalId,
				{ decisionId: decision.decisionId, items: [{ itemId: proposal.items[0]!.itemId, outcome: "skipped_protected", resultCode: null, bytesFreed: null }] },
				context,
			);
			const after = (await t.harness.snapshot(RulesDoc, t.root.id, context))?.batchesRunOneTap ?? 0;
			expect(after).toBe(before);
		} finally {
			await t.close();
		}
	});
});

describe("S5: two or more clear_app_cache items become one clear_all_caches item", () => {
	it("merges them into one item with the A6 target shape and the summed estimate, leaving other kinds alone", async () => {
		const { mergeClearAppCacheItems } = await import("../src/proposals.ts");
		const result = mergeClearAppCacheItems([
			{ kind: "advice", target: { text: "Open Settings" }, bytesEstimate: 0, reason: "manual" },
			{ kind: "clear_app_cache", target: { packageName: "com.a", label: "A" }, bytesEstimate: 400 },
			{ kind: "uninstall_app", target: { packageName: "com.u", label: "U" }, bytesEstimate: 50 },
			{ kind: "clear_app_cache", target: { packageName: "com.b", label: "B" }, bytesEstimate: 120 },
			{ kind: "clear_app_cache", target: { packageName: "com.c", label: "C" } },
		]);
		expect(result.merged).toBe(3);
		expect(result.items).toHaveLength(3);
		expect(result.items.map((item) => item.kind)).toEqual(["advice", "clear_all_caches", "uninstall_app"]);
		expect(result.items[1]).toMatchObject({
			kind: "clear_all_caches",
			target: {
				apps: [
					{ packageName: "com.a", label: "A", cacheBytes: 400 },
					{ packageName: "com.b", label: "B", cacheBytes: 120 },
					{ packageName: "com.c", label: "C", cacheBytes: 0 },
				],
			},
			bytesEstimate: 520,
		});
		expect(result.items[0]).toMatchObject({ kind: "advice", bytesEstimate: 0 });
		expect(result.items[2]).toMatchObject({ kind: "uninstall_app", bytesEstimate: 50 });
	});

	it("leaves a single clear_app_cache item as it is", async () => {
		const { mergeClearAppCacheItems } = await import("../src/proposals.ts");
		const items = [{ kind: "clear_app_cache" as const, target: { packageName: "com.a", label: "A" }, bytesEstimate: 400 }];
		const result = mergeClearAppCacheItems(items);
		expect(result.merged).toBe(0);
		expect(result.items).toEqual(items);
	});

	it("does not merge when a cache item has an invalid target, so createProposal still rejects it", async () => {
		const { mergeClearAppCacheItems } = await import("../src/proposals.ts");
		const result = mergeClearAppCacheItems([
			{ kind: "clear_app_cache", target: { packageName: "com.a", label: "A" }, bytesEstimate: 1 },
			{ kind: "clear_app_cache", target: { label: "no package" }, bytesEstimate: 1 },
		]);
		expect(result.merged).toBe(0);
		expect(result.items.map((item) => item.kind)).toEqual(["clear_app_cache", "clear_app_cache"]);
	});

	it("the propose_cleanup tool path stores one clear_all_caches item and tells the model", async () => {
		const t = await openTestHarness();
		try {
			const { proposeCleanup } = await import("../src/tools.ts");
			const out = await t.commit((tx) =>
				proposeCleanup(
					tx,
					t.root.id,
					{
						title: "Clear caches",
						reason: "r",
						items: [
							{ kind: "clear_app_cache", target: { packageName: "com.a", label: "A" }, bytesEstimate: 400 },
							{ kind: "clear_app_cache", target: { packageName: "com.b", label: "B" }, bytesEstimate: 100 },
						],
					},
					1000,
				),
			);
			const parsed = JSON.parse(out) as { proposalId: string; note?: string };
			expect(parsed.note).toMatch(/clear_all_caches/);
			const stored = await t.harness.snapshot(Proposals, t.root.id, context);
			const proposal = stored?.proposals[parsed.proposalId]!;
			expect(proposal.items).toHaveLength(1);
			expect(proposal.items[0]!.kind).toBe("clear_all_caches");
			expect(proposal.bytesEstimate).toBe(500);
		} finally {
			await t.close();
		}
	});
});
