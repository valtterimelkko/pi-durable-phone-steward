/**
 * Calendar-date helpers in the steward's configured time zone (PHONE_STEWARD_TIME_ZONE,
 * default UTC; `setTimeZone` overrides for tests). Ages for the photo rule are counted as
 * calendar dates in that zone, never 86400000-second blocks, so a DST change cannot move a
 * 90- or 120-day boundary.
 */

const MS_PER_DAY = 86_400_000;

/** The configured zone; `setTimeZone` (tests, start-up) beats the environment. */
let zoneOverride: string | undefined;

/** Set the time zone for all date helpers (`undefined` = follow the environment). */
export function setTimeZone(zone: string | undefined): void {
	zoneOverride = zone;
}

/** The steward's time zone: the override, else PHONE_STEWARD_TIME_ZONE, else UTC. */
export function stewardTimeZone(): string {
	return zoneOverride ?? process.env.PHONE_STEWARD_TIME_ZONE ?? "UTC";
}

/** The local calendar date of `ms`, as `YYYY-MM-DD`, in the steward's time zone. */
export function stewardDate(ms: number): string {
	// en-CA formats as ISO YYYY-MM-DD in the given time zone.
	return new Intl.DateTimeFormat("en-CA", {
		timeZone: stewardTimeZone(),
		year: "numeric",
		month: "2-digit",
		day: "2-digit",
	}).format(new Date(ms));
}

/** Parse `YYYY-MM-DD` to `{y, m, d}`. */
function parseDate(date: string): { y: number; m: number; d: number } {
	const match = /^(\d{4})-(\d{2})-(\d{2})$/.exec(date);
	if (match === null) throw new Error(`not a YYYY-MM-DD date: ${date}`);
	return { y: Number(match[1]), m: Number(match[2]), d: Number(match[3]) };
}

/** Proleptic-Gregorian day number of a civil date (exact at UTC midnight). */
function dayNumber(y: number, m: number, d: number): number {
	return Math.floor(Date.UTC(y, m - 1, d) / MS_PER_DAY);
}

/** Whole calendar days from `fromDate` to `toDate` (both `YYYY-MM-DD`), signed. */
export function daysBetweenDates(fromDate: string, toDate: string): number {
	const a = parseDate(fromDate);
	const b = parseDate(toDate);
	return dayNumber(b.y, b.m, b.d) - dayNumber(a.y, a.m, a.d);
}

/** Add `days` (negative for the past) to a `YYYY-MM-DD` date. */
export function addDaysToStewardDate(date: string, days: number): string {
	const { y, m, d } = parseDate(date);
	const shifted = new Date(dayNumber(y, m, d) * MS_PER_DAY + days * MS_PER_DAY);
	return `${String(shifted.getUTCFullYear()).padStart(4, "0")}-${String(shifted.getUTCMonth() + 1).padStart(2, "0")}-${String(shifted.getUTCDate()).padStart(2, "0")}`;
}

/** A UTC instant that falls on the given calendar date in the steward's time zone. */
export function stewardDateToUtcMs(date: string): number {
	const { y, m, d } = parseDate(date);
	// Noon UTC on the civil date, corrected once by the zone's own reading, so any zone
	// offset (including UTC+13) lands on the requested local date.
	const target = dayNumber(y, m, d);
	const guess = Date.UTC(y, m - 1, d, 12, 0, 0);
	const seen = parseDate(stewardDate(guess));
	return guess + (target - dayNumber(seen.y, seen.m, seen.d)) * MS_PER_DAY;
}

/** Whole calendar days between an item's date (an epoch, or a MediaRef-shaped object
 * falling back from dateTakenMs to dateAddedMs) and `nowMs`. */
export function ageDays(
	item:
		| number
		| { dateTakenMs?: number | null; dateAddedMs?: number | null },
	nowMs: number,
): number {
	const taken = typeof item === "number" ? item : (item.dateTakenMs ?? item.dateAddedMs);
	if (taken === undefined || taken === null) return 0;
	return daysBetweenDates(stewardDate(taken), stewardDate(nowMs));
}
