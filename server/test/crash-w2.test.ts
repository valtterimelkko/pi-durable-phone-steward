/**
 * crash tests: every new durable effect gets a real SIGKILL of a child
 * process at a deterministic gate, then a reopen of the same SQLite file. Gates sit on
 * both sides of: a rule task's job creations, the notify send, and the photo window's
 * batch step.
 */
import { spawn, type ChildProcess } from "node:child_process";
import { appendFileSync, existsSync } from "node:fs";
import { mkdtemp, rm } from "node:fs/promises";
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

async function waitForMarker(run: ChildRun, event: string, timeoutMs = 20_000): Promise<Record<string, unknown>> {
	const deadline = Date.now() + timeoutMs;
	while (Date.now() < deadline) {
		const found = markers(run).find((marker) => marker.event === event);
		if (found !== undefined) return found;
		await new Promise((resolve) => setTimeout(resolve, 10));
	}
	throw new Error(`marker ${event} never arrived; stdout=${JSON.stringify(run.stdout)} stderr=${JSON.stringify(run.stderr)}`);
}

async function waitForFile(path: string, timeoutMs = 20_000): Promise<void> {
	const deadline = Date.now() + timeoutMs;
	while (Date.now() < deadline) {
		if (existsSync(path)) return;
		await new Promise((resolve) => setTimeout(resolve, 10));
	}
	throw new Error(`marker file ${path} never appeared`);
}

async function sigkill(run: ChildRun): Promise<NodeJS.Signals | null> {
	run.child.kill("SIGKILL");
	const { signal } = await run.done;
	return signal;
}

