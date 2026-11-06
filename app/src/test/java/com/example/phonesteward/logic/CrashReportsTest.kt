package com.example.phonesteward.logic

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** A5 crash self-reports: the report shape, the on-phone outbox and the upload rule. */
class CrashReportsTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun report(at: Long = 1_000L, id: String = "crash-$at") = CrashReport(
        requestId = id,
        appVersion = "0.2.1",
        crashedAtMs = at,
        thread = "main",
        exception = "java.lang.IllegalStateException",
        stackTrace = "trace",
        device = CrashDevice(model = "IN2023", sdk = 33),
    )

    @Test
    fun `a report names the class and thread and keeps the head of a long trace`() {
        val deep = StackOverflowError("deep")
        deep.stackTrace = Array(5_000) { StackTraceElement("work.Graph", "get", "AppGraph.kt", it) }
        val made = CrashReports.from(
            threadName = "main",
            error = deep,
            appVersion = "0.2.1",
            nowMs = 42L,
            device = CrashDevice("IN2023", 33),
            requestId = "crash-1",
        )
        assertEquals("java.lang.StackOverflowError", made.exception)
        assertEquals("main", made.thread)
        assertEquals(42L, made.crashedAtMs)
        assertEquals(CrashReports.MAX_TRACE_CHARS, made.stackTrace.length)
        // crash traces are class names and frames only —
        // the message line never enters the trace.
        assertTrue(made.stackTrace.startsWith("\tat "))
        assertTrue(!made.stackTrace.contains("deep"))
    }

    @Test
    fun `neither crash nor error traces carry any exception message`() {
        val secret = java.io.IOException("path /sdcard/DCIM/secret.jpg and token=abc123")
        val nested = IllegalStateException("wrap /sdcard/private.jpg", secret)
        nested.addSuppressed(IllegalStateException("suppressed token=zzz-999"))

        val crash = CrashReports.from("main", nested, "0.2.2", 1L, CrashDevice("m", 33), "crash-x")
        val error = CrashReports.errorReport(
            "site.a", CrashReports.SEVERITY_ERROR, "main", nested, "0.2.2", 1L,
            CrashDevice("m", 33), "err-x",
        )
        for (trace in listOf(crash.stackTrace, error.stackTrace)) {
            assertTrue(trace.contains("\tat "))
            assertTrue(trace.contains("Caused by: java.io.IOException"))
            assertTrue(trace.contains("Suppressed: java.lang.IllegalStateException"))
            assertTrue(!trace.contains("secret"))
            assertTrue(!trace.contains("private.jpg"))
            assertTrue(!trace.contains("token="))
            assertTrue(!trace.contains("abc123"))
        }
    }

    @Test
    fun `the wire form uses the A5 field names and never sends a null device`() {
        val wire = Json.parseToJsonElement(CrashReports.encode(report())).jsonObject
        // A6 adds severity and site on top of the A5 body (severity always
        // serialised; a crash report carries no site).
        assertEquals(
            setOf("requestId", "appVersion", "crashedAtMs", "thread", "exception", "stackTrace", "device", "severity", "site"),
            wire.keys,
        )
        assertEquals("crash", wire["severity"]!!.jsonPrimitive.content)
        assertTrue(wire["site"] is kotlinx.serialization.json.JsonNull)
        val device = wire["device"] as JsonObject
        assertEquals("IN2023", device["model"]!!.jsonPrimitive.content)
        assertEquals(33, device["sdk"]!!.jsonPrimitive.int)
    }

    @Test
    fun `the outbox keeps saved reports across restarts, newest five only`() {
        val dir = tmp.newFolder("crashes")
        val outbox = CrashOutbox(dir)
        for (i in 1..7) outbox.save(report(at = i * 1_000L))
        val again = CrashOutbox(dir).pending()
        assertEquals(listOf(3_000L, 4_000L, 5_000L, 6_000L, 7_000L), again.map { it.report.crashedAtMs })
    }

    @Test
    fun `a corrupt outbox file is dropped, never sent`() {
        val dir = tmp.newFolder("crashes")
        File(dir, "crash-1.json").writeText("{not json")
        val outbox = CrashOutbox(dir)
        assertTrue(outbox.pending().isEmpty())
        assertFalse(File(dir, "crash-1.json").exists())
    }

    @Test
    fun `upload drops sent and refused reports and keeps the rest for the next start`() {
        val dir = tmp.newFolder("crashes")
        val outbox = CrashOutbox(dir)
        outbox.save(report(at = 1L, id = "a"))
        outbox.save(report(at = 2L, id = "b"))
        outbox.save(report(at = 3L, id = "c"))
        val answers = mapOf("a" to CrashUpload.SENT, "b" to CrashUpload.REFUSED, "c" to CrashUpload.RETRY_LATER)
        val sent = mutableListOf<String>()
        CrashUploader(outbox) { r -> sent += r.requestId; answers.getValue(r.requestId) }.uploadAll()
        assertEquals(listOf("a", "b", "c"), sent)
        assertEquals(listOf("c"), outbox.pending().map { it.report.requestId })
    }

    @Test
    fun `upload stops at the first retry-later so a rate limit is not hammered`() {
        val dir = tmp.newFolder("crashes")
        val outbox = CrashOutbox(dir)
        outbox.save(report(at = 1L, id = "a"))
        outbox.save(report(at = 2L, id = "b"))
        val sent = mutableListOf<String>()
        CrashUploader(outbox) { r -> sent += r.requestId; CrashUpload.RETRY_LATER }.uploadAll()
        assertEquals(listOf("a"), sent)
        assertEquals(2, outbox.pending().size)
    }

    @Test
    fun `an empty outbox makes no call at all`() {
        val outbox = CrashOutbox(tmp.newFolder("crashes"))
        var calls = 0
        CrashUploader(outbox) { calls++; CrashUpload.SENT }.uploadAll()
        assertEquals(0, calls)
    }
}
