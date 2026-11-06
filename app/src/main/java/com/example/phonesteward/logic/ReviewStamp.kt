package com.example.phonesteward.logic

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** N15: "From the review of 7 Oct 19:57" for a proposal card, in device local time. */
object ReviewStamp {

    fun text(
        createdAtMs: Long,
        zone: ZoneId = ZoneId.systemDefault(),
        locale: Locale = Locale.getDefault(),
    ): String? {
        if (createdAtMs <= 0) return null
        val when_ = Instant.ofEpochMilli(createdAtMs)
            .atZone(zone)
            .format(DateTimeFormatter.ofPattern("d MMM HH:mm", locale))
        return "From the review of $when_"
    }
}
