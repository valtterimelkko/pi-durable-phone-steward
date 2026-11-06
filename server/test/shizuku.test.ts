/**
 * protocol A10.2 (server half): the never-touch list enforced in code, package-name
 * validation, the `shell_info` / `force_stop` job params, `trim_caches` / `disable_app` /
 * `enable_app` proposal items, the device `permissions.shizuku` and `stewardTrash`
 * passthrough, and the weekly batch preferring `trim_caches` when Shizuku is ready.
 * Package names are invented (or well-known system ones, which carry no personal data).
 */
import { describe, expect, it } from "vitest";
import { BACKGROUND_CONTEXT } from "@earendil-works/chord/context";
import { Proposals } from "../src/proposals.ts";
import { Jobs } from "../src/jobs.ts";
import { Device, recordDeviceStatus, type DevicePermissions } from "../src/documents.ts";
import { proposeCleanup } from "../src/tools.ts";
import {
	isNeverTouch,
	validatePackageName,
	validateShellInfo,
	validateForceStop,
	shizukuState,
	STEWARD_PACKAGE,
} from "../src/shizuku.ts";
import { summariseToolArgs, summariseToolResult } from "../src/chat-tool-summary.ts";
import { openTestHarness, type TestHarness } from "./helpers.ts";
import { buildCacheBatch } from "../src/weekly-cache.ts";

const context = BACKGROUND_CONTEXT;
const MIB = 1024 * 1024;

async function propose(t: TestHarness, items: unknown[]): Promise<{ proposalId: string }> {
	return JSON.parse(await t.commit((tx) => proposeCleanup(tx, t.root.id, { title: "t", reason: "r", items }, Date.now()))) as { proposalId: string };
}
async function stored(t: TestHarness, id: string) {
	return (await t.harness.snapshot(Proposals, t.root.id, context))?.proposals[id]!;
}

describe("package names and the never-touch list", () => {
	it("accepts ordinary package names and refuses anything else", () => {
		expect(validatePackageName("com.example.maps")).toBe("com.example.maps");
		expect(validatePackageName("org.foo_bar.app2")).toBe("org.foo_bar.app2");
		for (const bad of ["", "nodots", "com..x", "com.x;rm -rf", "com.x y", "../x.y", "com.x\n", "1com.x", "com.x/y", 5, null, undefined]) {
			expect(() => validatePackageName(bad as never), String(bad)).toThrow(/package/i);
		}
		expect(() => validatePackageName("a".repeat(300) + ".b")).toThrow(/package/i);
	});

	it("never-touch covers the launcher, system UI, phone, settings, Google Play services, keyboards, installer and the steward", () => {
		for (const pkg of [
			STEWARD_PACKAGE,
			"android",
			"com.android.systemui",
			"com.android.phone",
			"com.android.settings",
			"com.google.android.gms",
			"com.google.android.gms.supervision",
			"com.google.android.gsf",
			"com.android.vending",
			"net.oneplus.launcher",
			"com.android.launcher3",
			"com.google.android.inputmethod.latin",
			"com.android.inputmethod.latin",
			"com.android.providers.contacts",
			"com.android.server.telecom",
			"com.android.packageinstaller",
			"com.google.android.permissioncontroller",
			"moe.shizuku.privileged.api",
			"COM.ANDROID.SYSTEMUI",
			// OxygenOS 13 (ColorOS base) and Qualcomm system services (parent review)
			"com.oplus.securitypermission",
			"com.coloros.safecenter",
			"com.qualcomm.qti.telephonyservice",
			"com.qti.qualcomm.datastatusnotification",
		]) {
			expect(isNeverTouch(pkg), pkg).toBe(true);
		}
		expect(STEWARD_PACKAGE).toBe("com.example.phonesteward");
	});

	it("does not block ordinary bloatware or look-alikes", () => {
		for (const pkg of ["com.example.maps", "com.facebook.katana", "com.android.egg", "com.googlefoo.app", "com.example.systemui2", "com.example.gms", "com.oplusfoo.app", "com.oneplus.gallery"]) {
			expect(isNeverTouch(pkg), pkg).toBe(false);
		}
	});
});

