package com.example.phonesteward.logic.files

import org.junit.Assert.assertEquals
import org.junit.Test

/** A11.3: the restore result line in the owner's words. Pure. */
class RestoreTextTest {

    private fun item(path: String, type: String, bytes: Long, reason: String? = null) =
        TrashItem(path, type, bytes, blocked = reason != null, reason = reason)

    @Test
    fun `what went back is named with its size`() {
        val r = RestoreResult(restored = listOf(item("OfflineMaps/230606", "dir", 2_600_000_000)), blocked = emptyList())
        assertEquals("Put back 1 folder (2.6 GB).", RestoreText.result(r))
    }

    @Test
    fun `folders and files are counted together`() {
        val r = RestoreResult(
            restored = listOf(item("Dl/a.bin", "file", 300), item("Dl/b.bin", "file", 700), item("Maps", "dir", 1000)),
            blocked = emptyList(),
        )
        assertEquals("Put back 1 folder and 2 files (2.0 KB).", RestoreText.result(r))
    }

    @Test
    fun `what stays is named with its plain reason`() {
        val r = RestoreResult(restored = emptyList(), blocked = listOf(item("Download/old.apk", "file", 300, "something is at that place now")))
        assertEquals("1 item stays in the trash: something is at that place now.", RestoreText.result(r))
    }

    @Test
    fun `both halves are said together`() {
        val r = RestoreResult(
            restored = listOf(item("Maps/230606", "dir", 2_600_000_000)),
            blocked = listOf(
                item("Download/old.apk", "file", 300, "something is at that place now"),
                item("Download/new.apk", "file", 400, "something is at that place now"),
            ),
        )
        assertEquals("Put back 1 folder (2.6 GB). 2 items stay in the trash: something is at that place now.", RestoreText.result(r))
    }

    @Test
    fun `different reasons are both named`() {
        val r = RestoreResult(
            restored = emptyList(),
            blocked = listOf(
                item("Download/old.apk", "file", 300, "something is at that place now"),
                item("Dl", "dir", 100, "a symbolic link is never restored"),
            ),
        )
        assertEquals(
            "2 items stay in the trash: something is at that place now; a symbolic link is never restored.",
            RestoreText.result(r),
        )
    }

    @Test
    fun `nothing restored and nothing blocked says so plainly`() {
        assertEquals("Nothing was in the trash to put back.", RestoreText.result(RestoreResult(emptyList(), emptyList())))
    }
}
