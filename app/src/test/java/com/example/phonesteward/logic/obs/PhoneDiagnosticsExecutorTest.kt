package com.example.phonesteward.logic.obs

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.startup.InitializationProvider
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.annotation.Config
import com.example.phonesteward.android.SyncWorker
import com.example.phonesteward.android.WorkState
import com.example.phonesteward.logic.CrashDevice
import com.example.phonesteward.logic.obs.Diagnostics
import com.example.phonesteward.logic.CrashOutbox
import com.example.phonesteward.logic.CrashReports
import com.example.phonesteward.logic.exec.RecordingSink
import com.example.phonesteward.logic.jobs.JobOutcome
import com.example.phonesteward.logic.exec.body
import com.example.phonesteward.net.DevicePermissions
import com.example.phonesteward.net.DiagnosticsOutbox
import com.example.phonesteward.net.DiagnosticsWorker
import com.example.phonesteward.net.Job
import com.example.phonesteward.net.PhoneDiagnosticsData

/** O6 (A6): the `phone_diagnostics` job answers the app's own state. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class PhoneDiagnosticsExecutorTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Before
    fun setUp() {
        // WorkManager initialises through the manifest's start-up provider.
        Robolectric.setupContentProvider(
            InitializationProvider::class.java,
            "${ApplicationProvider.getApplicationContext<android.content.Context>().packageName}.androidx-startup",
        )
    }

    private fun job() = Job(
        jobId = "pj-diag1",
        kind = "phone_diagnostics",
        params = JsonObject(emptyMap()),
        createdAtMs = 1L,
        expiresAtMs = 2L,
    )

    @Test
    fun `phone_diagnostics answers the A6 data from its suppliers`() {
        val executor = PhoneDiagnosticsExecutor(
            appVersion = "0.2.2",
            versionCode = 4,
            permissions = {
                DevicePermissions(true, true, true, false, true)
            },
            worker = { DiagnosticsWorker("ENQUEUED", lastRunMs = 9L, runAttemptCount = 1) },
            outboxCounts = { DiagnosticsOutbox(crashes = 1, errors = 2) },
            log = { listOf(EventEntry(1L, "info", "jobs_polled", "ran=0")) },
        )
        val outcome = executor.execute(job())
        assertTrue(outcome is JobOutcome.Ok)
        val data = ProtocolJsonOf(outcome as JobOutcome.Ok)
        assertEquals("0.2.2", data.appVersion)
        assertEquals(4, data.versionCode)
        assertEquals(false, data.permissions.manageMedia)
        assertEquals("ENQUEUED", data.worker.state)
        assertEquals(9L, data.worker.lastRunMs)
        assertEquals(1, data.outbox.crashes)
        assertEquals(2, data.outbox.errors)
        assertEquals(1, data.log.size)
        assertEquals("jobs_polled", data.log.single().event)
    }

    @Test
    fun `outbox counts separate crashes from error reports`() {
        val dir = tmp.newFolder("outbox")
        val outbox = CrashOutbox(dir)
        outbox.save(
            CrashReports.from("main", IllegalStateException("x"), "0.2.2", 1L, CrashDevice("m", 33), "crash-a"),
        )
        outbox.save(
            CrashReports.errorReport("s.a", CrashReports.SEVERITY_ERROR, "main", java.io.IOException("y"), "0.2.2", 2L, CrashDevice("m", 33), "err-b"),
        )
        val counts = OutboxCounts.of(dir)
        assertEquals(1, counts.crashes)
        assertEquals(1, counts.errors)
    }

    @Test
    fun `the periodic work state reads ENQUEUED once scheduled`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        SyncWorker.schedule(context)
        val worker = WorkState.periodicState(context, SyncWorker.WORK_NAME, lastRunMs = 42L)
        assertEquals("ENQUEUED", worker.state)
        assertEquals(42L, worker.lastRunMs)
    }

    @Test
    fun `without any periodic work the state is NONE`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        // A work name nothing schedules: Robolectric resets the Application
        // per test but the WorkManager instance can carry scheduled work over.
        val worker = WorkState.periodicState(context, "never-scheduled-work", lastRunMs = 0L)
        assertEquals("NONE", worker.state)
        assertEquals(0, worker.runAttemptCount)
    }

    @Test
    fun `a failing supplier reports a sanitised message and records through O5`() {
        // the failure path never forwards e.message — the
        // result names the exception class, and O5 records the site.
        val recording = RecordingSink()
        Diagnostics.install(recording)
        try {
            val executor = PhoneDiagnosticsExecutor(
                appVersion = "0.2.2",
                versionCode = 4,
                permissions = { error("SECRET token=zzz leaked state") },
                worker = { DiagnosticsWorker("NONE") },
                outboxCounts = { DiagnosticsOutbox() },
                log = { emptyList() },
            )
            val outcome = executor.execute(job())
            assertTrue(outcome is JobOutcome.Failed)
            val failed = outcome as JobOutcome.Failed
            assertEquals("DIAGNOSTICS_UNAVAILABLE", failed.errorCode)
            assertEquals("IllegalStateException", failed.message)
            assertEquals(listOf("diag.executor"), recording.sites)
        } finally {
            Diagnostics.resetForTest()
        }
    }

    private fun ProtocolJsonOf(outcome: JobOutcome.Ok): PhoneDiagnosticsData =
        com.example.phonesteward.net.ProtocolJson.decodeFromJsonElement(
            PhoneDiagnosticsData.serializer(),
            outcome.data.jsonObject,
        )
}
