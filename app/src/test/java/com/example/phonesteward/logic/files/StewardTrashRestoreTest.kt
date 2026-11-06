package com.example.phonesteward.logic.files

import java.io.File
import java.nio.file.Files
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * A11.3: restore from the Steward trash. The merge-descend rule, run with and without moving: an item whose original
 * path is free goes back (parents recreated, a same-volume rename); a folder that now stands where the item was is
 * descended into; anything else is refused for that entry with a plain reason and stays in the trash. No manifest is
 * needed, so a batch moved before A11 lists and restores correctly.
 */
class StewardTrashRestoreTest {

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

    private fun batches(): List<TrashBatch> = trash().listTrash()
    private fun batch(proposalId: String): TrashBatch = batches().single { it.proposalId == proposalId }
    private fun item(b: TrashBatch, path: String): TrashItem = b.items.single { it.path == path }

    // ---- listing: the dry run ----

    @Test
    fun `list groups by dated proposal folder, newest first, with paths and sizes`() {
        file(".StewardTrash/2026-09-20/pr-a/MapsOne/230606/a.bin", 1000)
        file(".StewardTrash/2026-10-05/pr-b/Dl/b.bin", 250)
        file(".StewardTrash/2026-10-05/pr-b/Dl/c.bin", 250)
        val list = batches()
        assertEquals(listOf("pr-b", "pr-a"), list.map { it.proposalId })
        val b = list[0]
        assertEquals("2026-10-05", b.date)
        assertEquals(dateMs(LocalDate.of(2026, 10, 5)), b.dateMs)
        assertEquals(listOf("Dl"), b.items.map { it.path })
        assertEquals("dir", b.items[0].type)
        assertEquals(500L, b.items[0].sizeBytes)
        assertEquals(500L, b.sizeBytes)
    }

    @Test
    fun `list on an empty trash says nothing is there`() {
        assertEquals(emptyList<TrashBatch>(), batches())
    }

    @Test
    fun `list rebuilds the original path of a batch moved without a manifest`() {
        // The proposal moved MapsWithMe/230606 while MapsWithMe/bookmarks stayed live: the trash has no manifest.
        file("MapsWithMe/bookmarks/b.bin", 30)
        file(".StewardTrash/2026-10-08/pr-7/MapsWithMe/230606/a.mwm", 1000)
        val b = batch("pr-7")
        assertEquals(listOf("MapsWithMe/230606"), b.items.map { it.path })
        assertEquals("dir", b.items[0].type)
        assertEquals(1000L, b.items[0].sizeBytes)
        assertFalse(b.items[0].blocked)
    }

    @Test
    fun `list marks an item blocked when something now stands at its path`() {
        file("Download/old.apk", 40) // a new file arrived at the original path
        file(".StewardTrash/2026-10-08/pr-1/Download/old.apk", 300)
        val blocked = batch("pr-1").items.single()
        assertTrue(blocked.blocked)
        assertEquals("something is at that place now", blocked.reason)
        assertEquals(300L, blocked.sizeBytes)
    }

    @Test
    fun `list descends into a folder that now stands at the path and shows only the free children`() {
        file("Download/sub/rebuilt.bin", 10) // a new child inside the folder the proposal moved
        file(".StewardTrash/2026-10-08/pr-2/Download/sub/a.bin", 100)
        file(".StewardTrash/2026-10-08/pr-2/Download/sub/rebuilt.bin", 20)
        val b = batch("pr-2")
        val free = b.items.single { !it.blocked }
        assertEquals("Download/sub/a.bin", free.path)
        assertEquals("file", free.type)
        assertEquals(100L, free.sizeBytes)
        val clash = b.items.single { it.blocked }
        assertEquals("Download/sub/rebuilt.bin", clash.path)
        assertEquals("something is at that place now", clash.reason)
    }

    // ---- restoring ----

    @Test
    fun `restoring a whole batch puts every item back where it was`() {
        file(".StewardTrash/2026-10-08/pr-1/MapsOne/230606/a.bin", 1000)
        file(".StewardTrash/2026-10-08/pr-1/MapsOne/230606/sub/b.bin", 500)
        val r = trash().restoreBatch(batch("pr-1"))
        assertTrue(r.blocked.isEmpty())
        assertEquals(listOf("MapsOne"), r.restored.map { it.path })
        assertEquals(1500L, r.bytesRestored)
        assertEquals(1000L, File(root, "MapsOne/230606/a.bin").length())
        assertEquals(500L, File(root, "MapsOne/230606/sub/b.bin").length())
    }

