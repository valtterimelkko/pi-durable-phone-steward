/**
 * the three new phone tools (`phone_files`, `phone_shell_info`, `phone_force_stop`)
 * queue replay-safe jobs with validated, fixed params; the model's tool set lists them and
 * still offers no tool that deletes, moves, uninstalls, clears or runs text.
 */
import { describe, expect, it } from "vitest";
import { BACKGROUND_CONTEXT } from "@earendil-works/chord/context";
import { filesShizukuExtension, FILES_SHIZUKU_TOOL_NAMES } from "../src/files-shizuku-tools.ts";
import { Jobs } from "../src/jobs.ts";
import { PROTOCOL_TOOL_NAMES, guardBlock, resolveAgentTools, stewardSections } from "../src/tools.ts";
import { STEWARD_PACKAGE } from "../src/shizuku.ts";
import { openTestHarness, type TestHarness } from "./helpers.ts";

const context = BACKGROUND_CONTEXT;

type Tool = { name: string; execute: (args: unknown, api: unknown, callContext: unknown) => Promise<{ output: Array<{ text: string }> }> };
const tools = (filesShizukuExtension as unknown as { tools: Tool[] }).tools;

function run(t: TestHarness, name: string, args: unknown, taskId = "task-1") {
	const tool = tools.find((candidate) => candidate.name === name)!;
	const api = { taskId, conversationId: t.root.id, commit: <T>(fn: Parameters<typeof t.commit>[0]) => t.commit(fn as never) as Promise<T> };
	return tool.execute(args, api, context).then((result) => JSON.parse(result.output[0]!.text) as { status: string; jobId: string });
}

describe("the tools queue jobs with validated params", () => {
	it("phone_files queues a files_query job with normalised params, and a replay finds the same job", async () => {
		const t = await openTestHarness();
		try {
			const first = await run(t, "phone_files", { op: "list", path: "MapsWithMe/", depth: 2 });
			expect(first).toMatchObject({ status: "queued" });
			const again = await run(t, "phone_files", { op: "list", path: "MapsWithMe/", depth: 2 });
			expect(again.jobId).toBe(first.jobId);
			const jobs = (await t.harness.snapshot(Jobs, t.root.id, context))!.jobs;
			expect(Object.keys(jobs)).toEqual([first.jobId]);
			expect(jobs[first.jobId]).toMatchObject({ kind: "files_query", status: "queued", params: { op: "list", path: "MapsWithMe", depth: 2 } });
		} finally {
			await t.close();
		}
	});

	it("phone_files refuses a bad path or op before anything is queued", async () => {
		const t = await openTestHarness();
		try {
			await expect(run(t, "phone_files", { op: "list", path: "/storage/emulated/0" })).rejects.toThrow(/BAD_PATH/);
			await expect(run(t, "phone_files", { op: "find" })).rejects.toThrow(/namePattern/);
			expect(Object.keys((await t.harness.snapshot(Jobs, t.root.id, context))?.jobs ?? {})).toEqual([]);
		} finally {
			await t.close();
		}
	});

	it("phone_shell_info queues shell_info with a fixed op only", async () => {
		const t = await openTestHarness();
		try {
			const out = await run(t, "phone_shell_info", { op: "app_sizes", command: "rm -rf /" });
			const job = (await t.harness.snapshot(Jobs, t.root.id, context))!.jobs[out.jobId]!;
			expect(job.kind).toBe("shell_info");
			expect(job.params).toEqual({ op: "app_sizes" });
			await expect(run(t, "phone_shell_info", { op: "pm list packages; id" }, "task-2")).rejects.toThrow(/op/);
		} finally {
			await t.close();
		}
	});

	it("phone_force_stop queues force_stop, but refuses the steward and the never-touch list", async () => {
		const t = await openTestHarness();
		try {
			const out = await run(t, "phone_force_stop", { packageName: "com.example.maps" });
			const job = (await t.harness.snapshot(Jobs, t.root.id, context))!.jobs[out.jobId]!;
			expect(job).toMatchObject({ kind: "force_stop", params: { packageName: "com.example.maps" } });
			await expect(run(t, "phone_force_stop", { packageName: STEWARD_PACKAGE }, "task-2")).rejects.toThrow(/never-touch/);
			await expect(run(t, "phone_force_stop", { packageName: "com.android.systemui" }, "task-3")).rejects.toThrow(/never-touch/);
			await expect(run(t, "phone_force_stop", { packageName: "bad name; reboot" }, "task-4")).rejects.toThrow(/package/i);
			expect(Object.keys((await t.harness.snapshot(Jobs, t.root.id, context))!.jobs)).toHaveLength(1);
		} finally {
			await t.close();
		}
	});
});

describe("the model's tool set after A10.1/A10.2", () => {
	it("offers exactly the protocol list (A10.7), including the three new phone tools, and the guard allows them", async () => {
		const t = await openTestHarness();
		try {
			const names = await resolveAgentTools(t.root, context);
			expect([...names].sort()).toEqual([...PROTOCOL_TOOL_NAMES].sort());
			expect(FILES_SHIZUKU_TOOL_NAMES).toEqual(["phone_files", "phone_shell_info", "phone_force_stop"]);
			for (const name of FILES_SHIZUKU_TOOL_NAMES) {
				expect(names).toContain(name);
				expect(guardBlock(name)).toBeUndefined();
			}
			// no tool can delete, move, uninstall, clear, write or run arbitrary text
			for (const name of ["bash", "write", "shell", "exec", "delete_files", "delete", "move", "uninstall_app", "trash_media", "disable_app", "trim_caches"]) {
				expect(names).not.toContain(name);
				expect(guardBlock(name)).toBeTruthy();
			}
			expect(names).toHaveLength(PROTOCOL_TOOL_NAMES.length);
		} finally {
			await t.close();
		}
	});

	it("the prompt tells the model to move, not free, and where the line is", () => {
		const text = (stewardSections() as unknown as { sections: Array<{ render: (input?: unknown) => string }> }).sections.map((s) => s.render({} as never)).join("\n");
		void text;
		const extra = (filesShizukuExtension as unknown as { sections: Array<{ render: (input?: unknown) => string }> }).sections.map((s) => s.render({} as never)).join("\n");
		expect(extra).toMatch(/Steward trash/);
		expect(extra).toMatch(/Keep/);
		expect(extra).toMatch(/Android\//);
		expect(extra).toMatch(/trim_caches/);
	});
});
