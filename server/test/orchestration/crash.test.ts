/**
 * Real SIGKILL crash tests for the orchestration integration (docs/protocol.md §8/§10).
 *
 * Every crash kills a separate child process at a deterministic gate — before or after
 * the mirror's file write, mid-line, or on both sides of the outbox's command call — and
 * every reopen is a fresh process opening the same SQLite file. Delivery counts come
 * from the stub command's witness file, never from memory; mirror integrity comes from the
 * file itself.
 */
import { spawn, type ChildProcess } from "node:child_process";
import { existsSync, writeFileSync, mkdirSync } from "node:fs";
import { mkdtemp, readFile, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { fileURLToPath } from "node:url";
import { afterAll, describe, expect, it } from "vitest";

const CHILD = fileURLToPath(new URL("./crash-child.ts", import.meta.url));

const dirs: string[] = [];

afterAll(async () => {
	for (const dir of dirs) await rm(dir, { recursive: true, force: true });
});

type ChildRun = {
	child: ChildProcess;
	stdout: string;
	stderr: string;
	done: Promise<{ code: number | null; signal: NodeJS.Signals | null }>;
};

function spawnChild(stage: Record<string, unknown>): ChildRun {
	const child = spawn(process.execPath, [CHILD, JSON.stringify(stage)], {
		stdio: ["ignore", "pipe", "pipe"],
		// The child arms crash gates; production code cannot (no such variable).
		env: { ...process.env, PHONE_STEWARD_CRASH_GATES: "1" },
	});
	const run: ChildRun = { child, stdout: "", stderr: "", done: undefined as never };
	run.done = new Promise((resolve, reject) => {
		child.stdout!.on("data", (chunk: Buffer) => {
			run.stdout += chunk.toString();
		});
		child.stderr!.on("data", (chunk: Buffer) => {
			run.stderr += chunk.toString();
		});
		child.on("error", reject);
		child.on("close", (code, signal) => resolve({ code, signal }));
	});
	return run;
}

function markers(run: ChildRun): Array<Record<string, unknown>> {
	return run.stdout
		.split("\n")
		.filter((line) => line.startsWith("MARKER "))
		.map((line) => JSON.parse(line.slice("MARKER ".length)) as Record<string, unknown>);
}

function lastResult(run: ChildRun): Record<string, unknown> {
	const results = markers(run).filter((marker) => marker.event === "result");
	if (results.length === 0) {
		throw new Error(`no result marker; stdout=${JSON.stringify(run.stdout)} stderr=${JSON.stringify(run.stderr)}`);
	}
	return results[results.length - 1]!;
}

async function waitForFile(path: string, timeoutMs = 30_000): Promise<void> {
	const deadline = Date.now() + timeoutMs;
	while (Date.now() < deadline) {
		if (existsSync(path)) return;
		await new Promise((resolve) => setTimeout(resolve, 10));
	}
	throw new Error(`marker file ${path} never appeared`);
}

async function sigkill(run: ChildRun): Promise<NodeJS.Signals | null> {
	run.child.kill("SIGKILL");
	const { signal } = await run.done;
	return signal;
}

async function scratch(prefix: string): Promise<string> {
	const dir = await mkdtemp(join(tmpdir(), `phone-steward-aoscrash-${prefix}-`));
	dirs.push(dir);
	return dir;
}

/**
 * Stub orchestration command that mimics a real one: it records the argv of every call
 * (the witness file, one JSON object per line: `{key, duplicate, argv}`), tracks seen
 * idempotency keys in a side file, and marks a repeat key as a duplicate no-op — the
 * effect a real capture backend has. The argv witness is what the crash tests read;
 * the duplicate flag is what "one persisted effect" is counted from.
 */
async function makeStubCommand(commandDir: string): Promise<{ commandPath: string; stubOut: string }> {
	mkdirSync(commandDir, { recursive: true });
	const commandPath = join(commandDir, "stub-orchestrator.js");
	writeFileSync(
		commandPath,
		[
			"#!/usr/bin/env node",
			"const fs = require('node:fs');",
			"const path = require('node:path');",
			"const argv = process.argv.slice(2);",
			"const keyIndex = argv.indexOf('--idempotency-key');",
			"const key = keyIndex === -1 ? null : argv[keyIndex + 1];",
			"const keysPath = path.join(path.dirname(process.env.ORCHESTRATION_STUB_OUT), 'seen-keys.json');",
			"let seen = {};",
			"try { seen = JSON.parse(fs.readFileSync(keysPath, 'utf8')); } catch {}",
			"let duplicate = false;",
			"if (key !== null) {",
			"  if (seen[key]) { duplicate = true; }",
			"  else { seen[key] = true; fs.writeFileSync(keysPath, JSON.stringify(seen)); }",
			"}",
			// Presence updates update one card in place (idempotent by design), like a real board.
			"if (argv[0] === 'presence' && argv[1] === 'update' && process.env.STEWARD_BOARD_DIR) {",
			"  const stepIndex = argv.indexOf('--step');",
			"  const statusIndex = argv.indexOf('--status');",
			"  const dir = path.join(process.env.STEWARD_BOARD_DIR, 'agents');",
			"  fs.mkdirSync(dir, { recursive: true });",
			"  fs.writeFileSync(path.join(dir, 'phone-steward.json'), JSON.stringify({ name: 'phone-steward', step: argv[stepIndex + 1], status: argv[statusIndex + 1] }));",
			"}",
			"fs.appendFileSync(process.env.ORCHESTRATION_STUB_OUT, JSON.stringify({ key, duplicate, argv }) + '\\n');",
		].join("\n"),
		{ mode: 0o755 },
	);
	return { commandPath, stubOut: join(commandDir, "calls.jsonl") };
}

describe("orchestration crash recovery (real SIGKILL, fresh reopen)", () => {
	it("mirror: kill before the event line's write; the reopen writes it exactly once", async () => {
		const dir = await scratch("mw1");
		const gateDir = join(dir, "gates");
		mkdirSync(gateDir, { recursive: true });
		const worklogDir = join(dir, "worklog");
		const stage = { stage: "mirror-fill", gate: "before-write", db: dir, gateDir, worklogDir, command: "", stubOut: "" };
		const a = spawnChild(stage);
		await waitForFile(join(gateDir, "before-write-marker"));
		expect(await sigkill(a)).toBe("SIGKILL");

		const b = spawnChild({ ...stage, stage: "mirror-verify" });
		const { code } = await b.done;
		expect(code, `verify child stderr=${JSON.stringify(b.stderr)}`).toBe(0);
		const result = lastResult(b);
		// header + model_change survived; the event line was written once, by the reopen.
		expect(result.count).toBe(3);
		expect(result.uniqueIds).toBe(true);
		expect(result.chainOk).toBe(true);
		expect(result.eventKinds).toEqual(["run_start"]);
	});

	it("mirror: kill after the write; the reopen never duplicates the line", async () => {
		const dir = await scratch("mw2");
		const gateDir = join(dir, "gates");
		mkdirSync(gateDir, { recursive: true });
		const worklogDir = join(dir, "worklog");
		const stage = { stage: "mirror-fill", gate: "after-write", db: dir, gateDir, worklogDir, command: "", stubOut: "" };
		const a = spawnChild(stage);
		await waitForFile(join(gateDir, "after-write-marker"));
		// The line is on disk; the kill lands before the drain trim.
		await new Promise((resolve) => setTimeout(resolve, 100));
		expect(await sigkill(a)).toBe("SIGKILL");

		const b = spawnChild({ ...stage, stage: "mirror-verify" });
		const { code } = await b.done;
		expect(code, `verify child stderr=${JSON.stringify(b.stderr)}`).toBe(0);
		const result = lastResult(b);
		expect(result.count).toBe(3);
		expect(result.uniqueIds).toBe(true);
		expect(result.chainOk).toBe(true);
		expect(result.eventKinds).toEqual(["run_start"]);
	});

	it("mirror: kill mid-line; the reopen repairs the truncated tail and rewrites it", async () => {
		const dir = await scratch("mw3");
		const gateDir = join(dir, "gates");
		mkdirSync(gateDir, { recursive: true });
		const worklogDir = join(dir, "worklog");
		const stage = { stage: "mirror-fill", gate: "truncated-write", db: dir, gateDir, worklogDir, command: "", stubOut: "" };
		const a = spawnChild(stage);
		await waitForFile(join(gateDir, "truncated-write-marker"));
		// The first half of the JSON line is flushed with no newline; SIGKILL freezes it.
		await new Promise((resolve) => setTimeout(resolve, 100));
		expect(await sigkill(a)).toBe("SIGKILL");
		// Prove the damage is really there before the reopen repairs it.
		const worklogFiles = (await readFile(join(worklogDir, existsSync(join(worklogDir, "x")) ? "x" : "")).catch(() => undefined), undefined);
		void worklogFiles;
		const { readdir } = await import("node:fs/promises");
		const fileName = (await readdir(worklogDir)).find((file) => file.endsWith(".jsonl"))!;
		const damaged = await readFile(join(worklogDir, fileName), "utf8");
		expect(damaged.endsWith("\n")).toBe(false);

		const b = spawnChild({ ...stage, stage: "mirror-verify" });
		const { code } = await b.done;
		expect(code, `verify child stderr=${JSON.stringify(b.stderr)}`).toBe(0);
		const result = lastResult(b);
		expect(result.count).toBe(3);
		expect(result.uniqueIds).toBe(true);
		expect(result.chainOk).toBe(true);
		expect(result.eventKinds).toEqual(["run_start"]);
	});

	it("outbox: kill after the presence call, before the mark; the replay re-sends the same update once", async () => {
		const dir = await scratch("ob1");
		const gateDir = join(dir, "gates");
		mkdirSync(gateDir, { recursive: true });
		const worklogDir = join(dir, "worklog");
		const commandDir = join(dir, "stub");
		const { commandPath, stubOut } = await makeStubCommand(commandDir);
		const stage = { stage: "outbox-enqueue", gate: "after-call", db: dir, gateDir, worklogDir, command: commandPath, stubOut };
		const a = spawnChild(stage);
		await waitForFile(join(gateDir, "after-call-marker"));
		// The CLI call completed; the mark checkpoint never ran.
		await new Promise((resolve) => setTimeout(resolve, 200));
		expect(await sigkill(a)).toBe("SIGKILL");
		const callsAfterKill = (await readFile(stubOut, "utf8")).split("\n").filter((line) => line !== "").length;
		expect(callsAfterKill).toBe(1);

		const b = spawnChild({ ...stage, stage: "outbox-verify" });
		const { code } = await b.done;
		expect(code, `verify child stderr=${JSON.stringify(b.stderr)}`).toBe(0);
		const result = lastResult(b);
		expect(result.entryStates).toEqual(["done"]);
		// Exactly one replay: the same argv again (idempotent update), nothing more.
		expect(result.callCount).toBe(2);
		expect(result.argvIdentical).toBe(true);
		expect(result.statuses).toEqual(["working", "working"]);
		// Idempotent at the card level too: still exactly one card, updated in place.
		expect(result.boardCardCount).toBe(1);
		// And a third process finds nothing left to do.
		const c = spawnChild({ ...stage, stage: "outbox-verify" });
		const done3 = await c.done;
		expect(done3.code).toBe(0);
		expect(lastResult(c).callCount).toBe(2);
		expect(lastResult(c).boardCardCount).toBe(1);
	}, 90_000);

	it("outbox: kill after the capture call; the replay re-sends the same note and token", async () => {
		const dir = await scratch("ob2");
		const gateDir = join(dir, "gates");
		mkdirSync(gateDir, { recursive: true });
		const worklogDir = join(dir, "worklog");
		const commandDir = join(dir, "stub");
		const { commandPath, stubOut } = await makeStubCommand(commandDir);
		const stage = { stage: "outbox-enqueue", gate: "after-call", db: dir, gateDir, worklogDir, command: commandPath, stubOut, kind: "capture" };
		const a = spawnChild(stage);
		await waitForFile(join(gateDir, "after-call-marker"));
		await new Promise((resolve) => setTimeout(resolve, 200));
		expect(await sigkill(a)).toBe("SIGKILL");

		const b = spawnChild({ ...stage, stage: "outbox-verify" });
		const { code } = await b.done;
		expect(code, `verify child stderr=${JSON.stringify(b.stderr)}`).toBe(0);
		const result = lastResult(b);
		expect(result.entryStates).toEqual(["done"]);
		// The replay still happens (two calls), but the idempotency key makes it a no-op at
		// the effect level: the stub records ONE persisted capture across the replay — same
		// key, identical note and token, no duplicate effect.
		expect(result.callCount).toBe(2);
		expect(result.persistedCaptures).toBe(1);
		expect(result.notesIdentical).toBe(true);
	}, 90_000);

	it("outbox: kill before the call; the replay delivers once from the reopened store", async () => {
		const dir = await scratch("ob3");
		const gateDir = join(dir, "gates");
		mkdirSync(gateDir, { recursive: true });
		const worklogDir = join(dir, "worklog");
		const commandDir = join(dir, "stub");
		const { commandPath, stubOut } = await makeStubCommand(commandDir);
		const stage = { stage: "outbox-enqueue", gate: "before-call", db: dir, gateDir, worklogDir, command: commandPath, stubOut };
		const a = spawnChild(stage);
		await waitForFile(join(gateDir, "before-call-marker"));
		expect(await sigkill(a)).toBe("SIGKILL");
		// No call was made before the kill.
		expect(existsSync(stubOut)).toBe(false);

		const b = spawnChild({ ...stage, stage: "outbox-verify" });
		const { code } = await b.done;
		expect(code, `verify child stderr=${JSON.stringify(b.stderr)}`).toBe(0);
		const result = lastResult(b);
		expect(result.entryStates).toEqual(["done"]);
		expect(result.callCount).toBe(1);
		expect(result.argvIdentical).toBe(true);
	}, 90_000);
});
