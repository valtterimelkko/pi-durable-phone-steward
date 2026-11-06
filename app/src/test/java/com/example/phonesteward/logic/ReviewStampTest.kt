package com.example.phonesteward.logic

import java.time.ZoneId
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** N15: the proposal card says which review it comes from, in device local time. */
class ReviewStampTest {

    // 2026-10-07 19:57:30 UTC
    private val createdAtMs = 1_791_403_050_000L

    @Test
    fun `the stamp reads from the review of day month and time`() {
        assertEquals(
            "From the review of 7 Oct 19:57",
            ReviewStamp.text(createdAtMs, ZoneId.of("UTC"), Locale.UK),
        )
    }

    @Test
    fun `the stamp follows the device time zone`() {
        assertEquals(
            "From the review of 7 Oct 20:57",
            ReviewStamp.text(createdAtMs, ZoneId.of("Europe/London"), Locale.UK),
        )
    }

    @Test
    fun `no creation time means no stamp`() {
        assertNull(ReviewStamp.text(0, ZoneId.of("UTC"), Locale.UK))
    }
}