async function freshDir(): Promise<string> {
	return mkdtemp(join(tmpdir(), "phone-steward-w2-"));
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

it("a rule task killed between job creations resumes to exactly three jobs, none duplicated", async () => {
	const dir = await freshDir();
	try {
		// The child arms the rule-task gate and creates the weekly-check run; the gate sits
		// between the first and second job-creating commits.
		const a = spawnChild({ stage: "rule-task-queue-jobs", db: dir, runId: "wc-crash-1" });
		await waitForMarker(a, "task-created");
		await waitForFile(join(dir, "rule-task-queue-marker"));
		expect(await sigkill(a)).toBe("SIGKILL");

		const b = spawnChild({ stage: "rule-task-jobs-verify", db: dir, runId: "wc-crash-1" });
		await expectCleanExit(b);
		const result = lastResult(b);
		// find-before-create on `<taskId>:<slot>` keys: the replay finds job 1 and creates
		// only jobs 2 and 3. (Bite: removing find-before-create still yields three IDs —
		// the IDs derive from the keys — so the bite probe for this rule is the
		// queueJob replay test in jobs.test.ts, which compares created flags.)
		expect(result.jobCount).toBe(3);
		expect(result.jobKinds).toEqual(["app_usage", "media_index", "storage_overview"]);
		expect(result.jobStatuses).toEqual(["queued", "queued", "queued"]);
	} finally {
		await rm(dir, { recursive: true, force: true });
	}
});

it("a notify task killed after the send replays to no second send; killed after the mark sends nothing", async () => {
	// Gate AFTER the send: the ping happened exactly once, the reopen must not repeat it.
	{
		const dir = await freshDir();
		const sentLog = join(dir, "sent.log");
		try {
			const a = spawnChild({ stage: "notify-gate", db: dir, gate: "notify:after-send", sentLog, dedupeKey: "k-after" });
			await waitForFile(join(dir, "notify-sent-marker"));
			expect(await sigkill(a)).toBe("SIGKILL");

			const b = spawnChild({ stage: "notify-verify", db: dir, gate: "notify:after-send", sentLog, dedupeKey: "k-after" });
			await expectCleanExit(b);
			const result = lastResult(b);
			expect(result.sends).toBe(1);
		} finally {
			await rm(dir, { recursive: true, force: true });
		}
	}
	// Gate BETWEEN the mark and the send: the mark wins, so the reopen skips the send —
	// at-most-once (one lost ping beats a duplicated one).
	{
		const dir = await freshDir();
		const sentLog = join(dir, "sent.log");
		try {
			const a = spawnChild({ stage: "notify-gate", db: dir, gate: "notify:after-mark", sentLog, dedupeKey: "k-mark" });
			await waitForFile(join(dir, "notify-after-mark-marker"));
			expect(await sigkill(a)).toBe("SIGKILL");

			const b = spawnChild({ stage: "notify-verify", db: dir, gate: "notify:after-mark", sentLog, dedupeKey: "k-mark" });
			await expectCleanExit(b);
			const result = lastResult(b);
			expect(result.sends).toBe(0);
		} finally {
			await rm(dir, { recursive: true, force: true });
		}
	}
});

it("a job expiry killed after the commit still delivers exactly one follow-up", async () => {
	const dir = await freshDir();
	try {
		const a = spawnChild({ stage: "expire-gate", db: dir });
		// The gate writes its marker file when the expiry commit has landed.
		await waitForFile(join(dir, "expiry-committed-marker"));
		expect(await sigkill(a)).toBe("SIGKILL");

		const b = spawnChild({ stage: "expire-verify", db: dir });
		await expectCleanExit(b);
		const result = lastResult(b);
		expect(result.expiryDeliveries).toBe(1);
	} finally {
		await rm(dir, { recursive: true, force: true });
	}
});

it("a decision killed after its commit retries to the same decisionId with duplicate: true", async () => {
	const dir = await freshDir();
	try {
		const a = spawnChild({ stage: "decision-crash-server", db: dir });
		const ready = await waitForMarker(a, "ready");
		// The gate marker appears once the decision commit has landed (response pending).
		await waitForFile(join(dir, "decision-committed-marker"));
		expect(await sigkill(a)).toBe("SIGKILL");

		const b = spawnChild({
			stage: "decision-retry-server",
			db: dir,
			proposalId: String(ready.proposalId),
		});
		await expectCleanExit(b);
		const result = lastResult(b);
		// The retry dedupes: same decisionId, duplicate true — never PROPOSAL_NOT_OPEN.
		expect(result.error).toBeUndefined();
		expect(result.duplicate).toBe(true);
		expect(String(result.decisionId)).toMatch(/^dc-/);
	} finally {
		await rm(dir, { recursive: true, force: true });
	}
});

it("a weekly task killed after its final job commit leaves exactly three persisted jobs", async () => {
	const dir = await freshDir();
	try {
		const a = spawnChild({ stage: "rule-task-queue-jobs", db: dir, runId: "wc-crash-2", gate: "after" });
		await waitForFile(join(dir, "rule-task-queue-marker"));
		expect(await sigkill(a)).toBe("SIGKILL");

		const b = spawnChild({ stage: "rule-task-jobs-verify", db: dir, runId: "wc-crash-2" });
		await expectCleanExit(b);
		const result = lastResult(b);
		expect(result.jobCount).toBe(3);
		expect(result.jobKinds).toEqual(["app_usage", "media_index", "storage_overview"]);
	} finally {
		await rm(dir, { recursive: true, force: true });
	}
});

it("a photo window killed AFTER the batch commit still has exactly one batch after reopen", async () => {
	const dir = await freshDir();
	try {
		const a = spawnChild({ stage: "photo-window-batch-gate", db: dir, runId: "pw-crash-2", gate: "after" });
		await waitForFile(join(dir, "photo-window-after-batch-marker"));
		expect(await sigkill(a)).toBe("SIGKILL");

		const b = spawnChild({ stage: "photo-window-batch-verify", db: dir, runId: "pw-crash-2" });
		await expectCleanExit(b);
		const result = lastResult(b);
		expect(result.proposals).toBe(1);
		expect(result.proposalSource).toBe("rule_batch");
	} finally {
		await rm(dir, { recursive: true, force: true });
	}
});

// item 3: SIGKILL after the effect+ledger commit for the result and
// device-status routes; the retry with the same requestId returns the original
// response with duplicate: true.
it("a result report killed after its commit retries with duplicate: true", async () => {
	const dir = await freshDir();
	try {
		const a = spawnChild({ stage: "route-crash-server", db: dir, route: "result", gate: "http:result-committed" });
		await waitForFile(join(dir, "http-result-committed-marker"));
		expect(await sigkill(a)).toBe("SIGKILL");

		const b = spawnChild({ stage: "route-retry-server", db: dir, route: "result" });
		await expectCleanExit(b);
		const result = lastResult(b);
		expect(result.status).toBe(200);
		expect(result.accepted).toBe(true);
		expect(result.duplicate).toBe(true);
	} finally {
		await rm(dir, { recursive: true, force: true });
	}
});

it("a device status killed after its commit retries with duplicate: true", async () => {
	const dir = await freshDir();
	try {
		const a = spawnChild({ stage: "route-crash-server", db: dir, route: "device", gate: "http:device-committed" });
		await waitForFile(join(dir, "http-device-committed-marker"));
		expect(await sigkill(a)).toBe("SIGKILL");

		const b = spawnChild({ stage: "route-retry-server", db: dir, route: "device" });
		await expectCleanExit(b);
		const result = lastResult(b);
		expect(result.status).toBe(200);
		expect(result.accepted).toBe(true);
		expect(result.duplicate).toBe(true);
	} finally {
		await rm(dir, { recursive: true, force: true });
	}
});

// item 1: SIGKILL at BOTH gaps (pre-submit, post-submit pre-checkpoint)
// for BOTH reporters; after reopening, exactly one follow-up and a terminal task.
for (const gap of ["reporter:before-submit", "reporter:after-submit"]) {
	it(`a phone-result reporter killed at ${gap} delivers exactly once and goes terminal`, async () => {
		const dir = await freshDir();
		try {
			const a = spawnChild({ stage: "reporter-gap", db: dir, gate: gap });
			await waitForFile(join(dir, `${gap.replaceAll(":", "-")}-marker`), 60000);
			expect(await sigkill(a)).toBe("SIGKILL");

			const b = spawnChild({ stage: "reporter-verify", db: dir });
			await expectCleanExit(b);
			const result = lastResult(b);
			expect(result.deliveries).toBe(1);
			expect(result.reporterTerminal).toBe(true);
			expect(result.jobStatus).toBe("reported");
		} finally {
			await rm(dir, { recursive: true, force: true });
		}
	});
}
for (const gap of ["expired:before-submit", "expired:after-submit"]) {
	it(`an expiry reporter killed at ${gap} delivers exactly once and goes terminal`, async () => {
		const dir = await freshDir();
		try {
			const a = spawnChild({ stage: "expired-gap", db: dir, gate: gap });
			await waitForFile(join(dir, `${gap.replaceAll(":", "-")}-marker`));
			expect(await sigkill(a)).toBe("SIGKILL");

			const b = spawnChild({ stage: "expired-verify", db: dir });
			await expectCleanExit(b);
			const result = lastResult(b);
			expect(result.deliveries).toBe(1);
			expect(result.reporterTerminal).toBe(true);
		} finally {
			await rm(dir, { recursive: true, force: true });
		}
	});
}

it("a photo window killed mid-batch resumes to exactly one batch proposal", async () => {
	const dir = await freshDir();
	try {
		// The child queues the media_index job, answers it as the phone would, and arms the
		// photo-window:batch gate — the kill lands between the remind checkpoint and the
		// batch commit.
		const a = spawnChild({ stage: "photo-window-batch-gate", db: dir, runId: "pw-crash-1" });
		await waitForFile(join(dir, "photo-window-batch-marker"));
		expect(await sigkill(a)).toBe("SIGKILL");

		const b = spawnChild({ stage: "photo-window-batch-verify", db: dir, runId: "pw-crash-1" });
		await expectCleanExit(b);
		const result = lastResult(b);
		expect(result.proposals).toBe(1);
		expect(result.proposalSource).toBe("rule_batch");
	} finally {
		await rm(dir, { recursive: true, force: true });
	}
});

for (const side of ["before", "after"] as const) {
	it(`a weekly settle killed ${side} its commit resumes to one snapshot, exactly one server-built cache batch and one message`, async () => {
		const dir = await freshDir();
		try {
			// The child answers all three jobs; the settle continuation reaches the gate that
			// sits on one side of the commit holding the snapshot, the run marker and the batch.
			const a = spawnChild({ stage: "weekly-settle-gate", db: dir, runId: `wc-settle-${side}`, gate: side });
			await waitForFile(join(dir, "weekly-settle-marker"));
			expect(await sigkill(a)).toBe("SIGKILL");

			const b = spawnChild({ stage: "weekly-settle-verify", db: dir, runId: `wc-settle-${side}` });
			await expectCleanExit(b);
			const result = lastResult(b);
			expect(result.snapshots).toBe(1);
			expect(result.builtProposals).toBe(1);
			expect(result.openProposals).toBe(1);
			expect(result.schedules).toBe(1);
			expect(result.scheduleMentionsBatch).toBe(true);
		} finally {
			await rm(dir, { recursive: true, force: true });
		}
	});
}

void appendFileSync;
