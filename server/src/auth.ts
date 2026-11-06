/**
 * Pairing and auth (docs/protocol.md §2): single-use 10-minute codes, hashed tokens
 * (only SHA-256 stored), 5 failed attempts per 10 minutes per client address, one paired
 * device (a new pairing revokes the old token), and revocation.
 */
import { defineDoc, type Tx } from "@earendil-works/pi-durable";
import { sha256Hex, generateDeviceToken, newDeviceId } from "./ids.ts";
import { ApiError, rateLimited } from "./errors.ts";
import { periodicCheckpoint } from "./checkpoint.ts";

const CODE_ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";
export const CODE_TTL_MS = 10 * 60_000;
const RATE_WINDOW_MS = 10 * 60_000;
const RATE_MAX_FAILURES = 5;
/** Prune ledger entries older than this so the document stays bounded. */
const LEDGER_TTL_MS = 24 * 60 * 60_000;

export type PairedDevice = {
	deviceId: string;
	/** SHA-256 hex of the base64url token; the token itself is never stored. */
	tokenHash: string;
	deviceName: string;
	pairedAtMs: number;
	revokedAtMs?: number;
};

export type PairingCode = {
	codeHash: string;
	createdAtMs: number;
	expiresAtMs: number;
	usedAtMs?: number;
	/** Filled only after a successful pair, for the doctor's output. */
	deviceId?: string;
};

export type FailedAttempt = { atMs: number; address: string };

export type AuthState = {
	devices: PairedDevice[];
	codes: PairingCode[];
	failedAttempts: FailedAttempt[];
};

export const AUTH = defineDoc<AuthState>({
	kind: "steward.auth",
	version: 1,
	// Pi Durable 1.4.0 requires an explicit checkpoint policy (no default).
	checkpointWhen: periodicCheckpoint(),
	scope: "session",
	initial: (): AuthState => ({ devices: [], codes: [], failedAttempts: [] }),
});

/** Uppercase, hyphens and spaces removed — the server ignores case and hyphens. */
export function normalisePairingCode(code: string): string {
	return code.toUpperCase().replaceAll("-", "").replaceAll(" ", "");
}

/** A random code in the safe alphabet, shown as `XXXX-XXXX` (pure). */
export function mintPairingCode(): string {
	let compact = "";
	for (let i = 0; i < 8; i++) {
		compact += CODE_ALPHABET[crypto.getRandomValues(new Uint8Array(1))[0]! % CODE_ALPHABET.length];
	}
	return `${compact.slice(0, 4)}-${compact.slice(4)}`;
}

/** Mint a pairing code valid for 10 minutes and store its hash. */
export async function createPairingCode(tx: Tx, nowMs: number): Promise<{ code: string; expiresAtMs: number }> {
	const code = mintPairingCode();
	const expiresAtMs = nowMs + CODE_TTL_MS;
	const state = await tx.doc(AUTH);
	state.codes.push({ codeHash: sha256Hex(normalisePairingCode(code)), createdAtMs: nowMs, expiresAtMs });
	return { code, expiresAtMs };
}

function prune(state: AuthState, nowMs: number): void {
	state.codes = state.codes.filter((c) => c.expiresAtMs > nowMs - LEDGER_TTL_MS);
	state.failedAttempts = state.failedAttempts.filter((f) => f.atMs > nowMs - LEDGER_TTL_MS);
}

export type PairInput = { code: string; deviceName: string; address: string; nowMs: number };
export type PairResult = { deviceId: string; token: string };

/**
 * Pair one device. Throws `PAIRING_CODE_INVALID` (400) for an expired, used or wrong code
 * — never saying which — and `RATE_LIMITED` (429) after 5 failures in 10 minutes for the
 * client address. Pairing a new device revokes the previous token.
 *
 * Failure counting is a separate, committed step (a thrown commit rolls back, so counting
 * inside the pairing commit would lose the failure): callers use `pairRequest`, which
 * checks the limit, pairs, and records failures in their own commits.
 */
