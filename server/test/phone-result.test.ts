/**
 * A11.2: phone results enter the conversation as deterministic per-kind summaries, and the
 * `phone_result` tool returns the full stored result (or one sub-value, paged) on demand.
 *
 * Found 2026-10-08: of 208 000 tokens in the steward's context, 172 000 were 17 raw phone
 * results (storage overviews, app-usage lists, media indexes) pasted whole as follow-up
 * inputs. Summaries fix that without losing the detail: the full result stays under the job,
 * and the tool fetches it again.
 */
import { describe, expect, it } from "vitest";
import { BACKGROUND_CONTEXT } from "@earendil-works/chord/context";
import type { JsonValue } from "@earendil-works/chord";
import { canonicalJson } from "../src/canonical.ts";
import { PHONE_RESULT_MAX_CHARS, PHONE_RESULT_MAX_LIMIT, phoneResultAnswer, phoneResultTool } from "../src/phone-result.ts";
import { PHONE_SUMMARY_MAX_CHARS, summarisePhoneResult } from "../src/phone-summary.ts";
import { Jobs, phoneResultText, type PhoneJob, type PhoneJobResult } from "../src/jobs.ts";
import { openTestHarness, type TestHarness } from "./helpers.ts";

const context = BACKGROUND_CONTEXT;

type Tool = { name: string; execute: (args: unknown, api: unknown, callContext: unknown) => Promise<{ output: Array<{ text: string }> }> };

function callTool(t: TestHarness, args: Record<string, unknown>): Promise<Record<string, any>> {
	const tool = phoneResultTool as unknown as Tool;
	const api = { taskId: "task-1", conversationId: t.root.id, commit: <T>(fn: (tx: never) => T) => t.commit(fn as never) as Promise<T> };
	return tool.execute(args, api, context).then((result) => JSON.parse(result.output[0]!.text) as Record<string, any>);
}

/** Put a job with a stored result straight into this conversation's jobs document. */
async function storeJob(t: TestHarness, jobId: string, kind: PhoneJob["kind"], data: JsonValue, status: PhoneJobResult["status"] = "ok"): Promise<void> {
	await t.commit(async (tx) => {
		const doc = await tx.doc(Jobs, t.root.id);
		doc.jobs[jobId] = {
			jobId,
			dedupeKey: jobId,
			kind,
			params: {},
			status: "reported",
			createdAtMs: 0,
			expiresAtMs: 1,
			conflictingReports: 0,
			result: { status, data, error: null, reportedAtMs: 0 },
		};
	});
}

const app = (n: number, total: number, label = `App ${n}`) => ({
	packageName: `com.example.app${n}`,
	label,
	appBytes: total,
	dataBytes: 0,
	cacheBytes: 0,
	isSystem: false,
});

