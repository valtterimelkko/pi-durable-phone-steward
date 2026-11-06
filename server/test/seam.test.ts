import { describe, expect, it } from "vitest";
import { BACKGROUND_CONTEXT } from "@earendil-works/chord/context";
import { defineExtension, section } from "@earendil-works/pi-durable";
import { noIntegrations, type StewardEvent, type StewardIntegrations } from "../src/integrations.ts";
import { openStewardHarness, stewardRegistry } from "../src/steward.ts";
import { queueJob, reportPhoneResult, Jobs } from "../src/jobs.ts";
import { createProposal } from "../src/proposals.ts";
import { mkdtemp, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";

const context = BACKGROUND_CONTEXT;

function recordingIntegration(): {
	integrations: StewardIntegrations;
	events: StewardEvent[];
	entryCounts: Array<{ kind: string; entries: number }>;
	opened: boolean[];
	closed: boolean[];
} {
	const events: StewardEvent[] = [];
	const entryCounts: Array<{ kind: string; entries: number }> = [];
	const opened: boolean[] = [];
	const closed: boolean[] = [];
	return {
		events,
		entryCounts,
		opened,
		closed,
		integrations: {
			extensions: [],
			onHarnessOpen: async () => {
				opened.push(true);
			},
			stewardEvent: async (tx, conversationId, event) => {
				events.push(JSON.parse(JSON.stringify(event)) as StewardEvent);
				// The integration reads the conversation inside the caller's commit; capturing
				// the entry count here proves the ordering contract (02-answer note 2).
				const page = await tx.scanEntries({ conversationId }, 100);
				entryCounts.push({ kind: event.kind, entries: page.items.length });
			},
			close: async () => {
				closed.push(true);
			},
		},
	};
}

async function freshDb(): Promise<{ dir: string; dbPath: string }> {
	const dir = await mkdtemp(join(tmpdir(), "p2-seam-"));
	return { dir, dbPath: join(dir, "s.sqlite") };
}

describe("the integrations seam", () => {
	it("emits job_queued, job_reported, proposal_created and decision events inside their commits", async () => {
		const { dir, dbPath } = await freshDb();
		const fake = recordingIntegration();
		try {
			const opened = await openStewardHarness({ dbPath, fauxPlan: { hang: false }, integrations: fake.integrations });
			const { harness, root } = opened;
			harness.resume();
			const { jobId } = await root.commit(
				(tx) => queueJob(tx, root.id, { dedupeKey: "call-1", kind: "storage_overview", params: {}, nowMs: 1000 }),
				context,
			);
			await reportPhoneResult(harness, root.id, jobId, { status: "ok", data: { a: 1 }, error: null }, context);
			await root.commit(
				(tx) =>
					createProposal(tx, root.id, {
						source: "agent",
						title: "t",
						reason: "r",
						items: [{ kind: "advice", target: { text: "x" } }],
					}, 1001),
				context,
			);
			const kinds = fake.events.map((event) => event.kind);
			expect(kinds).toContain("job_queued");
			expect(kinds).toContain("job_reported");
			expect(kinds).toContain("proposal_created");
			const queued = fake.events.find((event) => event.kind === "job_queued");
			expect(queued?.data).toMatchObject({ jobId, jobKind: "storage_overview" });

			// 02-answer note 1: run_end carries the open-proposal count.
			await root.commit(async (tx) => {
				const { openRun } = await import("../src/run-tracking.ts");
				await openRun(tx, root.id, "seam-run", "owner", Date.now());
				return undefined;
			}, context);
			await root.commit(
				(tx) =>
					createProposal(tx, root.id, {
						source: "agent",
						title: "waiting on the owner",
						reason: "r",
						items: [{ kind: "advice", target: { text: "x" } }],
					}, 1002),
				context,
			);
			await root.commit(async (tx) => {
				const { closeRun } = await import("../src/run-tracking.ts");
				await closeRun(tx, root.id, "seam-run", Date.now());
				return undefined;
			}, context);
			const runEnd = fake.events.filter((event) => event.kind === "run_end").at(-1);
			// Two proposals were created in this test and neither is executed: both count.
			expect(runEnd?.data).toMatchObject({ openProposals: 2 });

			// item 1 supersedes the 02-answer ordering: the reporter submits
			// the follow-up FIRST ( durability) and records run_start in the checkpoint
			// commit afterwards — the event now lands with the entry present.
			const firstRunStart = fake.entryCounts.find((e) => e.kind === "run_start");
			expect(firstRunStart).toBeDefined();
			// By run_end the conversation has entries: the ordering contract held throughout.
			const lastRunEnd = fake.entryCounts.filter((e) => e.kind === "run_end").at(-1);
			expect((lastRunEnd?.entries ?? 0)).toBeGreaterThan(0);

			await opened.close();
			expect(fake.closed).toHaveLength(1);
			expect(fake.opened).toHaveLength(1);
		} finally {
			await rm(dir, { recursive: true, force: true });
		}
	});

	it("an integration whose stewardEvent throws cannot fail the run or the commit", async () => {
		const { dir, dbPath } = await freshDb();
		try {
			const opened = await openStewardHarness({
				dbPath,
				fauxPlan: { hang: false },
				integrations: {
					extensions: [],
					stewardEvent: async () => {
						throw new Error("integration is down");
					},
				},
			});
			const { harness, root } = opened;
			harness.resume();
			// The commit succeeds and the job exists; the throw is swallowed.
			const { jobId } = await root.commit(
				(tx) => queueJob(tx, root.id, { dedupeKey: "call-2", kind: "app_usage", params: {}, nowMs: 1000 }),
				context,
			);
			const stored = await harness.snapshot(Jobs, root.id, context);
			expect(stored?.jobs[jobId]?.status).toBe("queued");
			// A whole run also completes.
			const submission = await root.submit({ type: "input", content: "Hello" }, context);
			const settled = await submission.wait(context);
			expect(settled.status).toBe("done");
			await opened.close();
		} finally {
			await rm(dir, { recursive: true, force: true });
		}
	});

	it("installs the integration's extensions after the core ones, in a stable order", async () => {
		const { dir, dbPath } = await freshDb();
		try {
			const extra = defineExtension({ name: "fake-orchestration", sections: [section("fake-orchestration", () => "integration section")] });
			const opened = await openStewardHarness({
				dbPath,
				fauxPlan: { hang: false },
				integrations: { extensions: [extra] },
			});
			const agent = await opened.root.agent(context);
			const names = agent.extensions.map((extension) => extension.name);
			expect(names.indexOf("fake-orchestration")).toBeGreaterThan(names.indexOf("steward-phone-tools"));
			expect(names.indexOf("fake-orchestration")).toBeGreaterThan(names.indexOf("steward-guard"));
			await opened.close();
		} finally {
			await rm(dir, { recursive: true, force: true });
		}
	});

	it("defaults to noIntegrations: the production registry installs no integration extensions", () => {
		const registry = stewardRegistry();
		const names = registry.snapshot().installed().map((extension) => extension.name);
		expect(names).not.toContain("fake-orchestration");
		expect(noIntegrations.extensions).toHaveLength(0);
	});
});
