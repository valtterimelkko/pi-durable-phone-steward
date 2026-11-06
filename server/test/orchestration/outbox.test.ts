/**
 * The orchestration outbox (docs/protocol.md §8): presence updates and captures are delivered
 * at most once per dedupe key, with retry and backoff, and never fail or block a run.
 * When the command is down, entries wait. Cross-process at-most-once behaviour (SIGKILL on
* both sides of the command call) is proven in `crash.test.ts`.
 */
import { mkdtemp, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { BACKGROUND_CONTEXT } from "@earendil-works/chord/context";
import { afterAll, describe, expect, it } from "vitest";
import { openTestSteward, type TestSteward } from "./test-steward.ts";
import { orchestrationOutboxExtension, enqueueOrchestrationEffect, OrchestrationOutbox } from "../../src/orchestration/outbox.ts";
import { presenceUpdateArgv, captureArgv } from "../../src/orchestration/client.ts";
import { setOrchestrationOutboxDelivery } from "../../src/orchestration/outbox.ts";

const context = BACKGROUND_CONTEXT;
const dirs: string[] = [];
const ROOT = "/nonexistent-orchestrator";

afterAll(async () => {
	for (const dir of dirs) await rm(dir, { recursive: true, force: true });
});

async function openHarness(): Promise<TestSteward> {
	const dir = await mkdtemp(join(tmpdir(), "phone-steward-outbox-"));
	dirs.push(dir);
	return openTestSteward({
		dbPath: join(dir, "steward.sqlite"),
		extensions: [orchestrationOutboxExtension],
		fauxPlan: { hang: false },
	}).then(async (opened) => {
		// Task scheduling is enabled by resume(); the outbox pump is a background task.
		opened.harness.resume();
		return opened;
	});
}

type RunnerScript = (call: number, argv: readonly string[]) => Promise<{ ok: true; stdout: string } | { ok: false; error: string }>;

function scriptedDelivery(script: RunnerScript): { calls: Array<{ argv: string[] }> } {
	const calls: Array<{ argv: string[] }> = [];
	setOrchestrationOutboxDelivery(async (argv) => {
		calls.push({ argv: [...argv] });
		return script(calls.length, argv);
	});
	return { calls };
}

async function enqueue(
	opened: TestSteward,
	dedupeKey: string,
	argv: readonly string[],
): Promise<void> {
	await opened.harness.commit(
		(tx) => enqueueOrchestrationEffect(tx, opened.root.id, { dedupeKey, kind: argv[0] === "memory" ? "capture" : "presence", argv: [...argv] }),
		context,
	);
}

async function entryState(opened: TestSteward, dedupeKey: string): Promise<Record<string, unknown> | undefined> {
	const doc = await opened.harness.snapshot(OrchestrationOutbox, opened.root.id, context);
	const entry = doc?.entries[dedupeKey];
	return entry === undefined ? undefined : (entry as unknown as Record<string, unknown>);
}

async function waitFor(
	opened: TestSteward,
	dedupeKey: string,
	predicate: (state: Record<string, unknown>) => boolean,
	timeoutMs = 15_000,
): Promise<Record<string, unknown>> {
	const deadline = Date.now() + timeoutMs;
	for (;;) {
		const state = await entryState(opened, dedupeKey);
		if (state !== undefined && predicate(state)) return state;
		if (Date.now() > deadline) {
			throw new Error(`outbox entry ${dedupeKey} never settled; last=${JSON.stringify(state)}`);
		}
		await new Promise((resolve) => setTimeout(resolve, 20));
	}
}

describe("orchestration outbox", () => {
	it("delivers a board update once and marks it done, passing the exact argv", async () => {
		const opened = await openHarness();
		try {
			const { calls } = scriptedDelivery(async () => ({ ok: true, stdout: "registered" }));
			const argv = presenceUpdateArgv("weekly check", "working");
			await enqueue(opened, "presence:run1:start", argv);
			const state = await waitFor(opened, "presence:run1:start", (s) => s.state === "done");
			expect(state.attempts).toBe(1);
			expect(state.deliveredAtMs).toBeTypeOf("number");
			expect(calls).toHaveLength(1);
			expect(calls[0]!.argv).toEqual(argv);
		} finally {
			await opened.harness.close(context);
		}
	});

	it("enqueues a dedupe key once: a second enqueue of the same key is a no-op", async () => {
		const opened = await openHarness();
		try {
			setOrchestrationOutboxDelivery(async () => ({ ok: true, stdout: "" }));
			await enqueue(opened, "presence:run2:start", presenceUpdateArgv("a", "working"));
			await enqueue(opened, "presence:run2:start", presenceUpdateArgv("a", "working"));
			const doc = await opened.harness.snapshot(OrchestrationOutbox, opened.root.id, context);
			expect(Object.keys(doc?.entries ?? {})).toEqual(["presence:run2:start"]);
			await waitFor(opened, "presence:run2:start", (s) => s.state === "done");
		} finally {
			await opened.harness.close(context);
		}
	});

	it("retries a failed call with backoff and delivers when the command recovers", async () => {
		const opened = await openHarness();
		try {
			const { calls } = scriptedDelivery(async (call) =>
				call === 1 ? { ok: false, error: "connect ECONNREFUSED" } : { ok: true, stdout: "" },
			);
			await enqueue(opened, "capture:evt1", captureArgv("Post-batch review. [capture aabbccdd00112233]", "aabbccdd00112233"));
			const state = await waitFor(opened, "capture:evt1", (s) => s.state === "done", 20_000);
			expect(state.attempts).toBe(2);
			expect(calls).toHaveLength(2);
			const midWait = await waitFor(opened, "capture:evt1", () => true, 1);
			void midWait;
		} finally {
			await opened.harness.close(context);
		}
	});

	it("entries wait while the command stays down; the store keeps them pending", async () => {
		const opened = await openHarness();
		try {
			const { calls } = scriptedDelivery(async () => ({ ok: false, error: "timeout" }));
			await enqueue(opened, "presence:run3", presenceUpdateArgv("down", "waiting"));
			// Give the pump time for two attempts, then require: still pending, no throw.
			await new Promise((resolve) => setTimeout(resolve, 3_500));
			const state = await entryState(opened, "presence:run3");
			expect(state?.state).toBe("pending");
			expect(Number(state?.attempts)).toBeGreaterThanOrEqual(2);
			expect(String(state?.lastError)).toContain("timeout");
			expect(calls.length).toBeGreaterThanOrEqual(2);
		} finally {
			await opened.harness.close(context);
		}
	});

	it("without a configured runner the entries wait instead of crashing", async () => {
		const opened = await openHarness();
		try {
			setOrchestrationOutboxDelivery(undefined);
			await enqueue(opened, "presence:run4", presenceUpdateArgv("unwired", "waiting"));
			await new Promise((resolve) => setTimeout(resolve, 500));
			const state = await entryState(opened, "presence:run4");
			expect(state?.state).toBe("pending");
		} finally {
			await opened.harness.close(context);
		}
	});

	it("a replayed enqueue of a delivered key neither redelivers nor resets the entry", async () => {
		const opened = await openHarness();
		try {
			const { calls } = scriptedDelivery(async () => ({ ok: true, stdout: "" }));
			const argv = presenceUpdateArgv("first", "working");
			await enqueue(opened, "presence:replay1", argv);
			const first = await waitFor(opened, "presence:replay1", (s) => s.state === "done");
			expect(calls).toHaveLength(1);
			// A replayed commit enqueues the same key again after delivery.
			await enqueue(opened, "presence:replay1", argv);
			await new Promise((resolve) => setTimeout(resolve, 500));
			const after = await entryState(opened, "presence:replay1");
			expect(after?.state).toBe("done");
			expect(calls).toHaveLength(1);
			// The done entry's facts are untouched by the replay.
			expect(after?.attempts).toBe(first.attempts);
			expect(after?.deliveredAtMs).toBe(first.deliveredAtMs);
		} finally {
			await opened.harness.close(context);
		}
	});

	it("an empty queue retires the runner; a later enqueue starts a fresh one", async () => {
		const opened = await openHarness();
		try {
			setOrchestrationOutboxDelivery(async () => ({ ok: true, stdout: "" }));
			await enqueue(opened, "presence:run5", presenceUpdateArgv("a", "working"));
			await waitFor(opened, "presence:run5", (s) => s.state === "done");
			// The runner retires and clears its slot once nothing is left.
			const deadline = Date.now() + 10_000;
			for (;;) {
				const doc = await opened.harness.snapshot(OrchestrationOutbox, opened.root.id, context);
				if (doc?.runnerTaskId === undefined) break;
				if (Date.now() > deadline) throw new Error("runner never retired");
				await new Promise((resolve) => setTimeout(resolve, 20));
			}
			await enqueue(opened, "presence:run6", presenceUpdateArgv("b", "waiting"));
			await waitFor(opened, "presence:run6", (s) => s.state === "done");
		} finally {
			await opened.harness.close(context);
		}
	});
});
