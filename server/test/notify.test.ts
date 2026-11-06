import { describe, expect, it } from "vitest";
import { BACKGROUND_CONTEXT } from "@earendil-works/chord/context";
import { Notifications, NotifyTask, queueNotify, setNotifySender } from "../src/notify.ts";
import { openTestHarness } from "./helpers.ts";

const context = BACKGROUND_CONTEXT;

function recordingSender(): { calls: Array<{ title: string; body: string }>; sender: (title: string, body: string) => Promise<void> } {
	const calls: Array<{ title: string; body: string }> = [];
	return {
		calls,
		sender: async (title, body) => {
			calls.push({ title, body });
		},
	};
}

describe("queueNotify", () => {
	it("queues one task per dedupe key, however often it is called", async () => {
		const t = await openTestHarness();
		try {
			const first = await t.commit((tx) =>
				queueNotify(tx, t.root.id, { dedupeKey: "photo-reminder:r1", category: "reminder", title: "t", body: "b" }, 1000),
			);
			const again = await t.commit((tx) =>
				queueNotify(tx, t.root.id, { dedupeKey: "photo-reminder:r1", category: "reminder", title: "t", body: "b" }, 1001),
			);
			expect(`${again}`).toBe(`${first}`);
			const other = await t.commit((tx) =>
				queueNotify(tx, t.root.id, { dedupeKey: "photo-batch:r1", category: "batch", title: "t", body: "b" }, 1002),
			);
			expect(`${other}`).not.toBe(`${first}`);
		} finally {
			await t.close();
		}
	});
});

describe("NotifyTask", () => {
	it("sends once for a dedupe key; a repeated task skips (bite probe: remove the sent-check and this fails)", async () => {
		const t = await openTestHarness();
		const { calls, sender } = recordingSender();
		setNotifySender(sender);
		try {
			const taskId = await t.commit((tx) =>
				queueNotify(tx, t.root.id, { dedupeKey: "k-1", category: "reminder", title: "Reminder", body: "3 items" }, 1000),
			);
			await t.waitTaskTerminal(taskId);
			const repeat = await t.commit((tx) =>
				queueNotify(tx, t.root.id, { dedupeKey: "k-1", category: "reminder", title: "Reminder", body: "3 items" }, 1001),
			);
			await t.waitTaskTerminal(repeat);
			expect(calls).toHaveLength(1);
			expect(calls[0]).toEqual({ title: "Reminder", body: "3 items" });
			const stored = await t.snapshot(Notifications);
			expect(stored?.sent["k-1"]?.sentAtMs).toBeGreaterThan(0);
		} finally {
			await t.close();
		}
	});

	it("never sends unless a sender is set — the default sender is a no-op, so tests cannot send", async () => {
		const t = await openTestHarness();
		setNotifySender(undefined);
		try {
			const taskId = await t.commit((tx) =>
				queueNotify(tx, t.root.id, { dedupeKey: "k-2", category: "proposal", title: "t", body: "b" }, 1000),
			);
			await t.waitTaskTerminal(taskId);
			const stored = await t.snapshot(Notifications);
			expect(stored?.sent["k-2"]).toBeDefined();
		} finally {
			await t.close();
		}
	});
});