    @Test
    fun `restoring a batch moved without a manifest puts the folder back inside the one that stayed`() {
        file("MapsWithMe/bookmarks/b.bin", 30)
        file(".StewardTrash/2026-10-08/pr-7/MapsWithMe/230606/a.mwm", 1000)
        val r = trash().restoreBatch(batch("pr-7"))
        assertEquals(listOf("MapsWithMe/230606"), r.restored.map { it.path })
        assertEquals(1000L, File(root, "MapsWithMe/230606/a.mwm").length())
        assertEquals("what stayed is untouched", 30L, File(root, "MapsWithMe/bookmarks/b.bin").length())
    }

    @Test
    fun `restoring one item leaves the rest of its batch in the trash`() {
        file(".StewardTrash/2026-10-08/pr-1/Dl/a.bin", 100)
        file(".StewardTrash/2026-10-08/pr-1/Docs/b.bin", 200)
        val b = batch("pr-1")
        val r = trash().restoreItem(b, item(b, "Dl"))
        assertEquals(listOf("Dl"), r.restored.map { it.path })
        assertEquals(100L, File(root, "Dl/a.bin").length())
        assertFalse(File(root, "Docs/b.bin").exists())
        assertTrue(File(root, ".StewardTrash/2026-10-08/pr-1/Docs/b.bin").exists())
    }

    @Test
    fun `restoring a blocked item changes nothing`() {
        file("Download/taken.bin", 5)
        file(".StewardTrash/2026-10-08/pr-1/Download/taken.bin", 200)
        val b = batch("pr-1")
        val r = trash().restoreItem(b, item(b, "Download/taken.bin"))
        assertTrue(r.restored.isEmpty())
        assertEquals("something is at that place now", r.blocked.single().reason)
        assertEquals(5L, File(root, "Download/taken.bin").length())
    }

    @Test
    fun `restoring one item descends a folder that now stands at its path`() {
        file("MapsWithMe/bookmarks/b.bin", 30)
        file(".StewardTrash/2026-10-08/pr-7/MapsWithMe/230606/a.mwm", 1000)
        val b = batch("pr-7")
        val r = trash().restoreItem(b, item(b, "MapsWithMe/230606"))
        assertEquals(listOf("MapsWithMe/230606"), r.restored.map { it.path })
        assertEquals(1000L, File(root, "MapsWithMe/230606/a.mwm").length())
    }

    @Test
    fun `a conflicting entry is refused with a plain reason and stays in the trash`() {
        file("Download/old.apk", 40)
        file(".StewardTrash/2026-10-08/pr-1/Download/old.apk", 300)
        val b = batch("pr-1")
        val r = trash().restoreBatch(b)
        assertTrue(r.restored.isEmpty())
        assertEquals(1, r.blocked.size)
        assertEquals("Download/old.apk", r.blocked[0].path)
        assertEquals("something is at that place now", r.blocked[0].reason)
        assertEquals(40L, File(root, "Download/old.apk").length())
        assertEquals(300L, File(root, ".StewardTrash/2026-10-08/pr-1/Download/old.apk").length())
    }

    @Test
    fun `a whole-batch restore reports what went back and what stayed`() {
        file("Download/taken.bin", 5)
        file(".StewardTrash/2026-10-08/pr-1/Download/free.bin", 100)
        file(".StewardTrash/2026-10-08/pr-1/Download/taken.bin", 200)
        val r = trash().restoreBatch(batch("pr-1"))
        assertEquals(listOf("Download/free.bin"), r.restored.map { it.path })
        assertEquals(listOf("Download/taken.bin"), r.blocked.map { it.path })
    }

    @Test
    fun `restoring a nested folder keeps the children that fit and refuses the ones that do not`() {
        file("Download/sub/b.bin", 7) // only b is taken now
        file(".StewardTrash/2026-10-08/pr-3/Download/sub/a.bin", 100)
        file(".StewardTrash/2026-10-08/pr-3/Download/sub/b.bin", 200)
        file(".StewardTrash/2026-10-08/pr-3/Download/sub/deep/c.bin", 300)
        val r = trash().restoreBatch(batch("pr-3"))
        assertEquals(listOf("Download/sub/a.bin", "Download/sub/deep"), r.restored.map { it.path }.sorted())
        assertEquals(listOf("Download/sub/b.bin"), r.blocked.map { it.path })
        assertEquals(100L, File(root, "Download/sub/a.bin").length())
        assertEquals(300L, File(root, "Download/sub/deep/c.bin").length())
        assertEquals(7L, File(root, "Download/sub/b.bin").length())
    }

    // ---- the trash left behind ----

