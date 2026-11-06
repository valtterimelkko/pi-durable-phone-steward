package com.example.phonesteward.logic.files

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import com.example.phonesteward.logic.ByteFormat
import com.example.phonesteward.net.StewardTrashStatus

/** The Status card's words for the Steward trash. Pure. */
object TrashText {

    fun describe(status: StewardTrashStatus, zone: ZoneId): String {
        if (status.bytes <= 0L) {
            return "Nothing is waiting. Files the steward deletes for you wait here for 30 days, so you can still get them back."
        }
        val size = ByteFormat.format(status.bytes)
        val oldest = status.oldestMs?.let {
            Instant.ofEpochMilli(it).atZone(zone).format(DateTimeFormatter.ofPattern("d MMM", Locale.UK))
        }
        val age = if (oldest != null) "The oldest items went in on $oldest and are" else "Items are"
        return "Holds $size. $age removed for good after 30 days; empty it now to free the space at once."
    }
}
