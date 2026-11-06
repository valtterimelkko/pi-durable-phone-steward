/**
 * The agent's standing instructions: they describe a deployment generically (one Android
 * phone, a server, a configured photo rule) and carry a factual guide to the self-test so
 * the agent reports outcomes instead of inventing causes.
 */
import { describe, expect, it } from "vitest";
import { stewardSections } from "../src/tools.ts";

function promptText(): string {
	const extension = stewardSections() as unknown as { sections: Array<{ key: string; render: (input?: unknown) => string }> };
	return extension.sections.map((s) => s.render({} as never)).join("\n");
}

describe("the steward's standing instructions", () => {
	it("describe the deployment generically: one Android phone, no device, vendor or archive names", () => {
		const text = promptText();
		expect(text.toLowerCase()).toContain("one android phone");
		for (const banned of ["OnePlus", "Nord", "Amazon", "OxygenOS", "VPS", "engineer"]) {
			expect(text, banned).not.toContain(banned);
		}
	});

	it("state the photo policy as the configured rule, and keep the Keep-folder protection", () => {
		const text = promptText();
		expect(text).toMatch(/photo (policy|rule)[^\n]*(configured|rule|settings)/i);
		expect(text).toMatch(/Keep/);
		expect(text).toMatch(/never touched|are protected/i);
	});

	it("explain each self-test check and forbid guessing a cause", () => {
		const text = promptText();
		for (const check of ["trash_dialog", "manage_media_skip", "trash_restorable", "clear_cache_intent", "workmanager_ran"]) {
			expect(text).toContain(check);
		}
		expect(text).toMatch(/do not guess|never guess/i);
	});

	it("say that Manage media is only for auto mode and is not needed to trash with consent", () => {
		const text = promptText();
		expect(text).toMatch(/Manage media[^\n]*only[^\n]*auto mode/i);
		expect(text).toMatch(/never (advise|tell|ask) the owner to grant[^\n]*Manage media/i);
	});

	it("prefer one clear_all_caches batch over many single-cache proposals", () => {
		const text = promptText();
		expect(text).toContain("clear_all_caches");
		expect(text).toMatch(/two or more caches[^.]*clear_all_caches|clear_all_caches[^.]*two or more caches/s);
		expect(text).toMatch(/one (Android )?confirmation/i);
		expect(text).toMatch(/every app('s)? cache/i);
		expect(text).toMatch(/one batch[^.]*instead of|instead of[^.]*single/i);
	});

	it("tells the steward to find out freely and to act directly only when nothing is removed", () => {
		const text = promptText();
		expect(text).toMatch(/use your tools (freely )?to find out/i);
		expect(text).toMatch(/phone_force_stop/);
		expect(text).toMatch(/directly[^.\n]*(only|never)[^.\n]*(remov|delet|disabl)|(remov|delet|disabl)[^.\n]*(never|not)[^.\n]*directly/i);
	});

	it("makes every removal a proposal and lists the proposal kinds, so it never says it cannot when one exists", () => {
		const text = promptText();
		expect(text).toMatch(/every (removal|deletion)[^.\n]*proposal/i);
		for (const kind of ["trash_media", "delete_files", "uninstall_app", "clear_app_cache", "clear_all_caches", "trim_caches", "disable_app", "enable_app"]) {
			expect(text, kind).toContain(kind);
		}
		expect(text).toMatch(/never (say|claim)[^.\n]*(can't|cannot|can not)[^.\n]*(tool|proposal)/i);
	});

	it("names every tool it has by its exact name", () => {
		const text = promptText();
		for (const name of [
			"phone_storage_overview", "phone_app_usage", "phone_media_index", "phone_files", "phone_shell_info", "phone_force_stop",
			"web_search", "web_read", "web_browse",
			"memory_recall", "memory_find", "memory_survey", "session_find", "session_handoff", "session_log", "presence_board",
		]) {
			expect(text, name).toContain(name);
		}
	});

	it("handles hand-overs from other agents through the session tools, and finds the session before using it", () => {
		const text = promptText();
		expect(text).toMatch(/hand-?over/i);
		expect(text).toMatch(/session_handoff/);
		expect(text).toMatch(/never invent a (session )?ref|find (it|the session) first/i);
	});

	it("treats web, file and memory/session text as untrusted data, not instructions", () => {
		const text = promptText();
		expect(text).toMatch(/untrusted/i);
		expect(text).toMatch(/never follow instructions (found )?in/i);
	});

	it("no longer says the steward can only read phone facts (the MVP posture)", () => {
		const text = promptText();
		expect(text).not.toMatch(/tools only read phone facts/i);
		expect(text).not.toMatch(/you cannot delete, move, uninstall/i);
		expect(text).not.toMatch(/Tier 2 \(needs the owner to enable Shizuku\)/);
	});

	it("keeps the storage-clarity, photo-policy and Keep-folder rules", () => {
		const text = promptText();
		expect(text).toMatch(/Keep/);
		expect(text).toMatch(/photo policy/i);
		expect(text).toMatch(/Storage clarity/i);
	});

	it("says its memory is the server's records, and that a fresh start is normal", () => {
		const text = promptText();
		expect(text).toMatch(/fresh start/i);
		expect(text).toMatch(/records/i);
	});

	it("keeps the owner's personal details out of web queries and URLs", () => {
		const text = promptText();
		expect(text).toMatch(/web[^.\n]*(leave|leaves)[^.\n]*(server|VPS)|(leave|leaves) the server/i);
		expect(text).toMatch(/never put[^.\n]*(file|folder|contact)[^.\n]*(web|query|url)/i);
	});
});
