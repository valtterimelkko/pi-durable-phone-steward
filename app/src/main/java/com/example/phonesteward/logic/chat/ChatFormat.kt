package com.example.phonesteward.logic.chat

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Small text formatting for the chat rows. Pure. */
object ChatFormat {

    fun duration(ms: Long): String = when {
        ms < 100 -> "<0.1 s"
        ms < 10_000 -> String.format(Locale.ROOT, "%.1f s", ms / 1000.0)
        ms < 60_000 -> "${ms / 1000} s"
        else -> {
            val minutes = ms / 60_000
            val seconds = (ms % 60_000) / 1000
            if (seconds == 0L) "$minutes min" else "$minutes min $seconds s"
        }
    }

    /** `HH:mm` for today, `d MMM HH:mm` otherwise; empty when the time is unknown. */
    fun time(atMs: Long, nowMs: Long, zone: ZoneId = ZoneId.systemDefault()): String {
        if (atMs <= 0) return ""
        val at = Instant.ofEpochMilli(atMs).atZone(zone)
        val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
        val pattern = if (at.toLocalDate() == today) "HH:mm" else "d MMM HH:mm"
        return at.format(DateTimeFormatter.ofPattern(pattern, Locale.UK))
    }
}