describe("per-kind digests (totals and counts always; the largest items first)", () => {
	it("storage_overview keeps the totals and categories and the 25 largest apps", () => {
		const data = {
			totalBytes: 256_000_000_000,
			freeBytes: 40_000_000_000,
			apps: Array.from({ length: 30 }, (_, index) => app(index, (index + 1) * 1_000)),
			categories: { imagesBytes: 1, videoBytes: 2, audioBytes: 3, documentsBytes: 4, appsBytes: 5, systemBytes: 6, otherBytes: 7 },
		};
		const summary = summarisePhoneResult("storage_overview", data as unknown as JsonValue) as Record<string, any>;
		expect(summary.totalBytes).toBe(256_000_000_000);
		expect(summary.freeBytes).toBe(40_000_000_000);
		expect(summary.categories).toEqual(data.categories);
		expect(summary.apps).toHaveLength(25);
		expect(summary.appsCount).toBe(30);
		// Biggest first: app 29 (30 000 bytes) then app 28.
		expect(summary.apps[0].packageName).toBe("com.example.app29");
		expect(summary.apps[1].packageName).toBe("com.example.app28");
	});

	it("app_usage keeps never-used apps and a count", () => {
		const data = {
			apps: [
				{ packageName: "com.a", label: "A", lastUsedMs: 5_000, installedMs: 1, isSystem: false },
				{ packageName: "com.b", label: "B", lastUsedMs: null, installedMs: 1, isSystem: false },
				{ packageName: "com.c", label: "C", lastUsedMs: 1_000, installedMs: 1, isSystem: false },
			],
		};
		const summary = summarisePhoneResult("app_usage", data as unknown as JsonValue) as Record<string, any>;
		expect(summary.appsCount).toBe(3);
		const packages = summary.apps.map((entry: any) => entry.packageName);
		expect(packages).toContain("com.b"); // never used
		expect(packages).toEqual(["com.b", "com.c", "com.a"]); // never-used first, then oldest
	});

	it("media_index keeps folder totals, the largest items and the mode's list", () => {
		const data = {
			scope: ["DCIM/Camera/"],
			folders: [
				{ folderKey: "Download", bytes: 10, count: 1, buckets: null },
				{ folderKey: "DCIM/Camera", bytes: 900, count: 4, buckets: { over4m: { count: 1, bytes: 900 } } },
			],
			largest: [{ mediaId: "external_primary:images:1", sizeBytes: 9 }, { mediaId: "external_primary:images:2", sizeBytes: 8 }],
			topLevelFiles: [{ name: "a.bin", sizeBytes: 3, modifiedMs: 0 }],
			oldApks: [],
		};
		const summary = summarisePhoneResult("media_index", data as unknown as JsonValue) as Record<string, any>;
		expect(summary.scope).toEqual(["DCIM/Camera/"]);
		expect(summary.folders[0].folderKey).toBe("DCIM/Camera"); // biggest folder first
		expect(summary.foldersCount).toBe(2);
		expect(summary.largest).toHaveLength(2);
		expect(summary.largestCount).toBe(2);
		expect(summary.topLevelFilesCount).toBe(1);
	});

	it("files_query keeps the first 60 rows, largest first, with the reported flags", () => {
		const data = {
			path: "Download",
			entries: Array.from({ length: 70 }, (_, index) => ({ path: `Download/f${index}`, type: "file", sizeBytes: index })),
			truncated: false,
		};
		const summary = summarisePhoneResult("files_query", data as unknown as JsonValue) as Record<string, any>;
		expect(summary.path).toBe("Download");
		expect(summary.entries).toHaveLength(60);
		expect(summary.entriesCount).toBe(70);
		expect(summary.entries[0].sizeBytes).toBe(69); // largest first
		expect(summary.truncated).toBe(false);
	});

	it("falls back to a generic digest that keeps scalars and cuts arrays to 20 with a count", () => {
		const summary = summarisePhoneResult("force_stop", {
			packageName: "com.a",
			stopped: true,
			trace: Array.from({ length: 50 }, (_, index) => ({ step: index })),
		} as unknown as JsonValue) as Record<string, any>;
		expect(summary.packageName).toBe("com.a");
		expect(summary.stopped).toBe(true);
		expect(summary.trace).toHaveLength(20);
		expect(summary.traceCount).toBe(50);
	});

	it("caps its own output at 4 000 characters however big the result is", () => {
		const data = {
			apps: Array.from({ length: 40 }, (_, index) => app(index, index, "A very long application label that repeats itself ".repeat(8))),
			categories: { imagesBytes: 1 },
			totalBytes: 1,
			freeBytes: 0,
		};
		const summary = summarisePhoneResult("storage_overview", data as unknown as JsonValue);
		expect(canonicalJson(summary).length).toBeLessThanOrEqual(PHONE_SUMMARY_MAX_CHARS);
	});

	it("is deterministic: same data, same text, whatever the key order", () => {
		const a = { freeBytes: 1, totalBytes: 2, apps: [app(2, 2), app(1, 1)] };
		const b = { apps: [app(1, 1), app(2, 2)], totalBytes: 2, freeBytes: 1 };
		expect(canonicalJson(summarisePhoneResult("storage_overview", a as unknown as JsonValue))).toBe(
			canonicalJson(summarisePhoneResult("storage_overview", b as unknown as JsonValue)),
		);
	});
});