describe("job params", () => {
	it("shell_info: op packages or app_sizes only", () => {
		expect(validateShellInfo({ op: "packages" })).toEqual({ op: "packages" });
		expect(validateShellInfo({ op: "app_sizes" })).toEqual({ op: "app_sizes" });
		expect(() => validateShellInfo({ op: "rm -rf /" })).toThrow(/op/);
		expect(() => validateShellInfo({})).toThrow(/op/);
		// whatever else the model sends is dropped: params are fixed templates
		expect(validateShellInfo({ op: "packages", command: "ls" } as never)).toEqual({ op: "packages" });
	});

	it("force_stop: a valid package that is not on the deny list", () => {
		expect(validateForceStop({ packageName: "com.example.maps" })).toEqual({ packageName: "com.example.maps" });
		expect(() => validateForceStop({ packageName: STEWARD_PACKAGE })).toThrow(/never|steward/i);
		expect(() => validateForceStop({ packageName: "com.android.systemui" })).toThrow(/never/i);
		expect(() => validateForceStop({ packageName: "bad name" })).toThrow(/package/i);
		expect(() => validateForceStop({})).toThrow(/package/i);
	});
});

describe("proposal items trim_caches, disable_app, enable_app", () => {
	it("trim_caches takes the clear_all_caches target shape and sums the estimate when none is given", async () => {
		const t = await openTestHarness();
		try {
			const apps = [
				{ packageName: "com.example.a", label: "A", cacheBytes: 300 * MIB },
				{ packageName: "com.example.b", label: "B", cacheBytes: 100 * MIB },
			];
			const created = await propose(t, [{ kind: "trim_caches", target: { apps }, bytesEstimate: 400 * MIB }]);
			const proposal = await stored(t, created.proposalId);
			expect(proposal.items[0]!.kind).toBe("trim_caches");
			expect(proposal.items[0]!.target).toEqual({ apps });
			await expect(propose(t, [{ kind: "trim_caches", target: { apps: [] } }])).rejects.toThrow(/trim_caches/);
		} finally {
			await t.close();
		}
	});

	it("trim_caches supersedes an earlier open cache proposal, and clear_all_caches supersedes trim_caches (one family)", async () => {
		const t = await openTestHarness();
		try {
			const apps = [{ packageName: "com.example.a", label: "A", cacheBytes: 5 }];
			const first = await propose(t, [{ kind: "clear_all_caches", target: { apps } }]);
			const second = await propose(t, [{ kind: "trim_caches", target: { apps } }]);
			expect((await stored(t, first.proposalId)).status).toBe("superseded");
			const third = await propose(t, [{ kind: "clear_all_caches", target: { apps } }]);
			expect((await stored(t, second.proposalId)).status).toBe("superseded");
			expect((await stored(t, third.proposalId)).status).toBe("open");
		} finally {
			await t.close();
		}
	});

	it("disable_app and enable_app need packageName and label; a bad package is refused", async () => {
		const t = await openTestHarness();
		try {
			const ok = await propose(t, [
				{ kind: "disable_app", target: { packageName: "com.example.bloat", label: "Bloat" }, bytesEstimate: 0 },
				{ kind: "enable_app", target: { packageName: "com.example.back", label: "Back" } },
			]);
			expect((await stored(t, ok.proposalId)).items.map((item) => item.kind)).toEqual(["disable_app", "enable_app"]);
			await expect(propose(t, [{ kind: "disable_app", target: { packageName: "com.example.bloat" } }])).rejects.toThrow(/label/);
			await expect(propose(t, [{ kind: "enable_app", target: { packageName: "x y", label: "X" } }])).rejects.toThrow(/package/i);
		} finally {
			await t.close();
		}
	});

	it("disable_app is refused for every never-touch package, naming it; enable_app is not", async () => {
		const t = await openTestHarness();
		try {
			for (const pkg of [STEWARD_PACKAGE, "com.android.systemui", "com.google.android.gms", "net.oneplus.launcher", "com.android.settings"]) {
				await expect(propose(t, [{ kind: "disable_app", target: { packageName: pkg, label: "X" } }]), pkg).rejects.toThrow(/never-touch|never touch/i);
			}
			expect(Object.keys((await t.harness.snapshot(Proposals, t.root.id, context))?.proposals ?? {})).toEqual([]);
			// re-enabling a protected package is harmless and is allowed
			await propose(t, [{ kind: "enable_app", target: { packageName: "com.android.systemui", label: "UI" } }]);
		} finally {
			await t.close();
		}
	});

	it("disable_app and enable_app share a family: a newer one supersedes the older open one, not other kinds", async () => {
		const t = await openTestHarness();
		try {
			const first = await propose(t, [{ kind: "disable_app", target: { packageName: "com.example.one", label: "One" } }]);
			const cache = await propose(t, [{ kind: "trim_caches", target: { apps: [{ packageName: "com.example.a", label: "A", cacheBytes: 1 }] } }]);
			const second = await propose(t, [{ kind: "disable_app", target: { packageName: "com.example.two", label: "Two" } }]);
			expect((await stored(t, first.proposalId)).status).toBe("superseded");
			expect((await stored(t, cache.proposalId)).status).toBe("open");
			expect((await stored(t, second.proposalId)).status).toBe("open");
		} finally {
			await t.close();
		}
	});
});

