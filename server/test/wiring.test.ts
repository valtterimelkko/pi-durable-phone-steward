/**
 * The wiring of the orchestration integration: the core always installs it
 * (protocol §8, §10). With orchestration disabled its calls are no-ops but the
 * worklog mirror still writes, so the integration is never replaced by `noIntegrations`.
 */
import { describe, expect, it } from "vitest";
import { mkdtempSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { loadIntegrations } from "../src/wiring.ts";
import { noIntegrations, type OrchestrationConfig } from "../src/integrations.ts";

function config(enabled: boolean): OrchestrationConfig {
	const dir = mkdtempSync(join(tmpdir(), "ps-wiring-"));
	return { enabled, command: "/nonexistent-orchestrator", memoryRoot: join(dir, "vault"), boardDir: join(dir, "board"), worklogDir: join(dir, "worklog"), timeoutMs: 1_000 };
}

describe("loadIntegrations (wiring)", () => {
	it("installs the orchestration integration even when it is disabled, so the event mirror runs", async () => {
		const integrations = await loadIntegrations(config(false));
		expect(integrations).not.toBe(noIntegrations);
		expect(typeof integrations.onHarnessOpen).toBe("function");
		expect(typeof integrations.stewardEvent).toBe("function");
	});

	it("installs it when enabled, with its extensions", async () => {
		const calls: string[][] = [];
		const integrations = await loadIntegrations(config(true), { runCommand: async (argv) => { calls.push([...argv]); return { code: 0, stdout: "", stderr: "" } as never; } });
		expect(integrations).not.toBe(noIntegrations);
		expect(integrations.extensions.length).toBeGreaterThan(0);
	});
});
