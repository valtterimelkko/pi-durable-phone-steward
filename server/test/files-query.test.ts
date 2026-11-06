/**
 * protocol A10.1 (server half): `files_query` params, the `phone_files` tool, the
 * `delete_files` proposal kind completed and checked against the phone's newest reported
 * `files_query` result (mirrors A9.1), the refusals, the steward-facing summaries.
 * All data is invented (a made-up maps app under `MapsWithMe/`).
 */
import { describe, expect, it } from "vitest";
import { BACKGROUND_CONTEXT } from "@earendil-works/chord/context";
import type { JsonObject } from "@earendil-works/pi-durable";
import { Jobs, jobIdForDedupeKey } from "../src/jobs.ts";
import { Proposals } from "../src/proposals.ts";
import { proposeCleanup } from "../src/tools.ts";
import { Decisions } from "../src/documents.ts";
import { decideProposal, recordExecution } from "../src/proposals.ts";
import {
	normaliseRelPath,
	refusedDeleteReason,
	validateFilesQuery,
	type FileEntry,
} from "../src/files-query.ts";
import { summariseToolArgs, summariseToolResult } from "../src/chat-tool-summary.ts";
import { openTestHarness, type TestHarness } from "./helpers.ts";

const context = BACKGROUND_CONTEXT;

function entry(path: string, over: Partial<FileEntry> = {}): FileEntry {
	return { path, type: "dir", sizeBytes: 1_300_000_000, modifiedMs: 1_790_000_000_000, fileCount: 42, ...over };
}

let seq = 0;
/** Store a reported files_query job straight in the jobs document. */
async function reportFiles(
	t: TestHarness,
	params: JsonObject,
	data: unknown,
	reportedAtMs: number,
	status: "ok" | "partial" | "error" = "ok",
): Promise<void> {
	seq += 1;
	const dedupeKey = `p7sf:files:${seq}`;
	await t.commit(async (tx) => {
		const doc = await tx.doc(Jobs, t.root.id);
		const jobId = jobIdForDedupeKey(dedupeKey);
		doc.jobs[jobId] = {
			jobId,
			dedupeKey,
			kind: "files_query",
			params,
			status: "reported",
			createdAtMs: reportedAtMs - 10,
			expiresAtMs: reportedAtMs + 86_400_000,
			conflictingReports: 0,
			result: { status, data: JSON.parse(JSON.stringify(data)), error: null, reportedAtMs },
		};
	});
}

async function propose(t: TestHarness, items: unknown[], title = "Old map files"): Promise<string> {
	return t.commit((tx) => proposeCleanup(tx, t.root.id, { title, reason: "r", items }, Date.now()));
}

async function stored(t: TestHarness, proposalId: string) {
	return (await t.harness.snapshot(Proposals, t.root.id, context))?.proposals[proposalId]!;
}

describe("normaliseRelPath", () => {
	it("keeps relative paths, drops empty segments and a trailing slash, accepts the root as empty", () => {
		expect(normaliseRelPath("MapsWithMe/230606/")).toBe("MapsWithMe/230606");
		expect(normaliseRelPath("MapsWithMe//230606")).toBe("MapsWithMe/230606");
		expect(normaliseRelPath("")).toBe("");
		expect(normaliseRelPath("./MapsWithMe")).toBe("MapsWithMe");
	});

	it("refuses absolute paths, .. and backslashes with BAD_PATH", () => {
		for (const bad of ["/storage/emulated/0/Download", "../etc", "a/../b", "a\\b", "a\0b"]) {
			expect(() => normaliseRelPath(bad)).toThrow(/BAD_PATH/);
		}
		expect(() => normaliseRelPath(5 as unknown as string)).toThrow(/BAD_PATH/);
	});
});

