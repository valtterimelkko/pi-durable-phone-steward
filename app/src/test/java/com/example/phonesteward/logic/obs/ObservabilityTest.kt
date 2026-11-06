package com.example.phonesteward.logic.obs

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import com.example.phonesteward.logic.CrashDevice
import com.example.phonesteward.logic.CrashOutbox
import com.example.phonesteward.logic.CrashReports
import com.example.phonesteward.net.DiagnosticsOutbox
import com.example.phonesteward.net.DiagnosticsWorker
import com.example.phonesteward.net.PhoneDiagnosticsData

/**
 * O5 (protocol A6): every exception the app catches and hides becomes an
 * error report through the A5 outbox, at most one per site, exception class
 * and app version per day, and never carries anything beyond the exception
 * class and stack trace. O6 (A6): the in-app event log caps at 200 entries
 * with 200-character details and holds no personal data.
 */
class ObservabilityTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val outboxDir get() = tmp.root.resolve("outbox")

    private fun diagnostics(now: () -> Long = { 1_000_000L }): AndroidDiagnostics =
        AndroidDiagnostics(
            filesDir = tmp.root.resolve("files"),
            outboxDir = outboxDir,
            appVersion = "0.2.2",
            deviceModel = "IN2023",
            sdkLevel = 33,
            now = now,
        )

    private fun pending() = CrashOutbox(outboxDir).pending()

    // ---- O5: the gate (pure) ----------------------------------------------

    @Test
    fun `the gate allows the first report of a key and suppresses the same day`() {
        val day = ErrorReportGate.dayOf(1_000_000L)
        assertEquals(day, ErrorReportGate.allow(emptyMap(), "site|java.io.IOException|0.2.2", day))
        assertEquals(
            null,
            ErrorReportGate.allow(mapOf("site|java.io.IOException|0.2.2" to day), "site|java.io.IOException|0.2.2", day),
        )
    }

    @Test
    fun `the same key is allowed again the next day`() {
        val day = ErrorReportGate.dayOf(1_000_000L)
        val stored = mapOf("site|java.io.IOException|0.2.2" to day)
        assertEquals(day + 1, ErrorReportGate.allow(stored, "site|java.io.IOException|0.2.2", day + 1))
    }

    @Test
    fun `a different exception class at the same site has its own key`() {
        val day = ErrorReportGate.dayOf(1_000_000L)
        val stored = mapOf("site|java.io.IOException|0.2.2" to day)
        assertEquals(day, ErrorReportGate.allow(stored, "site|java.lang.IllegalStateException|0.2.2", day))
    }

    @Test
    fun `a different site has its own key`() {
        val day = ErrorReportGate.dayOf(1_000_000L)
        val stored = mapOf("a|java.io.IOException|0.2.2" to day)
        assertEquals(day, ErrorReportGate.allow(stored, "b|java.io.IOException|0.2.2", day))
    }

    // ---- O5: hidden exception -> exactly one stored error report -----------

    @Test
    fun `a hidden exception is stored once as an error report with its site`() {
        diagnostics().errorReport("status.battery_button", IOException("battery dialog refused"))
        val stored = pending()
        assertEquals(1, stored.size)
        val report = stored.single().report
        assertEquals("error", report.severity)
        assertEquals("status.battery_button", report.site)
        assertEquals(IOException::class.java.name, report.exception)
        assertEquals("0.2.2", report.appVersion)
        assertTrue(report.stackTrace.contains("\tat "))
    }

    @Test
    fun `an error report keeps the stack frames but drops the message line`() {
        diagnostics().errorReport("status.battery_button", IOException("battery dialog refused"))
        val trace = pending().single().report.stackTrace
        assertTrue(trace.contains("\tat "))
        assertTrue(!trace.contains("battery dialog refused"))
    }

    @Test
    fun `a second identical report the same day is not stored`() {
        val d = diagnostics()
        d.errorReport("status.battery_button", IOException("boom"))
        d.errorReport("status.battery_button", IOException("boom"))
        assertEquals(1, pending().size)
    }

    @Test
    fun `concurrent identical failures store exactly one report`() {
        // report save and day stamp happen under one lock,
        // save FIRST — concurrent same-key failures cannot duplicate, and a
        // kill between the two writes can at worst produce one duplicate
        // (absorbed by the requestId rule), never a lost report.
        val d = diagnostics()
        val start = java.util.concurrent.CountDownLatch(1)
        val threads = (1..32).map {
            Thread {
                start.await()
                d.errorReport("race.site", IOException("boom"))
            }
        }
        threads.forEach { it.start() }
        start.countDown()
        threads.forEach { it.join() }
        assertEquals(1, pending().size)
    }

    @Test
    fun `a kill between save and stamp still resends the same request id`() {
        // the requestId is derived from site, exception
        // class, app version and the UTC day. Simulate the crash window —
        // the report saved, the day stamp never landed — and replay the same
        // failure: a second file appears, but with the SAME id, so the
        // server's requestId rule deduplicates the resend.
        val filesDir = tmp.root.resolve("files")
        var now = 1_000_000L
        val d = AndroidDiagnostics(
            filesDir = filesDir, outboxDir = outboxDir,
            appVersion = "0.2.2", deviceModel = "IN2023", sdkLevel = 33,
            now = { now },
        )
        d.errorReport("status.battery_button", IOException("boom"))
        now += 5_000 // the replay after the kill happens later, same UTC day
        java.io.File(filesDir, "error-report-gate.json").delete() // the stamp never landed
        d.errorReport("status.battery_button", IOException("boom"))
        val reports = pending()
        assertEquals(2, reports.size)
        assertEquals(1, reports.map { it.report.requestId }.distinct().size)
    }

    @Test
    fun `the stable error request id is deterministic and day-scoped`() {
        val a = ErrorReportGate.stableRequestId("s.a", "java.io.IOException", "0.2.2", 20_000L)
        val b = ErrorReportGate.stableRequestId("s.a", "java.io.IOException", "0.2.2", 20_000L)
        val nextDay = ErrorReportGate.stableRequestId("s.a", "java.io.IOException", "0.2.2", 20_001L)
        assertEquals(a, b)
        assertTrue(a != nextDay)
        assertTrue(a.startsWith("err-"))
        val hex = a.removePrefix("err-")
        assertEquals(32, hex.length)
        assertTrue(hex.all { it.isDigit() || it in 'a'..'f' })
    }

    @Test
    fun `the same site counts again the next day`() {
        var now = 1_000_000L
        val d = AndroidDiagnostics(
            filesDir = tmp.root.resolve("files"),
            outboxDir = outboxDir,
            appVersion = "0.2.2", deviceModel = "IN2023", sdkLevel = 33,
            now = { now },
        )
        d.errorReport("status.battery_button", IOException("boom"))
        now += 25L * 60 * 60 * 1000
        d.errorReport("status.battery_button", IOException("boom"))
        assertEquals(2, pending().size)
    }

    @Test
    fun `a crash report keeps the crash severity and no site`() {
        val report = CrashReports.from(
            threadName = "main",
            error = IllegalStateException("x"),
            appVersion = "0.2.2",
            nowMs = 5L,
            device = CrashDevice("IN2023", 33),
            requestId = "crash-x",
        )
        assertEquals("crash", report.severity)
        assertEquals(null, report.site)
    }

    @Test
    fun `an error report never carries the exception message or a file path`() {
        val d = diagnostics()
        d.errorReport("status.battery_button", IOException("secret path /sdcard/DCIM/private.jpg leaked"))
        val report = pending().single().report
        // The A6 rule: beyond the class and the stack trace, no content. The
        // event-log line names only the site and the class.
        assertTrue(!report.stackTrace.contains("private.jpg"))
        val log = d.eventLog()
        val entry = log.last { it.event == "error_report_stored" }
        assertEquals("status.battery_button IOException", entry.detail)
        assertTrue(!entry.detail.contains("private"))
    }

    @Test
    fun `a site label longer than 128 characters is truncated`() {
        diagnostics().errorReport("s".repeat(300), IOException("boom"))
        assertEquals(128, pending().single().report.site?.length)
    }

    // ---- O5: the sink facade ----------------------------------------------

    @Test
    fun `the Diagnostics facade forwards to the installed sink and defaults to no-op`() {
        Diagnostics.error("x", IOException("before install")) // no-op, no crash
        val d = diagnostics()
        Diagnostics.install(d)
        try {
            Diagnostics.error("facade.site", IOException("boom"))
            Diagnostics.event("info", "facade_event", "detail")
            assertEquals(1, pending().size)
            assertTrue(d.eventLog().any { it.event == "facade_event" })
        } finally {
            Diagnostics.resetForTest()
        }
    }

    // ---- O6: the event log -------------------------------------------------

    @Test
    fun `the event log keeps the newest 200 entries, oldest first`() {
        var now = 0L
        val log = EventLog(now = { now })
        for (i in 1..250) {
            now = i.toLong()
            log.log("info", "tick", "i=$i")
        }
        val entries = log.snapshot()
        assertEquals(200, entries.size)
        assertEquals("tick", entries.first().event)
        assertEquals("i=51", entries.first().detail)
        assertEquals("i=250", entries.last().detail)
    }

    @Test
    fun `an event detail is capped at 200 characters`() {
        val log = EventLog(now = { 1L })
        log.log("info", "big", "x".repeat(1_000))
        assertEquals(200, log.snapshot().single().detail.length)
    }

    @Test
    fun `the diagnostics executor data shape matches A6`() {
        val data = PhoneDiagnosticsData(
            appVersion = "0.2.2",
            versionCode = 4,
            permissions = com.example.phonesteward.net.DevicePermissions(
                usageAccess = true, allFiles = true, mediaRead = true, manageMedia = false, batteryUnrestricted = true,
            ),
            worker = DiagnosticsWorker(state = "ENQUEUED", lastRunMs = 9L, runAttemptCount = 2),
            outbox = DiagnosticsOutbox(crashes = 1, errors = 2),
            log = listOf(EventEntry(atMs = 1L, level = "info", event = "jobs_polled", detail = "ran=0")),
        )
        val json = com.example.phonesteward.net.ProtocolJson.encodeToString(
            PhoneDiagnosticsData.serializer(),
            data,
        )
        assertTrue(json.contains("\"versionCode\":4"))
        assertTrue(json.contains("\"state\":\"ENQUEUED\""))
        assertTrue(json.contains("\"jobs_polled\""))
        val back = com.example.phonesteward.net.ProtocolJson.decodeFromString(
            PhoneDiagnosticsData.serializer(),
            json,
        )
        assertEquals(data, back)
    }
}