describe("the follow-up text (jobs.phoneResultText)", () => {
	const ok = (data: JsonValue): PhoneJobResult => ({ status: "ok", data, error: null });

	it("sends a small result whole, exactly as before", () => {
		const text = phoneResultText("pj-000000000001", "force_stop", ok({ packageName: "com.a", stopped: true }));
		expect(text).toBe('[phone result pj-000000000001] kind=force_stop status=ok data={"packageName":"com.a","stopped":true}');
	});

	it("summarises a result too big for the conversation and points at phone_result", () => {
		const data = { apps: Array.from({ length: 400 }, (_, index) => app(index, index, "a long label ".repeat(6))) };
		expect(canonicalJson(data).length).toBeGreaterThan(PHONE_SUMMARY_MAX_CHARS);
		const text = phoneResultText("pj-000000000001", "storage_overview", ok(data as unknown as JsonValue));
		expect(text.startsWith("[phone result pj-000000000001] kind=storage_overview status=ok summary=")).toBe(true);
		expect(text).toContain('(full result: phone_result {"jobId":"pj-000000000001"})');
		// The summary part is at most 4 000 characters.
		const summary = text.slice(text.indexOf("summary=") + "summary=".length, text.indexOf(" (full result:"));
		expect(summary.length).toBeLessThanOrEqual(PHONE_SUMMARY_MAX_CHARS);
	});

	it("is byte-identical on a replay (deterministic digits and order)", () => {
		const data = { apps: Array.from({ length: 400 }, (_, index) => app(index, index * 3)) };
		const first = phoneResultText("pj-000000000002", "storage_overview", ok(data as unknown as JsonValue));
		const again = phoneResultText("pj-000000000002", "storage_overview", ok(data as unknown as JsonValue));
		expect(again).toBe(first);
	});

	it("leaves error and partial texts unchanged", () => {
		expect(phoneResultText("pj-000000000003", "app_usage", { status: "error", data: null, error: { code: "NOT_ALLOWED", message: "no access" } })).toBe(
			"[phone result pj-000000000003] kind=app_usage status=error error=NOT_ALLOWED no access",
		);
		expect(phoneResultText("pj-000000000004", "media_index", { status: "partial", data: null, error: { code: "BUDGET", message: "cut short" } })).toBe(
			"[phone result pj-000000000004] kind=media_index status=partial error=BUDGET cut short",
		);
	});
});

