/**
 * `bin/steward-serve` is the unit's ExecStart. It resolves the provider key from the
 * environment (PHONE_STEWARD_MODEL_KEY, then <PROVIDER>_API_KEY, then a key file) into
 * the server process's environment only, and refuses to start without one.
 */
import { describe, expect, it } from "vitest";
import { spawnSync } from "node:child_process";
import { mkdtempSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";

const script = join(__dirname, "..", "..", "bin", "steward-serve");

function run(extraEnv: Record<string, string>) {
	const dir = mkdtempSync(join(tmpdir(), "ps-serve-"));
	try {
		return spawnSync("bash", [script], {
			env: {
				PATH: process.env.PATH ?? "",
				PHONE_STEWARD_SERVE_DRY_RUN: "1",
				// Strip any inherited provider keys so the test exercises only its fixtures.
				ZAI_API_KEY: "", OPENAI_API_KEY: "", PHONE_STEWARD_MODEL_KEY: "", PHONE_STEWARD_KEY_FILE: "",
				...extraEnv,
			},
			encoding: "utf8",
		});
	} finally {
		// The fixtures hold fake keys; never leave them behind in /tmp.
		rmSync(dir, { recursive: true, force: true });
	}
}

describe("bin/steward-serve", () => {
	it("refuses to start without any key, and never prints one", () => {
		const missing = run({});
		expect(missing.status).not.toBe(0);
		expect(missing.stderr).toMatch(/no key for provider/i);
	});

	it("reads PHONE_STEWARD_MODEL_KEY and exports <PROVIDER>_API_KEY (presence, not value)", () => {
		const ok = run({ PHONE_STEWARD_MODEL_KEY: "test-key-not-real" });
		expect(ok.status).toBe(0);
		expect(ok.stdout).toContain("ZAI_API_KEY=set");
		expect(ok.stdout + ok.stderr).not.toContain("test-key-not-real");
	});

	it("follows the configured provider name", () => {
		const ok = run({ PHONE_STEWARD_MODEL_PROVIDER: "openai", OPENAI_API_KEY: "test-key-not-real" });
		expect(ok.status).toBe(0);
		expect(ok.stdout).toContain("OPENAI_API_KEY=set");
	});

	it("reads the key from PHONE_STEWARD_KEY_FILE when no environment key is set", () => {
		const dir = mkdtempSync(join(tmpdir(), "ps-serve-"));
		const keyFile = join(dir, "key");
		writeFileSync(keyFile, "  test-key-not-real\n");
		try {
			const ok = run({ PHONE_STEWARD_KEY_FILE: keyFile });
			expect(ok.status).toBe(0);
			expect(ok.stdout).toContain("ZAI_API_KEY=set");
			expect(ok.stdout + ok.stderr).not.toContain("test-key-not-real");
		} finally {
			rmSync(dir, { recursive: true, force: true });
		}
	});
});
