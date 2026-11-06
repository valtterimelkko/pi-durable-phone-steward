import { mkdir, mkdtemp, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { afterEach, beforeEach, describe, expect, it } from "vitest";
import { projectMemoryPath, readProjectMemory } from "../src/project-memory.ts";

let dir: string;
beforeEach(async () => {
	dir = await mkdtemp(join(tmpdir(), "steward-memory-"));
});
afterEach(async () => {
	await rm(dir, { recursive: true, force: true });
});

describe("readProjectMemory (the project brief file under the state dir)", () => {
	it("reads <stateDir>/memory/project.md", async () => {
		await mkdir(join(dir, "memory"), { recursive: true });
		await writeFile(join(dir, "memory", "project.md"), "# Phone\nKeeps WhatsApp media.");
		expect(await readProjectMemory(dir)).toContain("Keeps WhatsApp media.");
		expect(projectMemoryPath(dir)).toBe(join(dir, "memory", "project.md"));
	});

	it("is empty when the file is absent, and caps a huge file", async () => {
		expect(await readProjectMemory(dir)).toBe("");
		await mkdir(join(dir, "memory"), { recursive: true });
		await writeFile(join(dir, "memory", "project.md"), "z".repeat(200_000));
		expect((await readProjectMemory(dir)).length).toBeLessThanOrEqual(20_000);
	});
});
