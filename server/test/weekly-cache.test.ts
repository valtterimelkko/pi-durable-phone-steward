import { describe, expect, it } from "vitest";
import { buildCacheBatch, formatBytes, weeklyCheckMessage, weeklyResultLines, CACHE_APP_MIN_BYTES, CACHE_BATCH_MIN_BYTES } from "../src/weekly-cache.ts";
import type { SnapshotApp } from "../src/documents.ts";
import { canonicalJson } from "../src/canonical.ts";
import { PHONE_SUMMARY_MAX_CHARS } from "../src/phone-summary.ts";

const MIB = 1024 * 1024;

function app(packageName: string, label: string, cacheBytes: number): SnapshotApp {
	return { packageName, label, appBytes: 1, dataBytes: 1, cacheBytes, isSystem: false };
}

describe("buildCacheBatch (S7)", () => {
	it("uses 10 MiB and 100 MiB as the thresholds", () => {
		expect(CACHE_APP_MIN_BYTES).toBe(10 * MIB);
		expect(CACHE_BATCH_MIN_BYTES).toBe(100 * MIB);
	});

	it("includes every app at or above 10 MiB, biggest first, with the A6 target shape and the summed estimate", () => {
		const batch = buildCacheBatch(
			[app("a", "Small", 9 * MIB), app("b", "Edge", 10 * MIB), app("c", "Big", 2000 * MIB), app("d", "Mid", 300 * MIB)],
			"2026-10-07",
		);
		expect(batch).toBeDefined();
		expect(batch!.apps).toEqual([
			{ packageName: "c", label: "Big", cacheBytes: 2000 * MIB },
			{ packageName: "d", label: "Mid", cacheBytes: 300 * MIB },
			{ packageName: "b", label: "Edge", cacheBytes: 10 * MIB },
		]);
		expect(batch!.bytesEstimate).toBe(2310 * MIB);
		expect(batch!.title).toMatch(/^Clear app caches \(~2\.4 GB\)$/);
		expect(batch!.reason).toContain("2026-10-07");
		expect(batch!.reason).toContain("Big");
		expect(batch!.reason).toContain("Mid");
	});

	it("creates nothing when the included caches total under 100 MiB", () => {
		expect(buildCacheBatch([app("a", "A", 40 * MIB), app("b", "B", 50 * MIB), app("c", "C", 5 * MIB)], "2026-10-07")).toBeUndefined();
		expect(buildCacheBatch([], "2026-10-07")).toBeUndefined();
	});

	it("creates a batch at exactly 100 MiB, and ignores malformed rows", () => {
		const batch = buildCacheBatch(
			[app("a", "A", 60 * MIB), app("b", "B", 40 * MIB), { packageName: "x", label: "X" } as unknown as SnapshotApp],
			"2026-10-07",
		);
		expect(batch?.apps).toHaveLength(2);
		expect(batch?.bytesEstimate).toBe(100 * MIB);
	});
});

describe("formatBytes", () => {
	it("uses decimal GB and MB", () => {
		expect(formatBytes(3_300_000_000)).toBe("3.3 GB");
		expect(formatBytes(420_000_000)).toBe("420 MB");
	});
});

describe("weeklyCheckMessage (S7)", () => {
	const base = "[schedule weekly_check 2026-10-07] Run this week's storage check";

	it("lists the server's open proposals as authoritative and names the cache batch it just created", () => {
		const text = weeklyCheckMessage({
			date: "2026-10-07",
			open: [
				{ proposalId: "pr-1", title: "Clear app caches (~3.3 GB)", itemKinds: ["clear_all_caches"], bytesEstimate: 3_300_000_000 },
				{ proposalId: "pr-2", title: "Uninstall Foo", itemKinds: ["uninstall_app"], bytesEstimate: 50_000_000 },
			],
			builtCacheProposalId: "pr-1",
		});
		expect(text.startsWith(base)).toBe(true);
		expect(text).toContain("pr-1");
		expect(text).toContain("clear_all_caches");
		expect(text).toMatch(/just created by the server/i);
		expect(text).toContain("pr-2");
		expect(text).toMatch(/authoritative/i);
		expect(text).toMatch(/no other proposal is waiting for the owner/i);
	});

	it("says plainly when nothing is waiting and when the server built no cache batch", () => {
		const text = weeklyCheckMessage({ date: "2026-10-07", open: [], builtCacheProposalId: undefined });
		expect(text).toMatch(/no proposal is waiting for the owner/i);
		expect(text).toMatch(/built no cache batch/i);
	});
});

