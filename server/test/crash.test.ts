/**
 * S1 crash/duplicate tests .
 *
 * Every crash is a real SIGKILL of a separate child process at a deterministic point,
 * and every reopen is a fresh process opening the same SQLite file. Delivery counts are
 * read from the reopened storage, never from memory.
 */
import { spawn, type ChildProcess } from "node:child_process";
import { existsSync } from "node:fs";
import { mkdtemp, readFile, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { fileURLToPath } from "node:url";
import { expect, it } from "vitest";

const CHILD = fileURLToPath(new URL("./child-process.ts", import.meta.url));

type ChildRun = {
	child: ChildProcess;
	stdout: string;
	stderr: string;
	done: Promise<{ code: number | null; signal: NodeJS.Signals | null }>;
};

function spawnChild(stage: Record<string, unknown>): ChildRun {
	const child = spawn(process.execPath, [CHILD, JSON.stringify(stage)], {
		stdio: ["ignore", "pipe", "pipe"],
		// The child harness arms crash gates; production code cannot (no such variable).
		env: { ...process.env, PHONE_STEWARD_CRASH_GATES: "1" },
	});
	const run: ChildRun = { child, stdout: "", stderr: "", done: undefined as never };
	run.done = new Promise((resolve, reject) => {
		child.stdout!.on("data", (chunk: Buffer) => {
			run.stdout += chunk.toString();
		});
		child.stderr!.on("data", (chunk: Buffer) => {
			run.stderr += chunk.toString();
		});
		child.on("error", reject);
		child.on("close", (code, signal) => resolve({ code, signal }));
	});
	return run;
}

function markers(run: ChildRun): Array<Record<string, unknown>> {
	return run.stdout
		.split("\n")
		.filter((line) => line.startsWith("MARKER "))
		.map((line) => JSON.parse(line.slice("MARKER ".length)) as Record<string, unknown>);
}

async function waitForMarker(run: ChildRun, event: string, timeoutMs = 15_000): Promise<Record<string, unknown>> {
	const deadline = Date.now() + timeoutMs;
	while (Date.now() < deadline) {
		const found = markers(run).find((marker) => marker.event === event);
		if (found !== undefined) return found;
		await new Promise((resolve) => setTimeout(resolve, 10));
	}
	throw new Error(`marker ${event} never arrived; stdout=${JSON.stringify(run.stdout)} stderr=${JSON.stringify(run.stderr)}`);
}

async function waitForFile(path: string, timeoutMs = 15_000): Promise<void> {
	const deadline = Date.now() + timeoutMs;
	while (Date.now() < deadline) {
		if (existsSync(path)) return;
		await new Promise((resolve) => setTimeout(resolve, 5));
	}
	throw new Error(`marker file ${path} never appeared`);
}

/** Wait until `path` exists and holds non-empty content (SIGKILL can race a fresh write). */
async function waitForFileContent(path: string, timeoutMs = 15_000): Promise<string> {
	const deadline = Date.now() + timeoutMs;
	while (Date.now() < deadline) {
		if (existsSync(path)) {
			const content = (await readFile(path, "utf8")).trim();
			if (content !== "") return content;
		}
		await new Promise((resolve) => setTimeout(resolve, 5));
	}
	throw new Error(`file ${path} never held content`);
}

async function sigkill(run: ChildRun): Promise<NodeJS.Signals | null> {
	run.child.kill("SIGKILL");
	const { signal } = await run.done;
	return signal;
}

async function freshDir(): Promise<string> {
	return mkdtemp(join(tmpdir(), "phone-steward-s1-"));
}

function lastResult(run: ChildRun): Record<string, unknown> {
	const results = markers(run).filter((marker) => marker.event === "result");
	if (results.length === 0) {
		throw new Error(`no result marker; stdout=${JSON.stringify(run.stdout)} stderr=${JSON.stringify(run.stderr)}`);
	}
	return results[results.length - 1]!;
}

async function expectCleanExit(run: ChildRun): Promise<void> {
	const { code } = await run.done;
	expect(code, `child exited ${code}; stderr=${JSON.stringify(run.stderr)}`).toBe(0);
}

it("SIGKILL during a model turn, reopen, resume() completes the run exactly once", async () => {
	const dir = await freshDir();
	const pendingMarker = join(dir, "model-request-pending");
	try {
		// RED-proof knob: flipping hang to false means no request is ever pending and the
		// pending marker never appears, which must fail this test.
		const hang = true;
		const a = spawnChild({
			stage: "hang-model-turn",
			db: dir,
			...(hang ? { hang: true, pendingMarker } : {}),
		});
		await waitForMarker(a, "submitted");
		// The marker is written from inside the faux provider's response factory, so its
		// existence proves a model request was pending (in flight) at kill time.
		await waitForFile(pendingMarker);
		expect(await sigkill(a)).toBe("SIGKILL");

		const b = spawnChild({ stage: "resume-and-settle", db: dir });
		await expectCleanExit(b);
		const result = lastResult(b);
		expect(result.userEntries).toBe(1);
		expect(result.assistantEntries).toBe(1);
		expect(result.assistantTexts).toEqual(["Hello!"]);
	} finally {
		await rm(dir, { recursive: true, force: true });
	}
});

it("SIGKILL after a job is queued; the result reported after restart is delivered exactly once", async () => {
	const dir = await freshDir();
	try {
		const a = spawnChild({ stage: "queue-job", db: dir });
		const queued = await waitForMarker(a, "job-queued");
		expect(await sigkill(a)).toBe("SIGKILL");

		const b = spawnChild({ stage: "report-and-verify", db: dir, jobId: queued.jobId, reports: 1 });
		await expectCleanExit(b);
		const result = lastResult(b);
		expect(result.jobCount).toBe(1);
		expect(result.jobStatus).toBe("queued");
		expect(result.jobStatusAfter).toBe("reported");
		expect(result.phonejobDeliveries).toBe(1);
		expect(result.assistantEntries).toBe(3);
	} finally {
		await rm(dir, { recursive: true, force: true });
	}
});

it("a duplicate result report for the same job is ignored: one follow-up input, not two", async () => {
	const dir = await freshDir();
	try {
		const a = spawnChild({ stage: "queue-job", db: dir });
		const queued = await waitForMarker(a, "job-queued");
		expect(await sigkill(a)).toBe("SIGKILL");

		const b = spawnChild({ stage: "report-and-verify", db: dir, jobId: queued.jobId, reports: 2 });
		await expectCleanExit(b);
		const result = lastResult(b);
		expect(result.jobCount).toBe(1);
		expect(result.jobStatusAfter).toBe("reported");
		expect(result.phonejobDeliveries).toBe(1);
		expect(result.userEntries).toBe(2);
		expect(result.assistantEntries).toBe(3);
	} finally {
		await rm(dir, { recursive: true, force: true });
	}
});

it("a replayed tool call (killed between intent commit and job creation) finds or creates exactly one job", async () => {
	const dir = await freshDir();
	try {
		const a = spawnChild({ stage: "replay-gate", db: dir });
		await waitForMarker(a, "submitted");
		// The marker file appears only from inside execute(), after the call's intent is
		// committed and before the job is created: the exact replay window.
		await waitForFile(join(dir, "phone-echo-marker"));
		expect(await sigkill(a)).toBe("SIGKILL");

		const b = spawnChild({ stage: "resume-verify-jobs", db: dir });
		await expectCleanExit(b);
		const result = lastResult(b);
		expect(result.jobCount).toBe(1);
		expect(result.phoneEchoCalls).toBe(1);
		expect(result.queuedToolResults).toBe(1);
		expect(result.assistantEntries).toBe(2);
	} finally {
		await rm(dir, { recursive: true, force: true });
	}
});

it("a tool call killed after the job commit replays to the same single job; the run completes", async () => {
	const dir = await freshDir();
	try {
		const a = spawnChild({ stage: "replay-gate-after-job", db: dir });
		await waitForMarker(a, "submitted");
		// The marker file appears only from inside execute(), after the job-creating commit
		// and before the tool result: the window where a replay must find the job.
		await waitForFile(join(dir, "phone-echo-after-job-marker"));
		expect(await sigkill(a)).toBe("SIGKILL");

		const b = spawnChild({ stage: "resume-verify-jobs", db: dir });
		await expectCleanExit(b);
		const result = lastResult(b);
		expect(result.jobCount).toBe(1);
		expect(result.phoneEchoCalls).toBe(1);
		expect(result.queuedToolResults).toBe(1);
		expect(result.assistantEntries).toBe(2);
	} finally {
		await rm(dir, { recursive: true, force: true });
	}
});

it("a reporter killed between the follow-up submit and the mark checkpoint replays to exactly one delivery", async () => {
	const dir = await freshDir();
	try {
		const a = spawnChild({ stage: "queue-job", db: dir });
		const queued = await waitForMarker(a, "job-queued");
		expect(await sigkill(a)).toBe("SIGKILL");

		const b = spawnChild({ stage: "report-gate", db: dir, jobId: queued.jobId });
		// The marker appears after conversation.submit() returned and before the mark
		// checkpoint commits: re-entry into deliver must not duplicate the delivery.
		await waitForFile(join(dir, "reporter-after-submit-marker"));
		// The reporter's task id lands in a file a moment later; the child stays blocked at
		// the gate until it is killed, so reading it first is safe.
		const reporterId = await waitForFileContent(join(dir, "reporter-id"));
		expect(await sigkill(b)).toBe("SIGKILL");

		const c = spawnChild({ stage: "report-resume-verify", db: dir, jobId: queued.jobId, taskId: reporterId });
		await expectCleanExit(c);
		const result = lastResult(c);
		expect(result.jobCount).toBe(1);
		expect(result.phonejobDeliveries).toBe(1);
		expect(result.jobStatusAfter).toBe("reported");
		expect(result.outcome).toBe("completed");
		expect(result.assistantEntries).toBe(3);
	} finally {
		await rm(dir, { recursive: true, force: true });
	}
});

it("two concurrent reports for one job create one reporter and deliver exactly one follow-up", async () => {
	const dir = await freshDir();
	try {
		const a = spawnChild({ stage: "queue-job", db: dir });
		const queued = await waitForMarker(a, "job-queued");
		expect(await sigkill(a)).toBe("SIGKILL");

		const b = spawnChild({ stage: "report-concurrent-verify", db: dir, jobId: queued.jobId, reports: 2 });
		await expectCleanExit(b);
		const result = lastResult(b);
		expect(result.jobCount).toBe(1);
		expect(result.phonejobDeliveries).toBe(1);
		expect(result.jobStatusAfter).toBe("reported");
		expect(result.reporterCount).toBe(1);
		expect(result.reporterRecorded).toBe(true);
	} finally {
		await rm(dir, { recursive: true, force: true });
	}
});