export async function pairDevice(tx: Tx, input: PairInput): Promise<PairResult> {
	const state = await tx.doc(AUTH);

	const normalised = normalisePairingCode(input.code);
	const codeHash = sha256Hex(normalised);
	const record = state.codes.find((c) => c.codeHash === codeHash);
	const valid =
		record !== undefined &&
		record.usedAtMs === undefined &&
		record.expiresAtMs > input.nowMs &&
		normalised.length === 8 &&
		[...normalised].every((ch) => CODE_ALPHABET.includes(ch));
	if (!valid) {
		throw new ApiError(400, "PAIRING_CODE_INVALID", "the pairing code is not valid");
	}

	const token = generateDeviceToken();
	const deviceId = newDeviceId();
	const nowMs = input.nowMs;
	// One paired device in the MVP: revoke everything else (revoked devices stay in the
	// document so their tokens still answer TOKEN_REVOKED, not UNAUTHORIZED).
	for (const device of state.devices) {
		if (device.revokedAtMs === undefined) device.revokedAtMs = nowMs;
	}
	state.devices.push({ deviceId, tokenHash: sha256Hex(token), deviceName: input.deviceName, pairedAtMs: nowMs });
	record!.usedAtMs = nowMs;
	record!.deviceId = deviceId;
	return { deviceId, token };
}

/** The commit-binder shape `pairRequest` needs (Harness.commit or a test's bound commit). */
export type CommitFn = <T>(change: (tx: Tx) => T | Promise<T>) => Promise<T>;

/** Record one failed pairing attempt for the rate limit (its own commit, so it survives). */
export async function recordPairFailure(tx: Tx, address: string, nowMs: number): Promise<void> {
	const state = await tx.doc(AUTH);
	prune(state, nowMs);
	state.failedAttempts.push({ atMs: nowMs, address });
}

/** Throw RATE_LIMITED when this address has hit the failed-attempt limit. */
export async function assertPairAllowed(tx: Tx, address: string, nowMs: number): Promise<void> {
	const state = await tx.doc(AUTH);
	prune(state, nowMs);
	const recent = state.failedAttempts.filter(
		(f) => f.address === address && f.atMs > nowMs - RATE_WINDOW_MS,
	);
	if (recent.length >= RATE_MAX_FAILURES) {
		const oldest = Math.min(...recent.map((f) => f.atMs));
		const retryAfterSeconds = Math.max(1, Math.ceil((oldest + RATE_WINDOW_MS - nowMs) / 1000));
		throw rateLimited(retryAfterSeconds);
	}
}

/** The full pairing flow for one HTTP request: limit check → pair → record failures. */
export async function pairRequest(commit: CommitFn, input: PairInput): Promise<PairResult> {
	await commit((tx) => assertPairAllowed(tx, input.address, input.nowMs));
	try {
		return await commit((tx) => pairDevice(tx, input));
	} catch (error) {
		if (error instanceof ApiError && error.code === "PAIRING_CODE_INVALID") {
			await commit((tx) => recordPairFailure(tx, input.address, input.nowMs));
		}
		throw error;
	}
}

/**
 * Resolve a bearer token to its device id. Throws `TOKEN_REVOKED` (401) for a revoked
 * device and `UNAUTHORIZED` (401) for an unknown token.
 */
export async function authenticateToken(tx: Tx, token: string, nowMs: number): Promise<string> {
	const state = await tx.doc(AUTH);
	return authenticateTokenState(state, token, nowMs);
}

/** Read-only variant over an already-loaded auth state (the HTTP auth middleware). */
export function authenticateTokenState(state: AuthState, token: string, nowMs: number): string {
	const tokenHash = sha256Hex(token);
	const device = state.devices.find((d) => d.tokenHash === tokenHash);
	if (device === undefined) throw new ApiError(401, "UNAUTHORIZED", "missing or invalid device token");
	if (device.revokedAtMs !== undefined && device.revokedAtMs <= nowMs) {
		throw new ApiError(401, "TOKEN_REVOKED", "this device token has been revoked");
	}
	return device.deviceId;
}

/** Revoke a device by id (or the only active device when omitted). */
export async function revokeDevice(
	tx: Tx,
	deviceId: string | undefined,
	nowMs: number,
): Promise<{ revoked: string | undefined }> {
	const state = await tx.doc(AUTH);
	const active = state.devices.filter((d) => d.revokedAtMs === undefined);
	const target = deviceId === undefined ? (active[0]?.deviceId ?? undefined) : deviceId;
	for (const device of state.devices) {
		if (device.deviceId === target && device.revokedAtMs === undefined) device.revokedAtMs = nowMs;
	}
	return { revoked: target };
}

/** The active device, for status and doctor output. */
export function activeDevice(state: AuthState, nowMs: number): PairedDevice | undefined {
	return state.devices.find((d) => d.revokedAtMs === undefined || d.revokedAtMs > nowMs);
}
