package com.example.phonesteward.logic

import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.example.phonesteward.logic.exec.IndexedMedia
import com.example.phonesteward.logic.exec.MediaIndexBuilder
import com.example.phonesteward.net.MediaRef
import com.example.phonesteward.net.TopLevelFile

/**
 * N5 + protocol A6: a folder scope is a SUBTREE. Both sides normalise to a
 * trailing `/` before a prefix comparison, so `DCIM/Camera` matches
 * `DCIM/Camera/` and `Android/media/com.whatsapp/` covers WhatsApp's deep
 * media folders. Exact matching was the MVP behaviour; it returned no
 * candidates in the first live weekly check .
 */
class FolderScopesTest {

    @Test
    fun `DCIM-Camera matches DCIM-Camera with or without the trailing slash`() {
        assertTrue(FolderScopes.contains("DCIM/Camera", "DCIM/Camera/"))
        assertTrue(FolderScopes.contains("DCIM/Camera/", "DCIM/Camera/"))
        assertTrue(FolderScopes.contains("DCIM/Camera", "DCIM/Camera"))
        assertTrue(FolderScopes.contains("DCIM/Camera/", "DCIM/Camera"))
    }

    @Test
    fun `a scope entry covers every subfolder`() {
        assertTrue(
            FolderScopes.contains(
                "Android/media/com.whatsapp/",
                "Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Images/",
            ),
        )
        assertTrue(FolderScopes.contains("Download/", "Download/Telegram/2026/"))
    }

    @Test
    fun `a look-alike prefix does not match`() {
        assertFalse(FolderScopes.contains("DCIM/Cam", "DCIM/Camera/"))
        assertFalse(FolderScopes.contains("DCIM/Camera2", "DCIM/Camera/"))
        assertFalse(FolderScopes.contains("Download", "Downloads/"))
    }

    @Test
    fun `outside the subtree does not match`() {
        assertFalse(FolderScopes.contains("DCIM/Camera/", "Pictures/Screenshots/"))
    }

    // ---- candidates through the builder ------------------------------------

    private val now = 1_800_000_000_000L
    private val zone = ZoneId.of("UTC")
    private val days160 = 160L * 24 * 60 * 60 * 1000

    private fun row(path: String, name: String, dateMs: Long, video: Boolean = false) = IndexedMedia(
        ref = MediaRef(
            mediaId = "external_primary:${if (video) "video" else "images"}:1",
            relativePath = path,
            displayName = name,
            mimeType = if (video) "video/mp4" else "image/jpeg",
            sizeBytes = 1_000L,
            dateTakenMs = dateMs,
            dateAddedMs = dateMs,
            isFavourite = false,
            inKeep = false,
        ),
        isImage = !video,
        isVideo = video,
    )

    @Test
    fun `the 160-day-old camera video is a candidate when the scope names DCIM-Camera`() {
        // The live N5 finding: the agent asked for DCIM/Camera, the phone
        // stores DCIM/Camera/, and the item never appeared.
        val data = MediaIndexBuilder.candidates(
            scope = listOf("DCIM/Camera"),
            rows = listOf(row("DCIM/Camera/", "VID_20260430_102844.mp4", now - days160, video = true)),
            olderThanDays = 120,
            nowMillis = now,
            zone = zone,
        )
        assertEquals(1, data.items.size)
    }

    @Test
    fun `a deep WhatsApp image is a candidate under the com-whatsapp scope`() {
        val data = MediaIndexBuilder.candidates(
            scope = listOf("Android/media/com.whatsapp/"),
            rows = listOf(
                row(
                    "Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Images/",
                    "IMG-20260101-WA0001.jpg",
                    now - days160,
                ),
            ),
            olderThanDays = 120,
            nowMillis = now,
            zone = zone,
        )
        assertEquals(1, data.items.size)
    }

    @Test
    fun `an item outside every scope entry is never a candidate`() {
        val data = MediaIndexBuilder.candidates(
            scope = listOf("DCIM/Camera/"),
            rows = listOf(row("Pictures/Screenshots/", "shot.png", now - days160)),
            olderThanDays = 120,
            nowMillis = now,
            zone = zone,
        )
        assertEquals(0, data.items.size)
    }

    @Test
    fun `a young item in scope is not a candidate`() {
        val data = MediaIndexBuilder.candidates(
            scope = listOf("DCIM/Camera/"),
            rows = listOf(row("DCIM/Camera/", "new.jpg", now - 5L * 24 * 60 * 60 * 1000)),
            olderThanDays = 120,
            nowMillis = now,
            zone = zone,
        )
        assertEquals(0, data.items.size)
    }

    // ---- summary scoping ----------------------------------------------------

    @Test
    fun `summary buckets cover subfolders of a scope entry`() {
        val sub = row("DCIM/Camera/sub/", "old.jpg", now - days160)
        val outside = row("Movies/", "film.mp4", now - days160, video = true)
        val data = MediaIndexBuilder.summary(
            scope = listOf("DCIM/Camera"),
            rows = listOf(sub, outside),
            topLevelFiles = emptyList<TopLevelFile>(),
            nowMillis = now,
            zone = zone,
        )
        val buckets = data.folders.first { it.folderKey == "DCIM/Camera/sub" }.buckets
        assertEquals(1, buckets?.over4m?.count)
        assertNull("folders outside every scope entry carry no buckets", data.folders.first { it.folderKey == "Movies" }.buckets)
    }
}
