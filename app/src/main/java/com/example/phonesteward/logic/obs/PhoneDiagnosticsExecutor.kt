package com.example.phonesteward.logic.obs

import com.example.phonesteward.logic.jobs.JobExecutor
import com.example.phonesteward.logic.jobs.JobOutcome
import com.example.phonesteward.logic.exec.body
import com.example.phonesteward.net.DevicePermissions
import com.example.phonesteward.net.DiagnosticsOutbox
import com.example.phonesteward.net.DiagnosticsWorker
import com.example.phonesteward.net.Job
import com.example.phonesteward.net.PhoneDiagnosticsData

/**
 * O6 (protocol A6): the `phone_diagnostics` job. Answers the app's own
 * state — version, permissions as the app sees them, the periodic work's
 * WorkManager state, outbox counts and the in-app event log (newest last).
 * Pure: every fact arrives through a supplier, so Android edges are
 * injected and tests drive it without a device.
 */
class PhoneDiagnosticsExecutor(
    private val appVersion: String,
    private val versionCode: Int,
    private val permissions: () -> DevicePermissions,
    private val worker: () -> DiagnosticsWorker,
    private val outboxCounts: () -> DiagnosticsOutbox,
    private val log: () -> List<EventEntry>,
) : JobExecutor {

    override fun execute(job: Job): JobOutcome = try {
        val data = PhoneDiagnosticsData(
            appVersion = appVersion,
            versionCode = versionCode,
            permissions = permissions(),
            worker = worker(),
            outbox = outboxCounts(),
            log = log(),
        )
        JobOutcome.Ok(body(data))
    } catch (e: Exception) {
        // the failure text is the exception class plus a
        // fixed message — e.message is never forwarded (it can carry
        // content). O5 records the site for the maintainer.
        com.example.phonesteward.logic.obs.Diagnostics.error("diag.executor", e)
        JobOutcome.Failed("DIAGNOSTICS_UNAVAILABLE", e.javaClass.simpleName)
    }
}

/** Counts the pending A5 outbox by severity (A6 `outbox`). */
object OutboxCounts {
    fun of(dir: java.io.File): DiagnosticsOutbox {
        val pending = com.example.phonesteward.logic.CrashOutbox(dir).pending()
        return DiagnosticsOutbox(
            crashes = pending.count { it.report.severity != com.example.phonesteward.logic.CrashReports.SEVERITY_ERROR },
            errors = pending.count { it.report.severity == com.example.phonesteward.logic.CrashReports.SEVERITY_ERROR },
        )
    }
}
