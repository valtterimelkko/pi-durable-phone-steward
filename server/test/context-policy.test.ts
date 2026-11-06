/**
 * A11.1: compaction follows the model's window. Pi Durable's `CompactionPolicy` is expressed
 * against the model window: it blocks above `contextWindow - reserveTokens` and starts
 * background compaction `backgroundTokens` below that. This module maps the steward's
 * window-based policy onto it:
 *   reserveTokens     = max(16 384, round(0.10 x W))       (blocks at W - reserve)
 *   backgroundTokens  = (W - reserve) - round(compactAt x W)
 *   keepRecentTokens  = max(20 000, round(0.05 x W))
 * Windows so small that the global rule would put 75 % at or above the block point keep the
 * old small-window rule; an unknown window keeps Pi Durable's defaults.
 *
 * Also the steward's summary instructions and the code-built state brief.
 */
import { describe, expect, it } from "vitest";
import {
	COMPACT_AT_DEFAULT,
	COMPACT_AT_MAX,
	COMPACT_AT_MIN,
	KEEP_RECENT_TOKENS,
	MIN_RESERVE_TOKENS,
	STEWARD_SUMMARY_INSTRUCTIONS,
	clampCompactAt,
	compactionPolicyFor,
	compactAtTokensFor,
	openThreadsFrom,
} from "../src/context-policy.ts";
import { STATE_BRIEF_MAX_CHARS, buildStateBrief } from "../src/state-brief.ts";

describe("compactionPolicyFor (the model's window, not a working budget)", () => {
	it("maps the owner's windows: 1 000 000, 500 000 and 200 000", () => {
		expect(COMPACT_AT_DEFAULT).toBe(0.75);
		const million = compactionPolicyFor(1_000_000, COMPACT_AT_DEFAULT);
		expect(million).toEqual({ enabled: true, reserveTokens: 100_000, keepRecentTokens: 50_000, backgroundTokens: 150_000 });
		// Background compaction starts at 75 % of the window; it blocks at 90 %.
		expect(compactAtTokensFor(1_000_000, million)).toBe(750_000);
		expect(1_000_000 - million.reserveTokens).toBe(900_000);

		const half = compactionPolicyFor(500_000, COMPACT_AT_DEFAULT);
		expect(half).toEqual({ enabled: true, reserveTokens: 50_000, keepRecentTokens: 25_000, backgroundTokens: 75_000 });
		expect(compactAtTokensFor(500_000, half)).toBe(375_000);

		const fifth = compactionPolicyFor(200_000, COMPACT_AT_DEFAULT);
		expect(fifth).toEqual({ enabled: true, reserveTokens: 20_000, keepRecentTokens: 20_000, backgroundTokens: 30_000 });
		expect(compactAtTokensFor(200_000, fifth)).toBe(150_000);
	});

	it("keeps the answer reserve on a 128 000-token window and still starts at 75 % of the window", () => {
		const policy = compactionPolicyFor(128_000, COMPACT_AT_DEFAULT);
		expect(policy.reserveTokens).toBe(MIN_RESERVE_TOKENS);
		const block = 128_000 - policy.reserveTokens;
		expect(block).toBe(111_616);
		expect(compactAtTokensFor(128_000, policy)).toBe(96_000);
		expect(policy.keepRecentTokens).toBe(KEEP_RECENT_TOKENS);
	});

	it("honours a configured compactAt between 0.5 and 0.9", () => {
		const half = compactionPolicyFor(1_000_000, 0.5);
		expect(compactAtTokensFor(1_000_000, half)).toBe(500_000);
		// 0.9 equals the block point on a 1 000 000 window, so background compaction is
		// disabled and the block itself (90 %) is where compaction happens.
		const late = compactionPolicyFor(1_000_000, 0.9);
		expect(late.backgroundTokens).toBe(0);
		expect(compactAtTokensFor(1_000_000, late)).toBe(900_000);
	});

	it("keeps today's small-window rule where 75 % would sit at or above the block point", () => {
		const policy = compactionPolicyFor(24_000, COMPACT_AT_DEFAULT);
		// reserve = window / 4, background = 25 % of what is left, as before A11.1.
		expect(policy.reserveTokens).toBe(6_000);
		const effective = 24_000 - policy.reserveTokens;
		expect(policy.backgroundTokens).toBe(Math.round(0.25 * effective));
		expect(policy.keepRecentTokens).toBeLessThanOrEqual(Math.floor(effective / 2));
		expect(policy.keepRecentTokens).toBeGreaterThan(0);
	});

	it("clamps a configured fraction into 0.5–0.9 and falls back to 0.75 for nonsense", () => {
		expect(COMPACT_AT_MIN).toBe(0.5);
		expect(COMPACT_AT_MAX).toBe(0.9);
		expect(clampCompactAt(0.2)).toBe(0.5);
		expect(clampCompactAt(0.95)).toBe(0.9);
		expect(clampCompactAt(0.8)).toBe(0.8);
		expect(clampCompactAt(Number.NaN)).toBe(COMPACT_AT_DEFAULT);
		expect(clampCompactAt(-1)).toBe(COMPACT_AT_DEFAULT);
	});

	it("keeps Pi Durable's defaults for an unknown window", () => {
		const policy = compactionPolicyFor(0, COMPACT_AT_DEFAULT);
		expect(policy).toEqual({ enabled: true, reserveTokens: MIN_RESERVE_TOKENS, keepRecentTokens: KEEP_RECENT_TOKENS, backgroundTokens: 0 });
		expect(compactAtTokensFor(0, policy)).toBe(0);
	});
});