describe("the phone_result tool (server-only, read-only)", () => {
	const bigData = { apps: Array.from({ length: 120 }, (_, index) => app(index, index)) };

	it("returns the stored full data of a job of this conversation", async () => {
		const t = await openTestHarness();
		try {
			await storeJob(t, "pj-000000000010", "storage_overview", { totalBytes: 5, apps: [app(1, 1)] } as unknown as JsonValue);
			const out = await callTool(t, { jobId: "pj-000000000010" });
			expect(out.status).toBe("ok");
			expect(out.kind).toBe("storage_overview");
			expect(out.data).toMatchObject({ totalBytes: 5 });
		} finally {
			await t.close();
		}
	});

	it("walks a dotted path and pages an array by offset and limit", async () => {
		const t = await openTestHarness();
		try {
			await storeJob(t, "pj-000000000011", "storage_overview", bigData as unknown as JsonValue);
			const first = await callTool(t, { jobId: "pj-000000000011", path: "apps", limit: 50 });
			expect(first.status).toBe("ok");
			expect(first.path).toBe("apps");
			expect(first.items).toHaveLength(50);
			expect(first.count).toBe(120);
			expect(first.offset).toBe(0);
			expect(first.limit).toBe(50);
			expect(first.nextOffset).toBe(50);
			const second = await callTool(t, { jobId: "pj-000000000011", path: "apps", offset: 50, limit: 50 });
			expect(second.items[0]).toMatchObject({ packageName: "com.example.app50" });
			expect(second.nextOffset).toBe(100);
			const last = await callTool(t, { jobId: "pj-000000000011", path: "apps", offset: 100, limit: 50 });
			expect(last.items).toHaveLength(20);
			expect(last.nextOffset).toBeUndefined();
		} finally {
			await t.close();
		}
	});

	it("caps a page at 20 000 characters and says so with the next offset", async () => {
		const t = await openTestHarness();
		try {
			const wide = { rows: Array.from({ length: 500 }, (_, index) => ({ path: `Download/${"x".repeat(300)}-${index}`, sizeBytes: index })) };
			await storeJob(t, "pj-000000000012", "files_query", wide as unknown as JsonValue);
			const out = await callTool(t, { jobId: "pj-000000000012", path: "rows", limit: PHONE_RESULT_MAX_LIMIT });
			expect(out.status).toBe("ok");
			expect(out.truncated).toBe(true);
			expect(canonicalJson(out).length).toBeLessThanOrEqual(PHONE_RESULT_MAX_CHARS);
			expect(out.items.length).toBeLessThan(PHONE_RESULT_MAX_LIMIT);
			expect(out.nextOffset).toBe(out.items.length);
		} finally {
			await t.close();
		}
	});

	it("refuses a path that is not there, and a job of another conversation", async () => {
		const t = await openTestHarness();
		try {
			await storeJob(t, "pj-000000000013", "storage_overview", { apps: [] } as unknown as JsonValue);
			const missing = await callTool(t, { jobId: "pj-000000000013", path: "nope.deep" });
			expect(missing.status).toBe("no_such_path");

			const other = await t.harness.createConversation({ ownership: { kind: "ownerless" } }, context);
			await t.commit(async (tx) => {
				const doc = await tx.doc(Jobs, other.id);
				doc.jobs["pj-0000000000ff"] = {
					jobId: "pj-0000000000ff",
					dedupeKey: "other",
					kind: "storage_overview",
					params: {},
					status: "reported",
					createdAtMs: 0,
					expiresAtMs: 1,
					conflictingReports: 0,
					result: { status: "ok", data: { secret: "another conversation" } as unknown as JsonValue, error: null, reportedAtMs: 0 },
				};
			});
			const refused = await callTool(t, { jobId: "pj-0000000000ff" });
			expect(refused.status).toBe("not_found");
			expect(JSON.stringify(refused)).not.toContain("another conversation");
		} finally {
			await t.close();
		}
	});

	it("answers no_result for a job that has not been reported", async () => {
		const t = await openTestHarness();
		try {
			await t.commit(async (tx) => {
				const doc = await tx.doc(Jobs, t.root.id);
				doc.jobs["pj-000000000014"] = {
					jobId: "pj-000000000014",
					dedupeKey: "queued",
					kind: "storage_overview",
					params: {},
					status: "queued",
					createdAtMs: 0,
					expiresAtMs: Date.now() + 1_000,
					conflictingReports: 0,
				};
			});
			expect((await callTool(t, { jobId: "pj-000000000014" })).status).toBe("no_result");
		} finally {
			await t.close();
		}
	});

	it("is a pure reader: no write happens", async () => {
		const t = await openTestHarness();
		try {
			await storeJob(t, "pj-000000000015", "force_stop", { stopped: true } as unknown as JsonValue);
			const before = canonicalJson(await t.harness.snapshot(Jobs, t.root.id, context));
			await callTool(t, { jobId: "pj-000000000015" });
			expect(canonicalJson(await t.harness.snapshot(Jobs, t.root.id, context))).toBe(before);
		} finally {
			await t.close();
		}
	});
});

