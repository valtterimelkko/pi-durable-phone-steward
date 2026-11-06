package com.example.phonesteward.logic

import org.junit.Assert.assertEquals
import org.junit.Test

class FolderGroupingTest {

    private fun row(path: String?, bytes: Long = 1, name: String = "x") =
        MediaRow(
            displayName = name,
            relativePath = path,
            sizeBytes = bytes,
            dateTakenMillis = null,
            dateAddedMillis = null,
            isImage = false,
            isVideo = false,
            isFavourite = false,
        )

    @Test
    fun `whatsapp media groups at the package level`() {
        assertEquals(
            "Android/media/com.whatsapp",
            FolderGrouping.folderKey("Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Video"),
        )
        assertEquals(
            "Android/media/com.whatsapp",
            FolderGrouping.folderKey("Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Images"),
        )
    }

    @Test
    fun `telegram media groups at the package level`() {
        assertEquals(
            "Android/media/org.telegram.messenger",
            FolderGrouping.folderKey("Android/media/org.telegram.messenger/Telegram/Telegram Video"),
        )
    }

    @Test
    fun `bare package paths keep two segments`() {
        assertEquals(
            "org.telegram.messenger/Telegram",
            FolderGrouping.folderKey("org.telegram.messenger/Telegram"),
        )
    }

    @Test
    fun `camera and screenshot folders group at the second segment`() {
        assertEquals("DCIM/Camera", FolderGrouping.folderKey("DCIM/Camera"))
        assertEquals("Pictures/Screenshots", FolderGrouping.folderKey("Pictures/Screenshots"))
    }

    @Test
    fun `download keeps its single segment`() {
        assertEquals("Download", FolderGrouping.folderKey("Download"))
    }

    @Test
    fun `null blank and root paths go to the no-folder bucket`() {
        assertEquals("(no folder)", FolderGrouping.folderKey(null))
        assertEquals("(no folder)", FolderGrouping.folderKey(""))
        assertEquals("(no folder)", FolderGrouping.folderKey("/"))
    }

    @Test
    fun `a bare file name has no directory and goes to no-folder`() {
        assertEquals("(no folder)", FolderGrouping.folderKey("readme.txt"))
    }

    @Test
    fun `grouping sums bytes and counts items per folder`() {
        val rows = listOf(
            row("Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Video", bytes = 300, name = "a.mp4"),
            row("Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Images", bytes = 200, name = "b.jpg"),
            row("DCIM/Camera", bytes = 50, name = "c.jpg"),
        )
        val grouped = FolderGrouping.group(rows)
        assertEquals(2, grouped.size)
        assertEquals(
            FolderRow(folderKey = "Android/media/com.whatsapp", bytes = 500, itemCount = 2),
            grouped[0],
        )
        assertEquals(
            FolderRow(folderKey = "DCIM/Camera", bytes = 50, itemCount = 1),
            grouped[1],
        )
    }

    @Test
    fun `grouping sorts by bytes descending`() {
        val rows = listOf(
            row("Download", bytes = 10, name = "d.pdf"),
            row("DCIM/Camera", bytes = 999, name = "e.jpg"),
            row("Pictures/Screenshots", bytes = 5, name = "f.png"),
        )
        val keys = FolderGrouping.group(rows).map { it.folderKey }
        assertEquals(listOf("DCIM/Camera", "Download", "Pictures/Screenshots"), keys)
    }
}
