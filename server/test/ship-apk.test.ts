/**
 * bin/ship-apk : names the APK, writes .sha256 and an
 * atomic release.json, archives older APKs. Only temporary destination folders are used,
 * never the real artifacts folder (the script has no default destination).
 */
import { createHash } from "node:crypto";
import { spawnSync } from "node:child_process";
import { existsSync, mkdtempSync, readFileSync, readdirSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { afterEach, describe, expect, it } from "vitest";
import { ReleaseStore } from "../src/release.ts";

const script = join(__dirname, "..", "..", "bin", "ship-apk");
const dirs: string[] = [];

afterEach(() => {
	for (const dir of dirs.splice(0)) rmSync(dir, { recursive: true, force: true });
});

function temp(): string {
	const dir = mkdtempSync(join(tmpdir(), "ship-apk-"));
	dirs.push(dir);
	return dir;
}

function gradleFile(dir: string, versionName: string, versionCode: number): string {
	const file = join(dir, "build.gradle.kts");
	writeFileSync(file, `android {\n    defaultConfig {\n        versionCode = ${versionCode}\n        versionName = "${versionName}"\n    }\n}\n`);
	return file;
}

function apk(dir: string, name: string, content: string): string {
	const file = join(dir, name);
	writeFileSync(file, content);
	return file;
}

function ship(args: string[]) {
	return spawnSync("bash", [script, ...args], { encoding: "utf8" });
}

const sha = (text: string) => createHash("sha256").update(text).digest("hex");

describe("bin/ship-apk", () => {
	it("refuses without an explicit --dest and touches nothing", () => {
		const work = temp();
		const result = ship([apk(work, "built.apk", "x"), "--commit", "abc1234", "--gradle-file", gradleFile(work, "0.2.3", 5)]);
		expect(result.status).not.toBe(0);
		expect(result.stderr).toMatch(/--dest/);
		expect(readdirSync(work).sort()).toEqual(["build.gradle.kts", "built.apk"]);
	});

	it("names the file, writes the .sha256 and release.json, and the server accepts the record", async () => {
		const work = temp();
		const dest = join(temp(), "artifacts");
		const built = apk(work, "app-debug.apk", "apk bytes v5");
		const result = ship([built, "--dest", dest, "--commit", "abc1234ffff", "--gradle-file", gradleFile(work, "0.2.3", 5)]);
		expect(result.status, result.stderr).toBe(0);
		const file = "phone-steward-v0.2.3-abc1234.apk";
		expect(readFileSync(join(dest, file), "utf8")).toBe("apk bytes v5");
		expect(readFileSync(join(dest, `${file}.sha256`), "utf8")).toBe(`${sha("apk bytes v5")}  ${file}\n`);
		const release = JSON.parse(readFileSync(join(dest, "release.json"), "utf8"));
		expect(release).toMatchObject({ versionName: "0.2.3", versionCode: 5, file, sha256: sha("apk bytes v5"), sizeBytes: 12 });
		expect(Math.abs(release.publishedAtMs - Date.now())).toBeLessThan(60_000);
		// The A7 server reads exactly this record.
		const state = await new ReleaseStore(dest).current();
		expect(state.kind).toBe("ok");
		// No temp files left behind.
		expect(readdirSync(dest).filter((name) => name.includes(".tmp"))).toEqual([]);
	});

	it("moves older APKs and their .sha256 into archive/, leaves other files, and updates release.json", async () => {
		const work = temp();
		const dest = join(temp(), "artifacts");
		const gradle = gradleFile(work, "0.2.3", 5);
		expect(ship([apk(work, "a.apk", "old"), "--dest", dest, "--commit", "1111111", "--gradle-file", gradleFile(temp(), "0.2.2", 4)]).status).toBe(0);
		writeFileSync(join(dest, "notes.txt"), "keep me");
		const result = ship([apk(work, "b.apk", "newer"), "--dest", dest, "--commit", "2222222", "--gradle-file", gradle]);
		expect(result.status, result.stderr).toBe(0);
		expect(readdirSync(dest).sort()).toEqual(["archive", "notes.txt", "phone-steward-v0.2.3-2222222.apk", "phone-steward-v0.2.3-2222222.apk.sha256", "release.json"]);
		expect(readdirSync(join(dest, "archive")).sort()).toEqual(["phone-steward-v0.2.2-1111111.apk", "phone-steward-v0.2.2-1111111.apk.sha256"]);
		expect(JSON.parse(readFileSync(join(dest, "release.json"), "utf8")).versionCode).toBe(5);
		expect((await new ReleaseStore(dest).current()).kind).toBe("ok");
	});

	it("shipping the same build twice is harmless; an archive name clash is refused before anything moves", () => {
		const work = temp();
		const dest = join(temp(), "artifacts");
		const gradle = gradleFile(work, "0.2.3", 5);
		const built = apk(work, "a.apk", "same");
		expect(ship([built, "--dest", dest, "--commit", "abc1234", "--gradle-file", gradle]).status).toBe(0);
		expect(ship([built, "--dest", dest, "--commit", "abc1234", "--gradle-file", gradle]).status).toBe(0);
		expect(readdirSync(join(dest, "archive"))).toEqual([]);

		// A newer ship while archive/ already holds the older name: refuse, change nothing.
		writeFileSync(join(dest, "archive", "phone-steward-v0.2.3-abc1234.apk"), "earlier copy");
		const refused = ship([apk(work, "n.apk", "next"), "--dest", dest, "--commit", "9999999", "--gradle-file", gradleFile(temp(), "0.2.4", 6)]);
		expect(refused.status).not.toBe(0);
		expect(existsSync(join(dest, "phone-steward-v0.2.3-abc1234.apk"))).toBe(true);
		expect(existsSync(join(dest, "phone-steward-v0.2.4-9999999.apk"))).toBe(false);
		expect(JSON.parse(readFileSync(join(dest, "release.json"), "utf8")).versionCode).toBe(5);
	});

	it("reads the version from the property-override form used in app/build.gradle.kts", () => {
		const work = temp();
		const dest = join(temp(), "artifacts");
		const gradle = join(work, "build.gradle.kts");
		writeFileSync(
			gradle,
			'        versionCode = (project.findProperty("stewardVersionCode") as String?)?.toInt() ?: 7\n        versionName = (project.findProperty("stewardVersionName") as String?) ?: "0.2.9"\n',
		);
		const result = ship([apk(work, "a.apk", "x"), "--dest", dest, "--commit", "abc1234", "--gradle-file", gradle]);
		expect(result.status, result.stderr).toBe(0);
		expect(JSON.parse(readFileSync(join(dest, "release.json"), "utf8"))).toMatchObject({ versionName: "0.2.9", versionCode: 7 });
	});

	it("reads the real app/build.gradle.kts of this checkout", () => {
		const work = temp();
		const dest = join(temp(), "artifacts");
		const result = ship([apk(work, "a.apk", "x"), "--dest", dest, "--commit", "abc1234"]);
		expect(result.status, result.stderr).toBe(0);
		const release = JSON.parse(readFileSync(join(dest, "release.json"), "utf8"));
		expect(release.versionName).toMatch(/^\d+\.\d+\.\d+/);
		expect(release.versionCode).toBeGreaterThan(0);
	});

	it("refuses when it cannot read the version, or the APK is missing or empty", () => {
		const work = temp();
		const dest = join(temp(), "artifacts");
		const built = apk(work, "a.apk", "x");
		expect(ship([built, "--dest", dest, "--commit", "abc1234", "--gradle-file", join(work, "nope.kts")]).status).not.toBe(0);
		expect(ship([join(work, "missing.apk"), "--dest", dest, "--commit", "abc1234", "--gradle-file", gradleFile(work, "1", 1)]).status).not.toBe(0);
		expect(ship([apk(work, "empty.apk", ""), "--dest", dest, "--commit", "abc1234", "--gradle-file", gradleFile(work, "1", 1)]).status).not.toBe(0);
		expect(existsSync(join(dest, "release.json"))).toBe(false);
	});
});
