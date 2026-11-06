/**
 * The photo window's pure decision logic : ages are London calendar
 * dates; protection is `inKeep`, `isFavourite`, or restored per the decisions ledger; a
 * reminder always comes before a cut-off; a batch takes only items an EARLIER run reminded.
 */
import type { MediaRef } from "./jobs.ts";
import type { PhotoWindowRules } from "./documents.ts";
import { ageDays } from "./time.ts";

export type ReminderLedger = {
	/** mediaId → the run that reminded about it. */
	reminded: Record<string, { runId: string; atMs: number }>;
};

/** An item is protected when inKeep, isFavourite, or restored (§3.3). */
export function isProtectedRef(ref: MediaRef, restored: Set<string>): boolean {
	if (ref.inKeep === true || ref.isFavourite === true) return true;
	if (restored.has(ref.mediaId)) return true;
	const segments = ref.relativePath.split("/");
	return segments.some((segment) => segment.toLowerCase() === "keep");
}

/** Items in the reminder window: `reminderDays <= age < cutoffDays`, unprotected. */
export function reminderItemsForRun(
	items: readonly MediaRef[],
	rules: PhotoWindowRules,
	nowMs: number,
	ledger: ReminderLedger,
	restored: Set<string>,
): MediaRef[] {
	return items.filter((ref) => {
		if (isProtectedRef(ref, restored)) return false;
		if (ledger.reminded[ref.mediaId] !== undefined) return false;
		const age = ageDays(ref.dateTakenMs != null ? ref.dateTakenMs : ref.dateAddedMs, nowMs);
		return age >= rules.reminderDays && age < rules.cutoffDays;
	});
}

/**
 * Items for the cut-off batch: `age >= cutoffDays`, reminded by an EARLIER run (a reminder
 * always comes before a cut-off), unprotected, and still present in the current index.
 */
export function batchItemsForRun(
	items: readonly MediaRef[],
	rules: PhotoWindowRules,
	nowMs: number,
	ledger: ReminderLedger,
	currentRunId: string,
	restored: Set<string>,
): MediaRef[] {
	return items.filter((ref) => {
		if (isProtectedRef(ref, restored)) return false;
		const reminder = ledger.reminded[ref.mediaId];
		if (reminder === undefined || reminder.runId === currentRunId) return false;
		const age = ageDays(ref.dateTakenMs != null ? ref.dateTakenMs : ref.dateAddedMs, nowMs);
		return age >= rules.cutoffDays;
	});
}

/** Executed trash items that appear again in a new index are restored (protected from now on). */
export function restoredRefsInIndex(items: readonly MediaRef[], executedTrashIds: Set<string>): string[] {
	return items.filter((ref) => executedTrashIds.has(ref.mediaId)).map((ref) => ref.mediaId);
}
