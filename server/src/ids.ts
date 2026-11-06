import { randomBytes, randomUUID, createHash } from "node:crypto";

/** Token/token-hash helpers. The server stores only SHA-256 of any secret (protocol §2). */

export function sha256Hex(value: string): string {
	return createHash("sha256").update(value, "utf8").digest("hex");
}

/** A device token: 32 random bytes in base64url — 43 characters (protocol §2). */
export function generateDeviceToken(): string {
	return randomBytes(32).toString("base64url");
}

export function newDeviceId(): string {
	return `dev_${randomUUID().replaceAll("-", "").slice(0, 12)}`;
}

export function newId(prefix: string): string {
	return `${prefix}-${randomUUID().replaceAll("-", "").slice(0, 12)}`;
}

/** A short lowercase-hex id for worklog/SSE lines. */
export function shortHexId(): string {
	return randomBytes(4).toString("hex");
}

export function newSessionUuid(): string {
	return randomUUID();
}
