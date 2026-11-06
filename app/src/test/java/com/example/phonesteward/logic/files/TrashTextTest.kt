package com.example.phonesteward.logic.files

import java.time.LocalDate
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Test
import com.example.phonesteward.net.StewardTrashStatus

/** The Status card's words for the Steward trash. */
class TrashTextTest {

    private val zone = ZoneOffset.UTC
    private fun ms(d: LocalDate) = d.atStartOfDay(zone).toInstant().toEpochMilli()

    @Test
    fun `an empty trash says what the trash is for`() {
        assertEquals(
            "Nothing is waiting. Files the steward deletes for you wait here for 30 days, so you can still get them back.",
            TrashText.describe(StewardTrashStatus(), zone),
        )
    }

    @Test
    fun `a trash with items names its size and the oldest day`() {
        assertEquals(
            "Holds 2.6 GB. The oldest items went in on 3 Oct and are removed for good after 30 days; empty it now to free the space at once.",
            TrashText.describe(StewardTrashStatus(2_600_000_000L, ms(LocalDate.of(2026, 10, 3))), zone),
        )
    }

    @Test
    fun `a trash whose age is unknown still names its size`() {
        assertEquals(
            "Holds 40 MB. Items are removed for good after 30 days; empty it now to free the space at once.",
            TrashText.describe(StewardTrashStatus(40_000_000L, null), zone),
        )
    }
}
