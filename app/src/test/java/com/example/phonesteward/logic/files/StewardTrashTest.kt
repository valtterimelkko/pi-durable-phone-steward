package com.example.phonesteward.logic.files

import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import com.example.phonesteward.net.FileEntry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** A10.1: the Steward trash. A rename inside the same volume; space returns when it is emptied or 30 days pass. */
class StewardTrashTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var root: File
    private val zone: ZoneId = ZoneOffset.UTC
    private val day = LocalDate.of(2026, 10, 8)
    private var now = day.atTime(12, 0).atZone(zone).toInstant().toEpochMilli()

    private fun trash() = StewardTrash(root, zone) { now }

    @Before
    fun setUp() {
        root = tmp.newFolder("sdcard")
    }

    private fun file(rel: String, bytes: Int): File =
        File(root, rel).also { it.parentFile.mkdirs(); it.writeBytes(ByteArray(bytes) { 1 }) }

    private fun dateMs(d: LocalDate) = d.atStartOfDay(zone).toInstant().toEpochMilli()

    // ---- stat ----

    @Test
    fun `stat of a file gives its size and time and no file count`() {
        val f = file("Download/a.bin", 1234)
        f.setLastModified(1_790_000_000_000L)
        val s = trash().stat("Download/a.bin")!!
        assertEquals("file", s.type)
        assertEquals(1234L, s.sizeBytes)
        assertEquals(1_790_000_000_000L, s.modifiedMs)
        assertNull(s.fileCount)
    }

    @Test
    fun `stat of a folder gives recursive totals`() {
        file("MapsWithMe/230606/a.mwm", 1000)
        file("MapsWithMe/230606/sub/b.mwm", 500)
        file("MapsWithMe/other.txt", 10)
        val s = trash().stat("MapsWithMe")!!
        assertEquals("dir", s.type)
        assertEquals(1510L, s.sizeBytes)
        assertEquals(3L, s.fileCount)
    }

    @Test
    fun `stat of a missing path is null`() {
        assertNull(trash().stat("Nothing/here"))
    }

    @Test
    fun `stat refuses unsafe paths`() {
        assertNull(trash().stat("../x"))
        assertNull(trash().stat("/etc"))
    }

    @Test
    fun `stat does not follow a symlink out of the storage root`() {
        val outside = tmp.newFolder("outside")
        File(outside, "secret.bin").writeBytes(ByteArray(99))
        java.nio.file.Files.createSymbolicLink(File(root, "link").toPath(), outside.toPath())
        assertNull("a symlink is not a deletable thing", trash().stat("link"))
    }

    // ---- identity ----

    @Test
    fun `identity matches an unchanged file and folder`() {
        file("Download/a.bin", 10)
        file("Maps/x/1.mwm", 100)
        val t = trash()
        val f = t.stat("Download/a.bin")!!
        val d = t.stat("Maps")!!
        assertTrue(StewardTrash.matches(FileEntry("Download/a.bin", "file", 10, f.modifiedMs, null), f))
        assertTrue(StewardTrash.matches(FileEntry("Maps", "dir", 100, d.modifiedMs, 1), d))
    }

    @Test
    fun `identity fails when size, time, count or type changed`() {
        file("Download/a.bin", 10)
        file("Maps/x/1.mwm", 100)
        val t = trash()
        val f = t.stat("Download/a.bin")!!
        val d = t.stat("Maps")!!
        assertFalse(StewardTrash.matches(FileEntry("Download/a.bin", "file", 11, f.modifiedMs, null), f))
        assertFalse(StewardTrash.matches(FileEntry("Download/a.bin", "file", 10, f.modifiedMs + 1, null), f))
        assertFalse(StewardTrash.matches(FileEntry("Maps", "dir", 100, d.modifiedMs, 2), d))
        assertFalse(StewardTrash.matches(FileEntry("Maps", "dir", 101, d.modifiedMs, 1), d))
        assertFalse(StewardTrash.matches(FileEntry("Maps", "file", 100, d.modifiedMs, null), d))
    }

    // ---- move ----

    @Test
    fun `a folder moves into dated proposal folder keeping its path`() {
        file("MapsWithMe/230606/a.mwm", 1000)
        file("MapsWithMe/230606/b.mwm", 500)
        val result = trash().move("pr-7", "MapsWithMe/230606")
        assertTrue(result.toString(), result is MoveResult.Moved)
        assertEquals(1500L, (result as MoveResult.Moved).trashedBytes)
        assertFalse(File(root, "MapsWithMe/230606").exists())
        assertTrue(File(root, "MapsWithMe").exists())
        assertEquals(1000L, File(root, ".StewardTrash/2026-10-08/pr-7/MapsWithMe/230606/a.mwm").length())
    }

    @Test
    fun `a file moves too`() {
        file("Download/old.apk", 300)
        val r = trash().move("pr-1", "Download/old.apk") as MoveResult.Moved
        assertEquals(300L, r.trashedBytes)
        assertTrue(File(root, ".StewardTrash/2026-10-08/pr-1/Download/old.apk").isFile)
    }

    @Test
    fun `the trash keeps media scanners out`() {
        file("Download/old.apk", 300)
        trash().move("pr-1", "Download/old.apk")
        assertTrue(File(root, ".StewardTrash/.nomedia").exists())
    }

    @Test
    fun `a missing path fails and nothing is created`() {
        val r = trash().move("pr-1", "Nope/x")
        assertTrue(r is MoveResult.Failed)
        assertFalse(File(root, ".StewardTrash/2026-10-08/pr-1").exists())
    }

    @Test
    fun `refused paths are never moved, even if asked`() {
        file("Android/media/x/1.bin", 5)
        file("DCIM/Camera/1.jpg", 5)
        file("Docs/Keep/a.txt", 5)
        for (p in listOf("Android/media", "DCIM/Camera", "Docs/Keep", "", "../x", ".StewardTrash")) {
            assertTrue("$p must be refused", trash().move("pr-1", p) is MoveResult.Failed)
        }
        assertTrue(File(root, "Android/media/x/1.bin").exists())
        assertTrue(File(root, "DCIM/Camera/1.jpg").exists())
        assertTrue(File(root, "Docs/Keep/a.txt").exists())
    }

    @Test
    fun `a proposal id that is not a plain name is refused`() {
        file("Download/old.apk", 300)
        assertTrue(trash().move("../pr", "Download/old.apk") is MoveResult.Failed)
        assertTrue(trash().move("", "Download/old.apk") is MoveResult.Failed)
        assertTrue(File(root, "Download/old.apk").exists())
    }

    @Test
    fun `moving the same path again for the same proposal does not overwrite`() {
        file("Download/old.apk", 300)
        assertTrue(trash().move("pr-1", "Download/old.apk") is MoveResult.Moved)
        file("Download/old.apk", 20)
        assertTrue(trash().move("pr-1", "Download/old.apk") is MoveResult.Failed)
        assertEquals(300L, File(root, ".StewardTrash/2026-10-08/pr-1/Download/old.apk").length())
        assertTrue("the second file stays where it was", File(root, "Download/old.apk").exists())
    }

    @Test
    fun `a symlink is not moved`() {
        val outside = tmp.newFolder("outside2")
        java.nio.file.Files.createSymbolicLink(File(root, "link").toPath(), outside.toPath())
        assertTrue(trash().move("pr-1", "link") is MoveResult.Failed)
        assertTrue(outside.exists())
    }

    // ---- move only if it is still the reviewed thing (the re-stat after the owner's confirmation) ----

    @Test
    fun `moveIfUnchanged moves an item that is still what the proposal named`() {
        file("Maps/a.mwm", 1000)
        val t = trash()
        val s = t.stat("Maps")!!
        val entry = FileEntry("Maps", "dir", s.sizeBytes, s.modifiedMs, s.fileCount)
        val r = t.moveIfUnchanged("pr-9", entry)
        assertEquals(MoveResult.Moved(1000), r)
        assertFalse(File(root, "Maps").exists())
    }

    @Test
    fun `moveIfUnchanged does nothing when the item changed while the owner read the dialog`() {
        file("Maps/a.mwm", 1000)
        val t = trash()
        val s = t.stat("Maps")!!
        val entry = FileEntry("Maps", "dir", s.sizeBytes, s.modifiedMs, s.fileCount)
        file("Maps/b.mwm", 5) // a new file arrives
        val r = t.moveIfUnchanged("pr-9", entry)
        assertTrue(r.toString(), r is MoveResult.Failed)
        assertTrue((r as MoveResult.Failed).reason.contains("changed"))
        assertTrue(File(root, "Maps/a.mwm").exists())
        assertFalse(File(root, ".StewardTrash/2026-10-08").exists())
    }

    @Test
    fun `moveIfUnchanged on a vanished item fails`() {
        val entry = FileEntry("Gone", "dir", 1, 1, 1)
        assertTrue(trash().moveIfUnchanged("pr-9", entry) is MoveResult.Failed)
    }

    // ---- status ----

    @Test
    fun `an empty or missing trash reports zero and no oldest`() {
        val s = trash().status()
        assertEquals(0L, s.bytes)
        assertNull(s.oldestMs)
    }

    @Test
    fun `status sums the trash and names the oldest dated folder`() {
        file(".StewardTrash/2026-09-20/pr-a/Maps/a.bin", 1000)
        file(".StewardTrash/2026-10-05/pr-b/Dl/b.bin", 250)
        val s = trash().status()
        assertEquals(1250L, s.bytes)
        assertEquals(dateMs(LocalDate.of(2026, 9, 20)), s.oldestMs)
    }

    // ---- purge ----

    @Test
    fun `purge removes dated folders older than 30 days and keeps the rest`() {
        // today is 2026-10-08: 30 days back is 2026-09-08 (kept), 31 days back is 2026-09-07 (purged)
        file(".StewardTrash/2026-09-07/pr-old/Maps/a.bin", 1000)
        file(".StewardTrash/2026-09-08/pr-edge/Maps/b.bin", 200)
        file(".StewardTrash/2026-10-07/pr-new/Maps/c.bin", 30)
        val r = trash().purgeOlderThan(30)
        assertEquals(1000L, r.bytesRemoved)
        assertEquals(1, r.foldersRemoved)
        assertFalse(File(root, ".StewardTrash/2026-09-07").exists())
        assertTrue(File(root, ".StewardTrash/2026-09-08/pr-edge/Maps/b.bin").exists())
        assertTrue(File(root, ".StewardTrash/2026-10-07/pr-new/Maps/c.bin").exists())
    }

    @Test
    fun `purge leaves things that are not dated folders alone`() {
        file(".StewardTrash/notes/readme.txt", 5)
        trash().purgeOlderThan(30)
        assertTrue(File(root, ".StewardTrash/notes/readme.txt").exists())
    }

    @Test
    fun `purge on a missing trash is a no-op`() {
        val r = trash().purgeOlderThan(30)
        assertEquals(0L, r.bytesRemoved)
        assertEquals(0, r.foldersRemoved)
    }

    // ---- empty ----

    @Test
    fun `empty removes everything and says how much came back`() {
        file(".StewardTrash/2026-09-20/pr-a/Maps/a.bin", 1000)
        file(".StewardTrash/2026-10-05/pr-b/Dl/b.bin", 250)
        val freed = trash().empty()
        assertEquals(1250L, freed)
        assertEquals(0L, trash().status().bytes)
        assertNull(trash().status().oldestMs)
        assertFalse(File(root, ".StewardTrash/2026-09-20").exists())
    }

    @Test
    fun `empty never touches anything outside the trash`() {
        file("Download/keep.bin", 77)
        file(".StewardTrash/2026-09-20/pr-a/x.bin", 5)
        trash().empty()
        assertEquals(77L, File(root, "Download/keep.bin").length())
    }

    @Test
    fun `empty does not follow a symlink inside the trash`() {
        val outside = tmp.newFolder("outside3")
        File(outside, "precious.bin").writeBytes(ByteArray(40))
        File(root, ".StewardTrash/2026-09-20/pr-a").mkdirs()
        java.nio.file.Files.createSymbolicLink(File(root, ".StewardTrash/2026-09-20/pr-a/link").toPath(), outside.toPath())
        trash().empty()
        assertTrue("the target of the link is untouched", File(outside, "precious.bin").exists())
        assertNotNull(outside.listFiles())
    }
}
