/**
 * A8: the optional `argsSummary` / `resultSummary` on `tool` chat events. They are built
 * server-side from the tool call and its result, at most 300 characters, and carry only
 * counts, sizes, ids, kinds and statuses: never a file path, a media name, a token or any
 * other personal data (redaction is enforced here, like diagnostics.ts, not trusted).
 */
import { describe, expect, it } from "vitest";
import {
	TOOL_SUMMARY_LIMIT,
	callIdOf,
	summariseToolArgs,
	summariseToolResult,
	toolResultText,
} from "../src/chat-tool-summary.ts";

describe("summariseToolArgs", () => {
	it("says 'no arguments' for an empty call", () => {
		expect(summariseToolArgs("phone_snapshot", {})).toBe("no arguments");
		expect(summariseToolArgs("rules", undefined)).toBe("no arguments");
	});

	it("describes phone_media_index by mode and counts, never by folder name", () => {
		const text = summariseToolArgs("phone_media_index", {
			mode: "rule_candidates",
			folders: ["DCIM/Camera", "Pictures/Screenshots/", "Android/media/com.whatsapp/"],
			olderThanDays: 120,
		});
		expect(text).toBe("mode rule_candidates, 3 folders, older than 120 days");
		expect(text).not.toMatch(/DCIM|Camera|whatsapp|Screenshots/);
	});

	it("describes decisions by its limit", () => {
		expect(summariseToolArgs("decisions", { limit: 10 })).toBe("limit 10");
	});

	it("describes propose_cleanup by item kinds and counts, never by title, target or reason", () => {
		const text = summariseToolArgs("propose_cleanup", {
			title: "Trash IMG_2041.jpg and friends",
			reason: "old photos in DCIM/Camera",
			items: [
				{ kind: "trash_media", target: { displayName: "IMG_2041.jpg" }, bytesEstimate: 1000 },
				{ kind: "trash_media", target: { displayName: "IMG_2042.jpg" }, bytesEstimate: 1000 },
				{ kind: "advice", target: { text: "Keep folder note" } },
			],
		});
		expect(text).toBe("3 items: 2 trash_media, 1 advice");
		expect(text).not.toMatch(/IMG|DCIM|Keep|Trash/);
	});

	it("lists only the argument names of an unknown tool", () => {
		expect(summariseToolArgs("mystery", { path: "/sdcard/DCIM/secret.jpg", n: 3 })).toBe("arguments: path, n");
	});

	it("drops argument names that are not short identifiers", () => {
		expect(summariseToolArgs("mystery", { "/sdcard/DCIM/secret.jpg": 1, ok_key: 2 })).toBe("arguments: ok_key");
	});

	it("never exceeds the limit", () => {
		const args: Record<string, number> = {};
		for (let i = 0; i < 100; i += 1) args[`key_number_${i}`] = i;
		expect(summariseToolArgs("mystery", args).length).toBeLessThanOrEqual(TOOL_SUMMARY_LIMIT);
	});
});

describe("summariseToolResult", () => {
	it("reports a queued phone job by its id", () => {
		expect(summariseToolResult("phone_storage_overview", JSON.stringify({ status: "queued", jobId: "pj-1a2b3c" }), false)).toBe(
			"queued job pj-1a2b3c",
		);
	});

	it("reports a proposal by its id", () => {
		expect(summariseToolResult("propose_cleanup", JSON.stringify({ proposalId: "pr-123" }), false)).toBe("proposal pr-123 written");
	});

	it("notes a merge or supersede on a proposal", () => {
		const text = summariseToolResult(
			"propose_cleanup",
			JSON.stringify({ proposalId: "pr-9", superseded: ["pr-1"], note: "x" }),
			false,
		);
		expect(text).toBe("proposal pr-9 written, replaced 1 earlier proposal");
	});

	it("counts decision entries", () => {
		expect(summariseToolResult("decisions", JSON.stringify({ status: "ok", entries: [{}, {}, {}] }), false)).toBe("3 entries");
		expect(summariseToolResult("decisions", JSON.stringify({ status: "ok", entries: [] }), false)).toBe("no entries yet");
	});

	it("counts open proposals", () => {
		const text = summariseToolResult(
			"proposals",
			JSON.stringify({ status: "ok", openAndApproved: [{}, {}], recentClosed: [{}] }),
			false,
		);
		expect(text).toBe("2 open, 1 recently closed");
	});

	it("gives a snapshot's age", () => {
		expect(summariseToolResult("phone_snapshot", JSON.stringify({ status: "ok", ageMs: 3 * 3_600_000 + 5 }), false)).toBe(
			"snapshot 3 h old",
		);
		expect(summariseToolResult("phone_snapshot", JSON.stringify({ status: "no-snapshot" }), false)).toBe("no snapshot yet");
	});

	it("reports the trend status", () => {
		expect(summariseToolResult("storage_trend", JSON.stringify({ status: "ok", trend: {} }), false)).toBe("trend available");
		expect(summariseToolResult("storage_trend", JSON.stringify({ status: "not-enough-snapshots" }), false)).toBe(
			"not enough snapshots yet",
		);
	});

	it("falls back to status and size for an unknown tool, never the content", () => {
		const text = summariseToolResult("mystery", JSON.stringify({ status: "ok", secret: "/sdcard/DCIM/IMG_1.jpg" }), false);
		expect(text).toBe("status ok");
		expect(text).not.toMatch(/sdcard|IMG/);
		expect(summariseToolResult("mystery", "plain words that are not JSON", false)).toBe("29 characters");
	});

	it("redacts an error message: paths, file names and token-shaped runs", () => {
		const text = summariseToolResult(
			"propose_cleanup",
			"Error: cannot read /sdcard/DCIM/Camera/IMG_2041.jpg with token abcdefghijklmnopqrstuvwxyz0123 or notes.txt",
			true,
		);
		expect(text.startsWith("error: ")).toBe(true);
		expect(text).not.toMatch(/sdcard|DCIM|IMG_2041|abcdefghijklmnopqrstuvwxyz0123|notes\.txt/);
		expect(text).toContain("[redacted]");
	});

	it("caps an error at the limit", () => {
		expect(summariseToolResult("x", `Error: ${"word ".repeat(200)}`, true).length).toBeLessThanOrEqual(TOOL_SUMMARY_LIMIT);
	});
});

describe("helpers", () => {
	it("joins the text parts of a tool result", () => {
		expect(
			toolResultText({
				role: "toolResult",
				content: [
					{ type: "text", text: "a" },
					{ type: "image" },
					{ type: "text", text: "b" },
				],
			}),
		).toBe("ab");
		expect(toolResultText({ role: "toolResult", content: "plain" })).toBe("plain");
		expect(toolResultText({ role: "toolResult" })).toBe("");
	});

	it("keeps only a short id-shaped callId", () => {
		expect(callIdOf("call_abc-123")).toBe("call_abc-123");
		expect(callIdOf("has spaces/and/slashes")).toBeUndefined();
		expect(callIdOf("x".repeat(100))).toBeUndefined();
		expect(callIdOf(undefined)).toBeUndefined();
	});
});