/**
 * Correction 1 (review finding 2; owner intent: no raw phone dumps in the conversation): the
 * S8 weekly message carries each gathered phone result as the same per-kind summary the
 * follow-up text uses (A11.2), with the `phone_result` pointer for the full stored result —
 * never up to 12 000 raw characters of `data=`.
 */
describe("weeklyResultLines (S8, summarised like A11.2 — correction 1)", () => {
	const okJob = (jobId: string, kind: string, data: unknown) => ({
		jobId,
		kind,
		status: "reported",
		result: { status: "ok", data, error: null },
	});
	const bigApps = { apps: Array.from({ length: 400 }, (_, index) => ({ packageName: `com.example.app${index}`, label: "z".repeat(50), appBytes: index, dataBytes: 0, cacheBytes: 0, isSystem: false })) };

	it("summarises a big result and points at phone_result instead of pasting it", () => {
		const [line] = weeklyResultLines([okJob("pj-0000000001", "app_usage", bigApps)], canonicalJson);
		expect(line).toContain("[phone job pj-0000000001] kind=app_usage status=ok summary=");
		expect(line).toContain('(full result: phone_result {"jobId":"pj-0000000001"})');
		expect(line).not.toContain("data=");
		// The summary part is at most 4 000 characters, so the whole line is bounded.
		const summary = line!.slice(line!.indexOf("summary=") + "summary=".length, line!.indexOf(" (full result:"));
		expect(summary.length).toBeLessThanOrEqual(PHONE_SUMMARY_MAX_CHARS);
	});

	it("carries no raw data= for an ok result, however small", () => {
		const [line] = weeklyResultLines([okJob("pj-0000000002", "app_usage", { apps: [{ packageName: "com.a", label: "A", lastUsedMs: null, installedMs: 1, isSystem: false }] })], canonicalJson);
		expect(line).not.toContain("data=");
		expect(line).toContain("summary=");
		expect(line).toContain('phone_result {"jobId":"pj-0000000002"}');
	});

	it("keeps every line under a 4 500-character bound for a 50 000-character result", () => {
		const data = { apps: Array.from({ length: 500 }, (_, index) => ({ packageName: `com.example.app${index}`, label: "q".repeat(90), lastUsedMs: index, installedMs: 1, isSystem: false })) };
		expect(canonicalJson(data).length).toBeGreaterThan(50_000);
		const [line] = weeklyResultLines([okJob("pj-0000000003", "app_usage", data)], canonicalJson);
		expect(line!.length).toBeLessThan(4_500);
	});

	it("keeps the expired, error and snapshot lines as they were", () => {
		const lines = weeklyResultLines(
			[
				{ jobId: "pj-x", kind: "app_usage", status: "queued" },
				{ jobId: "pj-y", kind: "media_index", status: "reported", result: { status: "partial", data: null, error: { code: "BUDGET", message: "cut short" } } },
				okJob("pj-z", "storage_overview", bigApps),
			],
			canonicalJson,
		);
		const byJob = new Map(lines.map((line) => [line.slice(0, line.indexOf("]") + 1), line]));
		expect(byJob.get("[phone job pj-x]")).toBe("[phone job pj-x] kind=app_usage status=expired (the phone did not answer)");
		expect(byJob.get("[phone job pj-y]")).toBe("[phone job pj-y] kind=media_index status=partial error=BUDGET cut short");
		expect(byJob.get("[phone job pj-z]")).toBe("[phone job pj-z] kind=storage_overview status=ok (stored as this week's snapshot)");
	});

	it("is deterministic, and the weekly message keeps its open list and cache line", () => {
		const jobs = [okJob("pj-0000000004", "app_usage", bigApps), okJob("pj-0000000005", "media_index", { folders: [{ folderKey: "DCIM/Camera", bytes: 9, count: 1, buckets: null }] })];
		const first = weeklyResultLines(jobs, canonicalJson);
		expect(weeklyResultLines(jobs, canonicalJson)).toEqual(first);
		const message = weeklyCheckMessage({
			date: "2026-10-10",
			open: [{ proposalId: "pr-1", title: "Clear app caches (~3.3 GB)", itemKinds: ["clear_all_caches"], bytesEstimate: 3_300_000_000 }],
			builtCacheProposalId: "pr-1",
			results: first,
		});
		expect(message).toContain("[schedule weekly_check 2026-10-10]");
		expect(message).toMatch(/just created by the server/i);
		expect(message).toContain("pr-1");
		expect(message).toContain('phone_result {"jobId":"pj-0000000004"}');
		expect(message).not.toContain("data=");
	});
});
