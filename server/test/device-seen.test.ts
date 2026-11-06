import { describe, expect, it } from "vitest";
import { BACKGROUND_CONTEXT } from "@earendil-works/chord/context";
import { DEVICE_SEEN_THROTTLE_MS, Device, recordDeviceStatus, touchDeviceSeen } from "../src/documents.ts";
import { openTestHarness } from "./helpers.ts";

const context = BACKGROUND_CONTEXT;
const permissions = { usageAccess: true, allFiles: false, mediaRead: true, manageMedia: false, batteryUnrestricted: true };

describe("N9: touchDeviceSeen (job polls refresh lastSeenMs, at most once per minute)", () => {
	it("writes when stale or unset, skips inside the throttle window, and never touches permissions", async () => {
		const t = await openTestHarness();
		try {
			await t.commit((tx) => recordDeviceStatus(tx, t.root.id, { deviceId: "dev_x", appVersion: "0.2.2", permissions, nowMs: 1_000_000 }));
			const at = (nowMs: number) => t.commit((tx) => touchDeviceSeen(tx, t.root.id, "dev_x", nowMs));
			const seen = async () => (await t.harness.snapshot(Device, t.root.id, context))!;

			expect(await at(1_000_000 + DEVICE_SEEN_THROTTLE_MS - 1)).toBe(false);
			expect((await seen()).lastSeenMs).toBe(1_000_000);

			expect(await at(1_000_000 + DEVICE_SEEN_THROTTLE_MS)).toBe(true);
			const after = await seen();
			expect(after.lastSeenMs).toBe(1_000_000 + DEVICE_SEEN_THROTTLE_MS);
			expect(after.permissions).toEqual(permissions);
			expect(after.appVersion).toBe("0.2.2");

			// A poll 25 s later is throttled again.
			expect(await at(1_000_000 + DEVICE_SEEN_THROTTLE_MS + 25_000)).toBe(false);
		} finally {
			await t.close();
		}
	});

	it("writes on a device document that has never seen a status post", async () => {
		const t = await openTestHarness();
		try {
			expect(await t.commit((tx) => touchDeviceSeen(tx, t.root.id, "dev_y", 5_000))).toBe(true);
			const doc = (await t.harness.snapshot(Device, t.root.id, context))!;
			expect(doc).toMatchObject({ deviceId: "dev_y", lastSeenMs: 5_000 });
			expect(doc.permissions).toBeUndefined();
		} finally {
			await t.close();
		}
	});
});
