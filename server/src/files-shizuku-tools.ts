/**
 * the model's three new phone tools (protocol A10.1, A10.2): `phone_files`,
 * `phone_shell_info` and `phone_force_stop`. Like the other phone tools they only queue a
 * job and return `{status: "queued", jobId}` at once; the answer arrives later as a
 * `[phone result …]` message. Arguments are validated and normalised here, so the job
 * params are always fixed templates (the model never sends shell text or an absolute path)
 * and the never-touch list is enforced before anything is queued.
 *
 * Kept out of `tools.ts` (shared with other lanes); `tools.ts` only lists the names and
 * `steward.ts` installs the extension.
 */
import { defineExtension, defineTool, section } from "@earendil-works/pi-durable";
import { arr, lit, num, obj, opt, str, union, type Schema } from "@earendil-works/pi-schema";
import type { JsonObject } from "@earendil-works/pi-durable";
import { queueJob, type JobKind } from "./jobs.ts";
import { validateFilesQuery } from "./files-query.ts";
import { validateForceStop, validateShellInfo } from "./shizuku.ts";

/** The names this module adds to the protocol §8 tool list (A10.7). */
export const FILES_SHIZUKU_TOOL_NAMES = ["phone_files", "phone_shell_info", "phone_force_stop"] as const;

function queueTool(
	name: (typeof FILES_SHIZUKU_TOOL_NAMES)[number],
	kind: JobKind,
	description: string,
	parameters: Schema,
	validate: (args: unknown) => JsonObject,
) {
	return defineTool({
		name,
		description,
		parameters,
		replay: "safe",
		execute: async (args, api, callContext) => {
			// Validation is deterministic, so a replay re-validates to the same params.
			const params = validate(JSON.parse(JSON.stringify(args)));
			const { crashPoint } = await import("./crash-gate.ts");
			await crashPoint(`phone_tool:${name}`);
			const result = await api.commit(async (tx) => {
				const { jobId } = await queueJob(tx, api.conversationId, { dedupeKey: `${api.taskId}`, kind, params, nowMs: Date.now() });
				return { status: "queued" as const, jobId };
			}, callContext);
			await crashPoint(`phone_tool:${name}:after-job`);
			return { output: [{ type: "text", text: JSON.stringify(result) }] };
		},
	});
}

const phoneFilesTool = queueTool(
	"phone_files",
	"files_query",
	"Ask the phone about files and folders in shared storage (read-only; paths are relative to the storage root, '' is the root, never absolute, no '..'). op 'list' {path, depth 1-3}: the entries under a folder; op 'find' {root, namePattern (a case-insensitive glob such as *.mwm), type file|dir|any, minBytes, olderThanDays, limit up to 500}; op 'du' {paths: up to 20}: exact sizes and counts. Each entry has path, type, sizeBytes (folders: recursive total), modifiedMs and, for a folder, fileCount. Android/data and Android/obb show as unreadable. Answers are capped at 500 entries (truncated: true when cut). The result arrives as a later message. To remove something, propose a delete_files item for a path this tool returned.",
	obj(
		{
			op: union([lit("list"), lit("find"), lit("du")]),
			path: opt(str()),
			depth: opt(num()),
			root: opt(str()),
			namePattern: opt(str()),
			type: opt(union([lit("file"), lit("dir"), lit("any")])),
			minBytes: opt(num()),
			olderThanDays: opt(num()),
			limit: opt(num()),
			paths: opt(arr(str())),
		},
		{ open: true },
	),
	validateFilesQuery,
);

const phoneShellInfoTool = queueTool(
	"phone_shell_info",
	"shell_info",
	"Ask the phone for package facts through Shizuku (read-only, fixed commands). op 'packages': every package with enabled, system and installer; op 'app_sizes': exact code, data and cache bytes per package. Needs Shizuku running on the phone (the owner starts it after each reboot); otherwise the answer is a SHIZUKU_NOT_READY error. The result arrives as a later message.",
	obj({ op: union([lit("packages"), lit("app_sizes")]) }, { open: true }),
	validateShellInfo,
);

const phoneForceStopTool = queueTool(
	"phone_force_stop",
	"force_stop",
	"Stop a running app on the phone (Android 'force stop'; it deletes nothing and needs no proposal). Needs Shizuku. Refused for the steward itself and for system packages on the never-touch list (launcher, system UI, phone, settings, Google Play services, keyboards, installer). The result arrives as a later message.",
	obj({ packageName: str() }, { open: true }),
	validateForceStop,
);

/** The three tools plus the prompt section that tells the model how to use them. */
export const filesShizukuExtension = defineExtension({
	name: "steward-files-shizuku",
	tools: [phoneFilesTool, phoneShellInfoTool, phoneForceStopTool],
	sections: [
		section("files-and-shizuku", () =>
			[
				"FILES AND SHIZUKU (A10): you can look at any folder the app can reach with phone_files (list, find, du) and propose removing what you find.",
				"- To delete files or folders, call phone_files first, then propose a delete_files item for a path it returned (target: {path}; the server fills in size and time from the phone's own answer and refuses a path the phone never reported). The owner approves once in the app; the files are MOVED to the Steward trash and the space returns when that trash is emptied (after 30 days, or when the owner empties it). Say so: 'moved to the trash', not 'freed'.",
				"- Never propose: the storage root, anything under Android/, anything with a Keep folder in its path, or DCIM/ and Pictures/ (photos and videos go through trash_media).",
				"- Shizuku is the owner's optional shell-level access (set up after each reboot). The device status shows permissions.shizuku. With it: phone_shell_info (packages, exact sizes), phone_force_stop (not destructive, no proposal), and the proposal kinds trim_caches (every app's cache in one step, no Android dialog; prefer it over clear_all_caches when Shizuku is ready), disable_app and enable_app. Without it these fail with SHIZUKU_NOT_READY: tell the owner what to start, once.",
				"- disable_app is refused for launcher, system UI, phone, settings, Google Play services, keyboards, the installer and the steward. Prefer disabling to uninstalling for preinstalled apps; it is reversible with enable_app.",
			].join("\n"),
		),
	],
});
