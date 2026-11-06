package com.example.phonesteward.logic.files

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import com.example.phonesteward.net.RestoredItem

/**
 * A11.3: the restore reports waiting for the next sync. One small JSON file each, written atomically; a file that
 * does not parse is dropped, never sent; only the newest [RestoredOutbox.MAX_PENDING] are kept.
 */
class RestoredOutboxTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var dir: File
    private fun outbox() = RestoredOutbox(dir)

    private fun report(requestId: String, atMs: Long, path: String = "Dl/a.bin", bytes: Long = 100) = RestoredReport(
        requestId = requestId,
        proposalId = "pr-1",
        items = listOf(RestoredItem(path, bytes)),
        atMs = atMs,
    )

    @Before
    fun setUp() {
        dir = File(tmp.newFolder("outbox"), "restored-outbox")
    }

    @Test
    fun `nothing waiting on a fresh outbox`() {
        assertEquals(0, outbox().pending().size)
    }

    @Test
    fun `a saved report comes back with its requestId and items, oldest first`() {
        val o = outbox()
        o.save(report("req-b", 200, bytes = 500))
        o.save(report("req-a", 100))
        val pending = o.pending()
        assertEquals(listOf("req-a", "req-b"), pending.map { it.report.requestId })
        assertEquals("pr-1", pending[0].report.proposalId)
        assertEquals(RestoredItem("Dl/a.bin", 100), pending[0].report.items.single())
    }

    @Test
    fun `a sent report is removed and leaves nothing behind`() {
        val o = outbox()
        o.save(report("req-a", 100))
        o.remove(o.pending().single())
        assertEquals(0, o.pending().size)
        assertFalse(dir.walkTopDown().any { it.isFile })
    }

    @Test
    fun `a file that does not parse is dropped, never sent`() {
        dir.mkdirs()
        File(dir, "restored-5-broken.json").writeText("{ not json")
        assertEquals(0, outbox().pending().size)
        assertFalse(File(dir, "restored-5-broken.json").exists())
    }

    @Test
    fun `only the newest few are kept`() {
        val o = outbox()
        for (i in 1..RestoredOutbox.MAX_PENDING + 3) o.save(report("req-$i", i.toLong()))
        val pending = o.pending()
        assertEquals(RestoredOutbox.MAX_PENDING, pending.size)
        assertEquals("req-4", pending.first().report.requestId)
        assertEquals("req-${RestoredOutbox.MAX_PENDING + 3}", pending.last().report.requestId)
    }

    @Test
    fun `a report whose requestId has odd characters still round-trips`() {
        val o = outbox()
        o.save(report("req/with spaces&more", 1))
        assertEquals("req/with spaces&more", o.pending().single().report.requestId)
        assertNull("no temporary files are left", dir.listFiles()?.firstOrNull { it.name.endsWith(".tmp") })
        assertTrue(dir.walkTopDown().any { it.isFile })
    }
}
