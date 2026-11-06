import { describe, expect, it } from "vitest";
import {
	AUTH,
	authenticateToken,
	createPairingCode,
	mintPairingCode,
	normalisePairingCode,
	pairRequest,
	revokeDevice,
	type AuthState,
} from "../src/auth.ts";
import { generateDeviceToken } from "../src/ids.ts";
import { openTestHarness } from "./helpers.ts";

const CODE_ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";

describe("pairing codes", () => {
	it("generates an 8-character code from the safe alphabet, shown as XXXX-XXXX", () => {
		const code = mintPairingCode();
		const compact = code.replace("-", "");
		expect(code).toMatch(/^[^-]{4}-[^-]{4}$/);
		expect(compact).toHaveLength(8);
		for (const ch of compact) expect(CODE_ALPHABET).toContain(ch);
	});

	it("normalises case and strips hyphens and spaces", () => {
		expect(normalisePairingCode("k7q4-9mzd")).toBe("K7Q49MZD");
		expect(normalisePairingCode(" K7 Q4 9M ZD ")).toBe("K7Q49MZD");
	});
});

describe("pairRequest", () => {
	it("pairs a valid unused code once, hashes the token, and revokes the previous device", async () => {
		const t = await openTestHarness();
		try {
			const now = 1_790_000_000_000;
			const code1 = await t.commit((tx) => createPairingCode(tx, now).then((r) => r.code));
			const firstPair = await pairRequest(t.commit, {
				code: code1!,
				deviceName: "OnePlus Nord 2T",
				address: "10.0.0.1",
				nowMs: now,
			});
			expect(firstPair.deviceId).toMatch(/^dev_/);
			expect(firstPair.token).toHaveLength(43);
			const stored: AuthState | undefined = await t.snapshot(AUTH);
			expect(stored?.devices.filter((d) => d.revokedAtMs === undefined)).toHaveLength(1);
			expect(stored?.devices[0]!.tokenHash).not.toBe(firstPair.token);
			expect(stored?.devices[0]!.tokenHash).toHaveLength(64);
			expect(stored?.codes[0]!.usedAtMs).toBe(now);

			const code2 = await t.commit((tx) => createPairingCode(tx, now).then((r) => r.code));
			const second = await pairRequest(t.commit, {
				code: code2!,
				deviceName: "OnePlus Nord 2T",
				address: "10.0.0.2",
				nowMs: now,
			});
			const after: AuthState | undefined = await t.snapshot(AUTH);
			expect(after?.devices.filter((d) => d.revokedAtMs === undefined)).toHaveLength(1);
			expect(after?.devices.find((d) => d.revokedAtMs === undefined)?.deviceId).toBe(second.deviceId);
			await expect(
				t.commit((tx) => authenticateToken(tx, firstPair.token, now)),
			).rejects.toMatchObject({ code: "TOKEN_REVOKED" });
			await expect(t.commit((tx) => authenticateToken(tx, second.token, now))).resolves.toBe(second.deviceId);
		} finally {
			await t.close();
		}
	});

	it("rejects an expired, used or wrong code with PAIRING_CODE_INVALID and counts the failure", async () => {
		const t = await openTestHarness();
		try {
			const now = 1_790_000_000_000;
			await expect(
				pairRequest(t.commit, { code: "AAAA-BBBB", deviceName: "x", address: "10.0.0.1", nowMs: now }),
			).rejects.toMatchObject({ code: "PAIRING_CODE_INVALID", status: 400 });

			const valid = await t.commit((tx) => createPairingCode(tx, now).then((r) => r.code));
			await expect(
				pairRequest(t.commit, {
					code: valid!,
					deviceName: "x",
					address: "10.0.0.1",
					nowMs: now + 11 * 60_000,
				}),
			).rejects.toMatchObject({ code: "PAIRING_CODE_INVALID" });

			const reuse = await t.commit((tx) => createPairingCode(tx, now).then((r) => r.code));
			await pairRequest(t.commit, { code: reuse!, deviceName: "x", address: "10.0.0.1", nowMs: now });
			await expect(
				pairRequest(t.commit, { code: reuse!, deviceName: "x", address: "10.0.0.1", nowMs: now + 1000 }),
			).rejects.toMatchObject({ code: "PAIRING_CODE_INVALID" });

			const state: AuthState | undefined = await t.snapshot(AUTH);
			expect(state?.failedAttempts.filter((f) => f.address === "10.0.0.1").length).toBe(3);
		} finally {
			await t.close();
		}
	});

	it("rate limits after 5 failed attempts per address per 10 minutes, then recovers", async () => {
		const t = await openTestHarness();
		try {
			const now = 1_790_000_000_000;
			for (let i = 0; i < 5; i++) {
				await expect(
					pairRequest(t.commit, {
						code: `WRONG-CD${i}`,
						deviceName: "x",
						address: "10.0.0.9",
						nowMs: now + i,
					}),
				).rejects.toMatchObject({ code: "PAIRING_CODE_INVALID" });
			}
			await expect(
				pairRequest(t.commit, { code: "AAAA-BBBB", deviceName: "x", address: "10.0.0.9", nowMs: now + 5000 }),
			).rejects.toMatchObject({ code: "RATE_LIMITED", status: 429, retryAfterSeconds: expect.any(Number) });

			// A different address is unaffected.
			await expect(
				pairRequest(t.commit, { code: "AAAA-BBBB", deviceName: "x", address: "10.0.0.8", nowMs: now + 5000 }),
			).rejects.toMatchObject({ code: "PAIRING_CODE_INVALID" });

			// After the window the limited address may try again.
			await expect(
				pairRequest(t.commit, {
					code: "AAAA-BBBB",
					deviceName: "x",
					address: "10.0.0.9",
					nowMs: now + 10 * 60_000 + 6000,
				}),
			).rejects.toMatchObject({ code: "PAIRING_CODE_INVALID" });
		} finally {
			await t.close();
		}
	});
});

describe("tokens", () => {
	it("authenticates a valid token, rejects unknown ones, and revocation sticks", async () => {
		const t = await openTestHarness();
		try {
			const now = 1_790_000_000_000;
			const code = await t.commit((tx) => createPairingCode(tx, now).then((r) => r.code));
			const pair = await pairRequest(t.commit, {
				code: code!,
				deviceName: "OnePlus Nord 2T",
				address: "10.0.0.1",
				nowMs: now,
			});
			await expect(t.commit((tx) => authenticateToken(tx, pair.token, now))).resolves.toBe(pair.deviceId);
			await expect(
				t.commit((tx) => authenticateToken(tx, generateDeviceToken(), now)),
			).rejects.toMatchObject({ code: "UNAUTHORIZED", status: 401 });
			await t.commit((tx) => revokeDevice(tx, pair.deviceId, now));
			await expect(
				t.commit((tx) => authenticateToken(tx, pair.token, now)),
			).rejects.toMatchObject({ code: "TOKEN_REVOKED", status: 401 });
		} finally {
			await t.close();
		}
	});
});
