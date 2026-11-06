package com.example.phonesteward.logic.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** A10.1: paths are relative to the primary shared storage root; unsafe ones are refused; some can never be deleted. */
class FilePathsTest {

    @Test
    fun `the root is the empty path`() {
        assertEquals("", FilePaths.normalise(""))
        assertEquals("", FilePaths.normalise("."))
        assertEquals("", FilePaths.normalise("./"))
    }

    @Test
    fun `plain relative paths are kept and tidied`() {
        assertEquals("MapsWithMe", FilePaths.normalise("MapsWithMe"))
        assertEquals("MapsWithMe/230606", FilePaths.normalise("MapsWithMe/230606/"))
        assertEquals("a/b", FilePaths.normalise("./a//b/./"))
        assertEquals("Download/My Files", FilePaths.normalise("Download/My Files"))
    }

    @Test
    fun `absolute paths are refused`() {
        assertNull(FilePaths.normalise("/storage/emulated/0/Download"))
        assertNull(FilePaths.normalise("/"))
    }

    @Test
    fun `dot dot is refused wherever it sits`() {
        assertNull(FilePaths.normalise(".."))
        assertNull(FilePaths.normalise("../Download"))
        assertNull(FilePaths.normalise("Download/../../data"))
        assertNull(FilePaths.normalise("a/.."))
    }

    @Test
    fun `backslashes and NUL are refused`() {
        assertNull(FilePaths.normalise("a\\b"))
        assertNull(FilePaths.normalise("a\u0000b"))
    }

    @Test
    fun `names that merely contain dots are fine`() {
        assertEquals("a..b/c...", FilePaths.normalise("a..b/c..."))
        assertEquals(".hidden/x", FilePaths.normalise(".hidden/x"))
    }

    // ---- what can never be deleted ----

    private fun refused(path: String) = assertNotNull("$path should be refused", FilePaths.deleteRefusal(path))
    private fun allowed(path: String) = assertNull("$path should be allowed: ${FilePaths.deleteRefusal(path)}", FilePaths.deleteRefusal(path))

    @Test
    fun `the root is never a delete target`() {
        refused("")
    }

    @Test
    fun `Android and everything under it is refused, in any case`() {
        refused("Android")
        refused("Android/media")
        refused("Android/media/com.example.app/files")
        refused("android/data")
        refused("ANDROID/obb/x")
    }

    @Test
    fun `the Steward trash and everything under it is refused`() {
        refused(".StewardTrash")
        refused(".StewardTrash/2026-10-08/pr-1/MapsWithMe")
        refused(".stewardtrash/x")
    }

    @Test
    fun `any segment named Keep is refused, in any case`() {
        refused("Keep")
        refused("Documents/Keep/old.pdf")
        refused("Download/keep")
        refused("Pictures/KEEP/x") // also a media folder
        refused("Backups/KEEP/2026")
        allowed("Backups/Keepsakes/2026")
        allowed("Backups/Keep-this/2026")
    }

    @Test
    fun `the media folders go through trash_media, not files`() {
        refused("DCIM")
        refused("DCIM/Camera")
        refused("dcim/Camera/IMG_1.jpg")
        refused("Pictures")
        refused("Pictures/Screenshots/a.png")
        allowed("Documents/Pictures")
        allowed("Download/DCIM-backup")
    }

    @Test
    fun `ordinary folders are allowed`() {
        allowed("MapsWithMe")
        allowed("MapsWithMe/230606")
        allowed("Download/old-installer.apk")
        allowed("Movies/Trip")
        allowed("Music")
    }
}
