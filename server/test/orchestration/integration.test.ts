/**
 * The integration behaviour (`createOrchestrationIntegrations`): board card states
 * from run boundaries, the durable-review capture rule, every event reaching the worklog
 * mirror, and `enabled: false` silencing external calls while the mirror keeps writing.
 */
import { mkdtemp, readFile, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { BACKGROUND_CONTEXT } from "@earendil-works/chord/context";
import { afterAll, describe, expect, it } from "vitest";
import type { OrchestrationConfig } from "../../src/integrations.ts";
import { openTestSteward, type TestSteward } from "./test-steward.ts";
import { createOrchestrationIntegrations, type OrchestrationDeps } from "../../src/orchestration/index.ts";
import { OrchestrationOutbox } from "../../src/orchestration/outbox.ts";
import { presenceUpdateArgv, captureArgv } from "../../src/orchestration/client.ts";
import type { StewardEvent } from "../../src/integrations.ts";

const context = BACKGROUND_CONTEXT;
const dirs: string[] = [];
const ROOT = "/nonexistent-orchestrator";

afterAll(async () => {
	for (const dir of dirs) await rm(dir, { recursive: true, force: true });
});

async function scratch(prefix: string): Promise<string> {
	const dir = await mkdtemp(join(tmpdir(), `phone-steward-${prefix}-`));
	dirs.push(dir);
	return dir;
}

function config(worklogDir: string, overrides: Partial<OrchestrationConfig> = {}): OrchestrationConfig {
	return {
		enabled: true,
		command: ROOT,
		memoryRoot: join(worklogDir, "vault"),
		boardDir: join(worklogDir, "board"),
		worklogDir,
		timeoutMs: 10_000,
		...overrides,
	};
}

type Call = { argv: string[]; env: NodeJS.ProcessEnv; timeoutMs: number };

function recordingDeps(): { deps: OrchestrationDeps; calls: Call[] } {
	const calls: Call[] = [];
	return {
		calls,
		deps: {
			runCommand: async (argv, options) => {
				calls.push({ argv: [...argv], env: options.env, timeoutMs: options.timeoutMs });
				return { ok: true, stdout: "ok" };
			},
		},
	};
}

async function openWithIntegrations(
	worklogDir: string,
	cfg: OrchestrationConfig,
	deps: OrchestrationDeps,
): Promise<TestSteward & { integrations: ReturnType<typeof createOrchestrationIntegrations> }> {
	const integrations = createOrchestrationIntegrations(cfg, deps);
	const opened = await openTestSteward({
		dbPath: join(worklogDir, "steward.sqlite"),
		extensions: integrations.extensions,
		fauxPlan: { hang: false },
	});
	opened.harness.resume();
	await integrations.onHarnessOpen?.(opened.harness, context);
	return Object.assign(opened, { integrations });
}

async function emit(
	opened: TestSteward & { integrations: ReturnType<typeof createOrchestrationIntegrations> },
	event: StewardEvent,
): Promise<void> {
	await opened.harness.commit(
		(tx) => opened.integrations.stewardEvent!(tx, opened.root.id, event),
		context,
	);
}

async function outboxEntries(opened: TestSteward): Promise<Record<string, Record<string, unknown>>> {
	const doc = await opened.harness.snapshot(OrchestrationOutbox, opened.root.id, context);
	return (doc?.entries ?? {}) as unknown as Record<string, Record<string, unknown>>;
}

async function waitForDone(
	opened: TestSteward,
	dedupeKey: string,
	timeoutMs = 10_000,
): Promise<Record<string, unknown>> {
	const deadline = Date.now() + timeoutMs;
	for (;;) {
		const entries = await outboxEntries(opened);
		const entry = entries[dedupeKey];
		if (entry?.state === "done") return entry;
		if (Date.now() > deadline) throw new Error(`entry ${dedupeKey} never delivered: ${JSON.stringify(entry)}`);
		await new Promise((resolve) => setTimeout(resolve, 20));
	}
}

function mirrorFileOf(opened: TestSteward, worklogDir: string): Promise<string> {
	// One conversation: the only .jsonl in the worklog dir.
	return import("node:fs").then((fs) => {
		const name = fs.readdirSync(worklogDir).find((file) => file.endsWith(".jsonl"));
		if (name === undefined) throw new Error("no mirror file");
		return join(worklogDir, name);
	});
}

/**
 * Read the mirror once every line parses: the writer appends live, so a reader can
 * always catch a torn tail and simply re-read — the same tolerance a real store applies.
 */
async function readMirrorWhenWhole(filePath: string, timeoutMs = 5_000): Promise<string[]> {
	const deadline = Date.now() + timeoutMs;
	for (;;) {
		const content = await readFile(filePath, "utf8");
		const lines = content.split("\n").filter((line) => line !== "");
		if (content.endsWith("\n") && lines.every((line) => isJson(line))) return lines;
		if (Date.now() > deadline) throw new Error(`mirror file never settled: ${JSON.stringify(content.slice(-200))}`);
		await new Promise((resolve) => setTimeout(resolve, 25));
	}
}

function isJson(line: string): boolean {
	try {
		JSON.parse(line);
		return true;
	} catch {
		return false;
	}
}

describe("orchestration integration", () => {
	it("run_start sets the card to working with the reason as the step", async () => {
		const worklogDir = await scratch("int1");
		const { deps, calls } = recordingDeps();
		const opened = await openWithIntegrations(worklogDir, config(worklogDir), deps);
		try {
			await emit(opened, { kind: "run_start", atMs: 5, data: { reason: "owner" } });
			const entries = await outboxEntries(opened);
			const key = Object.keys(entries)[0]!;
			expect(key).toMatch(/^presence:[0-9a-f]{16}$/);
			await waitForDone(opened, key);
			expect(calls).toHaveLength(1);
			expect(calls[0]!.argv).toEqual(presenceUpdateArgv("owner", "working"));
			expect(calls[0]!.env.STEWARD_MEMORY_ROOT).toBe(config(worklogDir).memoryRoot);
			expect(calls[0]!.env.STEWARD_BOARD_DIR).toBe(config(worklogDir).boardDir);
			expect(calls[0]!.timeoutMs).toBe(10_000);
		} finally {
			await opened.integrations.close?.();
			await opened.harness.close(context);
		}
	});

	it("run_end always reports waiting: step idle, or the owner-approval step with a known proposal", async () => {
		const worklogDir = await scratch("int2");
		const { deps, calls } = recordingDeps();
		const opened = await openWithIntegrations(worklogDir, config(worklogDir), deps);
		try {
			// Amendment A1: the board has no idle — run end is waiting with the step idle.
			await emit(opened, { kind: "run_end", atMs: 6, data: {} });
			// A known, allowlisted proposal id names it in the step.
			await emit(opened, { kind: "run_end", atMs: 7, data: { openProposals: 1, reason: "proposal waiting", proposalId: "pr-0007" } });
			// No id known: the generic approval step.
			await emit(opened, { kind: "run_end", atMs: 8, data: { openProposals: 2 } });
			// A value that is not an allowlisted id is never echoed into the step.
			await emit(opened, { kind: "run_end", atMs: 9, data: { openProposals: 1, proposalId: "07700900123" } });
			const entries = await outboxEntries(opened);
			expect(Object.keys(entries)).toHaveLength(4);
			for (const key of Object.keys(entries)) await waitForDone(opened, key);
			const statuses = calls.map((call) => call.argv[call.argv.indexOf("--status") + 1]);
			expect(statuses).toEqual(["waiting", "waiting", "waiting", "waiting"]);
			const steps = calls.map((call) => call.argv[call.argv.indexOf("--step") + 1]);
			expect(steps).toEqual([
				"idle",
				"waiting for owner approval of pr-0007",
				"waiting for owner approval",
				"waiting for owner approval",
			]);
			// Every board call carries the stable join session (amendment A1).
			for (const call of calls) {
				expect(call.argv.slice(call.argv.indexOf("--join-session") + 1)).toEqual(["steward-main"]);
			}
		} finally {
			await opened.integrations.close?.();
			await opened.harness.close(context);
		}
	});

	it("review_line captures exactly the durable results", async () => {
		const worklogDir = await scratch("int3");
		const { deps, calls } = recordingDeps();
		const opened = await openWithIntegrations(worklogDir, config(worklogDir), deps);
		try {
			await emit(opened, { kind: "review_line", atMs: 8, data: { outcome: "failure", proposalId: "pr-0001" } });
			await emit(opened, { kind: "review_line", atMs: 9, data: { outcome: "ok", proposalId: "pr-0002", bytesEstimate: 1_000, bytesFreed: 100 } });
			// Ordinary success with a small delta: no capture.
			await emit(opened, { kind: "review_line", atMs: 10, data: { outcome: "ok", proposalId: "pr-0003", bytesEstimate: 1_000, bytesFreed: 900 } });
			const entries = await outboxEntries(opened);
			expect(Object.keys(entries)).toHaveLength(2);
			for (const key of Object.keys(entries)) {
				expect(key.startsWith("capture:")).toBe(true);
				await waitForDone(opened, key);
			}
			const notes = calls.map((call) => call.argv[call.argv.indexOf("--note") + 1]);
			expect(notes[0]).toContain("pr-0001");
			expect(notes[0]).toContain("failure");
			expect(notes[1]).toContain("90%");
			// Amendment A2: the argv carries the note's token as the idempotency key.
			const noteToken = (note: string): string => (note.match(/\[capture ([0-9a-f]{16})\]/) ?? [])[1] ?? "";
			expect(calls[0]!.argv).toEqual(captureArgv(notes[0]!, noteToken(notes[0]!)));
			const keys = calls.map((call) => call.argv[call.argv.indexOf("--idempotency-key") + 1]);
			for (let index = 0; index < keys.length; index += 1) {
				expect(keys[index]).toMatch(/^[0-9a-f]{16}$/);
				expect(notes[index]).toContain(`[capture ${keys[index]}]`);
			}
			expect(keys[0]).not.toBe(keys[1]);
		} finally {
			await opened.integrations.close?.();
			await opened.harness.close(context);
		}
	});

	it("every event becomes a steward-event worklog line", async () => {
		const worklogDir = await scratch("int4");
		const { deps } = recordingDeps();
		const opened = await openWithIntegrations(worklogDir, config(worklogDir), deps);
		try {
			await emit(opened, { kind: "run_start", atMs: 11, data: { reason: "weekly_check" } });
			await emit(opened, { kind: "job_queued", atMs: 12, data: { jobId: "pj-1", jobKind: "storage_overview" } });
			await emit(opened, { kind: "run_end", atMs: 13, data: {} });
			// The mirror started at onHarnessOpen; read once the file is whole and settled.
			const filePath = await mirrorFileOf(opened, worklogDir);
			const deadline = Date.now() + 5_000;
			let events: Array<Record<string, unknown>> = [];
			for (;;) {
				const lines = await readMirrorWhenWhole(filePath);
				events = lines
					.map((line) => JSON.parse(line) as Record<string, unknown>)
					.filter((line) => line.type === "custom")
					.map((line) => line.data as Record<string, unknown>);
				if (events.length >= 3 || Date.now() > deadline) break;
				await new Promise((resolve) => setTimeout(resolve, 25));
			}
			expect(events.map((data) => data.kind)).toEqual(["run_start", "job_queued", "run_end"]);
			expect(events[0]).toEqual({ kind: "run_start", reason: "weekly_check" });
		} finally {
			await opened.integrations.close?.();
			await opened.harness.close(context);
		}
	});

	it("enabled: false silences every external call but the mirror still writes", async () => {
		const worklogDir = await scratch("int5");
		const { deps, calls } = recordingDeps();
		const opened = await openWithIntegrations(worklogDir, config(worklogDir, { enabled: false }), deps);
		try {
			await emit(opened, { kind: "run_start", atMs: 14, data: { reason: "owner" } });
			await emit(opened, { kind: "review_line", atMs: 15, data: { outcome: "failure", proposalId: "pr-9" } });
			const entries = await outboxEntries(opened);
			expect(Object.keys(entries)).toHaveLength(0);
			await new Promise((resolve) => setTimeout(resolve, 300));
			expect(calls).toHaveLength(0);
			const filePath = await mirrorFileOf(opened, worklogDir);
			const lines = (await readFile(filePath, "utf8")).split("\n").filter((line) => line !== "");
			const kinds = lines
				.map((line) => JSON.parse(line) as Record<string, unknown>)
				.filter((line) => line.type === "custom")
				.map((line) => (line.data as Record<string, unknown>).kind);
			expect(kinds).toEqual(["run_start", "review_line"]);
		} finally {
			await opened.integrations.close?.();
			await opened.harness.close(context);
		}
	});

	it("event data is allowlisted before persistence: junk keys never reach the worklog line", async () => {
		const worklogDir = await scratch("int4b");
		const { deps } = recordingDeps();
		const opened = await openWithIntegrations(worklogDir, config(worklogDir), deps);
		try {
			// Amendment A3: the same event, but carrying file names, chat text and a
			// phone-number-shaped id alongside the legitimate facts.
			await emit(opened, {
				kind: "run_start",
				atMs: 21,
				data: {
					reason: "owner",
					fileName: "/home/adopter/private.pdf",
					chatText: "Free space is 5.0 GB, Susan.",
					jobId: "07700900123",
					contactName: "Susan",
					bytesFreed: 4_096,
				},
			});
			await emit(opened, {
				kind: "review_line",
				atMs: 22,
				data: {
					outcome: "failure",
					proposalId: "pr-0042",
					chatText: "WhatsApp chat with Susan",
					fileName: "DCIM/Camera/IMG_20260601_101010.jpg",
				},
			});
			const filePath = await mirrorFileOf(opened, worklogDir);
			const deadline = Date.now() + 5_000;
			let events: Array<Record<string, unknown>> = [];
			for (;;) {
				const lines = await readMirrorWhenWhole(filePath);
				events = lines
					.map((line) => JSON.parse(line) as Record<string, unknown>)
					.filter((line) => line.type === "custom")
					.map((line) => line.data as Record<string, unknown>);
				if (events.length >= 2 || Date.now() > deadline) break;
				await new Promise((resolve) => setTimeout(resolve, 25));
			}
			expect(events).toHaveLength(2);
			// Only the allowlisted facts survive — per kind.
			expect(events[0]).toEqual({ kind: "run_start", reason: "owner" });
			expect(events[1]).toEqual({ kind: "review_line", outcome: "failure", proposalId: "pr-0042" });
			const whole = await readFile(filePath, "utf8");
			for (const forbidden of ["private.pdf", "Susan", "07700900123", "IMG_20260601_101010", "Free space is 5.0 GB"]) {
				expect(whole, forbidden).not.toContain(forbidden);
			}
		} finally {
			await opened.integrations.close?.();
			await opened.harness.close(context);
		}
	});

	it("a full faux run completes with the integration installed; the run itself is untouched", async () => {
		const worklogDir = await scratch("int6");
		const { deps, calls } = recordingDeps();
		const opened = await openWithIntegrations(worklogDir, config(worklogDir), deps);
		try {
			// A real (scripted, free) model run through the core's own phone tool.
			const submission = await opened.root.submit(
				{ type: "input", content: "Please check my phone" },
				context,
			);
			const settled = await submission.wait(context);
			expect(settled.status).toBe("done");
			// No core event source exists in this minimal harness, so no effects fired.
			expect(calls).toHaveLength(0);
			// The conversation still mirrors its committed entries.
			const filePath = await mirrorFileOf(opened, worklogDir);
			const lines = await readMirrorWhenWhole(filePath);
			const types = lines.map((line) => (JSON.parse(line) as Record<string, unknown>).type);
			expect(types).toContain("message");
			const toolCalls = lines.filter((line) => line.includes("phone_echo"));
			expect(toolCalls.length).toBeGreaterThan(0);
		} finally {
			await opened.integrations.close?.();
			await opened.harness.close(context);
		}
	});
});
