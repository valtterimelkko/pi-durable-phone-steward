#!/usr/bin/env node
/**
 * The steward CLI : pair, revoke, status, jobs, proposals, rules, doctor,
 * selftest, run weekly-check|photo-window, and the observability reads (O1–O6).
 *
 * It talks to a RUNNING instance through the admin Unix socket (`<stateDir>/admin.sock`),
 * and to a STOPPED instance by opening its SQLite state directly (safe: nothing else owns
 * it). Both modes are reported in `--json` output as `via`.
 */
import { existsSync } from "node:fs";
import { spawn } from "node:child_process";
import { randomUUID } from "node:crypto";
import { connect as connectSocket } from "node:net";
import { join } from "node:path";
import { loadConfig } from "../src/config.ts";

// item 1: direct-state mode requires an EXPLICIT PHONE_STEWARD_STATE_DIR.
// Without it the CLI must never fall back to the default state path (the original
// incident created /var/lib/phone-steward/steward.sqlite exactly that way). The default
// is injectable (PHONE_STEWARD_DEFAULT_STATE_DIR) so tests never touch /var/lib.
const explicitStateDir = process.env.PHONE_STEWARD_STATE_DIR;
const stateDir = explicitStateDir ?? process.env.PHONE_STEWARD_DEFAULT_STATE_DIR ?? loadConfig().stateDir;
const adminPath = join(stateDir, "admin.sock");
const running = existsSync(adminPath);

/** O4: the journal binary is injectable so tests never need systemd. */
const journalctl = process.env.PHONE_STEWARD_JOURNALCTL ?? "journalctl";

function usage(): never {
	console.error(`usage: bin/steward <command>

Commands:
  pair                        print a one-time pairing code (10 minutes)
  revoke                      revoke the paired device's token
  status                      server, device, jobs and proposals overview
  jobs                        list jobs and their states
  job <jobId>                 one job's params, status and full result (O2)
  proposals [--all]           list open (or all) proposals
  rules                       show the standing rules
  selftest                    queue a phone_selftest job
  crashes [--errors|--crashes]  the app's crash and error reports, newest first (A5/O5)
  chat [--last N]             the conversation in order, oldest last (O1; default 20)
  snapshot                    the latest storage snapshot, summarised (O3)
  usage                       model tokens per day (O3)
  say "<text>"                post an owner chat message, marked [via server], through the running instance
  diag [--wait S]             queue phone_diagnostics and print the result (O6; default 60s)
  logs [--lines N]            the service journal, read-only (O4; default 200)
  run weekly-check|photo-window   run a rule task now (running instance only)
  compact                     compact the steward's context now (A10.9; running instance, wakes the model)
  fresh                       start the steward's context fresh from the state brief (A10.9; running instance)
  backup                      SQLite online backup into <state>/backups/, newest 7 kept (S3; no server needed)
  doctor                      health checks
  --json                      machine-readable output`);
	process.exit(2);
}

/** O5: crashes lists both severities; --errors / --crashes narrow it (both = no filter). */
function filterCrashReports<T extends { severity?: string }>(reports: T[], rest: string[]): T[] {
	const wantErrors = rest.includes("--errors");
	const wantCrashes = rest.includes("--crashes");
	const normalised = reports.map((report) => ({ ...report, severity: report.severity ?? "crash" }));
	if (wantErrors && !wantCrashes) return normalised.filter((report) => report.severity === "error");
	if (wantCrashes && !wantErrors) return normalised.filter((report) => report.severity !== "error");
	return normalised;
}

/** HTTP over the admin Unix socket. */
function adminRequest(method: string, path: string, body?: unknown): Promise<{ status: number; body: unknown }> {
	return new Promise((resolve, reject) => {
		const socket = connectSocket(adminPath, () => {
			const payload = body === undefined ? undefined : JSON.stringify(body);
			const header = `content-length: ${Buffer.byteLength(payload ?? "")}\r\n`;
			socket.write(
				`${method} ${path} HTTP/1.1\r\nhost: admin\r\nconnection: close\r\ncontent-type: application/json\r\n${payload === undefined ? "" : header}\r\n${payload ?? ""}`,
			);
		});
		let raw = "";
		socket.on("data", (chunk: Buffer) => {
			raw += chunk.toString("utf8");
		});
		socket.on("error", reject);
		socket.on("close", () => {
			const split = raw.indexOf("\r\n\r\n");
			const head = raw.slice(0, split);
			const text = raw.slice(split + 4);
			const status = Number(/^HTTP\/1\.[01] (\d+)/.exec(head)?.[1] ?? 0);
			let parsed: unknown = text;
			try {
				parsed = JSON.parse(text);
			} catch {
				// keep text
			}
			resolve({ status, body: parsed });
		});
	});
}

