import { describe, expect, it } from "vitest";
import { exitOnHarnessFailure } from "../src/harness-failure.ts";

/**
 * Pi Durable 1.2+: a storage error fails the whole Session and the host must restart, not limp.
 * 1.5.0 tags the end by `kind` (`{ kind: "closed" }` / `{ kind: "failed", error }`) instead of
 * `reason`, so this test passes the shape the installed `Session.closed` really produces.
 */
function seams() {
	const lines: string[] = [];
	const exits: number[] = [];
	return { lines, exits, deps: { log: (line: string) => lines.push(line), exit: (code: number) => exits.push(code) } };
}

describe("exit on a failed harness", () => {
	it("logs the cause and exits non-zero when the harness closed because of a storage error", async () => {
		const s = seams();
		const done = exitOnHarnessFailure(Promise.resolve({ kind: "failed", error: new Error("disk I/O error") }), s.deps);
		await done;
		expect(s.exits).toEqual([1]);
		expect(s.lines.join("\n")).toContain("disk I/O error");
	});

	it("does nothing when the harness was closed on purpose", async () => {
		const s = seams();
		await exitOnHarnessFailure(Promise.resolve({ kind: "closed" }), s.deps);
		expect(s.exits).toEqual([]);
		expect(s.lines).toEqual([]);
	});

	it("is fed by a real Harness whose storage fails, and exits on it", async () => {
		// The end-to-end half: a real `harness.closed` with a failing storage must still reach
		// `deps.exit(1)`. A cast that kept the old `reason` field would compile and never exit.
		const { Harness, MemoryStorage, createRegistry } = await import("@earendil-works/pi-durable");
		const { createModels } = await import("@earendil-works/pi-ai/models");
		const { BACKGROUND_CONTEXT } = await import("@earendil-works/chord/context");
		// Every commit fails: the Session fails on its first write and closes with `kind: "failed"`.
		class FailingStorage extends MemoryStorage {
			override async commit(): Promise<never> {
				throw new Error("disk I/O error");
			}
		}
		const harness = await Harness.open(new FailingStorage(), { models: createModels(), registry: createRegistry() }, BACKGROUND_CONTEXT);
		const s = seams();
		const watched = exitOnHarnessFailure(harness.closed, s.deps);
		// A real write: the root conversation's record. The storage refuses it, so the Session fails.
		await harness.root(BACKGROUND_CONTEXT).catch(() => undefined);
		await harness.close(BACKGROUND_CONTEXT).catch(() => undefined);
		await watched;
		expect(s.exits).toEqual([1]);
		expect(s.lines.join("\n")).toContain("storage error");
	});
});
