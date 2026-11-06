package com.example.phonesteward.logic.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import com.example.phonesteward.net.RestoredItem

/**
 * Correction 1 (review finding 1): the server refuses a report of more than 500 items with 400, and the outbox drops a
 * refused report, so a restore of more than 500 items must be split into chunks of at most 500 — each its own report
 * with its own requestId, so one refused chunk can never lose the others.
 */
class RestoreReportsTest {

    private var seq = 0
    private fun nextId(): String = "restore-${++seq}"

    private fun items(n: Int) = (1..n).map { RestoredItem("Dl/f%04d.bin".format(it), it.toLong()) }

    private fun chunks(n: Int) = RestoreReports.chunks("pr-1", items(n), atMs = 7L, newRequestId = ::nextId)

    @Test
    fun `nothing restored means no report at all`() {
        assertTrue(chunks(0).isEmpty())
    }

    @Test
    fun `up to the server's limit stays one report`() {
        assertEquals(1, chunks(1).size)
        assertEquals(1, chunks(500).size)
        assertEquals(500, chunks(500).single().items.size)
    }

    @Test
    fun `over the limit is split with the last chunk smaller`() {
        assertEquals(listOf(500, 500, 200), chunks(1200).map { it.items.size })
        assertEquals(listOf(500, 1), chunks(501).map { it.items.size })
        assertEquals(listOf(500, 500), chunks(1000).map { it.items.size })
    }

    @Test
    fun `every chunk keeps every item exactly once, in order`() {
        val all = chunks(1200).flatMap { it.items }
        assertEquals(1200, all.size)
        assertEquals(items(1200), all)
    }

    @Test
    fun `each chunk has its own requestId and the same proposal and time`() {
        val c = chunks(1200)
        assertEquals(3, c.map { it.requestId }.toSet().size)
        assertTrue(c.all { it.proposalId == "pr-1" })
        assertTrue(c.all { it.atMs == 7L })
        assertTrue(c.none { it.requestId.isEmpty() })
    }

    @Test
    fun `the split is exactly at the server's cap`() {
        assertEquals(500, RestoreReports.MAX_ITEMS)
    }
}
