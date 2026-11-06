/**
 * Crash self-reports (docs/protocol.md A5). The app stores its own uncaught exception
 * on the phone and posts it on the next start, so a crash reaches the server without a
 * human copying a stack trace (no ADB). A crash can come before pairing, so the route
 * takes no token; a valid one only attributes the report. The limits bound what an
 * unauthenticated client can store: a small body, a per-address rate, and a global cap,
 * with only the newest reports kept.
 */
import { defineDoc, type Tx } from "@earendil-works/pi-durable";
import { badRequest, rateLimited } from "./errors.ts";
import { newId } from "./ids.ts";
import { periodicCheckpoint } from "./checkpoint.ts";

export const CRASH_LIMITS = {
	bodyBytes: 64 * 1024,
	stackTraceChars: 32_768,
	exceptionChars: 512,
	threadChars: 128,
	appVersionChars: 64,
	modelChars: 64,
	siteChars: 128,
	perAddressPerHour: 10,
	globalPerHour: 60,
	keep: 50,
} as const;

const HOUR_MS = 60 * 60_000;

export type CrashDevice = { model?: string; sdk?: number };

export type CrashSeverity = "crash" | "error";

export type CrashReport = {
	reportId: string;
	receivedAtMs: number;
	/** The paired device when a valid token rode along. */
	device?: CrashDevice;
	deviceId?: string;
	appVersion: string;
	crashedAtMs: number;
	thread: string;
	exception: string;
	stackTrace: string;
	/** A5/O5: `crash` (default) or `error`; an `error` also carries the `site` label. */
	severity: CrashSeverity;
	site?: string;
};

export type CrashInput = Omit<CrashReport, "reportId" | "receivedAtMs" | "deviceId">;

export type CrashState = {
	reports: CrashReport[];
	/** Accepted reports in the last hour, for the rate limits. */
	accepted: Array<{ atMs: number; address: string }>;
};

export const Crashes = defineDoc<CrashState>({
	kind: "steward.crashes",
	version: 1,
	// Pi Durable 1.4.0 requires an explicit checkpoint policy (no default).
	checkpointWhen: periodicCheckpoint(),
	scope: "session",
	initial: (): CrashState => ({ reports: [], accepted: [] }),
});

function text(body: Record<string, unknown>, field: string, max: number, required: boolean): string | undefined {
	const value = body[field];
	if (value === undefined && !required) return undefined;
	if (typeof value !== "string" || (required && value.length === 0) || value.length > max) {
		throw badRequest(`${field} must be a ${required ? "non-empty " : ""}string of at most ${max} characters`);
	}
	return value;
}

/** Validate a report body; unknown fields are dropped. */
export function parseCrashBody(body: Record<string, unknown>): CrashInput {
	const crashedAtMs = body.crashedAtMs;
	if (typeof crashedAtMs !== "number" || !Number.isFinite(crashedAtMs) || crashedAtMs < 0) {
		throw badRequest("crashedAtMs must be epoch milliseconds");
	}
	const input: CrashInput = {
		appVersion: text(body, "appVersion", CRASH_LIMITS.appVersionChars, true)!,
		crashedAtMs,
		thread: text(body, "thread", CRASH_LIMITS.threadChars, true)!,
		exception: text(body, "exception", CRASH_LIMITS.exceptionChars, true)!,
		stackTrace: text(body, "stackTrace", CRASH_LIMITS.stackTraceChars, true)!,
		severity: "crash",
	};
	if (body.severity !== undefined) {
		if (body.severity !== "crash" && body.severity !== "error") {
			throw badRequest("severity must be crash or error");
		}
		input.severity = body.severity;
	}
	if (input.severity === "error") {
		const site = text(body, "site", CRASH_LIMITS.siteChars, true);
		if (site === undefined) throw badRequest("site is required for an error report");
		input.site = site;
	}
	if (body.device !== undefined) {
		const device = body.device as Record<string, unknown>;
		if (device === null || typeof device !== "object" || Array.isArray(device)) {
			throw badRequest("device must be an object");
		}
		const parsed: CrashDevice = {};
		const model = text(device, "model", CRASH_LIMITS.modelChars, false);
		if (model !== undefined) parsed.model = model;
		if (device.sdk !== undefined) {
			if (typeof device.sdk !== "number" || !Number.isInteger(device.sdk)) throw badRequest("device.sdk must be an integer");
			parsed.sdk = device.sdk;
		}
		input.device = parsed;
	}
	return input;
}

/** Append a report, keeping only the newest CRASH_LIMITS.keep (pure). */
export function appendCrash(reports: CrashReport[], report: CrashReport): CrashReport[] {
	return [...reports, report].slice(-CRASH_LIMITS.keep);
}

/** Throw RATE_LIMITED when this address, or all clients together, sent too many this hour. */
function assertCrashAllowed(state: CrashState, address: string, nowMs: number): void {
	state.accepted = state.accepted.filter((a) => a.atMs > nowMs - HOUR_MS);
	const fromAddress = state.accepted.filter((a) => a.address === address);
	const limit = (window: Array<{ atMs: number }>, max: number): void => {
		if (window.length < max) return;
		const oldest = Math.min(...window.map((a) => a.atMs));
		throw rateLimited(Math.max(1, Math.ceil((oldest + HOUR_MS - nowMs) / 1000)));
	};
	limit(fromAddress, CRASH_LIMITS.perAddressPerHour);
	limit(state.accepted, CRASH_LIMITS.globalPerHour);
}

/** Record one report inside the caller's commit; throws RATE_LIMITED before storing anything. */
export async function recordCrash(
	tx: Tx,
	input: CrashInput,
	meta: { address: string; deviceId: string | undefined; nowMs: number },
): Promise<CrashReport> {
	const state = await tx.doc(Crashes);
	assertCrashAllowed(state, meta.address, meta.nowMs);
	state.accepted.push({ atMs: meta.nowMs, address: meta.address });
	const report: CrashReport = JSON.parse(
		JSON.stringify({
			reportId: newId("cr"),
			receivedAtMs: meta.nowMs,
			...(meta.deviceId === undefined ? {} : { deviceId: meta.deviceId }),
			...input,
		}),
	) as CrashReport;
	state.reports = appendCrash(JSON.parse(JSON.stringify(state.reports)) as CrashReport[], report);
	return report;
}

/** The owner's ping: the exception class and version only, never the trace or message.
 * Only a `crash` pings (A5/O5): an `error` report is an observability record, not an alarm. */
export function crashNotification(report: CrashReport): { dedupeKey: string; title: string; body: string } | undefined {
	if (report.severity === "error") return undefined;
	return {
		dedupeKey: `crash:${report.appVersion}:${report.exception}`,
		title: "Phone Steward app crash",
		body: `The app (${report.appVersion}) crashed with ${report.exception}. See bin/steward crashes.`,
	};
}
