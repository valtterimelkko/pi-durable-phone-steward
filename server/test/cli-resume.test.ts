/**
 * item 1: direct-state CLI reads must never resume work. The service is
 * stopped; its pending tasks (here: a phone-result reporter killed mid-deliver by a real
 * SIGKILL, the crash-test pattern) belong to the service process, not to a CLI reader.
 * Every direct-state open therefore uses `resume: false`; only the service (main.ts, the
 * crash-recovery tests) resumes.
 */
import { spawn, type ChildProcess } from "node:child_process";
import { mkdtemp, rm } from "node:fs/promises";
import { existsSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { fileURLToPath } from "node:url";
import { describe, expect, it } from "vitest";
import { BACKGROUND_CONTEXT } from "@earendil-works/chord/context";
import { openStewardHarness } from "../src/steward.ts";
import { ChatEvents } from "../src/chat-events.ts";

const CHILD = fileURLToPath(new URL("./child-process.ts", import.meta.url));
const CLI = fileURLToPath(new URL("../src/cli.ts", import.meta.url));

function waitForFile(path: string, timeoutMs = 60_000): Promise<void> {
	return new Promise((resolve, reject) => {
		const deadline = Date.now() + timeoutMs;
		const tick = (): void => {
			if (existsSync(path)) return resolve();
			if (Date.now() > deadline) return reject(new Error(`marker never appeared: ${path}`));
			setTimeout(tick, 50);
		};
		tick();
	});
}

function sigkill(child: ChildProcess): Promise<string> {
	return new Promise((resolve) => {
		child.on("close", (code, signal) => resolve(signal ?? `exit-${code}`));
		child.kill("SIGKILL");
	});
}

function runCli(args: string[], stateDir: string): Promise<{ status: number | null; stdout: string; stderr: string }> {
	return new Promise((resolve) => {
		const child = spawn(process.execPath, ["--experimental-strip-types", CLI, ...args], {
			env: { ...process.env, PHONE_STEWARD_STATE_DIR: stateDir },
		});
		let stdout = "";
		let stderr = "";
		child.stdout.on("data", (chunk: Buffer) => (stdout += chunk.toString()));
		child.stderr.on("data", (chunk: Buffer) => (stderr += chunk.toString()));
		child.on("close", (status) => resolve({ status, stdout, stderr }));
	});
}

type StateSnapshot = {
	liveTasks: Array<{ id: string; kind: string; status: string; phase: string }>;
	chatSeq: number;
	entryCount: number;
	transcriptJson: string;
};

/** Read the stored state WITHOUT resuming anything (the option under test). */
async function readState(dbPath: string): Promise<StateSnapshot> {
	const opened = await openStewardHarness({ dbPath, resume: false, fauxPlan: { hang: false } });
	try {
		const watch = await opened.harness.watchTaskGraph(BACKGROUND_CONTEXT);
		const graph = watch.value;
		const liveTasks = Object.entries(graph.tasks).map(([id, node]) => ({
			id,
			kind: node.kind,
			status: node.state.status,
			phase: "phase" in node.state ? String(node.state.phase) : "",
		}));
		await watch.stop();
		const chat = await opened.harness.snapshot(ChatEvents, BACKGROUND_CONTEXT);
		const page = await opened.root.entries({}, 500, undefined, BACKGROUND_CONTEXT);
		return {
			liveTasks,
			chatSeq: chat?.seq ?? 0,
			entryCount: page.items.length,
			transcriptJson: JSON.stringify(page.items.map((entry) => entry.model?.map((m) => m.content))),
		};
	} finally {
		await opened.close();
	}
}

describe("correction 01 item 1: direct-state reads never resume work", () => {
	it("a CLI read against a stopped service leaves a mid-deliver reporter untouched", async () => {
		const dir = await mkdtemp(join(tmpdir(), "w3b-resume-"));
		try {
			// Producer (crash-test pattern): report a phone result with the reporter armed to
			// block at `reporter:before-submit`, then SIGKILL — the state dir now holds a
			// reported job and a reporter task frozen mid-deliver.
			const producer = spawn(
				process.execPath,
				[CHILD, JSON.stringify({ stage: "reporter-gap", db: dir, gate: "reporter:before-submit" })],
				{ env: { ...process.env, PHONE_STEWARD_CRASH_GATES: "1" } },
			);
			await waitForFile(join(dir, "reporter-before-submit-marker"));
			expect(await sigkill(producer)).toBe("SIGKILL");

			const before = await readState(join(dir, "steward.sqlite"));
			const reporter = before.liveTasks.find((task) => task.kind.includes("phone-result"));
			expect(reporter, `live tasks: ${JSON.stringify(before.liveTasks)}`).toBeDefined();

			// The reads under test: two direct-state CLI commands against the stopped service.
			for (const args of [["chat", "--json"], ["doctor", "--json"]]) {
				const run = await runCli(args, dir);
				expect(run.status, `${args.join(" ")}: ${run.stderr}`).toBe(0);
			}

			const after = await readState(join(dir, "steward.sqlite"));
			expect(after.chatSeq).toBe(before.chatSeq);
			expect(after.entryCount).toBe(before.entryCount);
			const afterReporter = after.liveTasks.find((task) => task.kind.includes("phone-result"));
			expect(afterReporter, "the reporter task must still exist").toBeDefined();
			expect(afterReporter!.status).toBe(reporter!.status);
			// The follow-up the reporter would deliver never appeared.
			expect(after.transcriptJson).not.toContain("[phone result");
			expect(JSON.parse(after.transcriptJson === "" ? "[]" : after.transcriptJson)).toBeDefined();
		} finally {
			await rm(dir, { recursive: true, force: true });
		}
	}, 90_000);
});
