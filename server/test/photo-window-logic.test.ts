import { describe, expect, it } from "vitest";
import { BACKGROUND_CONTEXT } from "@earendil-works/chord/context";
import type { MediaRef } from "../src/jobs.ts";
import {
	batchItemsForRun,
	isProtectedRef,
	reminderItemsForRun,
	restoredRefsInIndex,
	type ReminderLedger,
} from "../src/photo-window-logic.ts";
import type { PhotoWindowRules } from "../src/documents.ts";
import { AUTO_MODE_NOT_YET } from "../src/rule-guards.ts";
import { stewardDateToUtcMs, addDaysToStewardDate } from "../src/time.ts";

const context = BACKGROUND_CONTEXT;
void context;

const RULES: PhotoWindowRules = {
	enabled: true,
	reminderDays: 90,
	cutoffDays: 120,
	autoMode: false,
	folders: ["DCIM/Camera/", "Pictures/Screenshots/"],
	keepMarker: "Keep",
};

function ref(partial: Partial<MediaRef> & { mediaId: string; dateAddedMs: number }): MediaRef {
	return {
		relativePath: "DCIM/Camera/",
		displayName: `IMG_${partial.mediaId}.jpg`,
		mimeType: "image/jpeg",
		sizeBytes: 3_000_000,
		dateTakenMs: null,
		isFavourite: false,
		inKeep: false,
		...partial,
	};
}

describe("protection markers", () => {
	const now = stewardDateToUtcMs("2026-03-31");
	it("protects inKeep, favourites, and restored items; nothing else", () => {
		const restored = new Set(["restored-1"]);
		expect(isProtectedRef(ref({ mediaId: "a", dateAddedMs: now }), restored)).toBe(false);
		expect(isProtectedRef(ref({ mediaId: "b", dateAddedMs: now, inKeep: true }), restored)).toBe(true);
		expect(isProtectedRef(ref({ mediaId: "c", dateAddedMs: now, isFavourite: true }), restored)).toBe(true);
		expect(isProtectedRef(ref({ mediaId: "restored-1", dateAddedMs: now }), restored)).toBe(true);
	});

	it("treats a Keep folder case-insensitively at any path segment", () => {
		expect(isProtectedRef(ref({ mediaId: "d", dateAddedMs: now, relativePath: "Pictures/keep/" }), new Set())).toBe(true);
		expect(isProtectedRef(ref({ mediaId: "e", dateAddedMs: now, relativePath: "DCIM/Camera/" }), new Set())).toBe(false);
	});
});

describe("reminder window (3–4 months)", () => {
	it("picks items with reminderDays <= age < cutoffDays and drops protected ones", () => {
		const now = stewardDateToUtcMs("2026-03-31");
		const at90 = ref({ mediaId: "at90", dateAddedMs: stewardDateToUtcMs(addDaysToStewardDate("2026-03-31", -90)) });
		const at89 = ref({ mediaId: "at89", dateAddedMs: stewardDateToUtcMs(addDaysToStewardDate("2026-03-31", -89)) });
		const at120 = ref({ mediaId: "at120", dateAddedMs: stewardDateToUtcMs(addDaysToStewardDate("2026-03-31", -120)) });
		const kept = ref({ mediaId: "kept", dateAddedMs: at90.dateAddedMs, inKeep: true });
		const items = [at89, at90, at120, kept];
		const ledger: ReminderLedger = { reminded: {} };
		const picked = reminderItemsForRun(items, RULES, now, ledger, new Set());
		expect(picked.map((r) => r.mediaId)).toEqual(["at90"]);
	});
});

describe("cut-off batch (4 months)", () => {
	it("only takes items at or past cutoffDays that an EARLIER run reminded, and never protected ones", () => {
		const now = stewardDateToUtcMs("2026-04-30");
		const remindedLastRun = ref({ mediaId: "old1", dateAddedMs: stewardDateToUtcMs(addDaysToStewardDate("2026-04-30", -121)) });
		const neverReminded = ref({ mediaId: "old2", dateAddedMs: stewardDateToUtcMs(addDaysToStewardDate("2026-04-30", -130)) });
		const thisRunReminder = ref({ mediaId: "mid", dateAddedMs: stewardDateToUtcMs(addDaysToStewardDate("2026-04-30", -95)) });
		const keptOld = ref({ mediaId: "keptOld", dateAddedMs: remindedLastRun.dateAddedMs, inKeep: true });
		const ledger: ReminderLedger = {
			reminded: {
				old1: { runId: "photo-window:2026-03", atMs: now - 30 * 86_400_000 },
				keptOld: { runId: "photo-window:2026-03", atMs: now - 30 * 86_400_000 },
				mid: { runId: "photo-window:2026-04", atMs: now },
			},
		};
		const items = [remindedLastRun, neverReminded, thisRunReminder, keptOld];
		const batch = batchItemsForRun(items, RULES, now, ledger, "photo-window:2026-04", new Set());
		expect(batch.map((r) => r.mediaId)).toEqual(["old1"]);
	});

	it("a run two months late produces one batch covering both months, not two", () => {
		const now = stewardDateToUtcMs("2026-05-31");
		// Reminded in March; the April run never happened (missed month); May run batches it.
		const missed = ref({ mediaId: "m1", dateAddedMs: stewardDateToUtcMs("2026-01-15") });
		const reachedCutoffSince = ref({ mediaId: "m2", dateAddedMs: stewardDateToUtcMs("2026-01-30") });
		const ledger: ReminderLedger = {
			reminded: {
				m1: { runId: "photo-window:2026-03", atMs: stewardDateToUtcMs("2026-03-15") },
				m2: { runId: "photo-window:2026-03", atMs: stewardDateToUtcMs("2026-03-15") },
			},
		};
		const batch = batchItemsForRun([missed, reachedCutoffSince], RULES, now, ledger, "photo-window:2026-05", new Set());
		expect(batch.map((r) => r.mediaId).sort()).toEqual(["m1", "m2"]);
	});
});

describe("restored items", () => {
	it("an executed item seen again in the index is reported for the restored ledger", () => {
		const executed = new Set(["done-1"]);
		const items = [ref({ mediaId: "done-1", dateAddedMs: 1 }), ref({ mediaId: "new", dateAddedMs: 1 })];
		const restored = restoredRefsInIndex(items, executed);
		expect(restored).toEqual(["done-1"]);
	});
});

describe("auto mode guard", () => {
	it("refuses auto mode while no batch has run as one-tap approval", () => {
		expect(() => AUTO_MODE_NOT_YET({ autoMode: true, batchesRunOneTap: 0 })).toThrowError(
			expect.objectContaining({ code: "AUTO_MODE_NOT_YET" }),
		);
		expect(() => AUTO_MODE_NOT_YET({ autoMode: true, batchesRunOneTap: 2 })).not.toThrow();
		expect(() => AUTO_MODE_NOT_YET({ autoMode: false, batchesRunOneTap: 0 })).not.toThrow();
	});
});
