/**
 * Crash-gate guard: arming must be impossible outside test mode (review finding 4).
 *
 * The guard is proven through a fresh `node -e` process whose environment carries neither
 * `VITEST` nor `PHONE_STEWARD_CRASH_GATES`: there, `armCrashGate` must refuse, and an
 * unarmed `crashPoint` must be a no-op. Inside vitest the guard is open by design, so the
 * refusal itself can only be observed in a process that vitest did not configure.
 */
import { spawnSync } from "node:child_process";
import { fileURLToPath } from "node:url";
import { expect, it } from "vitest";

const GATE_MODULE = fileURLToPath(new URL("../src/crash-gate.ts", import.meta.url));

function runOutsideTestMode(script: string): { stdout: string; status: number | null } {
	const result = spawnSync(process.execPath, ["-e", script], {
		encoding: "utf8",
		timeout: 15_000,
		// A plain environment: no VITEST, no PHONE_STEWARD_CRASH_GATES.
		env: { PATH: process.env.PATH ?? "" },
	});
	return { stdout: result.stdout.trim(), status: result.status };
}

it("armCrashGate refuses outside test mode and crashPoint stays a no-op", () => {
	const { stdout, status } = runOutsideTestMode(`
		import("${GATE_MODULE}").then((gate) => {
			let armed = "ARMED";
			try {
				gate.armCrashGate("probe", "/tmp/s1-guard-should-not-exist", "/tmp/s1-guard-should-not-exist");
			} catch {
				armed = "REFUSED";
			}
			const started = Date.now();
			gate.crashPoint("never-armed").then(() => {
				const noop = Date.now() - started < 1000 ? "NOOP-OK" : "NOOP-SLOW";
				process.stdout.write(armed + " " + noop);
			});
		});
	`);
	expect(status, `probe exited ${status}; stdout=${JSON.stringify(stdout)}`).toBe(0);
	expect(stdout).toBe("REFUSED NOOP-OK");
});
