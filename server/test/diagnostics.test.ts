/**
 * O6: the phone_diagnostics job — queued by the admin route, answered by the phone in the
 * A6 result shape, stored with the log capped (200 entries, 200-char details), printed by
 * `bin/steward diag`, and NEVER delivered into the conversation: no follow-up input, no
 * run, no model turn. A real server on a temporary port and state dir.
 */
import { connect } from "node:net";
import { mkdtemp, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { afterAll, beforeAll, describe, expect, it } from "vitest";
import { BACKGROUND_CONTEXT } from "@earendil-works/chord/context";
import { loadConfig } from "../src/config.ts";
import { openStewardHarness } from "../src/steward.ts";
import { startStewardServers, type StewardServers } from "../src/http.ts";
import { ChatEvents } from "../src/chat-events.ts";
import { expireDueJobs } from "../src/jobs.ts";
import { normaliseDiagnosticsResult, DIAGNOSTICS_LIMITS } from "../src/diagnostics.ts";

let dir: string;
let port: number;
let servers: StewardServers;
let harness: Awaited<ReturnType<typeof openStewardHarness>>["harness"];
let rootConversation: Awaited<ReturnType<typeof openStewardHarness>>["root"];
let closeHarness: () => Promise<void>;
let token: string;

async function admin(method: "GET" | "POST", path: string, body?: unknown): Promise<{ status: number; body: any }> {
	return new Promise((resolve, reject) => {
		const socket = connect(join(dir, "admin.sock"), () => {
			const payload = body === undefined ? "" : JSON.stringify(body);
			socket.write(
				`${method} ${path} HTTP/1.1\r\nhost: admin\r\ncontent-type: application/json\r\ncontent-length: ${Buffer.byteLength(payload)}\r\nconnection: close\r\n\r\n${payload}`,
			);
		});
		let raw = "";
		socket.on("data", (chunk: Buffer) => {
			raw += chunk.toString("utf8");
		});
		socket.on("error", reject);
		socket.on("close", () => {
			const text = raw.slice(raw.indexOf("\r\n\r\n") + 4);
			resolve({ status: Number(/^HTTP\/1\.[01] (\d+)/.exec(raw)?.[1] ?? 0), body: JSON.parse(text) });
		});
	});
}

function device(path: string, body: unknown, method = "POST"): Promise<{ status: number; body: any }> {
	return fetch(`http://127.0.0.1:${port}${path}`, {
		method,
		headers: { "content-type": "application/json", authorization: `Bearer ${token}` },
		body: method === "GET" ? undefined : JSON.stringify(body),
	}).then(async (response) => ({ status: response.status, body: await response.json() }));
}

async function chatSeq(): Promise<number> {
	const doc = await harness.snapshot(ChatEvents, BACKGROUND_CONTEXT);
	return doc?.seq ?? 0;
}

function diagnosticsResult(overrides: Record<string, unknown> = {}): Record<string, unknown> {
	return {
		appVersion: "0.2.2",
		versionCode: 4,
		permissions: { usageAccess: true, allFiles: true, mediaRead: true, manageMedia: false, batteryUnrestricted: true },
		worker: { state: "ENQUEUED", lastRunMs: 1_760_000_000_000, runAttemptCount: 0 },
		outbox: { crashes: 0, errors: 0 },
		log: Array.from({ length: 250 }, (_, i) => ({
			atMs: 1_760_000_000_000 + i,
			level: "info",
			event: `event_${i}`,
			detail: `d-${i}-${"x".repeat(250)}`,
		})),
		...overrides,
	};
}

async function waitFor(check: () => Promise<boolean> | boolean, timeoutMs = 15_000): Promise<void> {
	const deadline = Date.now() + timeoutMs;
	while (Date.now() < deadline) {
		if (await check()) return;
		await new Promise((resolve) => setTimeout(resolve, 100));
	}
	throw new Error("timed out");
}

beforeAll(async () => {
	dir = await mkdtemp(join(tmpdir(), "w3b-diag-"));
	const config = loadConfig({ stateDir: dir, port: 0, notify: {}, schedules: { enabled: false } });
	const opened = await openStewardHarness({
		dbPath: join(dir, "steward.sqlite"),
		fauxPlan: { hang: false },
		model: { provider: "zai", modelId: "glm-5.3-flash" },
	});
	harness = opened.harness;
	rootConversation = opened.root;
	closeHarness = () => opened.close();
	servers = startStewardServers({ config, harness, root: opened.root, notifySender: async () => undefined });
	await new Promise<void>((resolve) => servers.tcp.listen(0, "127.0.0.1", resolve));
	port = (servers.tcp.address() as { port: number }).port;
	const pairCode = await admin("POST", "/admin/pair");
	const paired = await device("/v1/pair", { code: pairCode.body.code, deviceName: "diag-suite" });
	token = paired.body.token;
});

afterAll(async () => {
	await servers.close();
	await closeHarness();
	await rm(dir, { recursive: true, force: true });
});

describe("phone_diagnostics (O6)", () => {
	it("queues through POST /admin/diagnostics, stores the capped A6 result, and never reaches the conversation", async () => {
		const seqBefore = await chatSeq();
		const queued = await admin("POST", "/admin/diagnostics");
		expect(queued.status).toBe(200);
		expect(queued.body).toMatchObject({ queued: true });
		const jobId: string = queued.body.jobId;
		expect(jobId).toMatch(/^pj-/);

		const fetched = await device(`/v1/jobs?wait=5`, null, "GET");
		const job = (fetched.body.jobs as Array<{ jobId: string; kind: string; params: Record<string, unknown> }>).find(
			(candidate) => candidate.jobId === jobId,
		);
		expect(job).toBeDefined();
		expect(job!.kind).toBe("phone_diagnostics");
		expect(job!.params).toEqual({});

		const answered = await device(`/v1/jobs/${jobId}/result`, {
			requestId: `diag-result-${jobId}`,
			status: "ok",
			data: diagnosticsResult(),
		});
		expect(answered.status).toBe(200);

		await waitFor(async () => (await admin("GET", `/admin/jobs/${jobId}`)).body.status === "reported");
		await waitFor(async () => {
			// The reporter must have settled before we judge the conversation.
			const stored = (await admin("GET", `/admin/jobs/${jobId}`)).body;
			return stored.status === "reported" && (await chatSeq()) === seqBefore;
		});
		const stored = (await admin("GET", `/admin/jobs/${jobId}`)).body;
		expect(stored.status).toBe("reported");
		expect(stored.result.status).toBe("ok");
		const data = stored.result.data as { appVersion: string; log: Array<{ detail: string }> };
		expect(data.appVersion).toBe("0.2.2");
		expect(data.log.length).toBeLessThanOrEqual(DIAGNOSTICS_LIMITS.logEntries);
		// Newest entries survive the cap (the app's log is newest last).
		expect(data.log.at(-1)!.detail.length).toBeLessThanOrEqual(DIAGNOSTICS_LIMITS.detailChars);
		expect(data.log.some((entry) => entry.detail.length > DIAGNOSTICS_LIMITS.detailChars)).toBe(false);

		// No follow-up, no run, no model turn: the chat event store gained nothing.
		expect(await chatSeq()).toBe(seqBefore);
	}, 30_000);

	it("expiry tells no one: an expired diagnostics job creates no conversation entry", async () => {
		const seqBefore = await chatSeq();
		await harness.commit(async (tx) => {
			const { queueJob } = await import("../src/jobs.ts");
			const { jobId } = await queueJob(tx, rootConversation.id, {
				dedupeKey: "diag-expired:w3b",
				kind: "phone_diagnostics",
				params: {},
				nowMs: Date.now(),
			});
			const doc = await tx.doc(await import("../src/jobs.ts").then((m) => m.Jobs), rootConversation.id);
			doc.jobs[jobId]!.expiresAtMs = Date.now() - 1;
			return { jobId };
		}, BACKGROUND_CONTEXT);
		await expireDueJobs(harness, rootConversation.id, Date.now(), BACKGROUND_CONTEXT);
		await new Promise((resolve) => setTimeout(resolve, 800));
		expect(await chatSeq()).toBe(seqBefore);
	});

	it("a phone_selftest expiry still tells the conversation (control)", async () => {
		const seqBefore = await chatSeq();
		await harness.commit(async (tx) => {
			const { queueJob } = await import("../src/jobs.ts");
			const { jobId } = await queueJob(tx, rootConversation.id, {
				dedupeKey: "selftest-expired:w3b",
				kind: "phone_selftest",
				params: { checks: [] },
				nowMs: Date.now(),
			});
			const doc = await tx.doc(await import("../src/jobs.ts").then((m) => m.Jobs), rootConversation.id);
			doc.jobs[jobId]!.expiresAtMs = Date.now() - 1;
			return { jobId };
		}, BACKGROUND_CONTEXT);
		await expireDueJobs(harness, rootConversation.id, Date.now(), BACKGROUND_CONTEXT);
		await waitFor(async () => (await chatSeq()) > seqBefore, 20_000);
	});
});

describe("normaliseDiagnosticsResult (correction 01 item 3: cleaned on the server too)", () => {
	it("caps the log and details, keeping the newest entries", () => {
		const out = normaliseDiagnosticsResult(diagnosticsResult() as never) as {
			log: Array<{ detail: string; event: string }>;
		};
		expect(out.log).toHaveLength(DIAGNOSTICS_LIMITS.logEntries);
		expect(out.log[0]!.event).toBe(`event_${250 - DIAGNOSTICS_LIMITS.logEntries}`);
		expect(out.log.every((entry) => entry.detail.length <= DIAGNOSTICS_LIMITS.detailChars)).toBe(true);
	});

	it("keeps only the A6 fields and drops every other key", () => {
		const out = normaliseDiagnosticsResult({
			appVersion: "0.2.2",
			versionCode: 4,
			ownerEmail: "PRIVATE_SENTINEL@example.com",
			notes: "PRIVATE_SENTINEL",
			permissions: { usageAccess: true, allFiles: true, mediaRead: true, manageMedia: false, batteryUnrestricted: true, locationFine: true },
			worker: { state: "ENQUEUED", lastRunMs: 1, runAttemptCount: 0, queueName: "PRIVATE_SENTINEL" },
			outbox: { crashes: 1, errors: 2, pending: 9 },
			log: [{ atMs: 1, level: "info", event: "boot", detail: "ok", stack: "PRIVATE_SENTINEL" }],
		} as never) as Record<string, unknown>;
		expect(JSON.stringify(out)).not.toContain("PRIVATE_SENTINEL");
		expect(out).toMatchObject({
			appVersion: "0.2.2",
			versionCode: 4,
			permissions: { usageAccess: true, allFiles: true, mediaRead: true, manageMedia: false, batteryUnrestricted: true },
			worker: { state: "ENQUEUED", lastRunMs: 1, runAttemptCount: 0 },
			outbox: { crashes: 1, errors: 2 },
			log: [{ atMs: 1, level: "info", event: "boot", detail: "ok" }],
		});
		expect(Object.keys(out.permissions as object)).toHaveLength(5);
		expect(Object.keys(out.worker as object)).toHaveLength(3);
		expect(Object.keys(out.outbox as object)).toHaveLength(2);
		expect(Object.keys((out.log as Array<Record<string, unknown>>)[0]!)).toHaveLength(4);
	});

	it("replaces an event that is not a short snake_case name with invalid_event", () => {
		const out = normaliseDiagnosticsResult({
			log: [
				{ event: "good_event.name1", detail: "" },
				{ event: "Bad Event!", detail: "" },
				{ event: "x".repeat(65), detail: "" },
				{ event: "", detail: "" },
			],
		} as never) as { log: Array<{ event: string }> };
		expect(out.log.map((entry) => entry.event)).toEqual(["good_event.name1", "invalid_event", "invalid_event", "invalid_event"]);
	});

	it("redacts paths, file names and token-like runs in detail; normal details survive", () => {
		const out = normaliseDiagnosticsResult({
			log: [
				{ event: "scan", detail: "failed to read /storage/emulated/0/DCIM/PRIVATE_SENTINEL.jpg" },
				{ event: "auth", detail: "token eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9 rejected" },
				{ event: "cache_scan", detail: "scanned PRIVATE_SENTINEL.txt done" },
				{ event: "jobs_polled", detail: "polled 2 jobs" },
				{ event: "ui", detail: "status.battery_button" },
			],
		} as never) as { log: Array<{ detail: string }> };
		const all = JSON.stringify(out.log);
		expect(all).not.toContain("PRIVATE_SENTINEL");
		expect(all).not.toContain("eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9");
		expect(out.log[0]!.detail).toBe("failed to read [redacted]");
		expect(out.log[1]!.detail).toBe("token [redacted] rejected");
		expect(out.log[2]!.detail).toBe("scanned [redacted] done");
		expect(out.log[3]!.detail).toBe("polled 2 jobs");
		expect(out.log[4]!.detail).toBe("status.battery_button");
	});

	it("junk shapes clean to nothing: non-objects become null, a non-array log is dropped", () => {
		expect(normaliseDiagnosticsResult(null)).toBeNull();
		expect(normaliseDiagnosticsResult("junk" as never)).toBeNull();
		expect(normaliseDiagnosticsResult([1, 2] as never)).toBeNull();
		expect(normaliseDiagnosticsResult({ log: "nope" } as never)).toEqual({});
	});
});

describe("bin/steward diag and job (O2/O6)", () => {
	it("diag waits for the scripted result and prints it; job prints any stored job", async () => {
		const { spawn } = await import("node:child_process");
		const cli = new URL("../src/cli.ts", import.meta.url).pathname;
		const child = spawn(process.execPath, ["--experimental-strip-types", cli, "diag", "--wait", "15", "--json"], {
			env: { ...process.env, PHONE_STEWARD_STATE_DIR: dir },
		});
		let stdout = "";
		let stderr = "";
		child.stdout.on("data", (chunk: Buffer) => (stdout += chunk.toString()));
		child.stderr.on("data", (chunk: Buffer) => (stderr += chunk.toString()));

		const fetched = await device(`/v1/jobs?wait=10`, null, "GET");
		const job = (fetched.body.jobs as Array<{ jobId: string; kind: string }>)[0]!;
		expect(job.kind).toBe("phone_diagnostics");
		await device(`/v1/jobs/${job.jobId}/result`, {
			requestId: `diag-result-cli-${job.jobId}`,
			status: "ok",
			data: diagnosticsResult({ log: [{ atMs: 1, level: "info", event: "boot", detail: "ok" }] }),
		});

		const exit = await new Promise<number | null>((resolve) => child.on("close", resolve));
		expect(exit, stderr).toBe(0);
		const printed = JSON.parse(stdout) as { command: string; jobId: string; status: string; result: { data: { log: unknown[] } } };
		expect(printed.command).toBe("diag");
		expect(printed.jobId).toBe(job.jobId);
		expect(printed.status).toBe("reported");
		expect(printed.result.data.log).toHaveLength(1);

		const jobOut = await new Promise<{ status: number | null; stdout: string; stderr: string }>((resolve) => {
			const second = spawn(process.execPath, ["--experimental-strip-types", cli, "job", job.jobId, "--json"], {
				env: { ...process.env, PHONE_STEWARD_STATE_DIR: dir },
			});
			let out = "";
			let err = "";
			second.stdout.on("data", (chunk: Buffer) => (out += chunk.toString()));
			second.stderr.on("data", (chunk: Buffer) => (err += chunk.toString()));
			second.on("close", (status) => resolve({ status, stdout: out, stderr: err }));
		});
		expect(jobOut.status, jobOut.stderr).toBe(0);
		const stored = JSON.parse(jobOut.stdout) as { job: { jobId: string; params: {}; result: { data: { appVersion: string } } } };
		expect(stored.job.jobId).toBe(job.jobId);
		expect(stored.job.params).toEqual({});
		expect(stored.job.result.data.appVersion).toBe("0.2.2");
	}, 40_000);
});

describe("normaliseDiagnosticsResult enforces A6 value types (final correction, closure review finding)", () => {
	const SENTINEL_PATH = "/storage/emulated/0/DCIM/private.jpg";
	const SENTINEL_TOKEN = "TOKEN_SENTINEL_abcdefghijklmnopqrstuvwxyz0123456789";

	it("drops a field whose value has the wrong type, so nothing can ride in a permitted key", () => {
		const out = normaliseDiagnosticsResult({
			appVersion: SENTINEL_PATH,
			versionCode: "4",
			permissions: { usageAccess: SENTINEL_TOKEN, allFiles: true, mediaRead: { p: SENTINEL_PATH }, manageMedia: false, batteryUnrestricted: true },
			worker: { state: SENTINEL_TOKEN, lastRunMs: "x", runAttemptCount: 2 },
			outbox: { crashes: [SENTINEL_PATH], errors: 1 },
			log: [
				{ atMs: 1, level: SENTINEL_TOKEN, event: "jobs_polled", detail: { path: SENTINEL_PATH, token: SENTINEL_TOKEN } },
				{ atMs: "1", level: "info", event: "ok_event", detail: 42 },
			],
		} as never);
		const text = JSON.stringify(out);
		expect(text).not.toContain("private.jpg");
		expect(text).not.toContain("TOKEN_SENTINEL");
		expect(text).not.toContain("DCIM");
		const o = out as Record<string, any>;
		expect(o.permissions).toEqual({ allFiles: true, manageMedia: false, batteryUnrestricted: true });
		expect(o.worker).toEqual({ runAttemptCount: 2 });
		expect(o.outbox).toEqual({ errors: 1 });
		expect(o.versionCode).toBeUndefined();
		// appVersion is free text from the phone: it is redacted like a detail.
		expect(o.appVersion).toBe("[redacted]");
		expect(o.log[0]).toEqual({ atMs: 1, event: "jobs_polled" });
		expect(o.log[1]).toEqual({ level: "info", event: "ok_event" });
	});

	it("keeps well-typed A6 values unchanged", () => {
		const out = normaliseDiagnosticsResult({
			appVersion: "0.2.2", versionCode: 4,
			permissions: { usageAccess: true, allFiles: true, mediaRead: true, manageMedia: false, batteryUnrestricted: true },
			worker: { state: "ENQUEUED", lastRunMs: 5, runAttemptCount: 0 },
			outbox: { crashes: 0, errors: 2 },
			log: [{ atMs: 1, level: "warn", event: "status.battery_button", detail: "polled 2 jobs" }],
		} as never) as Record<string, any>;
		expect(out.appVersion).toBe("0.2.2");
		expect(out.versionCode).toBe(4);
		expect(out.worker).toEqual({ state: "ENQUEUED", lastRunMs: 5, runAttemptCount: 0 });
		expect(out.log[0]).toEqual({ atMs: 1, level: "warn", event: "status.battery_button", detail: "polled 2 jobs" });
	});
});
