import { afterEach, describe, expect, it } from "vitest";
import { addDaysToStewardDate, ageDays, setTimeZone, stewardDate, stewardDateToUtcMs } from "../src/time.ts";

/** Helper: UTC ms for a UTC wall-clock time. */
function utc(y: number, m: number, d: number, hh = 12, mm = 0): number {
	return Date.UTC(y, m - 1, d, hh, mm);
}

describe("stewardDate (the configured time zone, default UTC)", () => {
	afterEach(() => setTimeZone(undefined));

	it("defaults to UTC: the calendar date is the UTC date", () => {
		expect(stewardDate(utc(2026, 1, 1, 23, 30))).toBe("2026-01-01");
		expect(stewardDate(utc(2026, 6, 1, 23, 0))).toBe("2026-06-01");
	});

	it("follows the configured zone: Europe/London rolls the June date at 23:00 UTC", () => {
		setTimeZone("Europe/London");
		expect(stewardDate(utc(2026, 6, 1, 23, 0))).toBe("2026-06-02");
		expect(stewardDate(utc(2026, 1, 1, 23, 30))).toBe("2026-01-01");
	});

	it("follows a far-east zone and keeps the date arithmetic exact there", () => {
		setTimeZone("Pacific/Auckland");
		// 2026-06-01 23:00 UTC is 2026-06-02 11:00 in Auckland (UTC+12 in June).
		expect(stewardDate(utc(2026, 6, 1, 23, 0))).toBe("2026-06-02");
		// The date-to-instant helper returns an instant on the requested Auckland date.
		const at = stewardDateToUtcMs("2026-06-02");
		expect(stewardDate(at)).toBe("2026-06-02");
		expect(addDaysToStewardDate("2026-06-02", -90)).toBe("2026-03-04");
		expect(ageDays(stewardDateToUtcMs("2026-03-04"), at)).toBe(90);
	});
});

describe("ageDays across the 2026 DST changes", () => {
	it("counts calendar dates, not 86400000-second blocks, across the spring forward", () => {
		// Item taken 2026-01-01 18:00 UTC (London 18:00 GMT). Now is 2026-04-01 17:00 UTC,
		// which is London 18:00 BST: exactly 90 London calendar days later, though the raw
		// elapsed time is 90 days minus one hour. A naive ms/86400000 floor would say 89.
		const item = utc(2026, 1, 1, 18, 0);
		const now = utc(2026, 4, 1, 17, 0);
		expect(ageDays(item, now)).toBe(90);
	});

	it("keeps the exact 90-day edge across the spring-forward Sunday (29 March 2026)", () => {
		// 90 London calendar days before 2026-03-31 is 2025-12-31.
		expect(addDaysToStewardDate("2026-03-31", -90)).toBe("2025-12-31");
		// An item from 2025-12-31 23:59 UTC is exactly 90 days old on 2026-03-31 any time.
		const item = utc(2025, 12, 31, 23, 59);
		const now = stewardDateToUtcMs("2026-03-31") + 3600_000; // any time that London day
		expect(ageDays(item, now)).toBe(90);
	});

	it("keeps the exact 120-day edge across the autumn back (25 October 2026)", () => {
		// 120 London calendar days before 2026-10-29 is 2026-07-01.
		expect(addDaysToStewardDate("2026-10-29", -120)).toBe("2026-07-01");
		// 2026-10-25 01:30 UTC happens twice (BST then GMT); date maths is unaffected.
		const item = utc(2026, 7, 1, 0, 0);
		const now = utc(2026, 10, 29, 0, 0);
		expect(ageDays(item, now)).toBe(120);
	});

	it("places an item exactly at reminderDays in the reminder window and cutoffDays in the batch window", () => {
		const reminderDays = 90;
		const cutoffDays = 120;
		const now = stewardDateToUtcMs("2026-03-31");
		const atReminder = stewardDateToUtcMs(addDaysToStewardDate("2026-03-31", -90));
		const atCutoff = stewardDateToUtcMs(addDaysToStewardDate("2026-03-31", -120));
		expect(ageDays(atReminder, now)).toBe(reminderDays);
		expect(ageDays(atCutoff, now)).toBe(cutoffDays);
		expect(ageDays(atReminder, now) >= reminderDays && ageDays(atReminder, now) < cutoffDays).toBe(true);
		expect(ageDays(atCutoff, now) >= cutoffDays).toBe(true);
	});

	it("falls back to dateAdded when dateTaken is missing", () => {
		const now = stewardDateToUtcMs("2026-03-31");
		expect(ageDays({ dateTakenMs: undefined, dateAddedMs: utc(2026, 3, 1) }, now)).toBe(30);
	});
});
