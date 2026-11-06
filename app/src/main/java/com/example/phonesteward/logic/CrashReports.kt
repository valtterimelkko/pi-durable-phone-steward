package com.example.phonesteward.logic

import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** The phone that crashed (A5): model and Android SDK level only. */
@Serializable
data class CrashDevice(val model: String, val sdk: Int)

/**
 * One A5 report: a real crash, or (A6/O5) a non-fatal error the app caught
 * and hid, marked `severity: "error"` with the `site` it happened at.
 */
@Serializable
data class CrashReport(
    val requestId: String,
    val appVersion: String,
    val crashedAtMs: Long,
    val thread: String,
    val exception: String,
    val stackTrace: String,
    val device: CrashDevice,
    val severity: String = CrashReports.SEVERITY_CRASH,
    val site: String? = null,
)

object CrashReports {
    /** A5 field limits; a longer trace keeps its head, where the cause is named. */
    const val MAX_TRACE_CHARS = 32_768
    const val MAX_EXCEPTION_CHARS = 512
    const val MAX_THREAD_CHARS = 128
    const val MAX_VERSION_CHARS = 64

    /** A6 (O5): severity values and the site-label limit. */
    const val SEVERITY_CRASH = "crash"
    const val SEVERITY_ERROR = "error"
    const val MAX_SITE_CHARS = 128

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun from(
        threadName: String,
        error: Throwable,
        appVersion: String,
        nowMs: Long,
        device: CrashDevice,
        requestId: String,
    ): CrashReport = CrashReport(
        requestId = requestId,
        appVersion = appVersion.take(MAX_VERSION_CHARS),
        crashedAtMs = nowMs,
        thread = threadName.ifEmpty { "unnamed" }.take(MAX_THREAD_CHARS),
        exception = error.javaClass.name.take(MAX_EXCEPTION_CHARS),
        stackTrace = framesTrace(error).take(MAX_TRACE_CHARS),
        device = device.copy(model = device.model.take(64)),
        severity = SEVERITY_CRASH,
        site = null,
    )

    /**
     * the uploaded trace — for CRASH and ERROR severities
     * alike — is built from class names and frames only, for the top
     * exception and every cause and suppressed one:
     * `Caused by: java.io.IOException` with no message, then its frames.
     * Exception messages can carry file names, paths or tokens; the class
     * name in `exception` already names the cause.
     */
    fun framesTrace(error: Throwable): String {
        val out = StringBuilder()
        val seen = java.util.IdentityHashMap<Throwable, Unit>()
        var current: Throwable? = error
        var first = true
        while (current != null && seen.put(current, Unit) == null) {
            if (!first) out.append("Caused by: ").append(current.javaClass.name).append('\n')
            first = false
            for (frame in current.stackTrace) out.append("\tat ").append(frame).append('\n')
            for (suppressed in current.suppressed) {
                out.append("Suppressed: ").append(suppressed.javaClass.name).append('\n')
                for (frame in suppressed.stackTrace) out.append("\tat ").append(frame).append('\n')
            }
            current = current.cause
        }
        return out.toString().trimEnd()
    }

    /**
     * The one builder for both severities (A6/O5). An `error` report carries
     * the site it happened at; every severity's trace is class names and
     * frames only (correction 01-2).
     */
    fun errorReport(
        site: String?,
        severity: String,
        threadName: String,
        error: Throwable,
        appVersion: String,
        nowMs: Long,
        device: CrashDevice,
        requestId: String,
    ): CrashReport = CrashReport(
        requestId = requestId,
        appVersion = appVersion.take(MAX_VERSION_CHARS),
        crashedAtMs = nowMs,
        thread = threadName.ifEmpty { "unnamed" }.take(MAX_THREAD_CHARS),
        exception = error.javaClass.name.take(MAX_EXCEPTION_CHARS),
        stackTrace = framesTrace(error).take(MAX_TRACE_CHARS),
        device = device.copy(model = device.model.take(64)),
        severity = severity.take(64),
        site = site?.take(MAX_SITE_CHARS),
    )

    fun encode(report: CrashReport): String = json.encodeToString(CrashReport.serializer(), report)

    fun decode(text: String): CrashReport = json.decodeFromString(CrashReport.serializer(), text)
}

/** A stored report and the file it lives in. */
data class PendingCrash(val file: File, val report: CrashReport)

/**
 * Crash reports waiting on the phone for the next start (A5). One small JSON file each,
 * written atomically; only the newest [MAX_PENDING] are kept, and a file that does not
 * parse is deleted, never sent.
 */
class CrashOutbox(private val dir: File) {

    fun save(report: CrashReport) {
        dir.mkdirs()
        // The prefix follows the severity (A6/O5): crash- for crashes, error-
        // for hidden-exception reports; pending() reads both.
        val prefix = if (report.severity == CrashReports.SEVERITY_ERROR) "error-" else "crash-"
        val name = "$prefix${report.crashedAtMs}-${report.requestId.filter { it.isLetterOrDigit() || it == '-' }}.json"
        val tmp = File(dir, "$name.tmp")
        tmp.writeText(CrashReports.encode(report))
        if (!tmp.renameTo(File(dir, name))) tmp.delete()
        pending().dropLast(MAX_PENDING).forEach { it.file.delete() }
    }

    /** Oldest first; both severities. */
    fun pending(): List<PendingCrash> {
        val files = dir.listFiles { file ->
            (file.name.startsWith("crash-") || file.name.startsWith("error-")) && file.name.endsWith(".json")
        }.orEmpty()
        return files.mapNotNull { file ->
            try {
                PendingCrash(file, CrashReports.decode(file.readText()))
            } catch (_: Exception) {
                file.delete()
                null
            }
        }.sortedBy { it.report.crashedAtMs }
    }

    fun remove(crash: PendingCrash) {
        crash.file.delete()
    }

    companion object {
        const val MAX_PENDING = 5
    }
}

/** What one upload attempt means for the stored report. */
enum class CrashUpload {
    /** Accepted (or a duplicate): delete it. */
    SENT,

    /** The server refused this report for good (400, 409, 413): delete it. */
    REFUSED,

    /** Offline, rate-limited or a server error: keep it for the next start. */
    RETRY_LATER,
}

/** Sends the outbox oldest first; stops at the first retry-later so a limit is not hammered. */
class CrashUploader(
    private val outbox: CrashOutbox,
    private val send: (CrashReport) -> CrashUpload,
) {
    fun uploadAll() {
        for (crash in outbox.pending()) {
            when (send(crash.report)) {
                CrashUpload.SENT, CrashUpload.REFUSED -> outbox.remove(crash)
                CrashUpload.RETRY_LATER -> return
            }
        }
    }
}
