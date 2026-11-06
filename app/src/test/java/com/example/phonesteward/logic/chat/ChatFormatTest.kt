package com.example.phonesteward.logic.chat

import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

class ChatFormatTest {
    private val utc = ZoneId.of("UTC")

    @Test fun durations() {
        assertEquals("0.4 s", ChatFormat.duration(400))
        assertEquals("1.2 s", ChatFormat.duration(1_234))
        assertEquals("12 s", ChatFormat.duration(12_000))
        assertEquals("2 min 5 s", ChatFormat.duration(125_000))
        assertEquals("3 min", ChatFormat.duration(180_000))
        assertEquals("<0.1 s", ChatFormat.duration(20))
    }

    @Test fun todayShowsOnlyTheTime() {
        val now = 1_791_403_050_000L // 2026-10-07 19:57 UTC
        assertEquals("19:37", ChatFormat.time(now - 20 * 60_000, now, utc))
    }

    @Test fun anotherDayShowsTheDate() {
        val now = 1_791_403_050_000L
        assertEquals("5 Oct 19:57", ChatFormat.time(now - 2 * 86_400_000L, now, utc))
    }

    @Test fun unknownTimeIsEmpty() = assertEquals("", ChatFormat.time(0, 1_791_403_050_000L, utc))
}
