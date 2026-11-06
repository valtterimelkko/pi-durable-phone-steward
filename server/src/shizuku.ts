/**
 * Protocol A10.2, server half: Shizuku-backed tools and proposal kinds.
 *
 * The model never sends shell text. Every job carries fixed params (`shell_info`: an `op`;
 * `force_stop`: a validated package name) and the app maps them to fixed command templates.
 * The never-touch list lives here and is enforced in code before anything is queued or
 * proposed ( risk: launcher, system UI, phone, settings, Google Play services,
 * the keyboard, the installer, the steward). The phone enforces it again, plus "the
 * keyboard in use", which only the phone can know.
 */
import type { JsonObject } from "@earendil-works/pi-durable";
import { badRequest } from "./errors.ts";
import type { DeviceState } from "./documents.ts";

/** The steward app's own package (app/build.gradle.kts `applicationId`). */
export const STEWARD_PACKAGE = "com.example.phonesteward";

/** Android package names: dotted segments of letters, digits and underscores. */
const PACKAGE_RE = /^[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z][A-Za-z0-9_]*)+$/;
const PACKAGE_MAX = 255;

/** A package name the app would accept, or a plain error naming the rule. */
export function validatePackageName(value: unknown): string {
	if (typeof value !== "string" || value.length === 0 || value.length > PACKAGE_MAX || !PACKAGE_RE.test(value)) {
		throw badRequest("packageName must be an Android package name such as com.example.app (letters, digits, underscores and dots)");
	}
	return value;
}

/** Packages that are never disabled or force-stopped. Compared in lower case. */
const NEVER_TOUCH_EXACT: ReadonlySet<string> = new Set([
	STEWARD_PACKAGE,
	"android",
	"moe.shizuku.privileged.api",
	"com.android.vending",
	"com.google.android.gsf",
	"com.android.shell",
	"com.android.phone",
	"com.android.settings",
	"com.android.packageinstaller",
	"com.google.android.packageinstaller",
	"com.android.permissioncontroller",
	"com.google.android.permissioncontroller",
	"com.android.bluetooth",
	"com.android.nfc",
	"com.android.keychain",
	"com.android.documentsui",
	"com.google.android.documentsui",
	"com.android.externalstorage",
	"com.android.webview",
	"com.google.android.webview",
	"com.android.inputdevices",
	"com.android.location.fused",
	"com.android.contacts",
	"com.google.android.contacts",
	"com.android.dialer",
	"com.google.android.dialer",
	"com.android.mms",
	"com.google.android.apps.messaging",
	// launchers
	"net.oneplus.launcher",
	"com.oneplus.launcher",
	"com.android.launcher3",
	"com.google.android.apps.nexuslauncher",
	// keyboards (the phone also protects the one in use)
	"com.touchtype.swiftkey",
]);

/** Package-name prefixes (a name matches when it equals the prefix or continues it after a dot). */
const NEVER_TOUCH_PREFIXES: readonly string[] = [
	"com.android.systemui",
	"com.android.providers",
	"com.android.server",
	"com.android.inputmethod",
	"com.google.android.gms",
	"com.google.android.inputmethod",
	// OxygenOS 13 is built on ColorOS: its framework services live under these (parent review).
	"com.oplus",
	"com.coloros",
	// Qualcomm modem, telephony and data services.
	"com.qualcomm",
	"com.qti",
];

/** True for a package the steward must never disable or force-stop. */
export function isNeverTouch(packageName: string): boolean {
	const name = packageName.toLowerCase();
	if (NEVER_TOUCH_EXACT.has(name)) return true;
	return NEVER_TOUCH_PREFIXES.some((prefix) => name === prefix || name.startsWith(`${prefix}.`));
}

/** The error text for a refused package. */
export function neverTouchMessage(packageName: string, action: string): string {
	return `${action} is refused for ${packageName}: it is on the never-touch list (launcher, system UI, phone, settings, Google Play services, keyboards, installer, Shizuku and the steward itself).`;
}

/** `phone_shell_info` arguments → the job's fixed params. Anything else the model sent is dropped. */
export function validateShellInfo(args: unknown): JsonObject {
	const op = (args as { op?: unknown } | null | undefined)?.op;
	if (op !== "packages" && op !== "app_sizes") throw badRequest("op must be packages or app_sizes");
	return { op };
}

/** `phone_force_stop` arguments → the job's fixed params; refuses the never-touch list. */
export function validateForceStop(args: unknown): JsonObject {
	const packageName = validatePackageName((args as { packageName?: unknown } | null | undefined)?.packageName);
	if (isNeverTouch(packageName)) throw badRequest(neverTouchMessage(packageName, "force_stop"));
	return { packageName };
}

export type ShizukuState = "absent" | "not_running" | "no_permission" | "ready";

const SHIZUKU_STATES: readonly string[] = ["absent", "not_running", "no_permission", "ready"];

/** The last device status's Shizuku state; `absent` when unknown or malformed. */
export function shizukuState(device: Pick<DeviceState, "permissions"> | undefined): ShizukuState {
	const value = device?.permissions?.shizuku;
	return typeof value === "string" && SHIZUKU_STATES.includes(value) ? (value as ShizukuState) : "absent";
}
