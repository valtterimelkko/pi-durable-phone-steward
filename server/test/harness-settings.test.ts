import { describe, expect, it } from "vitest";
import { createRegistry, defineExtension } from "@earendil-works/pi-durable";
import { LIVE_EXTENSION_NAME, stewardHarnessSettings } from "../src/live/tools.ts";

/**
 * Review F3, kept for 1.5.0: a task cut off `settings.restartLimit` times is work the owner asked
 * for that did not get done. 1.5.0 no longer parks it — it ends visibly (`task_faulted` with
 * reason `restart_limit`, or `task_interrupted`), and there is still no way for the steward to
 * resubmit it, so the limit stays Infinity and the work runs again, as in 1.3 and earlier.
 * Whether to adopt a finite limit is a separate decision (§C.3 of the 1.5 migration plan).
 */
describe("steward harness settings", () => {
	it("keeps a restart-limited task runnable: the steward has no way to resubmit work that ended", () => {
		const settings = stewardHarnessSettings(createRegistry());
		expect(settings.restartLimit).toBe(Infinity);
	});

	it("still selects the installed extensions, leaving the live extension out", () => {
		const registry = createRegistry();
		registry.install(defineExtension({ name: "test-core" }));
		registry.install(defineExtension({ name: LIVE_EXTENSION_NAME }));
		expect(stewardHarnessSettings(registry).extensions.map((extension) => extension.name)).toEqual(["test-core"]);
	});
});
