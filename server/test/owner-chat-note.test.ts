/**
 * S9 (protocol A9.2): every owner chat turn carries, as a server note the owner never sees,
 * the server's authoritative open list and the latest outcome of each proposal decided in
 * the last 14 days. The model had answered "Are you here?" with a closed proposal "still
 * waiting" and "the decisions ledger is still empty", from its own old messages.
 */
import { describe, expect, it } from "vitest";
import { BACKGROUND_CONTEXT } from "@earendil-works/chord/context";
import { startChatBridge } from "../src/chat-bridge.ts";
import { chatEventsAfter } from "../src/chat-events.ts";
import { Decisions, type DecisionLedgerEntry } from "../src/documents.ts";
import { Proposals, type Proposal } from "../src/proposals.ts";
import { ownerChatNote, ownerTurnContent, stripServerNote, withServerNote } from "../src/owner-note.ts";
import { formatConversation } from "../src/observability.ts";
import { openTestHarness, type TestHarness } from "./helpers.ts";

const context = BACKGROUND_CONTEXT;
const DAY = 86_400_000;
const NOW = Date.UTC(2026, 9, 8, 12, 0, 0);

function proposal(id: string, status: Proposal["status"], over: Partial<Proposal> = {}): Proposal {
	return {
		proposalId: id,
		createdAtMs: NOW - 3 * DAY,
		source: "agent",
		title: `Title ${id}`,
		reason: "r",
		bytesEstimate: 3_300_000_000,
		status,
		items: [{ itemId: `it-${id}-0`, kind: "clear_all_caches", target: { apps: [] }, bytesEstimate: 3_300_000_000, reason: "" }],
		...over,
	};
}

function report(id: string, atMs: number, outcomes: Array<{ type: DecisionLedgerEntry["type"]; bytesFreed?: number }>): DecisionLedgerEntry[] {
	return outcomes.map((o, i) => ({
		atMs,
		type: o.type,
		proposalId: id,
		itemId: `it-${id}-${i}`,
		kind: "clear_all_caches",
		bytesFreed: o.bytesFreed ?? null,
	}));
}

describe("S9 defect 2: the server note text", () => {
	it("names the open list, and the latest outcome of each recently decided proposal", () => {
		const proposals = [
			proposal("pr-open", "open", { createdAtMs: NOW - DAY }),
			proposal("pr-done", "executed", { statusChangedAtMs: NOW - 2 * DAY }),
			proposal("pr-again", "open", { statusChangedAtMs: NOW - DAY, title: "Old videos" }),
			proposal("pr-failed", "open", { statusChangedAtMs: NOW - 4 * DAY }),
			proposal("pr-rej", "rejected", { statusChangedAtMs: NOW - 5 * DAY }),
			proposal("pr-sup", "superseded", { supersededBy: "pr-open", statusChangedAtMs: NOW - DAY }),
			proposal("pr-old", "executed", { statusChangedAtMs: NOW - 20 * DAY }),
		];
		const ledger: DecisionLedgerEntry[] = [
			...report("pr-old", NOW - 20 * DAY, [{ type: "executed", bytesFreed: 1 }]),
			...report("pr-done", NOW - 2 * DAY, [{ type: "executed", bytesFreed: 3_300_000_000 }]),
			// An earlier no-op report, then the latest one: only the latest counts.
			...report("pr-again", NOW - 3 * DAY, [{ type: "failed" }]),
			...report("pr-again", NOW - DAY, Array.from({ length: 15 }, () => ({ type: "cancelled" as const }))),
			...report("pr-failed", NOW - 4 * DAY, Array.from({ length: 12 }, () => ({ type: "failed" as const }))),
		];
		const note = ownerChatNote({ proposals, ledger, nowMs: NOW });
		// Authoritative open list: only the proposals whose status is open.
		expect(note).toContain("Server record (authoritative");
		expect(note).toContain("- pr-open:");
		expect(note).toContain("- pr-again:");
		// Latest outcomes.
		expect(note).toMatch(/pr-done[^\n]*executed[^\n]*1 done \(3\.3 GB freed\)/);
		expect(note).toMatch(/pr-again[^\n]*open again[^\n]*15 cancelled on the phone/);
		expect(note).not.toMatch(/pr-again[^\n]*failed/);
		expect(note).toMatch(/pr-failed[^\n]*open again[^\n]*12 failed on the phone/);
		expect(note).toMatch(/pr-rej[^\n]*rejected by the owner/);
		expect(note).toMatch(/pr-sup[^\n]*superseded by pr-open/);
		// Older than 14 days is left out; the ledger is not called empty.
		expect(note).not.toContain("pr-old");
		expect(note).toMatch(/ledger[^\n]*\b30 entries/i);
		expect(note).toContain("2026-10-06");
	});

	it("says plainly when nothing is open and nothing was decided", () => {
		const note = ownerChatNote({ proposals: [], ledger: [], nowMs: NOW });
		expect(note).toContain("no proposal is waiting for the owner");
		expect(note).toMatch(/No proposal was decided in the last 14 days/);
		expect(note).toMatch(/ledger[^\n]*\b0 entries/i);
	});

	it("never lists a proposal that is merely open and undecided under the decided outcomes", () => {
		const note = ownerChatNote({ proposals: [proposal("pr-new", "open")], ledger: [], nowMs: NOW });
		const decided = note.split("Decided in the last 14 days")[1] ?? "";
		expect(decided).not.toContain("pr-new");
	});

	it("withServerNote / stripServerNote round-trip the owner's exact text", () => {
		const typed = "Are you here?\n\nSecond paragraph with [brackets] and a line\nbreak ";
		const content = withServerNote(typed, "note body");
		expect(content.startsWith(typed)).toBe(true);
		expect(content).toContain("note body");
		expect(stripServerNote(content)).toBe(typed);
		expect(stripServerNote(typed)).toBe(typed);
	});
});