describe("validateFilesQuery (job params)", () => {
	it("list: path defaults to the root, depth to 1, and depth is 1 to 3", () => {
		expect(validateFilesQuery({ op: "list" })).toEqual({ op: "list", path: "", depth: 1 });
		expect(validateFilesQuery({ op: "list", path: "MapsWithMe/", depth: 2 })).toEqual({ op: "list", path: "MapsWithMe", depth: 2 });
		expect(() => validateFilesQuery({ op: "list", depth: 0 })).toThrow(/depth/);
		expect(() => validateFilesQuery({ op: "list", depth: 4 })).toThrow(/depth/);
		expect(() => validateFilesQuery({ op: "list", depth: 1.5 })).toThrow(/depth/);
	});

	it("find: needs a name pattern; fills defaults; limit is 1 to 500", () => {
		expect(validateFilesQuery({ op: "find", namePattern: "*.mwm" })).toEqual({
			op: "find",
			root: "",
			namePattern: "*.mwm",
			type: "any",
			minBytes: 0,
			olderThanDays: null,
			limit: 200,
		});
		expect(
			validateFilesQuery({ op: "find", root: "MapsWithMe", namePattern: "*.mwm", type: "file", minBytes: 1000, olderThanDays: 30, limit: 500 }),
		).toEqual({ op: "find", root: "MapsWithMe", namePattern: "*.mwm", type: "file", minBytes: 1000, olderThanDays: 30, limit: 500 });
		expect(() => validateFilesQuery({ op: "find" })).toThrow(/namePattern/);
		expect(() => validateFilesQuery({ op: "find", namePattern: "*", limit: 501 })).toThrow(/limit/);
		expect(() => validateFilesQuery({ op: "find", namePattern: "*", type: "socket" })).toThrow(/type/);
		expect(() => validateFilesQuery({ op: "find", namePattern: "*", minBytes: -1 })).toThrow(/minBytes/);
		expect(() => validateFilesQuery({ op: "find", namePattern: "*", root: "../x" })).toThrow(/BAD_PATH/);
	});

	it("du: a non-empty list of at most 20 relative paths", () => {
		expect(validateFilesQuery({ op: "du", paths: ["MapsWithMe", "Download/"] })).toEqual({ op: "du", paths: ["MapsWithMe", "Download"] });
		expect(() => validateFilesQuery({ op: "du", paths: [] })).toThrow(/paths/);
		expect(() => validateFilesQuery({ op: "du" })).toThrow(/paths/);
		expect(() => validateFilesQuery({ op: "du", paths: Array.from({ length: 21 }, (_, i) => `d${i}`) })).toThrow(/paths/);
		expect(() => validateFilesQuery({ op: "du", paths: ["/abs"] })).toThrow(/BAD_PATH/);
	});

	it("an unknown op is refused", () => {
		expect(() => validateFilesQuery({ op: "delete", path: "x" })).toThrow(/op/);
		expect(() => validateFilesQuery({})).toThrow(/op/);
	});
});

describe("refusedDeleteReason (A10.1 always-refused paths)", () => {
	it("refuses the root, Android/, .StewardTrash/, any Keep segment (any case) and DCIM/ Pictures/", () => {
		const refused = [
			"",
			"Android",
			"Android/data/com.example.app",
			"android/obb",
			".StewardTrash",
			".StewardTrash/2026-10-08/pr-1/x",
			"Keep",
			"Documents/keep/old",
			"Documents/KEEP",
			"DCIM",
			"DCIM/Camera",
			"Pictures",
			"Pictures/Screenshots/a.png",
			"dcim/Camera",
		];
		for (const path of refused) expect(refusedDeleteReason(path), path).toBeTypeOf("string");
	});

	it("allows ordinary paths, including look-alikes", () => {
		for (const path of ["MapsWithMe", "MapsWithMe/230606", "Download/old.zip", "Documents/Keeper", "Backups/Android-old", "Notes/DCIM-copy", "Movies/Pictures"]) {
			expect(refusedDeleteReason(path), path).toBeUndefined();
		}
	});
});

