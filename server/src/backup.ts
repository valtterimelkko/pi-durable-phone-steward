/**
 * S3 (protocol A10.8): a nightly backup of the steward's SQLite state.
 *
 * Uses SQLite's online backup API (`node:sqlite` `backup()`), so the copy is a consistent
 * snapshot even while the service has the database open in WAL mode; no file copy of a live
 * database is ever made. The source is opened READ-ONLY: this process never writes the
 * service's database. The copy goes to `<stateDir>/backups/steward-YYYYMMDD-HHMMSS.sqlite`
 * (UTC, so the names sort), is written to a `.partial` file first, checked with
 * `PRAGMA integrity_check`, and only then renamed into place. Only after a good backup are
 * the oldest `steward-*.sqlite` files beyond the newest seven removed; other files are left
 * alone.
 */
import { chmodSync, existsSync, mkdirSync, readdirSync, renameSync, rmSync, statSync } from "node:fs";
import { join } from "node:path";
import { backup, DatabaseSync } from "node:sqlite";

export const BACKUP_KEEP = 7;
const NAME = /^steward-\d{8}-\d{6}\.sqlite$/;

export function backupFileName(nowMs: number): string {
	const iso = new Date(nowMs).toISOString(); // 2026-10-09T03:04:05.000Z
	return `steward-${iso.slice(0, 10).replaceAll("-", "")}-${iso.slice(11, 19).replaceAll(":", "")}.sqlite`;
}

export type BackupOptions = {
	stateDir: string;
	keep?: number;
	now?: () => number;
};

export type BackupResult = { path: string; bytes: number; kept: number; pruned: string[] };

export async function backupState(options: BackupOptions): Promise<BackupResult> {
	const source = join(options.stateDir, "steward.sqlite");
	if (!existsSync(source)) throw new Error(`no steward.sqlite in ${options.stateDir}: nothing to back up`);
	const dir = join(options.stateDir, "backups");
	mkdirSync(dir, { recursive: true, mode: 0o700 });
	const final = join(dir, backupFileName((options.now ?? Date.now)()));
	const partial = `${final}.partial`;
	rmSync(partial, { force: true });
	const db = new DatabaseSync(source, { readOnly: true });
	try {
		await backup(db, partial);
	} catch (error) {
		rmSync(partial, { force: true });
		throw error;
	} finally {
		db.close();
	}
	try {
		// The copy of a WAL database is itself marked WAL: switch it to a plain rollback journal
		// so it is one self-contained file (no -wal/-shm beside it) that restores anywhere.
		const copy = new DatabaseSync(partial);
		try {
			copy.exec("PRAGMA journal_mode = DELETE");
			const check = copy.prepare("PRAGMA integrity_check").get() as { integrity_check?: string } | undefined;
			if (check?.integrity_check !== "ok") throw new Error(`the backup failed its integrity check: ${String(check?.integrity_check)}`);
		} finally {
			copy.close();
		}
		chmodSync(partial, 0o600);
		renameSync(partial, final);
	} catch (error) {
		rmSync(partial, { force: true });
		throw error;
	}
	const keep = Math.max(1, options.keep ?? BACKUP_KEEP);
	const all = readdirSync(dir).filter((name) => NAME.test(name)).sort();
	const pruned = all.slice(0, Math.max(0, all.length - keep));
	for (const name of pruned) rmSync(join(dir, name), { force: true });
	return { path: final, bytes: statSync(final).size, kept: all.length - pruned.length, pruned };
}