describe("S9 defect 2: the engineer's chat view", () => {
	it("shows the typed text only and marks that the model also got a server note", () => {
		const entry = (content: string) => ({ model: [{ role: "user", content, timestamp: 1 }] }) as never;
		const lines = formatConversation([entry(withServerNote("Are you here?", "open list")), entry("plain")], 10);
		expect(lines).toEqual([
			{ type: "message", role: "owner", atMs: 1, text: "Are you here?", serverNote: true },
			{ type: "message", role: "owner", atMs: 1, text: "plain" },
		]);
	});
});

describe("S9 defect 2: the note reaches the model and stays out of the owner's chat", () => {
	async function seed(t: TestHarness): Promise<void> {
		await t.commit(async (tx) => {
			const doc = await tx.doc(Proposals, t.root.id);
			doc.proposals["pr-exec"] = JSON.parse(JSON.stringify(proposal("pr-exec", "executed", { statusChangedAtMs: Date.now() - 1000 })));
			doc.proposals["pr-wait"] = JSON.parse(JSON.stringify(proposal("pr-wait", "open", { createdAtMs: Date.now() - 500 })));
			const ledger = await tx.doc(Decisions, t.root.id);
			for (const entry of report("pr-exec", Date.now() - 1000, [{ type: "executed", bytesFreed: 3_300_000_000 }])) {
				ledger.entries.push(JSON.parse(JSON.stringify(entry)));
			}
		});
	}

	it("the model input holds the typed text plus the note; the chat log and stream hold the typed text only", async () => {
		const t = await openTestHarness();
		const bridge = startChatBridge(t.root);
		try {
			await seed(t);
			const typed = "Are you here?";
			const content = await ownerTurnContent(t.harness, t.root.id, typed, context, Date.now());
			const submission = await t.root.submit({ type: "input", content, requestId: "owner-s9" }, context);
			await submission.wait(context);
			await t.root.waitForIdle(context);

			const view = await t.root.context(context);
			const userTexts = view.entries
				.map((entry) => entry.model?.[0])
				.filter((message) => message?.role === "user")
				.map((message) => (typeof message.content === "string" ? message.content : ""));
			const modelInput = userTexts.find((text) => text.startsWith(typed))!;
			expect(modelInput).toContain("pr-wait");
			expect(modelInput).toMatch(/pr-exec[^\n]*executed[^\n]*1 done \(3\.3 GB freed\)/);

			// The bridge is a view of committed entries; give it a moment to catch up.
			let messages: Array<{ type: string; data: { role?: string; text?: string } }> = [];
			for (let i = 0; i < 200; i++) {
				const events = await t.commit((tx) => chatEventsAfter(tx, 0));
				messages = events.filter((event) => event.type === "message") as never;
				if (messages.some((event) => event.data.role === "assistant")) break;
				await new Promise((resolve) => setTimeout(resolve, 25));
			}
			const owner = messages.filter((event) => event.data.role === "user");
			expect(owner.map((event) => event.data.text)).toEqual([typed]);
			const everything = JSON.stringify(await t.commit((tx) => chatEventsAfter(tx, 0)));
			expect(everything).not.toContain("pr-wait");
			expect(everything).not.toContain("server note");
		} finally {
			bridge.stop();
			await t.close();
		}
	});
});