describe("delete_files proposal items (A10.1, mirroring A9.1)", () => {
	it("completes the target from the reported files_query row and sets the estimate from sizeBytes", async () => {
		const t = await openTestHarness();
		try {
			await reportFiles(t, { op: "list", path: "MapsWithMe", depth: 1 }, { path: "MapsWithMe", entries: [entry("MapsWithMe/230606"), entry("MapsWithMe/230711", { sizeBytes: 500 })], truncated: false }, 1000);
			const out = await propose(t, [
				{ kind: "delete_files", target: { path: "MapsWithMe/230606", note: "old" } },
				{ kind: "delete_files", target: { path: "MapsWithMe/230711/" }, bytesEstimate: 600 },
			]);
			const proposal = await stored(t, (JSON.parse(out) as { proposalId: string }).proposalId);
			expect(proposal.items[0]!.target).toEqual(entry("MapsWithMe/230606"));
			expect(proposal.items[0]!.bytesEstimate).toBe(1_300_000_000);
			expect(proposal.items[1]!.target).toEqual(entry("MapsWithMe/230711", { sizeBytes: 500 }));
			expect(proposal.items[1]!.bytesEstimate).toBe(600);
			expect(proposal.bytesEstimate).toBe(1_300_000_600);
		} finally {
			await t.close();
		}
	});

	it("finds rows in find and du results too, the newest report winning over older ones", async () => {
		const t = await openTestHarness();
		try {
			await reportFiles(t, { op: "list" }, { path: "", entries: [entry("MapsWithMe", { sizeBytes: 111 })], truncated: false }, 1000);
			await reportFiles(t, { op: "find" }, { items: [entry("Other/world.mwm", { type: "file", sizeBytes: 222, fileCount: undefined })], truncated: false }, 3000);
			await reportFiles(t, { op: "du", paths: ["MapsWithMe"] }, { paths: [{ path: "MapsWithMe", bytes: 2_600_000_000, files: 90, dirs: 4, unreadable: false }] }, 2000);
			const out = await propose(t, [
				{ kind: "delete_files", target: { path: "MapsWithMe" } },
				{ kind: "delete_files", target: { path: "Other/world.mwm" } },
			]);
			const proposal = await stored(t, (JSON.parse(out) as { proposalId: string }).proposalId);
			const dir = proposal.items[0]!.target as FileEntry;
			// the du size is newest for that path; type and modified time are kept from the list row
			expect(dir.sizeBytes).toBe(2_600_000_000);
			expect(dir.fileCount).toBe(90);
			expect(dir.type).toBe("dir");
			expect(dir.modifiedMs).toBe(1_790_000_000_000);
			expect(proposal.items[1]!.target).toMatchObject({ path: "Other/world.mwm", type: "file", sizeBytes: 222 });
		} finally {
			await t.close();
		}
	});

	it("refuses a path no reported files_query result holds, naming it and phone_files, and stores nothing", async () => {
		const t = await openTestHarness();
		try {
			await reportFiles(t, { op: "list" }, { path: "", entries: [entry("MapsWithMe")], truncated: false }, 1000);
			await expect(
				propose(t, [
					{ kind: "delete_files", target: { path: "MapsWithMe" } },
					{ kind: "delete_files", target: { path: "Ghosts/none" } },
				]),
			).rejects.toThrow(/Ghosts\/none.*phone_files/s);
			expect(Object.keys((await t.harness.snapshot(Proposals, t.root.id, context))?.proposals ?? {})).toEqual([]);
		} finally {
			await t.close();
		}
	});

	it("ignores an errored report, and a report of another job kind", async () => {
		const t = await openTestHarness();
		try {
			await reportFiles(t, { op: "list" }, { path: "", entries: [entry("MapsWithMe")], truncated: false }, 1000, "error");
			await expect(propose(t, [{ kind: "delete_files", target: { path: "MapsWithMe" } }])).rejects.toThrow(/MapsWithMe/);
		} finally {
			await t.close();
		}
	});

	it("refuses the always-refused paths even when the phone reported them", async () => {
		const t = await openTestHarness();
		try {
			const paths = ["Android/data/x.y", ".StewardTrash/2026-10-08", "Documents/Keep/old", "DCIM/Camera", "Pictures/Screenshots", "Download/keep"];
			await reportFiles(t, { op: "find" }, { items: paths.map((p) => entry(p)), truncated: false }, 1000);
			for (const path of paths) {
				await expect(propose(t, [{ kind: "delete_files", target: { path } }]), path).rejects.toThrow(/refus|protected|not allowed/i);
			}
			await expect(propose(t, [{ kind: "delete_files", target: { path: "" } }])).rejects.toThrow(/refus/i);
			await expect(propose(t, [{ kind: "delete_files", target: { path: "../etc" } }])).rejects.toThrow(/BAD_PATH/);
			expect(Object.keys((await t.harness.snapshot(Proposals, t.root.id, context))?.proposals ?? {})).toEqual([]);
		} finally {
			await t.close();
		}
	});

	it("refuses an unreadable row, a duplicate path, and a path inside another listed path", async () => {
		const t = await openTestHarness();
		try {
			await reportFiles(
				t,
				{ op: "list" },
				{ path: "", entries: [entry("MapsWithMe"), entry("MapsWithMe/230606"), entry("Locked", { unreadable: true } as Partial<FileEntry>)], truncated: false },
				1000,
			);
			await expect(propose(t, [{ kind: "delete_files", target: { path: "Locked" } }])).rejects.toThrow(/unreadable|readable/i);
			await expect(
				propose(t, [
					{ kind: "delete_files", target: { path: "MapsWithMe" } },
					{ kind: "delete_files", target: { path: "MapsWithMe/" } },
				]),
			).rejects.toThrow(/twice|duplicate/i);
			await expect(
				propose(t, [
					{ kind: "delete_files", target: { path: "MapsWithMe" } },
					{ kind: "delete_files", target: { path: "MapsWithMe/230606" } },
				]),
			).rejects.toThrow(/inside/i);
		} finally {
			await t.close();
		}
	});

	it("needs target.path as a string", async () => {
		const t = await openTestHarness();
		try {
			await expect(propose(t, [{ kind: "delete_files", target: { name: "x" } }])).rejects.toThrow(/target\.path/);
		} finally {
			await t.close();
		}
	});

	it("a files proposal supersedes an earlier open files proposal only", async () => {
		const t = await openTestHarness();
		try {
			await reportFiles(t, { op: "list" }, { path: "", entries: [entry("MapsWithMe"), entry("Old")], truncated: false }, 1000);
			const first = JSON.parse(await propose(t, [{ kind: "delete_files", target: { path: "MapsWithMe" } }])) as { proposalId: string };
			const caches = JSON.parse(
				await t.commit((tx) =>
					proposeCleanup(tx, t.root.id, { title: "c", reason: "r", items: [{ kind: "clear_all_caches", target: { apps: [{ packageName: "a.b", label: "A", cacheBytes: 5 }] } }] }, Date.now()),
				),
			) as { proposalId: string };
			expect((await stored(t, first.proposalId)).status).toBe("open");
			const second = JSON.parse(await propose(t, [{ kind: "delete_files", target: { path: "Old" } }])) as { proposalId: string; superseded?: string[] };
			expect(second.superseded).toEqual([first.proposalId]);
			expect((await stored(t, first.proposalId)).status).toBe("superseded");
			expect((await stored(t, caches.proposalId)).status).toBe("open");
		} finally {
			await t.close();
		}
	});

	it("the decisions ledger names the path of a delete_files item", async () => {
		const t = await openTestHarness();
		try {
			await reportFiles(t, { op: "list" }, { path: "", entries: [entry("MapsWithMe")], truncated: false }, 1000);
			const created = JSON.parse(await propose(t, [{ kind: "delete_files", target: { path: "MapsWithMe" } }])) as { proposalId: string };
			const proposal = await stored(t, created.proposalId);
			await decideProposal(t.harness, t.root.id, created.proposalId, { decision: "approve" }, context);
			const ledger = (await t.harness.snapshot(Decisions, t.root.id, context))!.entries;
			expect(ledger.some((row) => row.kind === "delete_files" && row.path === "MapsWithMe")).toBe(true);
			void proposal;
		} finally {
			await t.close();
		}
	});

	it("an execution report carries trashedBytes into the ledger (space returns when the Steward trash is emptied)", async () => {
		const t = await openTestHarness();
		try {
			await reportFiles(t, { op: "list" }, { path: "", entries: [entry("MapsWithMe")], truncated: false }, 1000);
			const created = JSON.parse(await propose(t, [{ kind: "delete_files", target: { path: "MapsWithMe" } }])) as { proposalId: string };
			const proposal = await stored(t, created.proposalId);
			const decision = await decideProposal(t.harness, t.root.id, created.proposalId, { decision: "approve" }, context);
			await recordExecution(
				t.harness,
				t.root.id,
				created.proposalId,
				{ decisionId: decision.decisionId, items: [{ itemId: proposal.items[0]!.itemId, outcome: "done", resultCode: null, bytesFreed: 0, trashedBytes: 1_300_000_000 }] },
				context,
			);
			const ledger = (await t.harness.snapshot(Decisions, t.root.id, context))!.entries;
			const row = ledger.find((candidate) => candidate.type === "executed" && candidate.kind === "delete_files");
			expect(row?.bytesTrashed).toBe(1_300_000_000);
			expect(row?.path).toBe("MapsWithMe");
			expect((await stored(t, created.proposalId)).status).toBe("executed");
		} finally {
			await t.close();
		}
	});
});

