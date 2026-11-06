/** A10.9: `bin/steward compact` and `bin/steward fresh` reach a running instance through the admin socket. */
import { spawn, spawnSync } from "node:child_process";
import { mkdtemp, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { fileURLToPath } from "node:url";
import { BACKGROUND_CONTEXT } from "@earendil-works/chord/context";
import { CompactionEntry, ResetEntry } from "@earendil-works/pi-durable";
import { afterEach, beforeEach, describe, expect, it } from "vitest";
import { loadConfig } from "../src/config.ts";
import { startStewardServers, type StewardServers } from "../src/http.ts";
import { openRun } from "../src/run-tracking.ts";
import { openStewardHarness } from "../src/steward.ts";

const cli = fileURLToPath(new URL("../../bin/steward", import.meta.url));
const ctx = BACKGROUND_CONTEXT;
let dir: string;
beforeEach(async () => {
	dir = await mkdtemp(join(tmpdir(), "p7-cli-ctx-"));
});
afterEach(async () => {
	await rm(dir, { recursive: true, force: true });
});

function runCli(args: string[], env: Record<string, string>): Promise<{ code: number | null; stdout: string; stderr: string }> {
	return new Promise((resolve) => {
		const child = spawn(process.execPath, ["--experimental-strip-types", cli, ...args], { env: { PATH: process.env.PATH ?? "", ...env } });
		let stdout = "";
		let stderr = "";
		child.stdout.on("data", (c: Buffer) => (stdout += c.toString()));
		child.stderr.on("data", (c: Buffer) => (stderr += c.toString()));
		child.on("close", (code) => resolve({ code, stdout, stderr }));
	});
}

describe("bin/steward compact|fresh", () => {
	it("refuse to act without an explicit state dir", () => {
		for (const command of ["compact", "fresh"]) {
			const result = spawnSync(process.execPath, ["--experimental-strip-types", cli, command], { encoding: "utf8", env: { PATH: process.env.PATH ?? "" } });
			expect(result.status).toBe(1);
			expect(result.stderr).toMatch(/PHONE_STEWARD_STATE_DIR/);
		}
	});

	it("need the running instance", () => {
		const result = spawnSync(process.execPath, ["--experimental-strip-types", cli, "compact"], { encoding: "utf8", env: { PATH: process.env.PATH ?? "", PHONE_STEWARD_STATE_DIR: dir } });
		expect(result.status).toBe(1);
		expect(result.stderr).toMatch(/running instance/);
	});

	describe("against a running instance", () => {
		let servers: StewardServers;
		let opened: Awaited<ReturnType<typeof openStewardHarness>>;
		beforeEach(async () => {
			const config = loadConfig({ stateDir: dir, port: 0, notify: {}, schedules: { enabled: false } });
			opened = await openStewardHarness({ dbPath: join(dir, "steward.sqlite"), fauxPlan: { hang: false, models: [{ id: "w", contextWindow: 1_000_000 }] } });
			servers = startStewardServers({ config, harness: opened.harness, root: opened.root, catalogue: opened.catalogue, models: opened.models, contextControl: opened.context });
			await new Promise<void>((resolve) => servers.tcp.listen(0, "127.0.0.1", resolve));
		});
		afterEach(async () => {
			await servers.close();
			await opened.close();
		});

		it("fresh starts the context fresh", async () => {
			const result = await runCli(["fresh", "--json"], { PHONE_STEWARD_STATE_DIR: dir });
			expect(result.code, result.stderr).toBe(0);
			expect(JSON.parse(result.stdout)).toMatchObject({ command: "fresh", via: "admin-socket", accepted: true });
			for (let i = 0; i < 100 && (await opened.context.isBusy()); i++) await new Promise((r) => setTimeout(r, 50));
			const view = await opened.root.context(ctx);
			expect(view.head !== undefined && ResetEntry.is(view.head)).toBe(true);
		});

		it("compact compacts", async () => {
			// A11.1: the window is 1 000 000, so 50 000 recent tokens are kept; the transcript must
			// be bigger than that before a manual compaction has anything to cut.
			for (let i = 0; i < 3; i++) {
				const s = await opened.root.submit({ type: "input", content: `filler ${i} ${"lorem ipsum ".repeat(30_000)}`, requestId: `f${i}` }, ctx);
				await s.wait(ctx);
			}
			const result = await runCli(["compact"], { PHONE_STEWARD_STATE_DIR: dir });
			expect(result.code, result.stderr).toBe(0);
			for (let i = 0; i < 100 && (await opened.context.isBusy()); i++) await new Promise((r) => setTimeout(r, 50));
			expect((await opened.root.entries({}, 100, undefined, ctx)).items.some((e) => CompactionEntry.is(e))).toBe(true);
		});

		it("exit 1 with the server's reason while the steward is working", async () => {
			await opened.harness.commit((tx) => openRun(tx, opened.root.id, "busy", "owner", Date.now()), ctx);
			const result = await runCli(["fresh"], { PHONE_STEWARD_STATE_DIR: dir });
			expect(result.code).toBe(1);
			expect(result.stderr).toMatch(/409|working|idle/i);
		});
	});
});