describe("device status passthrough", () => {
	const base: DevicePermissions = { usageAccess: true, allFiles: true, mediaRead: true, manageMedia: false, batteryUnrestricted: true };

	it("stores permissions.shizuku and stewardTrash, and shizukuState reads the state", async () => {
		const t = await openTestHarness();
		try {
			expect(shizukuState(undefined)).toBe("absent");
			await t.commit((tx) =>
				recordDeviceStatus(tx, t.root.id, {
					deviceId: "dev_x",
					appVersion: "0.3.0",
					permissions: { ...base, shizuku: "ready" },
					stewardTrash: { bytes: 1234, oldestMs: 1_790_000_000_000 },
					nowMs: 5,
				}),
			);
			const doc = (await t.harness.snapshot(Device, t.root.id, context))!;
			expect(doc.permissions?.shizuku).toBe("ready");
			expect(doc.stewardTrash).toEqual({ bytes: 1234, oldestMs: 1_790_000_000_000 });
			expect(shizukuState(doc)).toBe("ready");
			// a later report without them keeps the last stewardTrash; an unknown shizuku value reads as absent
			await t.commit((tx) => recordDeviceStatus(tx, t.root.id, { deviceId: "dev_x", permissions: { ...base, shizuku: "bogus" as never }, nowMs: 6 }));
			const later = (await t.harness.snapshot(Device, t.root.id, context))!;
			expect(later.stewardTrash).toEqual({ bytes: 1234, oldestMs: 1_790_000_000_000 });
			expect(shizukuState(later)).toBe("absent");
		} finally {
			await t.close();
		}
	});

	it("drops a malformed stewardTrash and accepts oldestMs null", async () => {
		const t = await openTestHarness();
		try {
			await t.commit((tx) => recordDeviceStatus(tx, t.root.id, { deviceId: "d", stewardTrash: { bytes: "lots" } as never, nowMs: 1 }));
			expect((await t.harness.snapshot(Device, t.root.id, context))!.stewardTrash).toBeUndefined();
			await t.commit((tx) => recordDeviceStatus(tx, t.root.id, { deviceId: "d", stewardTrash: { bytes: 0, oldestMs: null }, nowMs: 2 }));
			expect((await t.harness.snapshot(Device, t.root.id, context))!.stewardTrash).toEqual({ bytes: 0, oldestMs: null });
		} finally {
			await t.close();
		}
	});
});

describe("the weekly cache batch prefers trim_caches when Shizuku is ready", () => {
	it("buildCacheBatch words the reason for the no-dialog route when asked", () => {
		const apps = [{ packageName: "com.example.a", label: "Big", appBytes: 1, dataBytes: 1, cacheBytes: 500 * MIB, isSystem: false }];
		const plain = buildCacheBatch(apps, "2026-10-08");
		const shizuku = buildCacheBatch(apps, "2026-10-08", { viaShizuku: true });
		expect(plain!.reason).toMatch(/One Android confirmation/);
		expect(shizuku!.reason).not.toMatch(/Android confirmation/);
		expect(shizuku!.reason).toMatch(/no Android dialog/i);
		expect(shizuku!.apps).toEqual(plain!.apps);
	});
});

describe("steward-facing summaries", () => {
	it("phone_shell_info, phone_force_stop and the new item kinds never show a package name", () => {
		expect(summariseToolArgs("phone_shell_info", { op: "packages" })).toBe("op packages");
		expect(summariseToolArgs("phone_force_stop", { packageName: "com.example.secret" })).toBe("1 package");
		expect(summariseToolResult("phone_shell_info", JSON.stringify({ status: "queued", jobId: "pj-abc" }), false)).toBe("queued job pj-abc");
		expect(summariseToolResult("phone_force_stop", JSON.stringify({ status: "queued", jobId: "pj-abc" }), false)).toBe("queued job pj-abc");
		expect(summariseToolArgs("propose_cleanup", { items: [{ kind: "disable_app", target: {} }, { kind: "trim_caches", target: {} }] })).toBe("2 items: 1 disable_app, 1 trim_caches");
	});
});

void Jobs;
