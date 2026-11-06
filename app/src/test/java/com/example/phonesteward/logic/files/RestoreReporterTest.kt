package com.example.phonesteward.logic.files

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import com.example.phonesteward.net.RestoredItem

/**
 * A11.3: the restore report is flushed through the outbox. The requestId stored with the report is the one sent, so a
 * retry after a lost answer can never double-record; a report the server refuses for good is dropped, and a transport
 * failure keeps the rest for the next sync.
 */
class RestoreReporterTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var outbox: RestoredOutbox
    private val sent = mutableListOf<RestoredReport>()
    private var answer: (RestoredReport) -> RestoreUpload = { RestoreUpload.SENT }

    private fun reporter() = RestoreReporter(outbox) { report ->
        sent += report
        answer(report)
    }

    @Before
    fun setUp() {
        outbox = RestoredOutbox(File(tmp.newFolder("out"), "restored-outbox"))
    }

    private fun save(requestId: String, atMs: Long) = outbox.save(
        RestoredReport(requestId, "pr-1", listOf(RestoredItem("Dl/$requestId.bin", 100)), atMs),
    )

    @Test
    fun `flush sends every waiting report oldest first and clears them`() {
        save("req-a", 100)
        save("req-b", 200)
        assertEquals(2, reporter().flush())
        assertEquals(listOf("req-a", "req-b"), sent.map { it.requestId })
        assertEquals(emptyList<RestoredReport>(), outbox.pending())
    }

    @Test
    fun `the stored requestId is the one sent, so a retry can never double-record`() {
        save("req-stable", 100)
        answer = { RestoreUpload.RETRY_LATER }
        assertEquals(0, reporter().flush())
        assertEquals(1, outbox.pending().size)
        answer = { RestoreUpload.SENT }
        assertEquals(1, reporter().flush())
        assertEquals(listOf("req-stable", "req-stable"), sent.map { it.requestId })
    }

    @Test
    fun `a transport failure keeps the report and stops the pass`() {
        save("req-a", 100)
        save("req-b", 200)
        answer = { if (it.requestId == "req-a") RestoreUpload.RETRY_LATER else RestoreUpload.SENT }
        assertEquals(0, reporter().flush())
        assertEquals(listOf("req-a"), sent.map { it.requestId })
        assertEquals(listOf("req-a", "req-b"), outbox.pending().map { it.report.requestId })
    }

    @Test
    fun `a report the server refuses for good is dropped`() {
        save("req-a", 100)
        answer = { RestoreUpload.REFUSED }
        assertEquals(0, reporter().flush())
        assertEquals(emptyList<RestoredReport>(), outbox.pending())
    }

    @Test
    fun `a flush with nothing waiting sends nothing`() {
        assertEquals(0, reporter().flush())
        assertEquals(emptyList<RestoredReport>(), sent)
    }
}
