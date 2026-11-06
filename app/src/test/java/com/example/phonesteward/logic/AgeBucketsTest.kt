package com.example.phonesteward.logic

import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Boundary tests for the  age rule: remind at 3 months, trash at 4 months.
 * Bucket edges: exactly 3 months starts the reminder bucket; exactly 4 months
 * has reached the cut-off (OVER_4_MONTHS).
 */
class AgeBucketsTest {

    private val london = ZoneId.of("Europe/London")

    private fun millis(t: ZonedDateTime): Long = t.toInstant().toEpochMilli()

    @Test
    fun `exactly three months old enters the reminder bucket`() {
        val now = ZonedDateTime.of(2026, 10, 6, 12, 0, 0, 0, london)
        val taken = now.minusMonths(3)
        assertEquals(
            AgeBucket.MONTHS_3_TO_4,
            AgeBuckets.bucketFor(millis(taken), null, millis(now), london),
        )
    }

    @Test
    fun `one minute shy of three months is still under three months`() {
        val now = ZonedDateTime.of(2026, 10, 6, 12, 0, 0, 0, london)
        val taken = now.minusMonths(3).plusMinutes(1)
        assertEquals(
            AgeBucket.UNDER_3_MONTHS,
            AgeBuckets.bucketFor(millis(taken), null, millis(now), london),
        )
    }

    @Test
    fun `one minute past three months sits in the reminder bucket`() {
        val now = ZonedDateTime.of(2026, 10, 6, 12, 0, 0, 0, london)
        val taken = now.minusMonths(3).minusMinutes(1)
        assertEquals(
            AgeBucket.MONTHS_3_TO_4,
            AgeBuckets.bucketFor(millis(taken), null, millis(now), london),
        )
    }

    @Test
    fun `exactly four months old has reached the cut-off`() {
        val now = ZonedDateTime.of(2026, 10, 6, 12, 0, 0, 0, london)
        val taken = now.minusMonths(4)
        assertEquals(
            AgeBucket.OVER_4_MONTHS,
            AgeBuckets.bucketFor(millis(taken), null, millis(now), london),
        )
    }

    @Test
    fun `one minute shy of four months sits in the reminder bucket`() {
        val now = ZonedDateTime.of(2026, 10, 6, 12, 0, 0, 0, london)
        val taken = now.minusMonths(4).plusMinutes(1)
        assertEquals(
            AgeBucket.MONTHS_3_TO_4,
            AgeBuckets.bucketFor(millis(taken), null, millis(now), london),
        )
    }

    @Test
    fun `seven months old is over the cut-off`() {
        val now = ZonedDateTime.of(2026, 10, 6, 12, 0, 0, 0, london)
        val taken = now.minusMonths(7)
        assertEquals(
            AgeBucket.OVER_4_MONTHS,
            AgeBuckets.bucketFor(millis(taken), null, millis(now), london),
        )
    }

    @Test
    fun `month length edge - 31 May minus three months is 28 February and the item at that instant is in the reminder bucket`() {
        val now = ZonedDateTime.of(2026, 5, 31, 12, 0, 0, 0, london)
        val boundary = now.minusMonths(3) // 2026 is not a leap year
        assertEquals(ZonedDateTime.of(2026, 2, 28, 12, 0, 0, 0, london).toInstant(), boundary.toInstant())
        assertEquals(
            AgeBucket.MONTHS_3_TO_4,
            AgeBuckets.bucketFor(millis(boundary), null, millis(now), london),
        )
        // The day after the short-month boundary is already "under three months".
        val after = ZonedDateTime.of(2026, 3, 1, 0, 0, 0, 0, london)
        assertEquals(
            AgeBucket.UNDER_3_MONTHS,
            AgeBuckets.bucketFor(millis(after), null, millis(now), london),
        )
    }

    @Test
    fun `month length edge - 31 March minus four months normalises to 30 November`() {
        val now = ZonedDateTime.of(2026, 3, 31, 12, 0, 0, 0, london)
        val boundary = now.minusMonths(4)
        assertEquals(ZonedDateTime.of(2025, 11, 30, 12, 0, 0, 0, london).toInstant(), boundary.toInstant())
        assertEquals(
            AgeBucket.OVER_4_MONTHS,
            AgeBuckets.bucketFor(millis(boundary), null, millis(now), london),
        )
        val justInside = ZonedDateTime.of(2025, 12, 1, 0, 0, 0, 0, london)
        assertEquals(
            AgeBucket.MONTHS_3_TO_4,
            AgeBuckets.bucketFor(millis(justInside), null, millis(now), london),
        )
    }

    @Test
    fun `time zone edge - the three month boundary moves with DST so the same instants bucket differently`() {
        // New York on 4 Nov 2026 is EST (-5, DST has ended); August was EDT (-4).
        val newYork = ZoneId.of("America/New_York")
        // Fixed UTC-5 has no DST, so its three-month boundary is one hour later.
        val fixedMinusFive = ZoneId.of("GMT-05:00")

        val nowInstant = ZonedDateTime.of(2026, 11, 4, 12, 0, 0, 0, newYork).toInstant()
        // 16:30 UTC on 4 Aug 2026: after New York's boundary (16:00 UTC, EDT),
        // but before the fixed zone's boundary (17:00 UTC).
        val takenInstant = ZonedDateTime.of(2026, 8, 4, 12, 30, 0, 0, ZoneId.of("GMT-04:00")).toInstant()

        assertEquals(
            AgeBucket.UNDER_3_MONTHS,
            AgeBuckets.bucketFor(takenInstant.toEpochMilli(), null, nowInstant.toEpochMilli(), newYork),
        )
        assertEquals(
            AgeBucket.MONTHS_3_TO_4,
            AgeBuckets.bucketFor(takenInstant.toEpochMilli(), null, nowInstant.toEpochMilli(), fixedMinusFive),
        )
    }

    @Test
    fun `missing DATE_TAKEN falls back to DATE_ADDED`() {
        val now = ZonedDateTime.of(2026, 10, 6, 12, 0, 0, 0, london)
        val tenDaysAgo = millis(now.minusDays(10))
        val hundredDaysAgo = millis(now.minusDays(100))
        assertEquals(
            AgeBucket.UNDER_3_MONTHS,
            AgeBuckets.bucketFor(null, tenDaysAgo, millis(now), london),
        )
        assertEquals(
            AgeBucket.MONTHS_3_TO_4,
            AgeBuckets.bucketFor(null, hundredDaysAgo, millis(now), london),
        )
    }

    @Test
    fun `zero DATE_TAKEN counts as missing and falls back`() {
        val now = ZonedDateTime.of(2026, 10, 6, 12, 0, 0, 0, london)
        val tenDaysAgo = millis(now.minusDays(10))
        assertEquals(
            AgeBucket.UNDER_3_MONTHS,
            AgeBuckets.bucketFor(0L, tenDaysAgo, millis(now), london),
        )
    }

    @Test
    fun `missing both dates lands in the unknown bucket`() {
        val now = ZonedDateTime.of(2026, 10, 6, 12, 0, 0, 0, london)
        assertEquals(
            AgeBucket.UNKNOWN,
            AgeBuckets.bucketFor(null, null, millis(now), london),
        )
    }

    @Test
    fun `a future timestamp is treated as under three months`() {
        val now = ZonedDateTime.of(2026, 10, 6, 12, 0, 0, 0, london)
        val tomorrow = millis(now.plusDays(1))
        assertEquals(
            AgeBucket.UNDER_3_MONTHS,
            AgeBuckets.bucketFor(tomorrow, null, millis(now), london),
        )
    }
}
