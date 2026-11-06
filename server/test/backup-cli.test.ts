/** S3: `bin/steward backup` end to end (scratch state dirs only). */
import { spawnSync } from "node:child_process";
import { existsSync } from "node:fs";
import { mkdtemp, rm } from "node:fs/promises";
import { DatabaseSync } from "node:sqlite";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { fileURLToPath } from "node:url";
import { afterEach, beforeEach, describe, expect, it } from "vitest";

let dir: string;
beforeEach(async () => {
	dir = await mkdtemp(join(tmpdir(), "p7-backup-cli-"));
});
afterEach(async () => {
	await rm(dir, { recursive: true, force: true });
});

function makeDb(path: string): void {
	const db = new DatabaseSync(path);
	db.exec("PRAGMA journal_mode = WAL; CREATE TABLE IF NOT EXISTS t (x INTEGER); INSERT INTO t VALUES (1);");
	db.close();
}

describe("bin/steward backup", () => {
	const cli = fileURLToPath(new URL("../../bin/steward", import.meta.url));
	const run = (args: string[], env: Record<string, string>) =>
		spawnSync(process.execPath, ["--experimental-strip-types", cli, ...args], { encoding: "utf8", env: { PATH: process.env.PATH ?? "", ...env } });

	it("refuses to guess the state directory", () => {
		const result = run(["backup"], {});
		expect(result.status).toBe(1);
		expect(result.stderr).toMatch(/PHONE_STEWARD_STATE_DIR/);
	});

	it("backs up the explicit state dir and reports the file (no server needed)", () => {
		makeDb(join(dir, "steward.sqlite"));
		const result = run(["backup", "--json"], { PHONE_STEWARD_STATE_DIR: dir });
		expect(result.status, result.stderr).toBe(0);
		const out = JSON.parse(result.stdout) as { command: string; path: string; kept: number };
		expect(out.command).toBe("backup");
		expect(out.path.startsWith(join(dir, "backups", "steward-"))).toBe(true);
		expect(existsSync(out.path)).toBe(true);
	});
});