async function main(): Promise<void> {
	const [command = "", ...rest] = process.argv.slice(2);
	const json = rest.includes("--json");
	if (command === "" || command === "help") usage();

	// Acting commands never fall back to the default state dir: on this host that is
	// production, and a probe or a docs recipe once nearly minted a production pairing
	// code that way (docs review). Reads may still use the default.
	const acting = ["pair", "revoke", "selftest", "run", "diag", "say", "compact", "fresh", "backup"];
	if (acting.includes(command) && explicitStateDir === undefined) {
		console.error(
			`[steward] '${command}' acts on an instance: set PHONE_STEWARD_STATE_DIR explicitly (production is ${stateDir}); refusing to use the default`,
		);
		process.exit(1);
	}

	if (command === "backup") {
		// S3: the online backup needs no running server (it reads the database read-only and
		// writes only under <state>/backups/); the explicit-state check above has run.
		const { backupState } = await import("../src/backup.ts");
		const result = await backupState({ stateDir });
		output(json, { via: running ? "admin-socket" : "direct-state", command, path: result.path, bytes: result.bytes, kept: result.kept, pruned: result.pruned });
		return;
	}

	if (!running && explicitStateDir === undefined) {
		console.error(
			`[steward] refusing to guess the state: set PHONE_STEWARD_STATE_DIR explicitly for direct-state mode, or run against the server (no ${adminPath})`,
		);
		process.exit(1);
	}
	if (!running && command !== "logs" && !["pair", "revoke", "status", "jobs", "proposals", "rules", "doctor", "crashes", "job", "chat", "snapshot", "usage"].includes(command)) {
		console.error(`[steward] the server is not running (no ${adminPath}); '${command}' needs the running instance`);
		process.exit(1);
	}

	if (command === "logs") {
		// O4: the service journal, read-only, in either mode. The journal binary is
		// injectable for tests; this command never opens the state directory.
		const linesIndex = rest.indexOf("--lines");
		const lines = linesIndex >= 0 ? Number(rest[linesIndex + 1]) || 200 : 200;
		const journalArgs = ["-u", "phone-steward", "-n", String(lines), "--no-pager"];
		if (json) {
			// item 4: --json wraps the journal output as {"lines": [...]}.
			const child = spawn(journalctl, journalArgs, { stdio: ["ignore", "pipe", "inherit"] });
			let stdout = "";
			child.stdout.on("data", (chunk: Buffer) => (stdout += chunk.toString()));
			const code = await new Promise<number | null>((resolve) => child.on("close", resolve));
			if (code !== 0) {
				console.error(`[steward] journalctl exited ${code}`);
				process.exit(code ?? 1);
			}
			output(true, { command, lines: stdout.split("\n").filter((line) => line.length > 0) });
			return;
		}
		const child = spawn(journalctl, journalArgs, { stdio: ["ignore", "inherit", "inherit"] });
		const code = await new Promise<number | null>((resolve) => child.on("close", resolve));
		process.exit(code ?? 1);
	}

	const via = running ? "admin-socket" : "direct-state";
	if (running) {
		const paths: Record<string, { method: string; path: string }> = {
			pair: { method: "POST", path: "/admin/pair" },
			revoke: { method: "POST", path: "/admin/revoke" },
			status: { method: "GET", path: "/admin/status" },
			jobs: { method: "GET", path: "/admin/jobs" },
			proposals: { method: "GET", path: "/admin/proposals" },
			rules: { method: "GET", path: "/admin/rules" },
			selftest: { method: "POST", path: "/admin/selftest" },
			doctor: { method: "GET", path: "/admin/doctor" },
			crashes: { method: "GET", path: "/admin/crashes" },
			diagnostics: { method: "POST", path: "/admin/diagnostics" },
		};
		if (command === "run") {
			const which = rest.find((r) => r === "weekly-check" || r === "photo-window");
			if (which === undefined) usage();
			const response = await adminRequest("POST", `/admin/run/${which}`);
			output(json, { via, command, ...((response.body ?? {}) as Record<string, unknown>) });
			return;
		}
		if (command === "job") {
			const jobId = rest.find((r) => !r.startsWith("--"));
			if (jobId === undefined) usage();
			const response = await adminRequest("GET", `/admin/jobs/${encodeURIComponent(jobId)}`);
			if (response.status === 404) {
				console.error(`[steward] unknown job ${jobId}`);
				process.exit(1);
			}
			output(json, { via, command, job: response.body });
			return;
		}
		if (command === "chat") {
			const lastIndex = rest.indexOf("--last");
			const last = lastIndex >= 0 ? Number(rest[lastIndex + 1]) || 20 : 20;
			const response = await adminRequest("GET", `/admin/chat?last=${encodeURIComponent(String(last))}`);
			output(json, { via, command, ...(response.body as Record<string, unknown>) });
			return;
		}
		if (command === "say") {
			// S7: the server adds the "[via server] " marker; the requestId makes a retry safe.
			const text = rest.filter((arg) => arg !== "--json").join(" ").trim();
			if (text.length === 0) usage();
			const requestId = `say-${randomUUID()}`;
			const response = await adminRequest("POST", "/admin/chat", { requestId, text });
			const body = (response.body ?? {}) as Record<string, unknown>;
			if (response.status !== 202) {
				const message = (body.error as { message?: string } | undefined)?.message ?? JSON.stringify(body);
				console.error(`[steward] the server refused the message (HTTP ${response.status}): ${message}`);
				process.exit(1);
			}
			output(json, { via, command, accepted: true, requestId });
			return;
		}
		if (command === "compact" || command === "fresh") {
			// A10.9: the same operation as POST /v1/chat/compact|fresh, through the admin socket.
			const response = await adminRequest("POST", `/admin/${command}`);
			const body = (response.body ?? {}) as Record<string, unknown>;
			if (response.status !== 202) {
				const message = (body.error as { message?: string } | undefined)?.message ?? JSON.stringify(body);
				console.error(`[steward] the server refused '${command}' (HTTP ${response.status}): ${message}`);
				process.exit(1);
			}
			output(json, { via, command, accepted: true });
			return;
		}
		if (command === "snapshot" || command === "usage") {
			const response = await adminRequest("GET", `/admin/${command}`);
			output(json, { via, command, ...(response.body as Record<string, unknown>) });
			return;
		}
		if (command === "diag") {
			const waitIndex = rest.indexOf("--wait");
			const waitSeconds = waitIndex >= 0 ? Number(rest[waitIndex + 1]) || 60 : 60;
			const queued = await adminRequest("POST", "/admin/diagnostics");
			const queuedBody = (queued.body ?? {}) as { jobId?: string };
			if (queued.status !== 200 || typeof queuedBody.jobId !== "string") {
				console.error(`[steward] the server refused the diagnostics queue (HTTP ${queued.status})`);
				process.exit(1);
			}
			const jobId = queuedBody.jobId;
			const deadline = Date.now() + waitSeconds * 1000;
			let job: Record<string, unknown> = { jobId, status: "queued" };
			while (Date.now() < deadline) {
				await new Promise((resolve) => setTimeout(resolve, 1000));
				const poll = await adminRequest("GET", `/admin/jobs/${encodeURIComponent(jobId)}`);
				const pollBody = (poll.body ?? {}) as Record<string, unknown>;
				if (pollBody.status === "reported" || pollBody.status === "expired") {
					job = pollBody;
					break;
				}
			}
			if (job.status !== "reported" && job.status !== "expired") {
				console.error(`[steward] no diagnostics result within ${waitSeconds}s (job ${jobId}); try bin/steward job ${jobId} later`);
				output(json, { via, command, ...job });
				process.exit(1);
			}
			output(json, { via, command, ...job });
			return;
		}
		const route = paths[command];
		if (route === undefined) usage();
		const response = await adminRequest(route.method, route.path);
		const body = (response.body ?? {}) as Record<string, unknown>;
		if (command === "crashes" && Array.isArray(body.crashes)) {
			body.crashes = filterCrashReports(body.crashes as Array<{ severity?: string }>, rest);
		}
		output(json, { via, command, ...body });
		return;
	}

	// Stopped instance: read the state directly. `resume: false` — its pending tasks
	// belong to the stopped service, never to a CLI reader (correction 01 item 1); pair
	// and revoke commit, but they do not resume work either.
	const { openStewardHarness, emptyHostModels } = await import("../src/steward.ts");
	// A direct-state reader never calls a model: an empty registry, not Pi's (A10.4).
	const opened = await openStewardHarness({ dbPath: join(stateDir, "steward.sqlite"), resume: false, hostModels: emptyHostModels() });
	const context = (await import("@earendil-works/chord/context")).BACKGROUND_CONTEXT;
	try {
		if (command === "pair") {
			const code = await opened.harness.commit((tx) => import("../src/auth.ts").then((m) => m.createPairingCode(tx, Date.now())), context);
			output(json, { via, command, code: code.code, expiresAtMs: code.expiresAtMs });
			return;
		}
		if (command === "revoke") {
			const result = await opened.harness.commit((tx) => import("../src/auth.ts").then((m) => m.revokeDevice(tx, undefined, Date.now())), context);
			output(json, { via, command, ...result });
			return;
		}
		if (command === "crashes") {
			const { Crashes } = await import("../src/crash-reports.ts");
			const state = await opened.harness.snapshot(Crashes, context);
			output(json, { via, command, crashes: filterCrashReports([...(state?.reports ?? [])].reverse(), rest) });
			return;
		}
		if (command === "doctor") {
			const { AUTH } = await import("../src/auth.ts");
			const { RulesDoc } = await import("../src/documents.ts");
			const { Jobs } = await import("../src/jobs.ts");
			const { Proposals } = await import("../src/proposals.ts");
			const auth = await opened.harness.snapshot(AUTH, context);
			const rules = await opened.harness.snapshot(RulesDoc, opened.root.id, context);
			const jobs = await opened.harness.snapshot(Jobs, opened.root.id, context);
			const proposals = await opened.harness.snapshot(Proposals, opened.root.id, context);
			const checks = {
				stateDirExists: existsSync(stateDir),
				storageOpened: true,
				devicePaired: (auth?.devices ?? []).some((device) => device.revokedAtMs === undefined),
				ruleVersion: rules?.ruleVersion ?? null,
				photoWindowEnabled: rules?.photoWindow.enabled ?? false,
				jobCount: Object.keys(jobs?.jobs ?? {}).length,
				openProposals: Object.values(proposals?.proposals ?? {}).filter((p) => p.status === "open").length,
			};
			output(json, { via, command, ok: Object.values(checks).every((v) => typeof v !== "boolean" || v), checks });
			return;
		}
		// status / jobs / proposals / rules: read-only overviews
		const { RulesDoc } = await import("../src/documents.ts");
		const { Jobs } = await import("../src/jobs.ts");
		const { Proposals } = await import("../src/proposals.ts");
		const { AUTH } = await import("../src/auth.ts");
		const { Snapshots } = await import("../src/documents.ts");
		const { formatConversation, summariseSnapshot, usagePerDay } = await import("../src/observability.ts");
		const auth = await opened.harness.snapshot(AUTH, context);
		const jobs = Object.values((await opened.harness.snapshot(Jobs, opened.root.id, context))?.jobs ?? {});
		const proposals = Object.values((await opened.harness.snapshot(Proposals, opened.root.id, context))?.proposals ?? {});
		if (command === "job") {
			const jobId = rest.find((r) => !r.startsWith("--"));
			const job = jobs.find((candidate) => candidate.jobId === jobId);
			if (job === undefined) {
				console.error(`[steward] unknown job ${jobId}`);
				process.exit(1);
			}
			output(json, { via, command, job: { ...job } });
			return;
		}
		if (command === "chat") {
			const lastIndex = rest.indexOf("--last");
			const last = lastIndex >= 0 ? Number(rest[lastIndex + 1]) || 20 : 20;
			const page = await opened.root.entries({}, 1000, undefined, context);
			output(json, { via, command, lines: formatConversation([...page.items].reverse(), last) });
			return;
		}
		if (command === "snapshot") {
			const snaps = await opened.harness.snapshot(Snapshots, opened.root.id, context);
			output(json, { via, command, snapshot: summariseSnapshot(snaps?.snapshots.at(-1)) });
			return;
		}
		if (command === "usage") {
			const page = await opened.root.entries({}, 2000, undefined, context);
			output(json, { via, command, days: usagePerDay(page.items) });
			return;
		}
		if (command === "jobs") {
			output(json, { via, command, jobs: jobs.map((j) => ({ jobId: j.jobId, kind: j.kind, status: j.status, createdAtMs: j.createdAtMs, expiresAtMs: j.expiresAtMs })) });
			return;
		}
		if (command === "proposals") {
			const all = rest.includes("--all");
			const filtered = all ? proposals : proposals.filter((p) => p.status === "open");
			output(json, { via, command, proposals: filtered.map((p) => ({ proposalId: p.proposalId, status: p.status, title: p.title, bytesEstimate: p.bytesEstimate, ...(p.supersededBy === undefined ? {} : { supersededBy: p.supersededBy }) })) });
			return;
		}
		if (command === "rules") {
			output(json, { via, command, rules: await opened.harness.snapshot(RulesDoc, opened.root.id, context) });
			return;
		}
		if (command === "status") {
			output(json, {
				via,
				command,
				devicePaired: (auth?.devices ?? []).some((device) => device.revokedAtMs === undefined),
				pendingJobs: jobs.filter((j) => j.status === "queued").length,
				openProposals: proposals.filter((p) => p.status === "open").length,
			});
			return;
		}
		usage();
	} finally {
		await opened.close();
	}
}

function output(json: boolean, payload: Record<string, unknown>): void {
	if (json) {
		console.log(JSON.stringify(payload, null, 2));
		return;
	}
	for (const [key, value] of Object.entries(payload)) {
		if (key === "rules" || key === "jobs" || key === "proposals" || key === "checks" || key === "crashes") {
			console.log(`${key}:`);
			console.log(JSON.stringify(value, null, 2));
		} else {
			console.log(`${key}: ${typeof value === "object" ? JSON.stringify(value) : String(value)}`);
		}
	}
}

main().catch((error) => {
	console.error(`[steward] ${error instanceof Error ? error.message : String(error)}`);
	process.exit(1);
});
