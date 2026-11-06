/**
 * Crash self-reports (docs/protocol.md A5): the app posts its own uncaught exceptions,
 * before or after pairing, so a crash on the phone reaches the server without a human
 * copying a stack trace. A real server on a temporary port and state dir.
 */
import { connect } from "node:net";
import { mkdtemp, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { afterAll, beforeAll, describe, expect, it } from "vitest";
import { loadConfig } from "../src/config.ts";
import { openStewardHarness } from "../src/steward.ts";
import { startStewardServers, type StewardServers } from "../src/http.ts";
import { CRASH_LIMITS, appendCrash, type CrashReport } from "../src/crash-reports.ts";

let dir: string;
let port: number;
let servers: StewardServers;
let closeHarness: () => Promise<void>;
const pings: Array<{ title: string; body: string }> = [];

async function admin(method: "GET" | "POST", path: string): Promise<any> {
	return new Promise((resolve, reject) => {
		const socket = connect(join(dir, "admin.sock"), () => {
			socket.write(`${method} ${path} HTTP/1.1\r\nhost: admin\r\ncontent-length: 0\r\nconnection: close\r\n\r\n`);
		});
		let raw = "";
		socket.on("data", (chunk: Buffer) => {
			raw += chunk.toString("utf8");
		});
		socket.on("error", reject);
		socket.on("close", () => resolve(JSON.parse(raw.slice(raw.indexOf("\r\n\r\n") + 4))));
	});
}

function post(
	path: string,
	body: unknown,
	headers: Record<string, string> = {},
): Promise<{ status: number; body: any; headers: Headers }> {
	return fetch(`http://127.0.0.1:${port}${path}`, {
		method: "POST",
		headers: { "content-type": "application/json", ...headers },
		body: typeof body === "string" ? body : JSON.stringify(body),
	}).then(async (response) => ({
		status: response.status,
		body: await response.json().catch(() => null),
		headers: response.headers,
	}));
}

let seq = 0;
function crash(overrides: Record<string, unknown> = {}): Record<string, unknown> {
	seq += 1;
	return {
		requestId: `crash-test-${seq}`,
		appVersion: "0.2.1-w3",
		crashedAtMs: 1_760_000_000_000 + seq,
		thread: "main",
		exception: "java.lang.StackOverflowError",
		stackTrace: "java.lang.StackOverflowError\n\tat com.example.phonesteward.android.AppGraph.<init>(AppGraph.kt:65)",
		device: { model: "IN2023", sdk: 33 },
		...overrides,
	};
}

async function waitFor(check: () => boolean, timeoutMs = 10_000): Promise<void> {
	const deadline = Date.now() + timeoutMs;
	while (Date.now() < deadline) {
		if (check()) return;
		await new Promise((resolve) => setTimeout(resolve, 100));
	}
	throw new Error("timed out");
}

beforeAll(async () => {
	dir = await mkdtemp(join(tmpdir(), "w3-crash-"));
	const config = loadConfig({ stateDir: dir, port: 0, notify: {}, schedules: { enabled: false } });
	const opened = await openStewardHarness({
		dbPath: join(dir, "steward.sqlite"),
		fauxPlan: { hang: false },
		model: { provider: "zai", modelId: "glm-5.3-flash" },
	});
	closeHarness = () => opened.close();
	servers = startStewardServers({
		config,
		harness: opened.harness,
		root: opened.root,
		notifySender: async (title, body) => {
			pings.push({ title, body });
		},
	});
	await new Promise<void>((resolve) => servers.tcp.listen(0, "127.0.0.1", resolve));
	port = (servers.tcp.address() as { port: number }).port;
});

afterAll(async () => {
	await servers.close();
	await closeHarness();
	await rm(dir, { recursive: true, force: true });
});

describe("POST /v1/crash (A5)", () => {
	it("accepts a report without a token (a crash can come before pairing) and lists it for the owner", async () => {
		const body = crash();
		const response = await post("/v1/crash", body);
		expect(response.status).toBe(200);
		expect(response.body.accepted).toBe(true);
		expect(response.body.reportId).toMatch(/^cr-[0-9a-f]+$/);
		const listed = await admin("GET", "/admin/crashes");
		const report = listed.crashes.find((c: CrashReport) => c.reportId === response.body.reportId);
		expect(report).toMatchObject({
			appVersion: "0.2.1-w3",
			exception: "java.lang.StackOverflowError",
			thread: "main",
			crashedAtMs: body.crashedAtMs,
			device: { model: "IN2023", sdk: 33 },
		});
		expect(report.stackTrace).toContain("AppGraph.<init>");
		expect(report.deviceId).toBeUndefined();
	});

	it("attributes the report to the paired device when a valid token rides along", async () => {
		const { code } = await admin("POST", "/admin/pair");
		const paired = await post("/v1/pair", { code, deviceName: "suite" });
		expect(paired.status).toBe(200);
		const response = await post("/v1/crash", crash(), { authorization: `Bearer ${paired.body.token}` });
		expect(response.status).toBe(200);
		const listed = await admin("GET", "/admin/crashes");
		const report = listed.crashes.find((c: CrashReport) => c.reportId === response.body.reportId);
		expect(report.deviceId).toBe(paired.body.deviceId);
	});

	it("never loses a report to auth state: an unknown token is ignored, not 401", async () => {
		const response = await post("/v1/crash", crash(), { authorization: "Bearer not-a-real-token" });
		expect(response.status).toBe(200);
		const listed = await admin("GET", "/admin/crashes");
		const report = listed.crashes.find((c: CrashReport) => c.reportId === response.body.reportId);
		expect(report.deviceId).toBeUndefined();
	});

	it("follows the §1 requestId rule: a repeat is a duplicate, a changed body a conflict", async () => {
		const body = crash();
		const first = await post("/v1/crash", body);
		const again = await post("/v1/crash", body);
		expect(again.status).toBe(200);
		expect(again.body).toMatchObject({ accepted: true, reportId: first.body.reportId, duplicate: true });
		const listed = await admin("GET", "/admin/crashes");
		expect(listed.crashes.filter((c: CrashReport) => c.reportId === first.body.reportId)).toHaveLength(1);
		const changed = await post("/v1/crash", { ...body, thread: "worker" });
		expect(changed.status).toBe(409);
		expect(changed.body.error.code).toBe("REQUEST_ID_CONFLICT");
	});

	it("caps the body at 64 KiB and the fields at their limits", async () => {
		const huge = await post("/v1/crash", crash({ stackTrace: "x".repeat(CRASH_LIMITS.bodyBytes + 10) }));
		expect(huge.status).toBe(413);
		expect(huge.body.error.code).toBe("BODY_TOO_LARGE");
		const longTrace = await post("/v1/crash", crash({ stackTrace: "x".repeat(CRASH_LIMITS.stackTraceChars + 1) }));
		expect(longTrace.status).toBe(400);
		expect(longTrace.body.error.message).toContain("stackTrace");
		const noException = await post("/v1/crash", crash({ exception: "" }));
		expect(noException.status).toBe(400);
		expect(noException.body.error.message).toContain("exception");
		const badSdk = await post("/v1/crash", crash({ device: { model: "x", sdk: "33" } }));
		expect(badSdk.status).toBe(400);
	});

	it("pings the owner once per exception and app version, with no stack trace or message", async () => {
		await waitFor(() => pings.length >= 1);
		// Several StackOverflowError reports above, one version: exactly one ping.
		await new Promise((resolve) => setTimeout(resolve, 500));
		const overflow = pings.filter((p) => p.body.includes("java.lang.StackOverflowError"));
		expect(overflow).toHaveLength(1);
		expect(overflow[0]!.title).toContain("crash");
		expect(overflow[0]!.body).toContain("0.2.1-w3");
		expect(overflow[0]!.body).not.toContain("AppGraph");
		await post("/v1/crash", crash({ exception: "java.lang.IllegalStateException", stackTrace: "secret detail" }));
		await waitFor(() => pings.some((p) => p.body.includes("IllegalStateException")));
		expect(pings.some((p) => p.body.includes("secret detail"))).toBe(false);
	});

	// Last: the per-address limit is shared by every request from 127.0.0.1.
	it("rate limits each client address (429 with Retry-After); another forwarded address is not affected", async () => {
		const from = (xff: string) => post("/v1/crash", crash(), { "x-forwarded-for": xff });
		for (let i = 0; i < CRASH_LIMITS.perAddressPerHour; i++) expect((await from("203.0.113.7")).status).toBe(200);
		const limited = await from("203.0.113.7");
		expect(limited.status).toBe(429);
		expect(limited.body.error.code).toBe("RATE_LIMITED");
		expect(limited.headers.get("retry-after")).not.toBeNull();
		expect((await from("198.51.100.9")).status).toBe(200);
	});
});

	describe("A5 severity (O5 server half)", () => {
		it("stores an error report with its site, defaults a crash to severity crash, and validates both fields", async () => {
			const error = await post("/v1/crash", crash({
				severity: "error",
				site: "StatusTab.batteryButton",
				exception: "android.app.ForegroundServiceDidNotStartInTimeException",
				stackTrace: "at android.app.ContextImpl.startService",
			}));
			expect(error.status).toBe(200);
			const crashDefault = await post("/v1/crash", crash());
			expect(crashDefault.status).toBe(200);
			const listed = await admin("GET", "/admin/crashes");
			const errorReport = listed.crashes.find((c: CrashReport) => c.reportId === error.body.reportId);
			const crashReport = listed.crashes.find((c: CrashReport) => c.reportId === crashDefault.body.reportId);
			expect(errorReport).toMatchObject({ severity: "error", site: "StatusTab.batteryButton" });
			expect(crashReport.severity).toBe("crash");

			const noSite = await post("/v1/crash", crash({ severity: "error", exception: "E1", stackTrace: "s" }));
			expect(noSite.status).toBe(400);
			expect(noSite.body.error.message).toContain("site");
			const longSite = await post("/v1/crash", crash({ severity: "error", site: "x".repeat(129), exception: "E2", stackTrace: "s" }));
			expect(longSite.status).toBe(400);
			const badSeverity = await post("/v1/crash", crash({ severity: "fatal", exception: "E3", stackTrace: "s" }));
			expect(badSeverity.status).toBe(400);
			expect(badSeverity.body.error.message).toContain("severity");
		});

		it("never pings the owner for an error report", async () => {
			const marker = "w3b-error-no-ping";
			await post("/v1/crash", crash({
				severity: "error",
				site: "PermissionsTab",
				exception: marker,
				stackTrace: "no ping expected",
			}));
			// Let any (wrong) notification path settle before asserting.
			await new Promise((resolve) => setTimeout(resolve, 700));
			expect(pings.some((p) => p.body.includes(marker) || p.title.includes(marker))).toBe(false);
			// … while a crash still pings (control).
			await post("/v1/crash", crash({ exception: marker, stackTrace: "control: crash pings" }));
			await waitFor(() => pings.some((p) => p.body.includes(marker)));
		}, 20_000);
	});

	describe("bin/steward crashes", () => {
	it("lists the reports, newest first, through the running instance's admin socket", async () => {
		// Asynchronous on purpose: the server under test runs in this process.
		const { spawn } = await import("node:child_process");
		const cli = new URL("../src/cli.ts", import.meta.url).pathname;
		const run = await new Promise<{ status: number | null; stdout: string; stderr: string }>((resolve) => {
			const child = spawn(process.execPath, ["--experimental-strip-types", cli, "crashes", "--json"], {
				env: { ...process.env, PHONE_STEWARD_STATE_DIR: dir },
			});
			let stdout = "";
			let stderr = "";
			child.stdout.on("data", (chunk: Buffer) => (stdout += chunk.toString()));
			child.stderr.on("data", (chunk: Buffer) => (stderr += chunk.toString()));
			child.on("close", (status) => resolve({ status, stdout, stderr }));
		});
		expect(run.status, run.stderr).toBe(0);
		const out = JSON.parse(run.stdout) as { via: string; crashes: CrashReport[] };
		expect(out.via).toBe("admin-socket");
		expect(out.crashes.length).toBeGreaterThan(5);
		expect(out.crashes[0]!.receivedAtMs).toBeGreaterThanOrEqual(out.crashes.at(-1)!.receivedAtMs);
	});

	async function runCli(args: string[]): Promise<{ status: number | null; stdout: string; stderr: string }> {
		const { spawn } = await import("node:child_process");
		const cli = new URL("../src/cli.ts", import.meta.url).pathname;
		return new Promise((resolve) => {
			const child = spawn(process.execPath, ["--experimental-strip-types", cli, ...args], {
				env: { ...process.env, PHONE_STEWARD_STATE_DIR: dir },
			});
			let stdout = "";
			let stderr = "";
			child.stdout.on("data", (chunk: Buffer) => (stdout += chunk.toString()));
			child.stderr.on("data", (chunk: Buffer) => (stderr += chunk.toString()));
			child.on("close", (status) => resolve({ status, stdout, stderr }));
		});
	}

	it("--errors lists only error reports and --crashes only crash reports (O5)", async () => {
		const errors = await runCli(["crashes", "--errors", "--json"]);
		expect(errors.status, errors.stderr).toBe(0);
		const errorList = JSON.parse(errors.stdout) as { crashes: Array<{ severity: string; site?: string }> };
		expect(errorList.crashes.length).toBeGreaterThan(0);
		for (const report of errorList.crashes) {
			expect(report.severity).toBe("error");
			expect(typeof report.site).toBe("string");
		}
		const crashes = await runCli(["crashes", "--crashes", "--json"]);
		expect(crashes.status, crashes.stderr).toBe(0);
		const crashList = JSON.parse(crashes.stdout) as { crashes: Array<{ severity: string }> };
		expect(crashList.crashes.length).toBeGreaterThan(0);
		for (const report of crashList.crashes) expect(report.severity).not.toBe("error");
	});
});

describe("crash retention", () => {
	it("keeps only the newest reports", () => {
		let reports: CrashReport[] = [];
		for (let i = 0; i < CRASH_LIMITS.keep + 5; i++) {
			reports = appendCrash(reports, {
				reportId: `cr-${i}`,
				receivedAtMs: i,
				appVersion: "v",
				crashedAtMs: i,
				thread: "main",
				exception: "E",
				stackTrace: "",
				severity: "crash",
			});
		}
		expect(reports).toHaveLength(CRASH_LIMITS.keep);
		expect(reports[0]!.reportId).toBe("cr-5");
		expect(reports.at(-1)!.reportId).toBe(`cr-${CRASH_LIMITS.keep + 4}`);
	});
});
