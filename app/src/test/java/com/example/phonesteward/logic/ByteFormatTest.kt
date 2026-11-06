package com.example.phonesteward.logic

import org.junit.Assert.assertEquals
import org.junit.Test

class ByteFormatTest {

    @Test
    fun `zero bytes`() {
        assertEquals("0 B", ByteFormat.format(0))
    }

    @Test
    fun `bytes stay exact`() {
        assertEquals("512 B", ByteFormat.format(512))
        assertEquals("999 B", ByteFormat.format(999))
    }

    @Test
    fun `one kilobyte is 1000 bytes`() {
        assertEquals("1.0 KB", ByteFormat.format(1000))
        assertEquals("1.0 KB", ByteFormat.format(1024))
    }

    @Test
    fun `one and a half kilobytes`() {
        assertEquals("1.5 KB", ByteFormat.format(1500))
    }

    @Test
    fun `one megabyte is a million bytes`() {
        assertEquals("1 MB", ByteFormat.format(1_000_000))
        assertEquals("6 MB", ByteFormat.format(6L * 1024 * 1024))
    }

    @Test
    fun `N28 below a gigabyte megabytes are whole numbers, as the steward server says them`() {
        // The server's formatBytes: >= 1e9 -> one-decimal GB, otherwise Math.round(bytes / 1e6) MB.
        assertEquals("612 MB", ByteFormat.format(612_000_000))
        assertEquals("120 MB", ByteFormat.format(120_000_000))
        assertEquals("641 MB", ByteFormat.format(640_500_000))
        assertEquals("6 MB", ByteFormat.format(5_500_000))
        assertEquals("999 MB", ByteFormat.format(999_400_000))
        assertEquals("1000 MB", ByteFormat.format(999_999_999))
        assertEquals("1.0 GB", ByteFormat.format(1_000_000_000))
        assertEquals("1.4 GB", ByteFormat.format(1_402_000_000))
    }

    @Test
    fun `one gigabyte is a billion bytes`() {
        assertEquals("1.0 GB", ByteFormat.format(1_000_000_000))
    }

    @Test
    fun `the cache figure the steward says as about 1 point 4 GB reads the same`() {
        assertEquals("1.4 GB", ByteFormat.format(1_402_000_000))
    }

    @Test
    fun `typical phone total`() {
        // 128 GB device, as Android's own storage screen shows it
        assertEquals("131.1 GB", ByteFormat.format(131_100_000_000L))
    }

    @Test
    fun `terabyte range`() {
        assertEquals("1.0 TB", ByteFormat.format(1_000_000_000_000L))
    }

    @Test
    fun `a value that rounds up to 1000 moves to the next unit`() {
        assertEquals("1 MB", ByteFormat.format(999_950))
        assertEquals("999.9 KB", ByteFormat.format(999_900))
    }

    @Test
    fun `decimal separator is a dot regardless of locale`() {
        val before = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale.forLanguageTag("fi-FI"))
            assertEquals("1.5 KB", ByteFormat.format(1500))
        } finally {
            java.util.Locale.setDefault(before)
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun `negative sizes are rejected`() {
        ByteFormat.format(-1)
    }
}
