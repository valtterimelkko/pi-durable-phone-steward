/**
 * Worklog mirror behaviour (`docs/protocol.md` §10): file name and mode, the fixture's
 * exact line sequence for the fixture's flow, restart idempotence, and truncated-tail
 * repair. Crash-across-process idempotence is proven with real SIGKILLs in
 * `test/orchestration/crash.test.ts`.
 */
import { mkdtemp, readFile, rm, stat, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { BACKGROUND_CONTEXT } from "@earendil-works/chord/context";
import { UserEntry, AssistantEntry, ToolResultEntry, type JsonObject } from "@earendil-works/pi-durable";
import type { AssistantMessage, ToolResultMessage, UserMessage } from "@earendil-works/pi-ai";
import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { afterAll, describe, expect, it, vi } from "vitest";
import { openStewardHarness, type StewardHarness } from "../../src/steward.ts";
import { recordStewardEvent } from "../../src/worklog/docs.ts";
import { WorklogMirror } from "../../src/worklog/mirror.ts";

const context = BACKGROUND_CONTEXT;
const FIXTURE_PATH = fileURLToPath(
	new URL("../../../docs/protocol-fixtures/steward-worklog.jsonl", import.meta.url),
);

const dirs: string[] = [];

afterAll(async () => {
	for (const dir of dirs) await rm(dir, { recursive: true, force: true });
});

async function scratchDir(): Promise<string> {
	const dir = await mkdtemp(join(tmpdir(), "phone-steward-mirror-"));
	dirs.push(dir);
	return dir;
}

function fakeUsage() {
	return {
		input: 1,
		output: 1,
		cacheRead: 0,
		cacheWrite: 0,
		totalTokens: 2,
		cost: { total: 0, input: 0, output: 0, cacheRead: 0, cacheWrite: 0 },
	};
}

function assistant(
	parts: Array<
		| { type: "text"; text: string }
		| { type: "toolCall"; id: string; name: string; arguments: JsonObject }
	>,
	stopReason: "stop" | "toolUse",
	timestamp: number,
): AssistantMessage {
	return {
		role: "assistant",
		content: parts,
		api: "openai-completions",
		provider: "zai",
		model: "glm-5.3-flash",
		usage: fakeUsage(),
		stopReason,
		timestamp,
	};
}

type Harness = StewardHarness & { worklogDir: string };

async function openHarness(): Promise<Harness> {
	const worklogDir = await scratchDir();
	const opened = await openStewardHarness({
		dbPath: join(worklogDir, "steward.sqlite"),
		fauxPlan: { hang: false },
	});
	// The fixture's model: the model_change line carries it at mirror start.
	await opened.root.configure({ model: { provider: "zai", modelId: "glm-5.3-flash" } }, context);
	return Object.assign(opened, { worklogDir });
}

function fixtureLines(): string[] {
	return readFileSync(FIXTURE_PATH, "utf8")
		.split("\n")
		.filter((line) => line.trim() !== "");
}

/** Append one message entry, returning its id. */
async function appendMessage(
	opened: Harness,
	message: UserMessage | AssistantMessage | ToolResultMessage,
): Promise<number> {
	return opened.harness.commit(async (tx) => {
		if (message.role === "user") {
			const entry = await tx.appendEntry(UserEntry, opened.root.id, { model: [message] });
			return Number(entry.id);
		}
		if (message.role === "assistant") {
			const entry = await tx.appendEntry(AssistantEntry, opened.root.id, { model: [message] });
			return Number(entry.id);
		}
		const entry = await tx.appendEntry(ToolResultEntry, opened.root.id, {
			model: [message],
			data: { diagnostics: [] },
		});
		return Number(entry.id);
	}, context);
}

async function emitEvent(
	opened: Harness,
	kind: Parameters<typeof recordStewardEvent>[2]["kind"],
	data: Record<string, string | number | boolean | null>,
): Promise<void> {
	await opened.harness.commit(
		(tx) => recordStewardEvent(tx, opened.root.id, { kind, atMs: Date.now(), data }),
		context,
	);
}

describe("worklog mirror", () => {
	it("creates the file 0600 with the header and model_change at start", async () => {
		const opened = await openHarness();
		const mirror = await WorklogMirror.start(opened.harness, opened.root.id, context, {
			worklogDir: opened.worklogDir,
		});
		try {
			const content = await readFile(mirror.filePath, "utf8");
			const lines = content.split("\n").filter((line) => line !== "");
			expect(lines.length).toBe(2);
			const header = JSON.parse(lines[0]!) as Record<string, unknown>;
			expect(header.type).toBe("session");
			expect(header.version).toBe(3);
			// No cwd is passed, so the header carries none.
			expect(header.cwd).toBeUndefined();
			expect(header.runtime).toBe("steward");
			expect(String(header.id)).toMatch(/^[0-9a-f-]{36}$/);
			const modelChange = JSON.parse(lines[1]!) as Record<string, unknown>;
			expect(modelChange.type).toBe("model_change");
			expect(modelChange.parentId).toBeNull();
			expect(modelChange.provider).toBe("zai");
			expect(modelChange.modelId).toBe("glm-5.3-flash");
			// File name: <sessionStart>_<uuid>.jsonl, flat in the worklog dir.
			expect(mirror.filePath).toMatch(/\d{4}-\d{2}-\d{2}T\d{2}-\d{2}-\d{2}-\d{3}Z_[0-9a-f-]{36}\.jsonl$/);
			const stats = await stat(mirror.filePath);
			expect(stats.mode & 0o777).toBe(0o600);
		} finally {
			await mirror.close();
			await opened.harness.close(context);
		}
	});

	it("reproduces the fixture's line sequence line for line for the fixture's flow", async () => {
		const opened = await openHarness();
		const mirror = await WorklogMirror.start(opened.harness, opened.root.id, context, {
			worklogDir: opened.worklogDir,
			cwd: "/opt/phone-steward",
		});
		try {
			// The fixture's flow, committed in the fixture's order. Events that follow an
			// entry are recorded in the same commit, after the entry append.
			await emitEvent(opened, "run_start", { reason: "owner" });
			await appendMessage(opened, {
				role: "user",
				content: [{ type: "text", text: "What is filling my phone?" }],
				timestamp: Date.parse("2026-10-07T09:00:00.030Z"),
			});
			await appendMessage(
				opened,
				assistant(
					[
						{ type: "text", text: "I will ask the phone for a storage overview." },
						{ type: "toolCall", id: "call_01", name: "phone_storage_overview", arguments: {} },
					],
					"toolUse",
					Date.parse("2026-10-07T09:00:02.000Z"),
				),
			);
			await opened.harness.commit(async (tx) => {
				const toolResult: ToolResultMessage = {
					role: "toolResult",
					toolCallId: "call_01",
					toolName: "phone_storage_overview",
					content: [{ type: "text", text: '{"status":"queued","jobId":"pj-task01"}' }],
					isError: false,
					timestamp: Date.parse("2026-10-07T09:00:02.050Z"),
				};
				await tx.appendEntry(ToolResultEntry, opened.root.id, { model: [toolResult], data: { diagnostics: [] } });
			}, context);
			// Recorded in its own commit, like a core that reports the event after the
			// result entry landed: the anchor scan is legal and the event follows it.
			await emitEvent(opened, "job_queued", { jobId: "pj-task01", jobKind: "storage_overview" });
			await appendMessage(
				opened,
				assistant([{ type: "text", text: "The phone will answer when it next checks in." }], "stop", Date.parse("2026-10-07T09:00:03.000Z")),
			);
			await emitEvent(opened, "run_end", {});
			await emitEvent(opened, "job_reported", { jobId: "pj-task01", status: "ok" });
			await emitEvent(opened, "run_start", { reason: "phone_result" });
			await appendMessage(opened, {
				role: "user",
				content: [
					{
						type: "text",
						text: "[phone result pj-task01] status ok: free 5.0 GB of 100.6 GB; largest app data: Play Store 7.4 GB, WhatsApp 4.1 GB.",
					},
				],
				timestamp: Date.parse("2026-10-07T09:20:00.020Z"),
			});
			await appendMessage(
				opened,
				assistant(
					[
						{
							type: "text",
							text: "Free space is 5.0 GB. The biggest reclaimable item is WhatsApp media; I have written a proposal.",
						},
						{
							type: "toolCall",
							id: "call_02",
							name: "propose_cleanup",
							arguments: {
								title: "Review WhatsApp media",
								items: [
									{
										kind: "advice",
										text: "Open WhatsApp > Settings > Storage and data > Manage storage.",
									},
								],
							},
						},
					],
					"toolUse",
					Date.parse("2026-10-07T09:20:04.000Z"),
				),
			);
			await opened.harness.commit(async (tx) => {
				const toolResult: ToolResultMessage = {
					role: "toolResult",
					toolCallId: "call_02",
					toolName: "propose_cleanup",
					content: [{ type: "text", text: '{"proposalId":"pr-0001"}' }],
					isError: false,
					timestamp: Date.parse("2026-10-07T09:20:04.050Z"),
				};
				await tx.appendEntry(ToolResultEntry, opened.root.id, { model: [toolResult], data: { diagnostics: [] } });
			}, context);
			await emitEvent(opened, "proposal_created", { proposalId: "pr-0001" });
			await appendMessage(
				opened,
				assistant(
					[{ type: "text", text: "Proposal pr-0001 is waiting for you in the app." }],
					"stop",
					Date.parse("2026-10-07T09:20:05.000Z"),
				),
			);
			await emitEvent(opened, "run_end", {});
			await mirror.sync();

			const mine = (await readFile(mirror.filePath, "utf8")).split("\n").filter((line) => line !== "");
			const fixture = fixtureLines();
			expect(mine.length).toBe(fixture.length);
			let previousId: string | undefined;
			for (let index = 0; index < fixture.length; index += 1) {
				const expected = JSON.parse(fixture[index]!) as Record<string, unknown>;
				const actual = JSON.parse(mine[index]!) as Record<string, unknown>;
				expect(actual.type, `line ${index}`).toBe(expected.type);
				if (expected.type === "session") {
					expect(actual.version).toBe(expected.version);
					expect(actual.cwd).toBe(expected.cwd);
					expect(actual.runtime).toBe(expected.runtime);
				} else {
					// Same keys in the same order; ids/timestamps differ but are well-formed.
					expect(Object.keys(actual), `line ${index}`).toEqual(Object.keys(expected));
					expect(String(actual.id), `line ${index}`).toMatch(/^[0-9a-f]{8}$/);
					expect(String(actual.timestamp), `line ${index}`).toMatch(/^\d{4}-\d{2}-\d{2}T/);
					// Linear parentId chain: each line follows the previous one.
					expect(actual.parentId, `line ${index} parentId`).toBe(index === 1 ? null : previousId);
					// Everything but id/parentId/timestamp equals the fixture's values.
					const trim = (line: Record<string, unknown>) => {
						const { id: _id, parentId: _parentId, timestamp: _timestamp, ...rest } = line;
						return rest;
					};
					expect(trim(actual), `line ${index} values`).toEqual(trim(expected));
					previousId = String(actual.id);
				}
			}
			// The steward-event kinds appear in the fixture's order.
			const kinds = mine
				.map((line) => JSON.parse(line) as Record<string, unknown>)
				.filter((line) => line.type === "custom")
				.map((line) => (line.data as Record<string, unknown>).kind);
			expect(kinds).toEqual([
				"run_start",
				"job_queued",
				"run_end",
				"job_reported",
				"run_start",
				"proposal_created",
				"run_end",
			]);
		} finally {
			await mirror.close();
			await opened.harness.close(context);
		}
	});

	it("a restarted mirror writes no line twice (same ids, same content)", async () => {
		const opened = await openHarness();
		const first = await WorklogMirror.start(opened.harness, opened.root.id, context, {
			worklogDir: opened.worklogDir,
		});
		await emitEvent(opened, "run_start", { reason: "owner" });
		await appendMessage(opened, {
			role: "user",
			content: [{ type: "text", text: "Hello" }],
			timestamp: 1,
		});
		await first.sync();
		const before = await readFile(first.filePath, "utf8");
		await first.close();

		const second = await WorklogMirror.start(opened.harness, opened.root.id, context, {
			worklogDir: opened.worklogDir,
		});
		try {
			const after = await readFile(second.filePath, "utf8");
			expect(after).toBe(before);
			// And the second mirror keeps mirroring with a continuous chain.
			await appendMessage(opened, {
				role: "user",
				content: [{ type: "text", text: "Again" }],
				timestamp: 2,
			});
			await second.sync();
			const lines = after.split("\n").filter((line) => line !== "");
			const grown = (await readFile(second.filePath, "utf8")).split("\n").filter((line) => line !== "");
			expect(grown.length).toBe(lines.length + 1);
			const tail = JSON.parse(grown[grown.length - 1]!) as Record<string, unknown>;
			const beforeTail = JSON.parse(grown[grown.length - 2]!) as Record<string, unknown>;
			expect(tail.parentId).toBe(beforeTail.id);
		} finally {
			await second.close();
			await opened.harness.close(context);
		}
	});

	it("stops observing commits once closed, so no sync follows close", async () => {
		// Pi Durable 1.5.0: `subscribeCommits()` is `observeCommits({ advance, end })` and detaching
		// is the mirror's own job; an observer left attached would sync a closed file forever.
		const opened = await openHarness();
		const mirror = await WorklogMirror.start(opened.harness, opened.root.id, context, {
			worklogDir: opened.worklogDir,
		});
		try {
			const spy = vi.spyOn(mirror, "sync");
			await mirror.close();
			await emitEvent(opened, "run_start", { reason: "owner" });
			await appendMessage(opened, { role: "user", content: [{ type: "text", text: "after close" }], timestamp: 9 });
			await new Promise((resolve) => setTimeout(resolve, 100));
			expect(spy).not.toHaveBeenCalled();
		} finally {
			await opened.harness.close(context);
		}
	});

	it("the normal order — event recorded before its entry — lands the event line first", async () => {
		const opened = await openHarness();
		const mirror = await WorklogMirror.start(opened.harness, opened.root.id, context, {
			worklogDir: opened.worklogDir,
		});
		try {
			// p2-core's instructed order (review finding 5): the core calls stewardEvent
			// before appending the entries its commit produces. Separate commits here.
			await emitEvent(opened, "run_start", { reason: "owner" });
			await appendMessage(opened, {
				role: "user",
				content: [{ type: "text", text: "What is filling my phone?" }],
				timestamp: 1,
			});
			await mirror.sync();
			const lines = (await readFile(mirror.filePath, "utf8"))
				.split("\n")
				.filter((line) => line !== "")
				.map((line) => JSON.parse(line) as Record<string, unknown>);
			// Fixture-exact: header, model_change, the run_start event, then the message.
			expect(lines.map((line) => line.type)).toEqual(["session", "model_change", "custom", "message"]);
			expect((lines[2]!.data as Record<string, unknown>).kind).toBe("run_start");
			expect((lines[3]!.message as Record<string, unknown>).role).toBe("user");
			// And the chain is linear across the boundary.
			expect(lines[3]!.parentId).toBe(lines[2]!.id);
		} finally {
			await mirror.close();
			await opened.harness.close(context);
		}
	});

	it("an event recorded after its own commit's appends lands after them (fallback anchor)", async () => {
		const opened = await openHarness();
		const mirror = await WorklogMirror.start(opened.harness, opened.root.id, context, {
			worklogDir: opened.worklogDir,
		});
		try {
			// One commit: entry append first, then the event — the anchor scan is not legal
			// after the append, so the event degrades to "after all entries known now".
			await opened.harness.commit(async (tx) => {
				const user: UserMessage = {
					role: "user",
					content: [{ type: "text", text: "Packed commit" }],
					timestamp: 10,
				};
				await tx.appendEntry(UserEntry, opened.root.id, { model: [user] });
				await recordStewardEvent(tx, opened.root.id, {
					kind: "run_end",
					atMs: 11,
					data: {},
				});
			}, context);
			await mirror.sync();
			const lines = (await readFile(mirror.filePath, "utf8"))
				.split("\n")
				.filter((line) => line !== "")
				.map((line) => JSON.parse(line) as Record<string, unknown>);
			const kinds = lines.map((line) => line.type);
			expect(kinds).toEqual(["session", "model_change", "message", "custom"]);
		} finally {
			await mirror.close();
			await opened.harness.close(context);
		}
	});

	it("repairs a truncated last line on start and rewrites it", async () => {
		const opened = await openHarness();
		const first = await WorklogMirror.start(opened.harness, opened.root.id, context, {
			worklogDir: opened.worklogDir,
		});
		await appendMessage(opened, {
			role: "user",
			content: [{ type: "text", text: "Crash mid-write" }],
			timestamp: 3,
		});
		await first.sync();
		const healthy = await readFile(first.filePath, "utf8");
		await first.close();
		await opened.harness.close(context);

		// Simulate the crash: half of the last line, no newline.
		const lines = healthy.split("\n").filter((line) => line !== "");
		const head = lines.slice(0, -1).join("\n") + "\n";
		const half = lines[lines.length - 1]!.slice(0, Math.floor(lines[lines.length - 1]!.length / 2));
		const dir = opened.worklogDir;
		const fileName = healthy.split("\n")[0] === "" ? undefined : undefined;
		void fileName;
		const mirrorPath = join(dir, (await import("node:fs")).readdirSync(dir)[0]!);
		await writeFile(mirrorPath, head + half, "utf8");

		// A fresh harness (a fresh process would do the same against the same file).
		const reopened = await openStewardHarness({
			dbPath: join(dir, "steward.sqlite"),
			fauxPlan: { hang: false },
		});
		const second = await WorklogMirror.start(reopened.harness, reopened.root.id, context, {
			worklogDir: dir,
		});
		try {
			const repaired = await readFile(second.filePath, "utf8");
			expect(repaired).toBe(healthy);
			const parsed = repaired.split("\n").filter((line) => line !== "").map((line) => JSON.parse(line));
			expect(parsed.length).toBe(lines.length);
		} finally {
			await second.close();
			await reopened.harness.close(context);
		}
	});
});