describe("phoneResultAnswer (the pure core)", () => {
	it("defaults the limit to 50 and rejects a nonsense offset or limit", () => {
		const job = {
			jobId: "pj-000000000020",
			kind: "storage_overview",
			result: { status: "ok", data: { apps: Array.from({ length: 80 }, (_, index) => app(index, index)) }, error: null, reportedAtMs: 0 },
		} as unknown as PhoneJob;
		const out = phoneResultAnswer(job, { jobId: job.jobId, path: "apps" }) as Record<string, any>;
		expect(out.limit).toBe(50);
		expect(out.items).toHaveLength(50);
		expect(() => phoneResultAnswer(job, { jobId: job.jobId, offset: -1 })).toThrow(/offset/);
		expect(() => phoneResultAnswer(job, { jobId: job.jobId, limit: 1_000 })).toThrow(/limit/);
	});
});

/**
 * Correction 1 (review finding 1): the 4 000-character cap is absolute for ANY JSON, not only
 * for the array-shaped results the per-kind rules produce. Result data is arbitrary JSON from
 * the paired phone (bounded only by the 2 MiB body limit), so key-dominated and deeply nested
 * payloads must not slip through.
 */
describe("the hard 4 000-character cap for any JSON (correction 1)", () => {
	const scalarKeys = (count: number, length: number) =>
		Object.fromEntries(Array.from({ length: count }, (_, index) => [`key_${String(index).padStart(4, "0")}`, "x".repeat(length)]));
	const longKeyObjects = (count: number, length: number) =>
		Object.fromEntries(Array.from({ length: count }, (_, index) => [`${"k".repeat(length)}_${index}`, 1]));
	const nestedObjects = (count: number) => ({ rows: Array.from({ length: count }, () => ({ s: "y".repeat(300) })) });
	const deep = (depth: number) => {
		let value: unknown = "leaf";
		for (let level = 0; level < depth; level++) value = { ["k".repeat(300)]: value };
		return value;
	};

	it("caps the shapes the reviewer measured, plus deep nesting and a 2 MB string, byte-identically", () => {
		const shapes: Array<[string, JsonValue]> = [
			["300 scalar keys x 300-char strings", scalarKeys(300, 300) as unknown as JsonValue],
			["40 keys of 500-char names", longKeyObjects(40, 500) as unknown as JsonValue],
			["60 nested objects each holding a 300-char string", nestedObjects(60) as unknown as JsonValue],
			["deep nesting (depth 50)", deep(50) as unknown as JsonValue],
			["a 2 MB string", "x".repeat(2_000_000) as unknown as JsonValue],
		];
		for (const [name, data] of shapes) {
			const first = summarisePhoneResult("storage_overview", data);
			const second = summarisePhoneResult("storage_overview", data);
			expect(canonicalJson(first).length, `${name} (first call)`).toBeLessThanOrEqual(PHONE_SUMMARY_MAX_CHARS);
			expect(canonicalJson(second), `${name} (deterministic)`).toBe(canonicalJson(first));
		}
	});

	it("keeps the Count fields when it prunes keys", () => {
		const data = { ...scalarKeys(300, 300), appsCount: 7 } as unknown as JsonValue;
		const summary = summarisePhoneResult("storage_overview", data) as Record<string, any>;
		expect(canonicalJson(summary).length).toBeLessThanOrEqual(PHONE_SUMMARY_MAX_CHARS);
		expect(summary.appsCount).toBe(7);
	});

	it("falls back to a minimal deterministic shape when even pruning cannot fit", () => {
		const summary = summarisePhoneResult("storage_overview", deep(300) as unknown as JsonValue) as Record<string, any>;
		expect(canonicalJson(summary).length).toBeLessThanOrEqual(PHONE_SUMMARY_MAX_CHARS);
		expect(summary).toMatchObject({ truncated: true, note: "use phone_result" });
		expect(typeof summary.keys).toBe("number");
		expect(summarisePhoneResult("storage_overview", deep(300) as unknown as JsonValue)).toEqual(summary);
	});
});
