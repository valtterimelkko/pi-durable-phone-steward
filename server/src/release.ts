/**
 * The release record (docs/protocol.md A7): the server serves at most one
 * app release, the newest APK shipped with `bin/ship-apk`. `release.json` sits beside the APK in
 * `PHONE_STEWARD_RELEASE_DIR`; the server re-hashes the file when it changes (cached by
 * size and mtime) and serves nothing if the hash differs. No path ever comes from a
 * request, and the file name in `release.json` must be a plain name inside the folder.
 */
import { createHash } from "node:crypto";
import { createReadStream } from "node:fs";
import { readFile, stat } from "node:fs/promises";
import { basename, join } from "node:path";

export type ReleaseRecord = {
	versionName: string;
	versionCode: number;
	file: string;
	sha256: string;
	sizeBytes: number;
	publishedAtMs: number;
};

export type ReleaseState =
	| { kind: "none" }
	| { kind: "corrupt"; reason: "size" | "sha256" }
	| { kind: "ok"; record: ReleaseRecord; filePath: string; mtimeMs: number };

export type HashFile = (path: string) => Promise<string>;

/** SHA-256 of a file, streamed (a 100 MiB APK is never held in memory). */
export const hashFileStreaming: HashFile = (path) =>
	new Promise((resolve, reject) => {
		const hash = createHash("sha256");
		createReadStream(path)
			.on("data", (chunk) => hash.update(chunk))
			.on("error", reject)
			.on("end", () => resolve(hash.digest("hex")));
	});

/** A plain file name: no separators, not a dot name, not empty. */
function plainFileName(name: unknown): name is string {
	return (
		typeof name === "string" &&
		name.length > 0 &&
		name.length <= 200 &&
		name === basename(name) &&
		!name.startsWith(".") &&
		!name.includes("\\") &&
		!name.includes("\0")
	);
}

function parseRecord(raw: unknown): ReleaseRecord | undefined {
	if (raw === null || typeof raw !== "object") return undefined;
	const r = raw as Record<string, unknown>;
	if (
		typeof r.versionName !== "string" ||
		r.versionName.length === 0 ||
		!Number.isInteger(r.versionCode) ||
		(r.versionCode as number) < 1 ||
		!plainFileName(r.file) ||
		typeof r.sha256 !== "string" ||
		!/^[0-9a-f]{64}$/.test(r.sha256) ||
		!Number.isInteger(r.sizeBytes) ||
		(r.sizeBytes as number) < 1 ||
		!Number.isInteger(r.publishedAtMs)
	) {
		return undefined;
	}
	return {
		versionName: r.versionName,
		versionCode: r.versionCode as number,
		file: r.file,
		sha256: r.sha256,
		sizeBytes: r.sizeBytes as number,
		publishedAtMs: r.publishedAtMs as number,
	};
}

export class ReleaseStore {
	readonly dir: string;
	private readonly hashFile: HashFile;
	/** `<path>|<size>|<mtimeMs>` → the file's actual SHA-256 (or the hashing in flight). */
	private readonly hashes = new Map<string, Promise<string>>();

	constructor(dir: string, hashFile: HashFile = hashFileStreaming) {
		this.dir = dir;
		this.hashFile = hashFile;
	}

	/** Read `release.json` and verify the APK it names. Never throws for a bad release. */
	async current(): Promise<ReleaseState> {
		let record: ReleaseRecord | undefined;
		try {
			record = parseRecord(JSON.parse(await readFile(join(this.dir, "release.json"), "utf8")));
		} catch {
			return { kind: "none" };
		}
		if (record === undefined) return { kind: "none" };
		const filePath = join(this.dir, record.file);
		let info;
		try {
			info = await stat(filePath);
		} catch {
			return { kind: "none" };
		}
		if (!info.isFile()) return { kind: "none" };
		if (info.size !== record.sizeBytes) return { kind: "corrupt", reason: "size" };
		const key = `${filePath}|${info.size}|${info.mtimeMs}`;
		let pending = this.hashes.get(key);
		if (pending === undefined) {
			pending = this.hashFile(filePath);
			// Keep only the newest file's hash: older keys are stale by construction.
			this.hashes.clear();
			this.hashes.set(key, pending);
			pending.catch(() => this.hashes.delete(key));
		}
		let actual: string;
		try {
			actual = await pending;
		} catch {
			return { kind: "none" };
		}
		if (actual !== record.sha256) return { kind: "corrupt", reason: "sha256" };
		return { kind: "ok", record, filePath, mtimeMs: info.mtimeMs };
	}
}
