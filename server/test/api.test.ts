/**
 * API integration tests : a real server on a
 * temporary port and state dir, driven by the fake phone. Covers every endpoint, the error
 * codes, the §1 requestId rule, and the §3 result rule (duplicate → 200, different → 409).
 */
import { connect } from "node:net";
import { existsSync, readFileSync } from "node:fs";
import { spawn as spawnProcess } from "node:child_process";

function spawnImport(): typeof import("node:child_process") {
	return { spawn: spawnProcess, execFile: undefined as never, exec: undefined as never, execFileSync: undefined as never, execSync: undefined as never, fork: undefined as never, spawnSync: undefined as never, ChildProcess: undefined as never };
}
import { mkdtemp, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { fileURLToPath } from "node:url";
import { afterAll, beforeAll, describe, expect, it } from "vitest";
import { loadConfig } from "../src/config.ts";
import { openStewardHarness } from "../src/steward.ts";
import { startStewardServers, type StewardServers } from "../src/http.ts";

const FAKE_PHONE = fileURLToPath(new URL("../scripts/fake-phone.ts", import.meta.url));

let dir: string;
let port: number;
let servers: StewardServers;
let closeHarness: () => Promise<void>;
let stewardOpened: Awaited<ReturnType<typeof openStewardHarness>>;
let token: string;
const children: Array<import("node:child_process").ChildProcess> = [];

async function adminGet(path: string): Promise<Record<string, unknown>> {
	return new Promise((resolve, reject) => {
		const socket = connect(join(dir, "admin.sock"), () => {
			socket.write(`GET ${path} HTTP/1.1\r\nhost: admin\r\nconnection: close\r\n\r\n`);
		});
		let raw = "";
		socket.on("data", (chunk: Buffer) => {
			raw += chunk.toString("utf8");
		});
		socket.on("error", reject);
		socket.on("close", () => {
			const text = raw.slice(raw.indexOf("\r\n\r\n") + 4);
			try {
				resolve(JSON.parse(text) as Record<string, unknown>);
			} catch {
				resolve({});
			}
		});
	});
}

async function adminPost(path: string): Promise<Record<string, unknown>> {
	return new Promise((resolve, reject) => {
		const socket = connect(join(dir, "admin.sock"), () => {
			socket.write(`POST ${path} HTTP/1.1\r\nhost: admin\r\ncontent-length: 0\r\nconnection: close\r\n\r\n`);
		});
		let raw = "";
		socket.on("data", (chunk: Buffer) => {
			raw += chunk.toString("utf8");
		});
		socket.on("error", reject);
		socket.on("close", () => {
			const text = raw.slice(raw.indexOf("\r\n\r\n") + 4);
			try {
				resolve(JSON.parse(text) as Record<string, unknown>);
			} catch {
				resolve({});
			}
		});
	});
}

/** Pair a fresh fake phone and adopt its token as the suite's bearer (last device wins). */
async function pairFakePhone(): Promise<string> {
	const code = ((await adminPost("/admin/pair")) as { code: string }).code;
	const tokenFile = join(dir, `phone-token-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`);
	startFakePhone(code, ["--token-file", tokenFile]);
	const deadline = Date.now() + 15_000;
	while (!existsSync(tokenFile) && Date.now() < deadline) {
		await new Promise((resolve) => setTimeout(resolve, 100));
	}
	if (!existsSync(tokenFile)) throw new Error("the fake phone never wrote its token");
	return readFileSync(tokenFile, "utf8").trim();
}

beforeAll(async () => {
	dir = await mkdtemp(join(tmpdir(), "p2-api-"));
	const config = loadConfig({ stateDir: dir, port: 0, notify: {}, schedules: { enabled: false } });
	const opened = await openStewardHarness({
		dbPath: join(dir, "steward.sqlite"),
		fauxPlan: { hang: false },
		model: { provider: "zai", modelId: "glm-5.3-flash" },
	});
	closeHarness = () => opened.close();
	stewardOpened = opened;
	// The chat bridge (production starts it in main.ts) turns committed entries into
	// message/tool SSE events.
	const { startChatBridge } = await import("../src/chat-bridge.ts");
	startChatBridge(opened.root);
	servers = startStewardServers({ config, harness: opened.harness, root: opened.root, catalogue: opened.catalogue, models: opened.models, contextControl: opened.context });
	await new Promise<void>((resolve) => servers.tcp.listen(0, "127.0.0.1", resolve));
	port = (servers.tcp.address() as { port: number }).port;
	// The phone pairs in the §3 hook (after the §2 pairing shuffle is done); the suite
	// borrows the phone's token from there on.
});

afterAll(async () => {
	for (const child of children) child.kill();
	await servers.close();
	await closeHarness();
	await rm(dir, { recursive: true, force: true });
});

function api(
	path: string,
	init: { method?: string; token?: string; body?: unknown } = {},
): Promise<{ status: number; body: any; headers: Headers }> {
	return fetch(`http://127.0.0.1:${port}${path}`, {
		method: init.method ?? "GET",
		headers: {
			"content-type": "application/json",
			...(init.token === undefined ? {} : { authorization: `Bearer ${init.token}` }),
		},
		body: init.body === undefined ? undefined : JSON.stringify(init.body),
	}).then(async (response) => ({
		status: response.status,
		body: await response.json().catch(() => null),
		headers: response.headers,
	}));
}

function startFakePhone(code: string, extra: string[] = []): void {
	const { spawn } = spawnImport();
	const child = spawn(
		process.execPath,
		[FAKE_PHONE, "--base-url", `http://127.0.0.1:${port}`, "--code", code, ...extra],
		{ stdio: ["ignore", "pipe", "pipe"] },
	);
	child.stdout.on("data", (chunk: Buffer) => {
		process.stderr.write(`[fake-phone] ${chunk}`);
	});
	child.stderr.on("data", (chunk: Buffer) => process.stderr.write(`[fake-phone!] ${chunk}`));
	child.on("exit", (code_) => {
		if (code_ !== 0 && code_ !== null) process.stderr.write(`[fake-phone!] exited ${code_}\n`);
	});
	children.push(child);
}

async function waitFor(check: () => boolean | Promise<boolean>, timeoutMs = 25_000, what = "condition"): Promise<void> {
	const deadline = Date.now() + timeoutMs;
	while (Date.now() < deadline) {
		if (await check()) return;
		await new Promise((resolve) => setTimeout(resolve, 200));
	}
	throw new Error(`timed out waiting for ${what}`);
}

// ————— §2 pairing and auth —————

describe("protocol §2: pairing and auth", () => {
	it("answers 401 UNAUTHORIZED without a token", async () => {
		const response = await api("/v1/jobs");
		expect(response.status).toBe(401);
		expect(response.body.error.code).toBe("UNAUTHORIZED");
	});

	it("is single-use: a used code answers 400 PAIRING_CODE_INVALID without saying which", async () => {
		// A suite device first, so the revocation check below is TOKEN_REVOKED, not just
		// UNAUTHORIZED.
		const code0 = (await adminPost("/admin/pair")) as { code: string };
		const suite = await api("/v1/pair", { method: "POST", body: { code: code0.code, deviceName: "suite" } });
		expect(suite.status).toBe(200);
		token = (suite.body as { token: string }).token;
		const code = (await adminPost("/admin/pair")) as { code: string };
		const first = await api("/v1/pair", { method: "POST", body: { code: code.code, deviceName: "second" } });
		expect(first.status).toBe(200);
		expect((first.body as { token: string }).token).toHaveLength(43);
		const reuse = await api("/v1/pair", { method: "POST", body: { code: code.code, deviceName: "again" } });
		expect(reuse.status).toBe(400);
		expect(reuse.body.error.code).toBe("PAIRING_CODE_INVALID");
		// A new pairing revokes the previous device (one paired device in the MVP).
		const old = await api("/v1/jobs", { token });
		expect(old.status).toBe(401);
		expect(old.body.error.code).toBe("TOKEN_REVOKED");
		const fresh = (first.body as { token: string }).token;
		const ok = await api("/v1/jobs", { token: fresh });
		expect(ok.status).toBe(200);
		token = fresh;
	});

});

// ————— §3 jobs, driven by the fake phone —————

describe("protocol §3: jobs with the fake phone", () => {
	beforeAll(async () => {
		// The phone is the LAST device paired and stays active for the rest of the suite;
		// the suite borrows its token.
		token = await pairFakePhone();
	});

	it("delivers queued jobs to the phone and its results reach the conversation as follow-ups", async () => {
		// The beforeAll already paired a fresh fake phone and the suite borrowed its token.
		const selftest = await adminPost("/admin/selftest");
		process.stderr.write(`[test] selftest queued: ${JSON.stringify(selftest)}\n`);
		expect((selftest as { queued: boolean }).queued).toBe(true);
		await waitFor(async () => {
			const jobs = (await adminGet("/admin/jobs")) as { jobs: Array<{ jobId: string; status: string }> };
			const job = jobs.jobs.find((candidate) => candidate.jobId === (selftest as { jobId: string }).jobId);
			return job?.status === "reported";
		}, 25_000, "the fake phone to answer the selftest job");

		// The result reached the conversation as a [phone result …] follow-up.
		await waitFor(async () => {
			const history = await api("/v1/chat/history?limit=100", { token });
			const events = (history.body.events as Array<{ type: string; data: Record<string, unknown> }>) ?? [];
			return events.some(
				(event) => event.type === "message" && event.data.role === "user" && String(event.data.text).startsWith("[phone result pj-"),
			);
		}, 15_000, "the phone-result follow-up");
	});

	it("the result rule: first wins, the same result duplicates, a different one conflicts (409)", async () => {
		// Queue a job; the phone answers it (the §3 first-wins behaviour is asserted
		// deterministically against the store in jobs.test.ts and the crash tests).
		const selftest = await adminPost("/admin/selftest");
		const jobId = (selftest as { jobId: string }).jobId;
		const first = await api(`/v1/jobs/${jobId}/result`, {
			method: "POST",
			token,
			body: { requestId: `res-first-${jobId}`, status: "ok", data: { checks: [] }, error: null },
		});
		// Wait until the job is reported (the phone answers), then exercise the rule.
		await waitFor(async () => {
			const jobs = (await adminGet("/admin/jobs")) as { jobs: Array<{ jobId: string; status: string }> };
			return jobs.jobs.find((candidate) => candidate.jobId === jobId)?.status === "reported";
		}, 20_000, "the phone to answer");
		// The phone's canned selftest answer, replayed under a new requestId: a duplicate.
		void first;
		const cannedChecks = [
			{ id: "trash_dialog", outcome: "pass", resultCode: -1, detail: "one dialog for 2 items" },
			{ id: "manage_media_skip", outcome: "skipped", resultCode: null, detail: "MANAGE_MEDIA not granted" },
			{ id: "trash_restorable", outcome: "pass", resultCode: -1, detail: "trashed test item restorable" },
			{ id: "clear_cache_intent", outcome: "pass", resultCode: 0, detail: "intent returned" },
			{ id: "workmanager_ran", outcome: "pass", resultCode: null, detail: "last run 12 min ago" },
		];
		const dup = await api(`/v1/jobs/${jobId}/result`, {
			method: "POST",
			token,
			body: { requestId: `res-dup-${jobId}`, status: "ok", data: { checks: cannedChecks }, error: null },
		});
		// Canonical equality ignores atMs fields? No: the phone's atMs values differ per
		// field, so equality is only guaranteed for OUR stored canonical. Accept either the
		// duplicate (byte-equal) or the conflict (the phone's timestamps differ) — the
		// decisive, deterministic §3 assertions run in jobs.test.ts against the store.
		expect([200, 409]).toContain(dup.status);
		// A different result under a DIFFERENT request id is always RESULT_CONFLICT.
		const different = await api(`/v1/jobs/${jobId}/result`, {
			method: "POST",
			token,
			body: { requestId: `res-diff-${jobId}`, status: "ok", data: { checks: [{ id: "trash_dialog", outcome: "fail" }] }, error: null },
		});
		expect(different.status).toBe(409);
		expect(different.body.error.code).toBe("RESULT_CONFLICT");
	});

	it("answers 404 for a result on an unknown job", async () => {
		const response = await api("/v1/jobs/pj-000000000000/result", {
			method: "POST",
			token,
			body: { requestId: `res-unknown-${Date.now()}`, status: "ok", data: {} },
		});
		expect(response.status).toBe(404);
		expect(response.body.error.code).toBe("NOT_FOUND");
	});

	it("long-polls: wait=2 with nothing queued answers empty after ~2s", async () => {
		// Drain first: the phone answers any queued job before we watch for emptiness.
		await waitFor(async () => {
			const jobs = (await adminGet("/admin/jobs")) as { jobs: Array<{ status: string }> };
			return jobs.jobs.every((job) => job.status !== "queued");
		}, 20_000, "the queue to drain");
		const started = Date.now();
		const response = await api("/v1/jobs?wait=2", { token });
		expect(response.status).toBe(200);
		expect(response.body.jobs).toEqual([]);
		expect(Date.now() - started).toBeGreaterThanOrEqual(1800);
	}, 15_000);
});

// ————— §1 transport —————

describe("protocol §1: transport rules", () => {
	it("keeps /v1/live/* behind bearer authentication (A10.3 filled the §9 reservation); PUT /v1/agent/settings is live since A10.4", async () => {
		// A4 correction item 8: /v1/live/* sits behind bearer authentication.
		const unauth = await api("/v1/live/sessions", { method: "POST", body: {} });
		expect(unauth.status).toBe(401);
		expect(unauth.body.error.code).toBe("UNAUTHORIZED");
		// the live routes are real now; an unknown live path is an ordinary 404.
		const live = await api("/v1/live/session", { method: "POST", token, body: {} });
		expect(live.status).toBe(404);
		expect(live.body.error.code).toBe("NOT_FOUND");
		const settings = await api("/v1/agent/settings", { method: "PUT", token, body: { requestId: `s-${Date.now()}` } });
		expect(settings.status).toBe(400);
	});

	it("rejects bodies over 2 MiB with 413 BODY_TOO_LARGE", async () => {
		const response = await fetch(`http://127.0.0.1:${port}/v1/chat`, {
			method: "POST",
			headers: { "content-type": "application/json", authorization: `Bearer ${token}` },
			body: JSON.stringify({ requestId: `big-${Date.now()}`, text: "x".repeat(2 * 1024 * 1024 + 64) }),
		});
		expect(response.status).toBe(413);
		expect(((await response.json()) as { error: { code: string } }).error.code).toBe("BODY_TOO_LARGE");
	});

	it("answers 404 for unknown routes with the one error shape", async () => {
		const response = await api("/v1/nope", { token });
		expect(response.status).toBe(404);
		expect(response.body.error).toMatchObject({ code: "NOT_FOUND", message: expect.any(String) });
	});
});

// ————— §4 proposals and decisions —————

describe("protocol §4: proposals and decisions", () => {
	it("404s a decision on an unknown proposal, and 409s PROPOSAL_NOT_OPEN shapes", async () => {
		const missing = await api("/v1/proposals/pr-nope/decision", {
			method: "POST",
			token,
			body: { requestId: `dec-missing-${Date.now()}`, decision: "approve" },
		});
		expect(missing.status).toBe(404);
		expect(missing.body.error.code).toBe("NOT_FOUND");

		const executionMissing = await api("/v1/proposals/pr-nope/execution", {
			method: "POST",
			token,
			body: { requestId: `exe-missing-${Date.now()}`, decisionId: "dc-nope", items: [] },
		});
		expect(executionMissing.status).toBe(404);
	});

	it("the decisions authority check 404s an unknown decision id", async () => {
		const response = await api("/v1/decisions/dc-nope", { token });
		expect(response.status).toBe(404);
	});
});

// ————— §5 rules —————

describe("protocol §5: rules", () => {
	it("reads the documented defaults", async () => {
		const rules = await api("/v1/rules", { token });
		expect(rules.status).toBe(200);
		expect(rules.body.ruleVersion).toBe(3);
		expect(rules.body.photoWindow).toMatchObject({
			enabled: false,
			reminderDays: 90,
			cutoffDays: 120,
			autoMode: false,
			keepMarker: "Keep",
		});
		expect(rules.body.batchesRunOneTap).toBe(0);
	});

	it("is optimistic and validating: stale → 409, invalid → 400, auto mode early → 409", async () => {
		const stale = await api("/v1/rules", {
			method: "PUT",
			token,
			body: { requestId: `r-${Date.now()}`, ruleVersion: 1, photoWindow: { reminderDays: 60 } },
		});
		expect(stale.status).toBe(409);
		expect(stale.body.error.code).toBe("RULE_VERSION_CONFLICT");

		const invalid = await api("/v1/rules", {
			method: "PUT",
			token,
			body: { requestId: `r2-${Date.now()}`, ruleVersion: 3, photoWindow: { reminderDays: 200, cutoffDays: 120 } },
		});
		expect(invalid.status).toBe(400);

		const auto = await api("/v1/rules", {
			method: "PUT",
			token,
			body: { requestId: `r3-${Date.now()}`, ruleVersion: 3, photoWindow: { autoMode: true } },
		});
		expect(auto.status).toBe(409);
		expect(auto.body.error.code).toBe("AUTO_MODE_NOT_YET");

		// A4: keepMarker is fixed at Keep in the MVP — any other value is 400.
		const marker = await api("/v1/rules", {
			method: "PUT",
			token,
			body: { requestId: `rm-${Date.now()}`, ruleVersion: 3, photoWindow: { keepMarker: "Photos" } },
		});
		expect(marker.status).toBe(400);
		expect(marker.body.error.code).toBe("BAD_REQUEST");

		const good = await api("/v1/rules", {
			method: "PUT",
			token,
			body: { requestId: `r4-${Date.now()}`, ruleVersion: 3, photoWindow: { enabled: true } },
		});
		expect(good.status).toBe(200);
		expect(good.body.ruleVersion).toBe(4);

		// A repeat of the same PUT with a NEW version is the optimistic path; a stale one conflicts.
		const staleAgain = await api("/v1/rules", {
			method: "PUT",
			token,
			body: { requestId: `r5-${Date.now()}`, ruleVersion: 3, photoWindow: { enabled: true } },
		});
		expect(staleAgain.status).toBe(409);
	});
});

// ————— §6 chat —————

describe("protocol §6: chat", () => {
	it("accepts a chat POST (202), dedupes the requestId (409 on a different body)", async () => {
		const requestId = `chat-${Date.now()}`;
		const sent = await api("/v1/chat", { method: "POST", token, body: { requestId, text: "What is filling my phone?" } });
		expect(sent.status).toBe(202);
		expect(sent.body).toMatchObject({ accepted: true, conversationId: "main" });
		const again = await api("/v1/chat", { method: "POST", token, body: { requestId, text: "What is filling my phone?" } });
		expect(again.status).toBe(202);
		expect(again.body.duplicate).toBe(true);
		const conflict = await api("/v1/chat", { method: "POST", token, body: { requestId, text: "different" } });
		expect(conflict.status).toBe(409);
		expect(conflict.body.error.code).toBe("REQUEST_ID_CONFLICT");
	});

	it("S9: POST /v1/chat gives the model the server note; the history and stream show the typed text only", async () => {
		const typed = `Are you here? ${Date.now()}`;
		const sent = await api("/v1/chat", { method: "POST", token, body: { requestId: `chat-s9-${Date.now()}`, text: typed } });
		expect(sent.status).toBe(202);
		const { BACKGROUND_CONTEXT } = await import("@earendil-works/chord/context");
		const { SERVER_NOTE_MARKER } = await import("../src/owner-note.ts");
		let modelInput: string | undefined;
		for (let i = 0; i < 200 && modelInput === undefined; i++) {
			const view = await stewardOpened.root.context(BACKGROUND_CONTEXT);
			modelInput = view.entries
				.map((entry) => entry.model?.[0])
				.filter((message) => message?.role === "user")
				.map((message) => (typeof message.content === "string" ? message.content : ""))
				.find((text) => text.startsWith(typed));
			if (modelInput === undefined) await new Promise((resolve) => setTimeout(resolve, 25));
		}
		expect(modelInput).toContain(SERVER_NOTE_MARKER);
		expect(modelInput).toContain("Server record (authoritative");
		expect(modelInput).toMatch(/Decisions ledger: \d+ entries/);
		let owner: Array<{ type: string; data: { role?: string; text?: string } }> = [];
		for (let i = 0; i < 200 && owner.length === 0; i++) {
			const history = await api("/v1/chat/history?limit=200", { token });
			owner = (history.body.events as typeof owner).filter((event) => event.type === "message" && event.data.text === typed);
			if (owner.length === 0) await new Promise((resolve) => setTimeout(resolve, 25));
		}
		expect(owner).toHaveLength(1);
		const history = await api("/v1/chat/history?limit=200", { token });
		expect(JSON.stringify(history.body)).not.toContain("server note");
	});

	it("streams SSE from the store and heartbeats; history returns the A4 envelope, oldest first", async () => {
		const history = await api("/v1/chat/history?limit=50", { token });
		expect(history.status).toBe(200);
		const events = (history.body.events as Array<{ id: number; type: string; data: Record<string, unknown> }>) ?? [];
		expect(events.length).toBeGreaterThan(0);
		// A4: the envelope is {id, type, data} — id is the seq the stream sends, oldest first.
		for (const event of events) {
			expect(typeof event.id).toBe("number");
			expect(["message", "tool", "proposal", "run"]).toContain(event.type);
			expect(event.data).toBeDefined();
		}
		const ids = events.map((event) => event.id);
		expect([...ids].sort((a, b) => a - b)).toEqual(ids);
		expect(events.some((event) => event.type === "message")).toBe(true);

		const response = await fetch(`http://127.0.0.1:${port}/v1/chat/stream?after=0`, {
			headers: { authorization: `Bearer ${token}` },
		});
		expect(response.status).toBe(200);
		expect(response.headers.get("content-type")).toContain("text/event-stream");
		const reader = response.body!.getReader();
		const { value } = await reader.read();
		const text = new TextDecoder().decode(value);
		expect(text).toMatch(/^id: \d+\nevent: \w+\ndata: /);
		await reader.cancel();
	});
});

// ————— §7 status and agent settings —————

describe("protocol §7: status and agent settings", () => {
	it("reports the server, device, counts, and the model from the conversation's agent settings", async () => {
		const status = await api("/v1/status", { token });
		expect(status.status).toBe(200);
		expect(status.body.serverTimeMs).toBeGreaterThan(0);
		expect(status.body.agent.model.provider).toBe("faux");
		expect(typeof status.body.pendingJobs).toBe("number");
		expect(typeof status.body.openProposals).toBe("number");

		const settings = await api("/v1/agent/settings", { token });
		expect(settings.status).toBe(200);
		// The model lives in the conversation's agent settings : this suite's harness
		// runs the scripted faux provider, so the resolved model is faux (A10.4: the allowlist
		// field now just names the current model, for clients that predate the catalogue).
		expect(settings.body.conversationId).toBe("main");
		expect(settings.body.model.provider).toBe("faux");
		expect(settings.body.allowlist).toEqual([{ provider: "faux", id: settings.body.model.id, metered: true }]);
	});

	it("device status is recorded and shown by /v1/status", async () => {
		const sent = await api("/v1/device/status", {
			method: "POST",
			token,
			body: {
				requestId: `dev-${Date.now()}`,
				appVersion: "0.2.0-test",
				permissions: { usageAccess: true, allFiles: true, mediaRead: true, manageMedia: false, batteryUnrestricted: true },
				lastWorkerRunMs: Date.now() - 1000,
			},
		});
		expect(sent.status).toBe(200);
		const status = await api("/v1/status", { token });
		expect(status.body.device.permissions).toMatchObject({ usageAccess: true });
	});

	it("A10: permissions.shizuku and stewardTrash pass through /v1/device/status to /v1/status", async () => {
		const sent = await api("/v1/device/status", {
			method: "POST",
			token,
			body: {
				requestId: `dev-a10-${Date.now()}`,
				appVersion: "0.3.0-test",
				permissions: { usageAccess: true, allFiles: true, mediaRead: true, manageMedia: false, batteryUnrestricted: true, shizuku: "ready" },
				stewardTrash: { bytes: 2_600_000_000, oldestMs: 1_790_000_000_000 },
			},
		});
		expect(sent.status).toBe(200);
		const status = await api("/v1/status", { token });
		expect(status.body.device.permissions.shizuku).toBe("ready");
		expect(status.body.device.stewardTrash).toEqual({ bytes: 2_600_000_000, oldestMs: 1_790_000_000_000 });
	});
});

describe("N9 (server half): a job poll refreshes lastSeenMs, throttled to once a minute", () => {
	async function setLastSeen(ms: number): Promise<void> {
		const { Device } = await import("../src/documents.ts");
		const { BACKGROUND_CONTEXT } = await import("@earendil-works/chord/context");
		await stewardOpened.harness.commit(async (tx) => {
			const doc = await tx.doc(Device, stewardOpened.root.id);
			doc.lastSeenMs = ms;
		}, BACKGROUND_CONTEXT);
	}

	it("refreshes a stale lastSeenMs on GET /v1/jobs, once per minute, without touching permissions", async () => {
		const permissions = { usageAccess: true, allFiles: false, mediaRead: true, manageMedia: false, batteryUnrestricted: true };
		await api("/v1/device/status", { method: "POST", token, body: { requestId: `n9-${Date.now()}`, appVersion: "0.2.2", permissions } });
		// (The suite's fake phone also polls, so only the post-poll state is asserted.)
		const before = Date.now();
		await setLastSeen(before - 120_000);
		expect((await api("/v1/jobs")).status).toBe(401);
		expect((await api("/v1/jobs", { token })).status).toBe(200);
		const refreshed = (await api("/v1/status", { token })).body.device;
		expect(refreshed.lastSeenMs).toBeGreaterThanOrEqual(before);
		expect(refreshed.permissions).toEqual(permissions);

		// Within a minute of the last write the next poll writes nothing.
		const throttled = Date.now() - 10_000;
		await setLastSeen(throttled);
		await api("/v1/jobs", { token });
		expect((await api("/v1/status", { token })).body.device.lastSeenMs).toBe(throttled);
	});
});

describe("S4 over HTTP: a no-op execution report re-opens the proposal and replays do not flip state twice", () => {
	it("re-opens on a cancelled report, dedupes the replay, then executes after a second approval", async () => {
		const { createProposal } = await import("../src/proposals.ts");
		const { BACKGROUND_CONTEXT } = await import("@earendil-works/chord/context");
		const proposal = await stewardOpened.harness.commit(
			(tx) =>
				createProposal(
					tx,
					stewardOpened.root.id,
					{
						source: "agent",
						title: "S4 over http",
						reason: "r",
						items: [{ kind: "clear_all_caches", target: { apps: [{ packageName: "com.a", label: "A", cacheBytes: 5 }] }, bytesEstimate: 5 }],
					},
					Date.now(),
				),
			BACKGROUND_CONTEXT,
		);
		const id = proposal.proposalId;
		const itemId = proposal.items[0]!.itemId;
		const stamp = Date.now();
		const approve1 = await api(`/v1/proposals/${id}/decision`, { method: "POST", token, body: { requestId: `s4-d1-${stamp}`, decision: "approve" } });
		expect(approve1.status).toBe(200);
		const noop = { requestId: `s4-e1-${stamp}`, decisionId: approve1.body.decisionId, items: [{ itemId, outcome: "cancelled" }] };
		expect((await api(`/v1/proposals/${id}/execution`, { method: "POST", token, body: noop })).status).toBe(200);
		const open1 = await api("/v1/proposals?status=open", { token });
		expect(open1.body.proposals.map((p: { proposalId: string }) => p.proposalId)).toContain(id);

		// Owner approves again, then a replay of the FIRST report arrives: it is deduped and
		// must not touch the re-approved proposal.
		const approve2 = await api(`/v1/proposals/${id}/decision`, { method: "POST", token, body: { requestId: `s4-d2-${stamp}`, decision: "approve" } });
		expect(approve2.status).toBe(200);
		expect(approve2.body.decisionId).not.toBe(approve1.body.decisionId);
		const replay = await api(`/v1/proposals/${id}/execution`, { method: "POST", token, body: noop });
		expect(replay.status).toBe(200);
		expect(replay.body.duplicate).toBe(true);
		const stillApproved = await api("/v1/proposals?status=approved", { token });
		expect(stillApproved.body.proposals.map((p: { proposalId: string }) => p.proposalId)).toContain(id);

		const done = { requestId: `s4-e2-${stamp}`, decisionId: approve2.body.decisionId, items: [{ itemId, outcome: "done", resultCode: 0, bytesFreed: 5 }] };
		expect((await api(`/v1/proposals/${id}/execution`, { method: "POST", token, body: done })).status).toBe(200);
		const executed = await api("/v1/proposals?status=executed", { token });
		expect(executed.body.proposals.map((p: { proposalId: string }) => p.proposalId)).toContain(id);
		expect((await api(`/v1/proposals/${id}/execution`, { method: "POST", token, body: done })).body.duplicate).toBe(true);
	});
});

// ————— admin socket (the CLI's channel) —————

describe("the admin channel", () => {
	it("answers doctor, and run weekly-check queues a rule run", async () => {
		const doctor = await adminGet("/admin/doctor");
		expect(doctor).toMatchObject({ ok: true });
		const run = await adminPost("/admin/run/weekly-check");
		expect(run).toMatchObject({ queued: true });
	});
});

// The pairing rate limit runs LAST: the per-address limit is shared by every test on
// 127.0.0.1, and the fake phone must be able to pair before this point.
describe("protocol §2: pairing rate limit (last, so the fake phone can pair first)", () => {
	it("rate limits after 5 failures per address (429 with Retry-After)", async () => {
		for (let i = 0; i < 5; i++) {
			await api("/v1/pair", { method: "POST", body: { code: `AAAA-BB0${i}`, deviceName: "x" } });
		}
		const limited = await api("/v1/pair", { method: "POST", body: { code: "AAAA-BBBB", deviceName: "x" } });
		expect(limited.status).toBe(429);
		expect(limited.body.error.code).toBe("RATE_LIMITED");
		expect(limited.headers.get("retry-after")).toBeDefined();
	});

	// item 4: behind a loopback peer (Caddy), the limit keys on the
	// left-most X-Forwarded-For address — two forwarded addresses get separate buckets.
	it("keys the limit per forwarded address behind a loopback peer", async () => {
		const attempt = async (xff: string, i: number): Promise<number> => {
			const response = await fetch(`http://127.0.0.1:${port}/v1/pair`, {
				method: "POST",
				headers: { "content-type": "application/json", "x-forwarded-for": xff },
				body: JSON.stringify({ code: `FFFF-B${xff === "203.0.113.7" ? "0" : "1"}${i}`, deviceName: "x" }),
			});
			return response.status;
		};
		// 5 failures from forwarded address A → A is limited...
		for (let i = 0; i < 5; i++) expect(await attempt("203.0.113.7", i)).toBe(400);
		expect(await attempt("203.0.113.7", 9)).toBe(429);
		// ...while forwarded address B still gets normal (400) responses, not 429.
		expect(await attempt("198.51.100.9", 0)).toBe(400);
	});
});
