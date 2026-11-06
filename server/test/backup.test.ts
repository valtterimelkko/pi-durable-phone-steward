/**
 * S3 (protocol A10.8): `bin/steward backup` copies the live SQLite state with SQLite's online
 * backup API into `<stateDir>/backups/`, keeping the newest seven. Scratch dirs only: nothing
 * here touches /var/lib/phone-steward.
 */
import { existsSync, readdirSync, statSync } from "node:fs";
import { mkdtemp, rm } from "node:fs/promises";
import { DatabaseSync } from "node:sqlite";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { afterEach, beforeEach, describe, expect, it } from "vitest";
import { BACKUP_KEEP, backupState, backupFileName } from "../src/backup.ts";
import { openStewardHarness } from "../src/steward.ts";
import { storeSnapshot } from "../src/documents.ts";
import { BACKGROUND_CONTEXT } from "@earendil-works/chord/context";

let dir: string;
beforeEach(async () => {
	dir = await mkdtemp(join(tmpdir(), "p7-backup-"));
});
afterEach(async () => {
	await rm(dir, { recursive: true, force: true });
});

function makeDb(path: string, rows = 3): void {
	const db = new DatabaseSync(path);
	db.exec("PRAGMA journal_mode = WAL; CREATE TABLE IF NOT EXISTS t (x INTEGER); DELETE FROM t;");
	for (let i = 0; i < rows; i++) db.exec(`INSERT INTO t VALUES (${i});`);
	db.close();
}

describe("backupFileName", () => {
	it("is sortable and in UTC", () => {
		expect(backupFileName(Date.parse("2026-10-09T03:04:05Z"))).toBe("steward-20261009-030405.sqlite");
	});
});

describe("backupState", () => {
	it("writes a consistent copy into <stateDir>/backups with mode 0600 and no partial file left", async () => {
		makeDb(join(dir, "steward.sqlite"), 5);
		const result = await backupState({ stateDir: dir, now: () => Date.parse("2026-10-09T03:00:00Z") });
		expect(result.path).toBe(join(dir, "backups", "steward-20261009-030000.sqlite"));
		expect(existsSync(result.path)).toBe(true);
		expect(statSync(result.path).mode & 0o777).toBe(0o600);
		expect(readdirSync(join(dir, "backups"))).toEqual(["steward-20261009-030000.sqlite"]);
		const copy = new DatabaseSync(result.path, { readOnly: true });
		expect(copy.prepare("SELECT count(*) AS c FROM t").get()).toMatchObject({ c: 5 });
		expect(copy.prepare("PRAGMA integrity_check").get()).toMatchObject({ integrity_check: "ok" });
		copy.close();
		expect(result.bytes).toBeGreaterThan(0);
	});

	it("keeps the newest seven and prunes the rest, never touching other files", async () => {
		makeDb(join(dir, "steward.sqlite"));
		let day = 1;
		for (let i = 0; i < 10; i++) {
			await backupState({ stateDir: dir, now: () => Date.parse(`2026-10-${String(day++).padStart(2, "0")}T03:00:00Z`) });
		}
		const names = readdirSync(join(dir, "backups")).sort();
		expect(BACKUP_KEEP).toBe(7);
		expect(names).toEqual(Array.from({ length: 7 }, (_, i) => `steward-202610${String(i + 4).padStart(2, "0")}-030000.sqlite`));
	});

	it("leaves foreign files in the backups dir alone", async () => {
		makeDb(join(dir, "steward.sqlite"));
		const { mkdirSync, writeFileSync } = await import("node:fs");
		mkdirSync(join(dir, "backups"));
		writeFileSync(join(dir, "backups", "notes.txt"), "keep me");
		writeFileSync(join(dir, "backups", "steward-old.sqlite.partial"), "stale");
		for (let i = 1; i <= 9; i++) await backupState({ stateDir: dir, now: () => Date.parse(`2026-11-0${i}T03:00:00Z`) });
		const names = readdirSync(join(dir, "backups"));
		expect(names).toContain("notes.txt");
		expect(names.filter((n) => n.startsWith("steward-2026") && n.endsWith(".sqlite"))).toHaveLength(7);
	});

	it("is a consistent snapshot of a database the service has open (online backup, WAL mode)", async () => {
		const opened = await openStewardHarness({ dbPath: join(dir, "steward.sqlite"), fauxPlan: { hang: false } });
		try {
			await opened.harness.commit((tx) => storeSnapshot(tx, opened.root.id, { capturedAtMs: 1, totalBytes: 10, freeBytes: 5, apps: [], categories: {} as never }), BACKGROUND_CONTEXT);
			const result = await backupState({ stateDir: dir });
			const reopened = await openStewardHarness({ dbPath: result.path, fauxPlan: { hang: false }, resume: false });
			try {
				const { Snapshots } = await import("../src/documents.ts");
				const snaps = await reopened.harness.snapshot(Snapshots, reopened.root.id, BACKGROUND_CONTEXT);
				expect(snaps?.snapshots).toHaveLength(1);
			} finally {
				await reopened.close();
			}
		} finally {
			await opened.close();
		}
	});

	it("fails clearly when there is no state to back up, and creates nothing", async () => {
		await expect(backupState({ stateDir: dir })).rejects.toThrow(/no steward\.sqlite/);
		expect(existsSync(join(dir, "backups"))).toBe(false);
	});

	it("does not prune when the new backup fails", async () => {
		makeDb(join(dir, "steward.sqlite"));
		for (let i = 1; i <= 7; i++) await backupState({ stateDir: dir, now: () => Date.parse(`2026-12-0${i}T03:00:00Z`) });
		await rm(join(dir, "steward.sqlite"));
		await expect(backupState({ stateDir: dir, now: () => Date.parse("2026-12-09T03:00:00Z") })).rejects.toThrow();
		expect(readdirSync(join(dir, "backups"))).toHaveLength(7);
	});
});
