/**
 * The orchestration client's allowlist (docs/protocol.md §8): only
 * `presence update` with fixed arguments plus `--step/--status/--name/--join-session`,
 * and `memory capture` with fixed head plus `--note/--idempotency-key`. Crafted input — a
 * step containing `--name evil`, a newline, another flag, a malformed key or a status the
 * steward never sends — must not be able to call another subcommand or change a fixed
 * argument: the argv is built structurally and re-checked against the allowlist before
 * every call, and execFile passes arguments as argv entries, never through a shell.
 */
import { mkdtemp, chmod, readFile, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { afterAll, describe, expect, it } from "vitest";
import type { OrchestrationConfig } from "../../src/integrations.ts";
import {
	assertAllowlisted,
	presenceUpdateArgv,
	captureArgv,
	createOrchestrationClient,
	sanitizeNote,
	sanitizeSingleLine,
	type OrchestrationRunner,
} from "../../src/orchestration/client.ts";

const dirs: string[] = [];

afterAll(async () => {
	for (const dir of dirs) await rm(dir, { recursive: true, force: true });
});

async function scratchDir(prefix: string): Promise<string> {
	const dir = await mkdtemp(join(tmpdir(), `phone-steward-${prefix}-`));
	dirs.push(dir);
	return dir;
}

function recordingRunner(): {
	runner: OrchestrationRunner;
	calls: Array<{ argv: string[]; command: string; env: NodeJS.ProcessEnv; timeoutMs: number }>;
} {
	const calls: Array<{ argv: string[]; command: string; env: NodeJS.ProcessEnv; timeoutMs: number }> = [];
	const runner: OrchestrationRunner = async (argv, options) => {
		calls.push({ argv: [...argv], command: options.command, env: options.env, timeoutMs: options.timeoutMs });
		return { ok: true, stdout: "stub ok" };
	};
	return { runner, calls };
}

function config(command: string, overrides: Partial<OrchestrationConfig> = {}): OrchestrationConfig {
	return {
		enabled: true,
		command,
		memoryRoot: "/tmp/memory-scratch",
		boardDir: "/tmp/board-scratch",
		worklogDir: "/tmp/worklog-scratch",
		timeoutMs: 10_000,
		...overrides,
	};
}

const KEY = "abcd1234abcd1234";

describe("allowlisted argv builders", () => {
	it("presence update argv carries exactly the fixed arguments plus step, status and the stable join session", () => {
		expect(presenceUpdateArgv("checking storage", "working")).toEqual([
			"presence",
			"update",
			"--step",
			"checking storage",
			"--status",
			"working",
			"--name",
			"phone-steward",
			"--join-session",
			"steward-main",
		]);
	});

	it("capture argv carries the idempotency key and nothing else", () => {
		expect(captureArgv("Post-batch review: proposal pr-1 finished failure. [capture abcd1234abcd1234]", KEY)).toEqual([
			"memory",
			"capture",
			"--note",
			"Post-batch review: proposal pr-1 finished failure. [capture abcd1234abcd1234]",
			"--idempotency-key",
			KEY,
		]);
	});

	it("crafted step text cannot add a flag, change the name or break the line", () => {
		const crafted = "owner\n--name evil --source session-end-prompt --status idle";
		const argv = presenceUpdateArgv(crafted, "working");
		// One argv entry after --step: the newline is sanitised away, and no crafted
		// flag becomes its own argument.
		const stepIndex = argv.indexOf("--step");
		expect(argv[stepIndex! + 1]).toBe("owner --name evil --source session-end-prompt --status idle");
		expect(argv.filter((part) => part === "--name")).toHaveLength(1);
		expect(argv.filter((part) => part === "--status")).toHaveLength(1);
		expect(argv.filter((part) => part === "--join-session")).toHaveLength(1);
		// And the allowlist check accepts the built argv.
		expect(() => assertAllowlisted(argv)).not.toThrow();
	});

	it("crafted note text cannot add a capture flag", () => {
		const crafted = "note\n--source manual-note --target horizon --work 123";
		const argv = captureArgv(crafted, KEY);
		// The note stays one argv element — its embedded newline cannot split arguments.
		const noteIndex = argv.indexOf("--note");
		expect(typeof argv[noteIndex! + 1]).toBe("string");
		expect(argv.length).toBe(captureArgv("x", KEY).length);
		expect(argv.indexOf("--work")).toBe(-1);
		expect(argv.filter((part) => part === "--idempotency-key")).toHaveLength(1);
		expect(() => assertAllowlisted(argv)).not.toThrow();
	});

	it("rejects statuses outside working|waiting before any argv is built", () => {
		// The steward only ever sends working|waiting.
		expect(() => presenceUpdateArgv("x", "idle" as never)).toThrow();
		expect(() => presenceUpdateArgv("x", "busy" as never)).toThrow();
		expect(() => presenceUpdateArgv("x", "waiting --name evil" as never)).toThrow();
		for (const status of ["working", "waiting"] as const) {
			expect(() => presenceUpdateArgv("x", status)).not.toThrow();
		}
	});

	it("rejects idempotency keys that are not 16 lowercase hex", () => {
		for (const bad of ["", "abcd1234", "ABCD1234ABCD1234", "abcd1234abcd12341", "zzzz1234abcd1234", "abcd1234abcd1234 extra"]) {
			expect(() => captureArgv("note", bad), bad).toThrow();
		}
		expect(() => captureArgv("note", KEY)).not.toThrow();
	});

	it("assertAllowlisted rejects any other subcommand or a changed fixed argument", () => {
		const evil = [
			"memory", "capture", "--note", "x", "--idempotency-key", KEY, "--target", "project",
		];
		expect(() => assertAllowlisted(evil)).toThrow();
		const recall = ["memory", "recall", "what changed"];
		expect(() => assertAllowlisted(recall)).toThrow();
		const seed = ["seed"];
		expect(() => assertAllowlisted(seed)).toThrow();
		const noName = [
			"presence", "update", "--step", "x", "--status", "waiting", "--join-session", "steward-main",
		];
		expect(() => assertAllowlisted(noName)).toThrow();
		const noJoinSession = [
			"presence", "update", "--step", "x", "--status", "waiting", "--name", "phone-steward",
		];
		expect(() => assertAllowlisted(noJoinSession)).toThrow();
		const wrongJoinSession = [
			"presence", "update", "--step", "x", "--status", "working", "--name", "phone-steward", "--join-session", "other",
		];
		expect(() => assertAllowlisted(wrongJoinSession)).toThrow();
		const idleStatus = presenceUpdateArgv("x", "working").slice(0, -6).concat("--status", "idle", "--name", "phone-steward", "--join-session", "steward-main");
		expect(() => assertAllowlisted(idleStatus)).toThrow();
		const badKey = [
			"memory", "capture", "--note", "x", "--idempotency-key", "NOTHEX",
		];
		expect(() => assertAllowlisted(badKey)).toThrow();
	});

	it("sanitisation strips control characters and caps length", () => {
		expect(sanitizeSingleLine("a\nb\tc")).toBe("a b c");
		expect(sanitizeSingleLine("  spaced   out  ")).toBe("spaced out");
		expect(sanitizeSingleLine("x".repeat(500)).length).toBe(200);
		expect(sanitizeNote("line1\r\nline2")).toBe("line1\nline2");
	});
});

describe("client calls", () => {
	it("runs the built argv with the configured command, scratch roots and timeout", async () => {
		const { runner, calls } = recordingRunner();
		const client = createOrchestrationClient(config("/usr/local/bin/my-orchestrator"), { runCommand: runner });
		const result = await client.presenceUpdate("weekly check", "working");
		expect(result).toEqual({ ok: true, stdout: "stub ok" });
		expect(calls).toHaveLength(1);
		expect(calls[0]!.argv).toEqual(presenceUpdateArgv("weekly check", "working"));
		expect(calls[0]!.command).toBe("/usr/local/bin/my-orchestrator");
		expect(calls[0]!.env.STEWARD_MEMORY_ROOT).toBe("/tmp/memory-scratch");
		expect(calls[0]!.env.STEWARD_BOARD_DIR).toBe("/tmp/board-scratch");
		expect(calls[0]!.timeoutMs).toBe(10_000);
	});

	it("disabled config makes every call a no-op without running anything", async () => {
		const { runner, calls } = recordingRunner();
		const client = createOrchestrationClient(config("/usr/local/bin/my-orchestrator", { enabled: false }), { runCommand: runner });
		expect(await client.presenceUpdate("x", "waiting")).toEqual({ ok: true, stdout: "" });
		expect(await client.capture("note", KEY)).toEqual({ ok: true, stdout: "" });
		expect(calls).toHaveLength(0);
	});

	it("reports failures as results instead of throwing", async () => {
		const failing: OrchestrationRunner = async () => ({ ok: false, error: "exit 3" });
		const client = createOrchestrationClient(config("/usr/local/bin/my-orchestrator"), { runCommand: failing });
		const result = await client.capture("note", KEY);
		expect(result.ok).toBe(false);
		if (!result.ok) expect(result.error).toContain("exit 3");
	});

	it("the default runner spawns the configured executable with argv, not a shell string", async () => {
		const stubDir = await scratchDir("cli");
		const stubPath = join(stubDir, "stub-cli.js");
		const outPath = join(stubDir, "calls.jsonl");
		await writeFile(
			stubPath,
			[
				"#!/usr/bin/env node",
				"const fs = require('node:fs');",
				"fs.appendFileSync(process.env.ORCHESTRATION_STUB_OUT, JSON.stringify(process.argv.slice(2)) + '\\n');",
			].join("\n"),
			{ mode: 0o755 },
		);
		await chmod(stubPath, 0o755);
		const previousOut = process.env.ORCHESTRATION_STUB_OUT;
		process.env.ORCHESTRATION_STUB_OUT = outPath;
		// The client snapshots the environment at creation, so the stub path goes first.
		const client = createOrchestrationClient(config(stubPath, { timeoutMs: 20_000 }));
		try {
			const result = await client.presenceUpdate("live path", "working");
			expect(result.ok).toBe(true);
		} finally {
			if (previousOut === undefined) delete process.env.ORCHESTRATION_STUB_OUT;
			else process.env.ORCHESTRATION_STUB_OUT = previousOut;
		}
		const calls = (await readFile(outPath, "utf8")).split("\n").filter((line) => line !== "");
		expect(calls).toHaveLength(1);
		const argv = JSON.parse(calls[0]!) as string[];
		expect(argv).toEqual(presenceUpdateArgv("live path", "working"));
	}, 60_000);
});
