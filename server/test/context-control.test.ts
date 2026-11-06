/**
 * A10.9: compaction at the working budget, manual compaction, fresh starts, and the
 * `context` status, against the faux model (no model cost, no network).
 */
import { mkdtemp, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { BACKGROUND_CONTEXT } from "@earendil-works/chord/context";
import { CompactionEntry, ResetEntry } from "@earendil-works/pi-durable";
import { fauxAssistantMessage } from "@earendil-works/pi-ai/providers/faux";
import { afterEach, beforeEach, describe, expect, it } from "vitest";
import { chatEventsAfter } from "../src/chat-events.ts";
import { AgentBusyError } from "../src/context-control.ts";
import { openRun } from "../src/run-tracking.ts";
import { openStewardHarness } from "../src/steward.ts";
import { SERVER_NOTE_MARKER } from "../src/server-note.ts";
import { COMPACT_AT_DEFAULT } from "../src/context-policy.ts";

const ctx = BACKGROUND_CONTEXT;
let dir: string;
let opened: Awaited<ReturnType<typeof openStewardHarness>>;

beforeEach(async () => {
	dir = await mkdtemp(join(tmpdir(), "p7-context-"));
});
afterEach(async () => {
	await opened?.close();
	await rm(dir, { recursive: true, force: true });
});

async function open(window: number, compactAt: number = COMPACT_AT_DEFAULT, extra: { freshCompactionTimeoutMs?: number } = {}) {
	opened = await openStewardHarness({
		dbPath: join(dir, "steward.sqlite"),
		fauxPlan: { hang: false, models: [{ id: "faux-window", contextWindow: window }] },
		compactAt,
		...extra,
	});
	return opened;
}

/** One owner turn of about `chars` characters, settled. */
async function say(chars: number, label: string): Promise<void> {
	const body = `${label} ${"lorem ipsum dolor ".repeat(Math.ceil(chars / 18))}`.slice(0, chars);
	const submission = await opened.root.submit({ type: "input", content: body, requestId: `${label}-${Math.random()}` }, ctx);
	await submission.wait(ctx);
	await opened.root.waitForIdle(ctx);
}

async function newestEntries() {
	return (await opened.root.entries({}, 500, undefined, ctx)).items;
}

describe("the compaction policy follows the model's window", () => {
	it("is measured against the conversation model's window and recomputes on a model change", async () => {
		const o = await open(200_000);
		// window 200 000: reserve 20 000, background at 30 000 below the block (75 %).
		expect(o.context.policy()).toMatchObject({ enabled: true, reserveTokens: 20_000, backgroundTokens: 30_000 });
		expect((await o.context.status()).budget).toBe(200_000);
		expect((await o.context.status()).compactAtTokens).toBe(150_000);
		o.context.setModel({ provider: "x", modelId: "y" }, 1_000_000);
		expect(o.context.policy().reserveTokens).toBe(100_000);
		expect(o.context.policy().backgroundTokens).toBe(150_000);
		const status = await o.context.status();
		expect(status.budget).toBe(1_000_000);
		expect(status.compactAtTokens).toBe(750_000);
	});

	it("is what Pi Durable reads at every resolution (a getter on the harness settings)", async () => {
		const o = await open(200_000);
		expect(o.settings.compaction).toMatchObject({ reserveTokens: 20_000 });
		o.context.setModel({ provider: "x", modelId: "y" }, 1_000_000);
		expect(o.settings.compaction?.reserveTokens).toBe(100_000);
	});

	it("honours a configured compact-at fraction", async () => {
		const o = await open(1_000_000, 0.5);
		expect((await o.context.status()).compactAtTokens).toBe(500_000);
	});
});

describe("threshold compaction", () => {
	it("does not fire while the context is under 75 % of the window", async () => {
		await open(1_000_000);
		await say(8_000, "small-a");
		await say(8_000, "small-b");
		const entries = await newestEntries();
		expect(entries.some((entry) => CompactionEntry.is(entry))).toBe(false);
	});

	it("fires between 75 % and 90 % of the window, as a background compaction (faux model)", async () => {
		const o = await open(200_000);
		// Grow the context in steps. Pi Durable decides at the start of a turn from the context
		// so far plus the new input (here about 15 000 tokens): the first turn whose request
		// would pass 75 % of the window (150 000) triggers it, and it must not wait for the
		// block (90 %, 180 000) or fire early. A budget-based policy (the withdrawn 128 000
		// working budget) would have fired far earlier.
		const STEP = 15_000;
		let startedWith = 0;
		let compactedDuringTurn: number | undefined;
		for (let turn = 0; turn < 15 && compactedDuringTurn === undefined; turn++) {
			startedWith = (await o.context.status()).tokens;
			await say(STEP * 4, `grow-${turn}`);
			const deadline = Date.now() + 5_000;
			while (Date.now() < deadline && !(await newestEntries()).some((entry) => CompactionEntry.is(entry))) {
				if (startedWith <= 150_000) break;
				await new Promise((resolve) => setTimeout(resolve, 50));
			}
			if ((await newestEntries()).some((entry) => CompactionEntry.is(entry))) compactedDuringTurn = startedWith;
		}
		expect(compactedDuringTurn).toBeDefined();
		expect(compactedDuringTurn! + STEP).toBeGreaterThan(150_000);
		expect(compactedDuringTurn! + STEP).toBeLessThanOrEqual(180_000);
	});
});

describe("a restart", () => {
	it("keeps the stored model choice and measures the policy against its window", async () => {
		const plan = { hang: false, models: [{ id: "faux-a", contextWindow: 200_000 }, { id: "faux-b", contextWindow: 1_000_000 }] };
		opened = await openStewardHarness({ dbPath: join(dir, "steward.sqlite"), fauxPlan: plan });
		expect(opened.context.policy().reserveTokens).toBe(20_000);
		await opened.root.configure({ model: { provider: "faux", modelId: "faux-b" } }, ctx);
		await opened.close();
		opened = await openStewardHarness({ dbPath: join(dir, "steward.sqlite"), fauxPlan: plan });
		expect((await opened.root.agent(ctx)).model).toEqual({ provider: "faux", modelId: "faux-b" });
		expect(opened.context.policy().reserveTokens).toBe(100_000);
	});
});

describe("the threshold summary (steward instructions)", () => {
	it("asks the model for the steward's headings and keeps the owner's preferences", async () => {
		const o = await open(1_000_000);
		let seen = "";
		o.faux!.setResponses([
			(context) => {
				seen = JSON.stringify(context.messages);
				return fauxAssistantMessage("## Goal\nx\n\n## Open threads\n- y");
			},
		]);
		const out = await o.context.thresholdSummary([{ role: "user", content: "I never want the Keep folder touched", timestamp: 1 }]);
		expect(out).toContain("## Open threads");
		expect(seen).toContain("Owner preferences and decisions");
		expect(seen).toContain("Keep folder");
	});

	it("returns undefined on a model error, so Pi Durable's default summary is used", async () => {
		const o = await open(1_000_000);
		o.faux!.setResponses([fauxAssistantMessage("boom", { stopReason: "error", errorMessage: "model down" })]);
		expect(await o.context.thresholdSummary([{ role: "user", content: "hi", timestamp: 1 }])).toBeUndefined();
	});
});

describe("status", () => {
	it("reports tokens, the window as the budget, the compact-at threshold and null times before anything happened", async () => {
		const o = await open(200_000);
		const before = await o.context.status();
		expect(before).toMatchObject({ budget: 200_000, compactAtTokens: 150_000, lastCompactionMs: null, lastFreshStartMs: null });
		await say(8_000, "status-turn");
		const after = await o.context.status();
		expect(after.tokens).toBeGreaterThan(1_500);
		expect(after.tokens).toBeGreaterThan(before.tokens);
	});
});

describe("manual compaction", () => {
	it("compacts a long conversation, leaves the chat log alone and records the time", async () => {
		// A11.1: a 1 000 000-token window keeps 50 000 recent tokens, so the transcript must be
		// bigger than that before a manual compaction has anything to cut. A 200 000-token window
		// keeps 20 000; three turns of about 15 000 tokens is then a real cut.
		const o = await open(200_000);
		for (const label of ["a", "b", "c"]) await say(60_000, `long-${label}`);
		const eventsBefore = await o.harness.commit((tx) => chatEventsAfter(tx, 0), ctx);
		const result = await o.context.compact();
		expect(result.compacted).toBe(true);
		const entries = await newestEntries();
		expect(entries.some((entry) => CompactionEntry.is(entry))).toBe(true);
		const eventsAfter = await o.harness.commit((tx) => chatEventsAfter(tx, 0), ctx);
		expect(eventsAfter).toEqual(eventsBefore);
		expect((await o.context.status()).lastCompactionMs).toBeGreaterThan(0);
	});

	it("refuses while a run is open (409 AGENT_BUSY upstream)", async () => {
		const o = await open(1_000_000);
		await o.harness.commit((tx) => openRun(tx, o.root.id, "busy-1", "owner", Date.now()), ctx);
		await expect(o.context.compact()).rejects.toBeInstanceOf(AgentBusyError);
		await expect(o.context.fresh()).rejects.toBeInstanceOf(AgentBusyError);
	});
});

describe("fresh start", () => {
	it("resets the model context with a code-built brief, keeps the chat log and draws one divider", async () => {
		const o = await open(1_000_000);
		await say(2_000, "before-fresh");
		const eventsBefore = await o.harness.commit((tx) => chatEventsAfter(tx, 0), ctx);
		await o.context.fresh();
		const view = await o.root.context(ctx);
		expect(view.head !== undefined && ResetEntry.is(view.head)).toBe(true);
		const brief = String((view.head!.model?.[0] as { content: unknown }).content);
		expect(brief).toContain(SERVER_NOTE_MARKER);
		expect(brief).toContain("fresh start");
		expect(brief).toContain("no proposal is waiting for the owner");
		// The old messages are no longer in the model's context.
		expect(JSON.stringify(view.messages)).not.toContain("before-fresh");
		// The chat log keeps every earlier event; the only addition is the context divider.
		const eventsAfter = await o.harness.commit((tx) => chatEventsAfter(tx, 0), ctx);
		expect(eventsAfter.slice(0, eventsBefore.length)).toEqual(eventsBefore);
		const added = eventsAfter.slice(eventsBefore.length);
		expect(added.map((event) => event.type)).toEqual(["context"]);
		expect(added[0]!.data).toMatchObject({ kind: "fresh_start" });
		expect((await o.context.status()).lastFreshStartMs).toBeGreaterThan(0);
	});

	it("starts small whatever the transcript held", async () => {
		const o = await open(1_000_000);
		for (const label of ["a", "b", "c"]) await say(60_000, `big-${label}`);
		const big = (await o.context.status()).tokens;
		await o.context.fresh();
		const small = (await o.context.status()).tokens;
		expect(small).toBeLessThan(big / 4);
	});

	it("carries the owner's preferences and open threads from the last summary into the next brief", async () => {
		const o = await open(200_000);
		// Script the summariser to return the headings the steward asks for.
		const summary = "## Goal\nTidy.\n\n## Owner preferences and decisions\n- Never touch WhatsApp media.\n\n## Open threads\n- Waiting on the owner about caches.\n\n## Progress\nScanned.";
		o.faux!.setResponses([...Array.from({ length: 6 }, () => (() => ({ role: "assistant", content: [{ type: "text", text: summary }], api: "faux", provider: "faux", model: "faux-window", usage: { input: 10, output: 10, cacheRead: 0, cacheWrite: 0, totalTokens: 20, cost: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0, total: 0 } }, stopReason: "stop", timestamp: Date.now() }) as never))]);
		for (const label of ["a", "b", "c"]) await say(60_000, `pref-${label}`);
		await o.context.fresh();
		const view = await o.root.context(ctx);
		const brief = String((view.head!.model?.[0] as { content: unknown }).content);
		expect(brief).toContain("Never touch WhatsApp media.");
		expect(brief).toContain("Waiting on the owner about caches.");
		expect(brief).not.toContain("Scanned.");
		// ...and survives a second fresh start (the chain across weeks).
		await o.context.fresh();
		const again = await o.root.context(ctx);
		expect(String((again.head!.model?.[0] as { content: unknown }).content)).toContain("Never touch WhatsApp media.");
	});

	it("does the automatic pre-weekly fresh start once per run id", async () => {
		const o = await open(1_000_000);
		expect(await o.context.freshBeforeWeeklyCheck("weekly-check:manual:1")).toBe(true);
		expect(await o.context.freshBeforeWeeklyCheck("weekly-check:manual:1")).toBe(false);
		expect(await o.context.freshBeforeWeeklyCheck("weekly-check:manual:2")).toBe(true);
	});
});
