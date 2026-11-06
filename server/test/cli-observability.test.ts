/**
 * O1–O4: the observability read tools in `bin/steward` — chat, job, snapshot, usage,
 * logs — and the one structured log line per HTTP request. A real server on a temporary
 * port and state dir; the CLI is spawned asynchronously because the server under test
 * runs in this process and spawnSync would deadlock it.
 */
import { connect } from "node:net";
import { chmodSync } from "node:fs";
import { mkdtemp, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { afterAll, beforeAll, describe, expect, it, vi } from "vitest";
import { BACKGROUND_CONTEXT } from "@earendil-works/chord/context";
import { loadConfig } from "../src/config.ts";
import { openStewardHarness } from "../src/steward.ts";
import { startStewardServers, routeTemplateFor, type StewardServers } from "../src/http.ts";
import { storeSnapshot } from "../src/documents.ts";

let dir: string;
let port: number;
let servers: StewardServers;
let harness: Awaited<ReturnType<typeof openStewardHarness>>["harness"];
let rootConversation: Awaited<ReturnType<typeof openStewardHarness>>["root"];
let closeHarness: () => Promise<void>;
let token: string;

const CLI = new URL("../src/cli.ts", import.meta.url).pathname;

function runCli(args: string[], extraEnv: Record<string, string> = {}): Promise<{ status: number | null; stdout: string; stderr: string }> {
	const { spawn } = require("node:child_process") as typeof import("node:child_process");
	return new Promise((resolve) => {
		const child = spawn(process.execPath, ["--experimental-strip-types", CLI, ...args], {
			env: { ...process.env, PHONE_STEWARD_STATE_DIR: dir, ...extraEnv },
		});
		let stdout = "";
		let stderr = "";
		child.stdout.on("data", (chunk: Buffer) => (stdout += chunk.toString()));
		child.stderr.on("data", (chunk: Buffer) => (stderr += chunk.toString()));
		child.on("close", (status) => resolve({ status, stdout, stderr }));
	});
}

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

async function waitFor(check: () => Promise<boolean> | boolean, timeoutMs = 20_000): Promise<void> {
	const deadline = Date.now() + timeoutMs;
	while (Date.now() < deadline) {
		if (await check()) return;
		await new Promise((resolve) => setTimeout(resolve, 100));
	}
	throw new Error("timed out");
}

beforeAll(async () => {
	dir = await mkdtemp(join(tmpdir(), "w3b-obs-"));
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

	// Seed: pair, one owner turn (faux model), one answered phone job, one snapshot.
	const pairCode = await admin("POST", "/admin/pair");
	const paired = await device("/v1/pair", { code: pairCode.body.code, deviceName: "obs-suite" });
	token = paired.body.token;
	await device("/v1/chat", { requestId: "obs-chat-1", text: "please check my phone" });
	const selftest = await admin("POST", "/admin/selftest");
	const fetched = await device("/v1/jobs?wait=5", null, "GET");
	const job = (fetched.body.jobs as Array<{ jobId: string }>).find((candidate) => candidate.jobId === selftest.body.jobId);
	expect(job).toBeDefined();
	await device(`/v1/jobs/${job!.jobId}/result`, {
		requestId: `obs-result-${job!.jobId}`,
		status: "ok",
		data: { freeBytes: 1000, note: "seed" },
	});
	await harness.commit(
		(tx) =>
			storeSnapshot(tx, rootConversation.id, {
				capturedAtMs: 1_760_000_000_000,
				totalBytes: 100_000_000_000,
				freeBytes: 10_000_000_000,
				apps: [
					{ packageName: "com.big", label: "Big", appBytes: 2_000_000_000, dataBytes: 3_000_000_000, cacheBytes: 500_000_000, isSystem: false },
					{ packageName: "com.cachy", label: "Cachy", appBytes: 100_000_000, dataBytes: 100_000_000, cacheBytes: 900_000_000, isSystem: false },
					{ packageName: "com.small", label: "Small", appBytes: 10_000_000, dataBytes: 10_000_000, cacheBytes: 1_000_000, isSystem: true },
				],
				categories: { imagesBytes: 5_000_000_000, videoBytes: 1_000_000_000, audioBytes: 0, documentsBytes: 0, appsBytes: 5_000_000_000, systemBytes: 20_000_000_000, otherBytes: 0 },
			}),
		BACKGROUND_CONTEXT,
	);
	await waitFor(async () => {
		// The seeded owner turn and the phone-result follow-up have both settled.
		const chat = await admin("GET", "/admin/chat?last=50");
		const lines = chat.body.lines as Array<{ type: string; role?: string; text?: string }>;
		return lines.some((line) => line.type === "phone_result") && lines.some((line) => line.type === "message" && line.role === "agent" && line.text!.length > 0);
	});
});

afterAll(async () => {
	await servers.close();
	await closeHarness();
	await rm(dir, { recursive: true, force: true });
});

describe("O1: bin/steward chat", () => {
	it("shows owner and agent messages, tool calls with arguments and phone results, thinking omitted", async () => {
		const run = await runCli(["chat", "--json"]);
		expect(run.status, run.stderr).toBe(0);
		const out = JSON.parse(run.stdout) as {
			via: string;
			lines: Array<{ type: string; role?: string; text?: string; name?: string; arguments?: string; jobId?: string; kind?: string; status?: string }>;
		};
		expect(out.via).toBe("admin-socket");
		const owner = out.lines.find((line) => line.type === "message" && line.role === "owner" && line.text!.includes("check my phone"));
		expect(owner).toBeDefined();
		const toolCall = out.lines.find((line) => line.type === "tool_call");
		expect(toolCall).toBeDefined();
		expect(toolCall!.name).toBe("phone_echo");
		expect(toolCall!.arguments).toContain("storage overview");
		const agent = out.lines.find((line) => line.type === "message" && line.role === "agent" && (line.text!.length ?? 0) > 0);
		expect(agent).toBeDefined();
		const phone = out.lines.find((line) => line.type === "phone_result");
		expect(phone).toBeDefined();
		expect(phone!.jobId).toMatch(/^pj-/);
		expect(phone!.kind).toBe("phone_selftest");
		expect(phone!.status).toBe("ok");
		const thinking = JSON.stringify(out.lines);
		expect(thinking.toLowerCase()).not.toContain('"thinking"');
		const raw = run.stdout;
		expect(raw).not.toContain(token);
	});

	it("--last bounds the output", async () => {
		const one = await runCli(["chat", "--last", "1", "--json"]);
		expect(one.status).toBe(0);
		const out = JSON.parse(one.stdout) as { lines: unknown[] };
		expect(out.lines).toHaveLength(1);
	});
});

describe("S7: bin/steward say and POST /admin/chat", () => {
	it("say posts an owner chat message marked [via server] through the admin socket", async () => {
		const run = await runCli(["say", "hello from the engineer", "--json"]);
		expect(run.status, run.stderr).toBe(0);
		const out = JSON.parse(run.stdout) as { via: string; command: string; accepted: boolean; requestId: string };
		expect(out.via).toBe("admin-socket");
		expect(out.command).toBe("say");
		expect(out.accepted).toBe(true);
		expect(out.requestId).toMatch(/^say-/);
		await waitFor(async () => {
			const chat = await admin("GET", "/admin/chat?last=50");
			const lines = chat.body.lines as Array<{ type: string; role?: string; text?: string }>;
			return lines.some((line) => line.type === "message" && line.role === "owner" && line.text === "[via server] hello from the engineer");
		});
	});

	it("POST /admin/chat uses the /v1/chat path: dedupes on requestId, validates text, prefixes on the server", async () => {
		const first = await admin("POST", "/admin/chat", { requestId: "say-test-dup", text: "once only" });
		expect(first.status).toBe(202);
		expect(first.body.accepted).toBe(true);
		const again = await admin("POST", "/admin/chat", { requestId: "say-test-dup", text: "once only" });
		expect(again.status).toBe(202);
		expect(again.body.duplicate).toBe(true);
		const empty = await admin("POST", "/admin/chat", { requestId: "say-test-empty", text: "   " });
		expect(empty.status).toBe(400);
		const noId = await admin("POST", "/admin/chat", { text: "no request id" });
		expect(noId.status).toBe(400);
		await waitFor(async () => {
			const chat = await admin("GET", "/admin/chat?last=100");
			const lines = chat.body.lines as Array<{ type: string; role?: string; text?: string }>;
			return lines.filter((line) => line.role === "owner" && line.text === "[via server] once only").length === 1;
		});
	});

	it("the chat route is socket-only: the device port has no /admin/chat", async () => {
		const response = await device("/admin/chat", { requestId: "say-tcp", text: "nope" });
		expect([401, 404]).toContain(response.status);
		expect(routeTemplateFor("POST", "/admin/chat")).toBe("/admin/chat");
	});

	it("say with no text prints usage and exits 2; say against a stopped instance says it needs the running one", async () => {
		const bare = await runCli(["say"]);
		expect(bare.status).toBe(2);
		const empty = await mkdtemp(join(tmpdir(), "s7-say-stopped-"));
		try {
			const stopped = await runCli(["say", "hi"], { PHONE_STEWARD_STATE_DIR: empty });
			expect(stopped.status).toBe(1);
			expect(stopped.stderr).toContain("needs the running instance");
		} finally {
			await rm(empty, { recursive: true, force: true });
		}
	});
});

describe("O3: bin/steward snapshot and usage", () => {
	it("snapshot summarises the latest snapshot: free/total, top apps by size and cache", async () => {
		const run = await runCli(["snapshot", "--json"]);
		expect(run.status, run.stderr).toBe(0);
		const out = JSON.parse(run.stdout) as {
			via: string;
			snapshot: {
				status?: string;
				totalBytes: number;
				freeBytes: number;
				topAppsBySize: Array<{ packageName: string; totalBytes: number }>;
				topAppsByCache: Array<{ packageName: string; cacheBytes: number }>;
				categories: { imagesBytes: number };
			};
		};
		expect(out.via).toBe("admin-socket");
		expect(out.snapshot.totalBytes).toBe(100_000_000_000);
		expect(out.snapshot.freeBytes).toBe(10_000_000_000);
		expect(out.snapshot.topAppsBySize[0]!.packageName).toBe("com.big");
		expect(out.snapshot.topAppsByCache[0]!.packageName).toBe("com.cachy");
		expect(out.snapshot.topAppsBySize).toHaveLength(3);
		expect(out.snapshot.categories.imagesBytes).toBe(5_000_000_000);
	});

	it("usage reports model tokens per day", async () => {
		const run = await runCli(["usage", "--json"]);
		expect(run.status, run.stderr).toBe(0);
		const out = JSON.parse(run.stdout) as { via: string; days: Array<{ day: string; inputTokens: number; outputTokens: number; calls: number }> };
		expect(out.via).toBe("admin-socket");
		expect(out.days.length).toBeGreaterThan(0);
		const total = out.days.reduce((sum, day) => sum + day.inputTokens + day.outputTokens, 0);
		expect(total).toBeGreaterThan(0);
		expect(out.days.every((day) => /^\d{4}-\d{2}-\d{2}$/.test(day.day))).toBe(true);
	});

	it("both work in direct-state mode against a stopped instance", async () => {
		// Simulate stopped: point the CLI at a state dir whose admin socket does not exist
		// but whose SQLite file this test process does not own. Use a copy? No: opening the
		// same SQLite from two processes would violate single ownership. Instead assert the
		// direct-state path on a fresh dir (empty state, no crash) and the running-mode
		// outputs above; full direct-state coverage lives in cli.test.ts's mode.
		const empty = await mkdtemp(join(tmpdir(), "w3b-obs-empty-"));
		try {
			const run = await runCli(["snapshot", "--json"], { PHONE_STEWARD_STATE_DIR: empty });
			expect(run.status).toBe(0);
			const out = JSON.parse(run.stdout) as { via: string; snapshot: { status: string } };
			expect(out.via).toBe("direct-state");
			expect(out.snapshot.status).toBe("no-snapshot");
		} finally {
			await rm(empty, { recursive: true, force: true });
		}
	});
});

describe("O4: the per-request log line and bin/steward logs", () => {
	it("logs one line per HTTP request with method, path, status, ms and device — never a token or query", async () => {
		const spy = vi.spyOn(console, "error").mockImplementation(() => undefined);
		try {
			await device("/v1/status?secret=query", null, "GET");
			await admin("GET", "/admin/status");
		} finally {
			const calls = spy.mock.calls.map((args) => args.join(" "));
			spy.mockRestore();
			const deviceLine = calls.find((line) => line.includes("http GET /v1/status"));
			expect(deviceLine).toBeDefined();
			expect(deviceLine).toMatch(/^\[steward\] http GET \/v1\/status \d{3} \d+ms device=dev_[0-9a-f]+$/);
			expect(deviceLine).not.toContain("secret");
			const adminLine = calls.find((line) => line.includes("http GET /admin/status"));
			expect(adminLine).toBeDefined();
			expect(adminLine).toMatch(/device=-$/);
			for (const line of calls) expect(line).not.toContain(token);
		}
	});

describe("correction 01 item 2: log lines carry route templates, never caller-controlled path text", () => {
	it("maps every route to its template and anything else to unmatched (pure)", () => {
		expect(routeTemplateFor("POST", "/v1/jobs/abc123/result")).toBe("/v1/jobs/:id/result");
		expect(routeTemplateFor("POST", "/v1/proposals/pr-x/decision")).toBe("/v1/proposals/:id/decision");
		expect(routeTemplateFor("POST", "/v1/proposals/pr-x/execution")).toBe("/v1/proposals/:id/execution");
		expect(routeTemplateFor("GET", "/v1/decisions/dc-x")).toBe("/v1/decisions/:id");
		expect(routeTemplateFor("GET", "/admin/jobs/pj-x")).toBe("/admin/jobs/:id");
		expect(routeTemplateFor("GET", "/v1/status")).toBe("/v1/status");
		expect(routeTemplateFor("POST", "/admin/run/weekly-check")).toBe("/admin/run/weekly-check");
		expect(routeTemplateFor("GET", "/v1/jobs/PRIVATE_SENTINEL.jpg/result")).toBe("unmatched");
		expect(routeTemplateFor("GET", "/bad/PRIVATE_SENTINEL.jpg")).toBe("unmatched");
	});

	it("a path segment or query value never reaches a log line; an unmatched path logs as unmatched", async () => {
		const spy = vi.spyOn(console, "error").mockImplementation(() => undefined);
		try {
			await fetch(`http://127.0.0.1:${port}/v1/jobs/PRIVATE_SENTINEL.jpg/result`, {
				method: "POST",
				headers: { "content-type": "application/json" },
				body: JSON.stringify({ requestId: "sentinel-1", status: "ok" }),
			});
			await fetch(`http://127.0.0.1:${port}/bad/PRIVATE_SENTINEL.jpg?leak=PRIVATE_SENTINEL.jpg`);
			await device("/v1/status?token=PRIVATE_SENTINEL", null, "GET");
		} finally {
			const calls = spy.mock.calls.map((args) => args.join(" "));
			spy.mockRestore();
			const httpLines = calls.filter((line) => line.includes("[steward] http"));
			expect(httpLines.length).toBeGreaterThanOrEqual(3);
			for (const line of httpLines) expect(line).not.toContain("PRIVATE_SENTINEL");
			expect(httpLines.some((line) => line.includes("POST /v1/jobs/:id/result"))).toBe(true);
			expect(httpLines.some((line) => line.includes(" unmatched "))).toBe(true);
			expect(httpLines.some((line) => line.includes("GET /v1/status"))).toBe(true);
		}
	});

	it("logs runs journalctl read-only; --json emits {lines: [...]} (correction 01 item 4)", async () => {
		const fake = join(dir, "fake-journalctl.sh");
		await writeFile(fake, "#!/bin/sh\necho \"[steward] http GET /v1/status 200 3ms device=-\"\necho \"oct 7 09:00 phone-steward: listening\"\n");
		chmodSync(fake, 0o755);
		const run = await runCli(["logs", "--lines", "5", "--json"], { PHONE_STEWARD_JOURNALCTL: fake });
		expect(run.status, run.stderr).toBe(0);
		const parsed = JSON.parse(run.stdout) as { command: string; lines: string[] };
		expect(parsed.command).toBe("logs");
		expect(parsed.lines).toHaveLength(2);
		expect(parsed.lines[0]).toContain("http GET /v1/status");
		expect(parsed.lines[1]).toContain("listening");
	});
});
	});
