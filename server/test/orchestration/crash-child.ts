/**
 * Child process for the orchestration SIGKILL crash tests. One stage per invocation:
 *
 *     node test/orchestration/crash-child.ts '<json stage>'
 *
 * Same protocol as `test/child-process.ts`: `MARKER {…}` lines on stdout, marker files
 * written from inside the armed crash gate (kernel-visible at once), and every stage
 * opens the SQLite file its parent named, so a reopen stage proves recovery in a fresh
 * process. The outbox stages deliver through the REAL execFile runner against a stub
 * command (its argv witness lands in `$ORCHESTRATION_STUB_OUT`), so the crash window is
 * the real spawn, not a script.
 */
import { existsSync } from "node:fs";
import { mkdir, readFile, readdir } from "node:fs/promises";
import { dirname, join } from "node:path";
import { createHash } from "node:crypto";
import { BACKGROUND_CONTEXT } from "@earendil-works/chord/context";
import { armCrashGate } from "../../src/crash-gate.ts";
import type { OrchestrationConfig } from "../../src/integrations.ts";
import { createOrchestrationIntegrations } from "../../src/orchestration/index.ts";
import { OrchestrationOutbox } from "../../src/orchestration/outbox.ts";
import { openTestSteward } from "./test-steward.ts";

const context = BACKGROUND_CONTEXT;

type Stage = {
	stage: "mirror-fill" | "mirror-verify" | "outbox-enqueue" | "outbox-verify";
	gate: "before-write" | "after-write" | "truncated-write" | "before-call" | "after-call";
	db: string;
	gateDir: string;
	worklogDir: string;
	command: string;
	stubOut: string;
	kind?: "presence" | "capture";
};

const stage = JSON.parse(process.argv[2]!) as Stage;

function markerLine(payload: Record<string, unknown>): void {
	process.stdout.write(`MARKER ${JSON.stringify(payload)}\n`);
}

function keepAlive(): void {
	setInterval(() => {}, 1_000_000);
}

function config(): OrchestrationConfig {
	return {
		// Mirror stages need no orchestration calls; the outbox stages enable them.
		enabled: stage.stage === "outbox-enqueue" || stage.stage === "outbox-verify",
		command: stage.command,
		memoryRoot: join(stage.gateDir, "memory"),
		boardDir: join(stage.gateDir, "board"),
		worklogDir: stage.worklogDir,
		timeoutMs: 20_000,
	};
}

function isJson(line: string): boolean {
	try {
		JSON.parse(line);
		return true;
	} catch {
		return false;
	}
}

/** Wait until the mirror file is stable: newline-terminated, every line parseable. */
async function mirrorSettledLines(filePath: string, stableFor = 3): Promise<string[]> {
	let previous = "";
	let stable = 0;
	for (;;) {
		const content = existsSync(filePath) ? await readFile(filePath, "utf8") : "";
		if (content === previous && content.endsWith("\n") && content.split("\n").every((line) => line === "" || isJson(line))) {
			stable += 1;
			if (stable >= stableFor) return content.split("\n").filter((line) => line !== "");
		} else {
			stable = 0;
		}
		previous = content;
		await new Promise((resolve) => setTimeout(resolve, 50));
	}
}

