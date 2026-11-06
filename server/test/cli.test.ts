/**
 * item 1 (parent P1): the CLI never silently defaults to production state.
 * Without a reachable admin socket, direct-state mode requires PHONE_STEWARD_STATE_DIR to
 * be set explicitly; otherwise the CLI exits non-zero and creates nothing. The default
 * path is injectable (PHONE_STEWARD_DEFAULT_STATE_DIR) so the test never touches
 * /var/lib.
 */
import { existsSync } from "node:fs";
import { mkdtemp, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { fileURLToPath } from "node:url";
import { expect, it } from "vitest";

const CLI = fileURLToPath(new URL("../src/cli.ts", import.meta.url));

function runCli(env: Record<string, string | undefined>, args: string[]): Promise<{ code: number | null; stderr: string; stdout: string }> {
	return new Promise((resolve) => {
		const { spawn } = require("node:child_process") as typeof import("node:child_process");
		const cleanEnv: Record<string, string | undefined> = { ...process.env };
		delete cleanEnv.PHONE_STEWARD_STATE_DIR;
		delete cleanEnv.PHONE_STEWARD_DEFAULT_STATE_DIR;
		const child = spawn(process.execPath, ["--experimental-strip-types", CLI, ...args], {
			env: { ...cleanEnv, ...env },
			stdio: ["ignore", "pipe", "pipe"],
		});
		let stderr = "";
		let stdout = "";
		child.stdout!.on("data", (chunk: Buffer) => {
			stdout += chunk.toString();
		});
		child.stderr!.on("data", (chunk: Buffer) => {
			stderr += chunk.toString();
		});
		child.on("close", (code) => resolve({ code, stderr, stdout }));
	});
}

it("exits non-zero with no state dir variable, creating nothing at the default path", async () => {
	const home = await mkdtemp(join(tmpdir(), "cli-home-"));
	const defaultDir = join(home, "default-state");
	try {
		const { code, stderr } = await runCli(
			{ HOME: home, PHONE_STEWARD_DEFAULT_STATE_DIR: defaultDir },
			["status", "--json"],
		);
		expect(code, `stderr=${stderr}`).not.toBe(0);
		expect(stderr).toContain("PHONE_STEWARD_STATE_DIR");
		// Bite: restoring the silent fallback makes this test fail — and proves nothing was
		// created at the default path while the guard holds.
		expect(existsSync(defaultDir)).toBe(false);
	} finally {
		await rm(home, { recursive: true, force: true });
	}
});

it("works in direct-state mode when PHONE_STEWARD_STATE_DIR is explicit", async () => {
	const dir = await mkdtemp(join(tmpdir(), "cli-explicit-"));
	try {
		const { code, stderr } = await runCli(
			{ PHONE_STEWARD_STATE_DIR: dir, HOME: dir },
			["status", "--json"],
		);
		expect(code, `stderr=${stderr}`).toBe(0);
	} finally {
		await rm(dir, { recursive: true, force: true });
	}
});

it("lists crash reports in direct-state mode (A5)", async () => {
	const dir = await mkdtemp(join(tmpdir(), "cli-crashes-"));
	try {
		const { code, stderr, stdout } = await runCli({ PHONE_STEWARD_STATE_DIR: dir, HOME: dir }, ["crashes", "--json"]);
		expect(code, `stderr=${stderr}`).toBe(0);
		expect(JSON.parse(stdout)).toMatchObject({ via: "direct-state", command: "crashes", crashes: [] });
	} finally {
		await rm(dir, { recursive: true, force: true });
	}
});

it("refuses acting commands against the default state dir: pair, revoke, selftest, run, diag and say need an explicit PHONE_STEWARD_STATE_DIR", async () => {
	// A running instance at the default path (production on this host) is simulated by an
	// admin.sock file there: the refusal must come before any connection is attempted.
	const home = await mkdtemp(join(tmpdir(), "cli-act-"));
	const defaultDir = join(home, "default-state");
	const { mkdirSync, writeFileSync } = await import("node:fs");
	mkdirSync(defaultDir, { recursive: true });
	writeFileSync(join(defaultDir, "admin.sock"), "");
	try {
		for (const args of [["pair"], ["revoke"], ["selftest"], ["run", "weekly-check"], ["diag"], ["say", "hello"]]) {
			const { code, stderr } = await runCli({ HOME: home, PHONE_STEWARD_DEFAULT_STATE_DIR: defaultDir }, [...args, "--json"]);
			expect(code, `${args.join(" ")}: stderr=${stderr}`).not.toBe(0);
			expect(stderr).toContain("PHONE_STEWARD_STATE_DIR");
		}
	} finally {
		await rm(home, { recursive: true, force: true });
	}
});