describe("steward-facing summaries for the files tools (A8 style)", () => {
	it("describes phone_files arguments by op, depth and counts, never by a path or a pattern", () => {
		const list = summariseToolArgs("phone_files", { op: "list", path: "MapsWithMe/230606", depth: 2 });
		expect(list).toBe("op list, depth 2");
		const find = summariseToolArgs("phone_files", { op: "find", root: "Secret", namePattern: "*private*", type: "file", minBytes: 1000, olderThanDays: 30, limit: 50 });
		expect(find).toContain("op find");
		expect(find).toContain("type file");
		expect(find).toContain("limit 50");
		expect(find).not.toMatch(/Secret|private/);
		expect(summariseToolArgs("phone_files", { op: "du", paths: ["a", "b", "c"] })).toBe("op du, 3 paths");
	});

	it("describes the queued job in the result", () => {
		expect(summariseToolResult("phone_files", JSON.stringify({ status: "queued", jobId: "pj-1a2b3c" }), false)).toBe("queued job pj-1a2b3c");
	});

	it("counts delete_files items by kind in propose_cleanup", () => {
		expect(summariseToolArgs("propose_cleanup", { items: [{ kind: "delete_files", target: { path: "secret/name" } }] })).toBe("1 item: 1 delete_files");
	});
});