try {
	switch (stage.stage) {
		case "mirror-fill": {
			const integrations = createOrchestrationIntegrations(config(), {});
			const opened = await openTestSteward({
				dbPath: join(stage.db, "steward.sqlite"),
				extensions: integrations.extensions,
				fauxPlan: { hang: false },
			});
			// Starts the mirror: header + model_change are written before any gate is armed.
			await integrations.onHarnessOpen?.(opened.harness, context);
			// Arm AFTER the initial sync so the gate catches the event line's write.
			armCrashGate(
				`orchestration-mirror:${stage.gate}`,
				join(stage.gateDir, `${stage.gate}-marker`),
				join(stage.gateDir, `${stage.gate}-release`),
			);
			await opened.harness.commit(
				(tx) =>
					integrations.stewardEvent?.(tx, opened.root.id, {
						kind: "run_start",
						atMs: 1_000,
						data: { reason: "owner" },
					}),
				context,
			);
			markerLine({ event: "event-recorded" });
			keepAlive();
			break;
		}
		case "mirror-verify": {
			const integrations = createOrchestrationIntegrations(config(), {});
			const opened = await openTestSteward({
				dbPath: join(stage.db, "steward.sqlite"),
				extensions: integrations.extensions,
				fauxPlan: { hang: false },
			});
			// Mirror start repairs a truncated tail and reconciles the missing lines.
			await integrations.onHarnessOpen?.(opened.harness, context);
			const dirEntries = await readdir(stage.worklogDir);
			const filePath = join(stage.worklogDir, dirEntries.find((file) => file.endsWith(".jsonl"))!);
			const lines = await mirrorSettledLines(filePath);
			const parsed = lines.map((line) => JSON.parse(line) as Record<string, unknown>);
			const ids = parsed.filter((line) => line.type !== "session").map((line) => String(line.id));
			let chainOk = true;
			let previousId: string | undefined;
			for (let index = 1; index < parsed.length; index += 1) {
				const expected = index === 1 ? null : previousId;
				if (parsed[index]!.parentId !== expected) chainOk = false;
				previousId = String(parsed[index]!.id);
			}
			const eventKinds = parsed
				.filter((line) => line.type === "custom")
				.map((line) => (line.data as Record<string, unknown>).kind);
			const sha256 = createHash("sha256").update(`${lines.join("\n")}\n`).digest("hex");
			markerLine({
				event: "result",
				count: lines.length,
				uniqueIds: new Set(ids).size === ids.length,
				chainOk,
				eventKinds,
				sha256,
				fileName: dirEntries.find((file) => file.endsWith(".jsonl")),
			});
			await integrations.close?.();
			await opened.harness.close(context);
			break;
		}
		case "outbox-enqueue": {
			// The delivery env snapshot is taken when the integrations are created, so the
			// stub witness path must be in the environment first.
			process.env.ORCHESTRATION_STUB_OUT = stage.stubOut;
			await mkdir(dirname(stage.command), { recursive: true });
			const integrations = createOrchestrationIntegrations(config(), {});
			const opened = await openTestSteward({
				dbPath: join(stage.db, "steward.sqlite"),
				extensions: integrations.extensions,
				fauxPlan: { hang: false },
			});
			await integrations.onHarnessOpen?.(opened.harness, context);
			// The pump is a background task: scheduling needs resume(), as on a real boot.
			opened.harness.resume();
			armCrashGate(
				`orchestration-outbox:${stage.gate}`,
				join(stage.gateDir, `${stage.gate}-marker`),
				join(stage.gateDir, `${stage.gate}-release`),
			);
			await opened.harness.commit(
				(tx) =>
					stage.kind === "capture"
						? integrations.stewardEvent?.(tx, opened.root.id, {
								kind: "review_line",
								atMs: 2_000,
								data: { outcome: "failure", proposalId: "pr-crash-1" },
							})
						: integrations.stewardEvent?.(tx, opened.root.id, {
								kind: "run_start",
								atMs: 2_000,
								data: { reason: "crash probe" },
							}),
				context,
			);
			markerLine({ event: "enqueued", kind: stage.kind ?? "presence" });
			keepAlive();
			break;
		}
		case "outbox-verify": {
			process.env.ORCHESTRATION_STUB_OUT = stage.stubOut;
			const integrations = createOrchestrationIntegrations(config(), {});
			const opened = await openTestSteward({
				dbPath: join(stage.db, "steward.sqlite"),
				extensions: integrations.extensions,
				fauxPlan: { hang: false },
			});
			opened.harness.resume();
			// Wait until every outbox entry is done, then read the stub's witness file.
			const deadline = Date.now() + 60_000;
			let entries: Record<string, Record<string, unknown>> = {};
			for (;;) {
				const doc = await opened.harness.snapshot(OrchestrationOutbox, opened.root.id, context);
				entries = (doc?.entries ?? {}) as unknown as Record<string, Record<string, unknown>>;
				if (
					Object.values(entries).length > 0 &&
					Object.values(entries).every((entry) => entry.state === "done")
				) {
					break;
				}
				if (Date.now() > deadline) break;
				await new Promise((resolve) => setTimeout(resolve, 50));
			}
		const witness = existsSync(stage.stubOut)
			? (await readFile(stage.stubOut, "utf8"))
					.split("\n")
					.filter((line) => line !== "")
					.map((line) => JSON.parse(line) as { key: string | null; duplicate: boolean; argv: string[] })
			: [];
		const calls = witness.map((entry) => entry.argv);
		const notes = calls.map((argv) => argv[argv.indexOf("--note") + 1] ?? "");
		const statuses = calls.map((argv) => argv[argv.indexOf("--status") + 1] ?? "");
		// A capture effect is persisted only for a first-seen idempotency key.
		const persistedCaptures = witness.filter((entry) => entry.key !== null && !entry.duplicate).length;
		// Presence updates are idempotent: re-sending updates one card, never a second.
		const boardCardDir = join(stage.gateDir, "board", "agents");
		const boardCardCount = existsSync(boardCardDir)
			? (await readdir(boardCardDir)).filter((file) => file.endsWith(".json")).length
			: 0;
		markerLine({
			event: "result",
			entryStates: Object.values(entries).map((entry) => entry.state),
			callCount: calls.length,
			argvIdentical:
				calls.length > 0 && calls.every((argv) => JSON.stringify(argv) === JSON.stringify(calls[0])),
			notesIdentical: notes.length > 0 && notes.every((note) => note === notes[0]),
			statuses,
			persistedCaptures,
			boardCardCount,
		});
			await integrations.close?.();
			await opened.harness.close(context);
			break;
		}
	}
} catch (error) {
	console.error(String(error));
	process.exit(1);
}
