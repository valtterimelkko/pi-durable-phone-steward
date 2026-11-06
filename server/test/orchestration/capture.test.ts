/**
 * Capture redaction : captures are built only from
 * `StewardEvent` data (ids, counts, sizes, outcomes) through fixed templates. Model
 * text, chat text, file names and contact names never reach a note — adversarial event
 * values shaped like file paths, phone numbers or prose are dropped, and only
 * enum-checked outcomes and shape-checked ids pass.
 */
import { describe, expect, it } from "vitest";
import type { StewardEvent } from "../../src/integrations.ts";
import { captureToken, reviewLineCapture } from "../../src/orchestration/capture.ts";

function reviewEvent(data: Record<string, string | number | boolean | null>): StewardEvent {
	return { kind: "review_line", atMs: 1_000, data };
}

const FORBIDDEN = [
	"/home/adopter/private.pdf",
	"DCIM/Camera/IMG_20260601_101010.jpg",
	"07700900123",
	"+44 7700 900123",
	"John",
	"WhatsApp chat with Susan",
];

function expectRedacted(note: string | undefined): void {
	expect(note).toBeDefined();
	for (const forbidden of FORBIDDEN) {
		expect(note!, `must not contain ${forbidden}`).not.toContain(forbidden);
	}
}

describe("review_line captures", () => {
	it("captures a failure outcome with the proposal id and a dedupe token", () => {
		const result = reviewLineCapture(reviewEvent({ outcome: "failure", proposalId: "pr-0001" }));
		expect(result!.note).toContain("pr-0001");
		expect(result!.note).toContain("failure");
		expect(result!.note).toMatch(/\[capture [0-9a-f]{16}\]$/);
	});

	it("captures a cancellation", () => {
		expect(reviewLineCapture(reviewEvent({ outcome: "cancelled", proposalId: "pr-2" }))).toBeDefined();
	});

	it("captures freed bytes differing from the estimate by more than 50 percent", () => {
		const over = reviewLineCapture(reviewEvent({ outcome: "ok", proposalId: "pr-3", bytesEstimate: 3_000, bytesFreed: 100 }));
		expect(over!.note).toContain("3,000".replace(",", "") ?? "3000");
		expect(over!.note).toContain("3000");
		expect(over!.note).toContain("97%");
		// Exactly 50 percent is not "more than 50".
		expect(reviewLineCapture(reviewEvent({ outcome: "ok", proposalId: "pr-4", bytesEstimate: 1_000, bytesFreed: 500 }))).toBeUndefined();
		expect(reviewLineCapture(reviewEvent({ outcome: "ok", proposalId: "pr-5", bytesEstimate: 1_000, bytesFreed: 1_200 }))).toBeUndefined();
	});

	it("does not capture an ordinary success", () => {
		expect(reviewLineCapture(reviewEvent({ outcome: "ok", proposalId: "pr-6", bytesEstimate: 1_000, bytesFreed: 1_000 }))).toBeUndefined();
	});

	it("returns nothing for other event kinds", () => {
		expect(reviewLineCapture({ kind: "run_start", atMs: 0, data: { outcome: "failure" } })).toBeUndefined();
	});

	it("drops adversarial values: file names, phone numbers and prose never appear", () => {
		const result = reviewLineCapture(
			reviewEvent({
				outcome: "failure",
				proposalId: "pr-7",
				fileName: FORBIDDEN[0]!,
				relativePath: FORBIDDEN[1]!,
				ownerPhone: FORBIDDEN[2]!,
				altPhone: FORBIDDEN[3]!,
				contactName: FORBIDDEN[4]!,
				chatText: FORBIDDEN[5]!,
			}),
		);
		expectRedacted(result!.note);
	});

	it("drops an outcome outside the fixed enum and an id that is not a steward id", () => {
		expect(reviewLineCapture(reviewEvent({ outcome: "failure /home/adopter/x.pdf", proposalId: "pr-8" }))).toBeUndefined();
		expect(reviewLineCapture(reviewEvent({ outcome: "failure", proposalId: "/etc/passwd" }))!.note).not.toContain("passwd");
		expect(reviewLineCapture(reviewEvent({ outcome: "failure", proposalId: "pr 9 with spaces" }))!.note).not.toContain("with spaces");
		// Numbers that are not safe integers are dropped; the capture keeps its id line.
		const weird = reviewLineCapture(reviewEvent({ outcome: "ok", proposalId: "pr-10", bytesEstimate: Number.NaN, bytesFreed: Number.POSITIVE_INFINITY, bytesEstimateName: "/home/x" }));
		if (weird !== undefined) expectRedacted(weird.note);
	});

	it("accepts only steward id shapes: file names and phone numbers are withheld", () => {
		// The review's evidence values: both were accepted by the loose shape before.
		const file = reviewLineCapture(reviewEvent({ outcome: "failure", proposalId: "IMG_20260601_101010.jpg" }));
		expect(file).toBeDefined();
		expect(file!.note).toContain("(id withheld)");
		expect(file!.note).not.toContain("IMG_20260601_101010");
		const phone = reviewLineCapture(reviewEvent({ outcome: "cancelled", proposalId: "07700900123" }));
		expect(phone).toBeDefined();
		expect(phone!.note).toContain("(id withheld)");
		expect(phone!.note).not.toContain("07700900123");
		// Each steward prefix passes; dev_ too.
		for (const id of ["pr-1", "pj-abc_123", "rb-9", "dc-x", "it-y", "dev_z"] as const) {
			const kept = reviewLineCapture(reviewEvent({ outcome: "failure", proposalId: id }));
			expect(kept!.note).toContain(id);
		}
	});

	it("the token is stable for the same event facts and differs across facts", () => {
		const a = reviewEvent({ outcome: "failure", proposalId: "pr-11" });
		const b = reviewEvent({ outcome: "failure", proposalId: "pr-11" });
		const c = reviewEvent({ outcome: "failure", proposalId: "pr-12" });
		expect(captureToken(a)).toMatch(/^[0-9a-f]{16}$/);
		expect(captureToken(a)).toBe(captureToken(b));
		expect(captureToken(a)).not.toBe(captureToken(c));
	});

	it("the note never contains model or chat text keys even when they look harmless", () => {
		const result = reviewLineCapture(
			reviewEvent({ outcome: "failure", proposalId: "pr-13", assistantText: "Free space is 5.0 GB.", userText: "What is filling my phone?" }),
		);
		expectRedacted(result!.note);
		expect(result!.note).not.toContain("Free space");
	});
});
