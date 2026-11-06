/** A10.9: the weekly check begins with a fresh start (a summary chain lives at most a week). */
import { mkdtemp, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { BACKGROUND_CONTEXT } from "@earendil-works/chord/context";
import { ResetEntry } from "@earendil-works/pi-durable";
import { afterEach, beforeEach, describe, expect, it } from "vitest";
import { chatEventsAfter } from "../src/chat-events.ts";
import { Jobs } from "../src/jobs.ts";
import { WeeklyCheckTask, type WeeklyCheckInput } from "../src/rule-tasks.ts";
import { openStewardHarness } from "../src/steward.ts";

const ctx = BACKGROUND_CONTEXT;
let dir: string;
let opened: Awaited<ReturnType<typeof openStewardHarness>>;
beforeEach(async () => {
	dir = await mkdtemp(join(tmpdir(), "p7-weekly-fresh-"));
});
afterEach(async () => {
	await opened?.close();
	await rm(dir, { recursive: true, force: true });
});

async function startWeekly(runId: string): Promise<void> {
	const input: WeeklyCheckInput = { runId, start: "open" };
	await opened.root.commit(
		(tx) => tx.createTask(WeeklyCheckTask, input, { ownership: { kind: "conversation" }, background: true }),
		ctx,
	);
	for (let i = 0; i < 400; i++) {
		const stored = await opened.harness.snapshot(Jobs, opened.root.id, ctx);
		if (Object.keys(stored?.jobs ?? {}).length >= 3) return;
		await new Promise((resolve) => setTimeout(resolve, 25));
	}
	throw new Error("the weekly check never queued its jobs");
}

describe("weekly check and fresh start", () => {
	it("resets the model context before it queues the phone jobs, and leaves the chat log", async () => {
		opened = await openStewardHarness({ dbPath: join(dir, "s.sqlite"), fauxPlan: { hang: false } });
		const submission = await opened.root.submit({ type: "input", content: "remember-this-marker hello", requestId: "pre-1" }, ctx);
		await submission.wait(ctx);
		const chatBefore = await opened.harness.commit((tx) => chatEventsAfter(tx, 0), ctx);
		await startWeekly("weekly-check:manual:fresh-1");
		const view = await opened.root.context(ctx);
		expect(view.head !== undefined && ResetEntry.is(view.head)).toBe(true);
		expect(JSON.stringify(view.messages)).not.toContain("remember-this-marker");
		const chatAfter = await opened.harness.commit((tx) => chatEventsAfter(tx, 0), ctx);
		expect(chatAfter.slice(0, chatBefore.length)).toEqual(chatBefore);
		expect(chatAfter.slice(chatBefore.length).map((event) => event.type)).toEqual(["context"]);
	});

	it("does it once per run, even if the open phase is entered again", async () => {
		opened = await openStewardHarness({ dbPath: join(dir, "s.sqlite"), fauxPlan: { hang: false } });
		await startWeekly("weekly-check:manual:fresh-2");
		expect(await opened.context.freshBeforeWeeklyCheck("weekly-check:manual:fresh-2")).toBe(false);
	});
});
