package com.example.phonesteward.logic

import java.util.Locale

/**
 * Human byte formatting with decimal SI units (1 KB = 1000 B, 1 MB = 10^6 B,
 * 1 GB = 10^9 B), as Android's own storage screen and the steward server
 * show them. One decimal, always a dot separator regardless of locale, except
 * megabytes, which are whole numbers (N28: the server's `formatBytes` says
 * "612 MB" below 1 GB and "1.4 GB" from 1 GB on, and the app now says the same).
 * Owner decision 2026-10-08: it used to be binary (1024), which made a
 * 1,402,000,000 byte cache read "1.3 GB" where the steward said "about 1.4 GB".
 */
object ByteFormat {

    private val units = arrayOf("B", "KB", "MB", "GB", "TB")

    fun format(bytes: Long): String {
        require(bytes >= 0) { "negative size: $bytes" }
        if (bytes < 1000) return "$bytes B"
        // N28: the same rule as the server. From 1 GB a decimal; below it whole megabytes.
        if (bytes in 999_950L until 1_000_000_000L) return "${Math.round(bytes / 1_000_000.0)} MB"
        var value = bytes.toDouble()
        var unit = 0
        while (unit < units.lastIndex && Math.round(value * 10) >= 10_000L) {
            value /= 1000.0
            unit++
        }
        return String.format(Locale.ROOT, "%.1f %s", value, units[unit])
    }
}