    @Test
    fun `restoring everything removes the empty folders it leaves behind and keeps nomedia`() {
        File(root, ".StewardTrash/.nomedia").also { it.parentFile.mkdirs(); it.createNewFile() }
        file(".StewardTrash/2026-10-08/pr-1/Dl/a.bin", 100)
        val r = trash().restoreBatch(batch("pr-1"))
        assertTrue(r.blocked.isEmpty())
        assertFalse("the proposal folder is gone", File(root, ".StewardTrash/2026-10-08/pr-1").exists())
        assertFalse("the dated folder is gone", File(root, ".StewardTrash/2026-10-08").exists())
        assertTrue("the trash itself stays", File(root, ".StewardTrash").isDirectory)
        assertTrue("the media scanners stay out", File(root, ".StewardTrash/.nomedia").exists())
        assertEquals(0L, trash().status().bytes)
    }

    @Test
    fun `a partly restored batch keeps its folders and the bytes still held`() {
        file("Download/taken.bin", 5)
        file(".StewardTrash/2026-10-08/pr-1/Download/free.bin", 100)
        file(".StewardTrash/2026-10-08/pr-1/Download/taken.bin", 200)
        trash().restoreBatch(batch("pr-1"))
        assertTrue(File(root, ".StewardTrash/2026-10-08/pr-1/Download/taken.bin").exists())
        assertEquals(200L, trash().status().bytes)
        assertEquals(1, batches().size)
    }

    // ---- safety ----

    @Test
    fun `restore never follows a symbolic link standing at the original path`() {
        val outside = tmp.newFolder("outside")
        File(outside, "precious.bin").writeBytes(ByteArray(40))
        Files.createSymbolicLink(File(root, "Download").toPath(), outside.toPath())
        file(".StewardTrash/2026-10-08/pr-1/Download/a.bin", 100)
        val r = trash().restoreBatch(batch("pr-1"))
        assertTrue("nothing goes through the link", r.restored.isEmpty())
        assertEquals("something is at that place now", r.blocked.single().reason)
        assertEquals(40L, File(outside, "precious.bin").length())
        assertFalse(File(outside, "Download/a.bin").exists())
    }

    @Test
    fun `restore never follows a symbolic link inside the trash`() {
        val outside = tmp.newFolder("outside2")
        File(outside, "precious.bin").writeBytes(ByteArray(40))
        File(root, ".StewardTrash/2026-10-08/pr-1").mkdirs()
        Files.createSymbolicLink(File(root, ".StewardTrash/2026-10-08/pr-1/Dl").toPath(), outside.toPath())
        val r = trash().restoreBatch(batch("pr-1"))
        assertTrue(r.restored.isEmpty())
        assertEquals("a symbolic link is never restored", r.blocked.single().reason)
        assertFalse(File(root, "Dl").exists())
        assertEquals(40L, File(outside, "precious.bin").length())
    }

    @Test
    fun `restore refuses a batch whose folder is no longer there`() {
        file(".StewardTrash/2026-10-08/pr-1/Dl/a.bin", 100)
        val b = batch("pr-1")
        File(root, ".StewardTrash/2026-10-08/pr-1").deleteRecursively()
        val r = trash().restoreBatch(b)
        assertTrue(r.restored.isEmpty())
        assertTrue(r.blocked.isEmpty())
    }

    @Test
    fun `an entry that vanishes mid-restore is not reported as a failure`() {
        // The double tap is guarded, but any second restore of the same entries (an older screen, a raced tap) can take
        // one entry while this pass runs. A healthy item must then not be listed as "it could not be put back".
        file("Dl/keep.txt", 10)
        file(".StewardTrash/2026-10-08/pr-1/Dl/a.bin", 100)
        file(".StewardTrash/2026-10-08/pr-1/Dl/b.bin", 100)
        val b = batch("pr-1")
        assertEquals(listOf("Dl/a.bin", "Dl/b.bin"), b.items.map { it.path }.sorted())
        val t = trash()
        t.onBeforeMoveForTest = { entry ->
            if (entry.name == "a.bin") File(entry.parentFile, "b.bin").delete() // the other restore got there first
        }
        val r = t.restoreBatch(b)
        assertEquals(listOf("Dl/a.bin"), r.restored.map { it.path })
        assertTrue("a gone entry is neither restored nor a failure: ${r.blocked}", r.blocked.isEmpty())
        assertEquals(100L, File(root, "Dl/a.bin").length())
    }

    @Test
    fun `restore refuses a batch pretending to sit outside the trash`() {
        file(".StewardTrash/2026-10-08/pr-1/Dl/a.bin", 100)
        val fake = TrashBatch("../..", 0, "pr-1", listOf(TrashItem("Dl/a.bin", "dir", 100)))
        val r = trash().restoreBatch(fake)
        assertTrue(r.restored.isEmpty())
        assertTrue(File(root, ".StewardTrash/2026-10-08/pr-1/Dl/a.bin").exists())
    }
}