describe("the steward's summary instructions", () => {
	it("ask for the owner's preferences, decisions and open threads, and to drop re-readable numbers", () => {
		const text = STEWARD_SUMMARY_INSTRUCTIONS;
		expect(text).toMatch(/preferences/i);
		expect(text).toMatch(/decisions/i);
		expect(text).toMatch(/open threads/i);
		expect(text).toMatch(/numbers[^.]*(tools|re-read)/i);
		expect(text).toContain("## Owner preferences and decisions");
		expect(text).toContain("## Open threads");
	});
});

describe("openThreadsFrom (the state brief takes only these parts of the last summary)", () => {
	const summary = [
		"## Goal",
		"Keep the phone tidy.",
		"",
		"## Owner preferences and decisions",
		"- Prefers one batch.",
		"- Keeps WhatsApp media.",
		"",
		"## Open threads",
		"- Waiting on the owner for pr-3.",
		"",
		"## Progress",
		"Scanned storage on Monday, 14 GB used.",
	].join("\n");

	it("returns the preferences and open-threads sections, not the rest", () => {
		const out = openThreadsFrom(summary);
		expect(out).toContain("Prefers one batch.");
		expect(out).toContain("Waiting on the owner for pr-3.");
		expect(out).not.toContain("Scanned storage");
		expect(out).not.toContain("Keep the phone tidy.");
	});

	it("is empty when the summary has neither section", () => {
		expect(openThreadsFrom("## Goal\nSomething.")).toBe("");
		expect(openThreadsFrom(undefined)).toBe("");
	});

	it("matches the headings case-insensitively and caps its length", () => {
		const long = `## open THREADS\n${"x".repeat(20_000)}`;
		const out = openThreadsFrom(long);
		expect(out.length).toBeLessThanOrEqual(3_100);
		expect(out).toContain("x");
	});
});

describe("buildStateBrief", () => {
	const base = {
		nowMs: Date.parse("2026-10-08T10:00:00Z"),
		serverNote: "Server record (authoritative, read just now): no proposal is waiting for the owner.\nDecisions ledger: 12 entries.",
		rulesText: "Photo window: off.",
		snapshotText: "Latest snapshot 2026-10-05: 40 GB free of 256 GB.",
		summaryThreads: "- Waiting on nothing.",
		projectMemory: "Phone Steward keeps WhatsApp media.",
	};

	it("is built from documents only: no transcript text, with each part labelled", () => {
		const brief = buildStateBrief(base);
		expect(brief).toContain("fresh start");
		expect(brief).toContain("no proposal is waiting for the owner");
		expect(brief).toContain("Photo window: off.");
		expect(brief).toContain("40 GB free");
		expect(brief).toContain("Waiting on nothing.");
		expect(brief).toContain("Phone Steward keeps WhatsApp media.");
		expect(brief).toMatch(/project memory[^\n]*data/i);
	});

	it("leaves out the parts that are empty", () => {
		const brief = buildStateBrief({ ...base, projectMemory: "", summaryThreads: "" });
		expect(brief).not.toMatch(/project memory/i);
		expect(brief).not.toMatch(/open threads/i);
	});

	it("stays under its own cap whatever the parts hold, keeping the server record first", () => {
		const huge = "y".repeat(200_000);
		const brief = buildStateBrief({ ...base, rulesText: huge, snapshotText: huge, summaryThreads: huge, projectMemory: huge });
		expect(brief.length).toBeLessThanOrEqual(STATE_BRIEF_MAX_CHARS);
		expect(brief).toContain("no proposal is waiting for the owner");
	});

	it("tells the model plainly what happened and to use its tools to re-read facts", () => {
		const brief = buildStateBrief(base);
		expect(brief).toMatch(/earlier (conversation|messages)/i);
		expect(brief).toMatch(/tools/i);
	});
});
