package com.example.phonesteward.logic.obs

import java.io.File
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import com.example.phonesteward.logic.CrashDevice
import com.example.phonesteward.logic.CrashOutbox
import com.example.phonesteward.logic.CrashReports
import com.example.phonesteward.net.RequestIds

/**
 * O6 (protocol A6): one in-app event-log line. `detail` carries codes,
 * counts, ids and screen names only — never file names, media metadata,
 * tokens or other personal data; the cap is enforced here.
 */
@kotlinx.serialization.Serializable
data class EventEntry(val atMs: Long, val level: String, val event: String, val detail: String)

/**
 * The in-memory event log (O6): a ring buffer of the newest [capacity]
 * entries, snapshot oldest first. Pure JVM and thread-safe.
 */
class EventLog(private val capacity: Int = 200, private val now: () -> Long = System::currentTimeMillis) {

    private val lock = Any()
    private val buffer = ArrayDeque<EventEntry>()

    fun log(level: String, event: String, detail: String) {
        val entry = EventEntry(
            atMs = now(),
            level = level.take(16),
            event = event.take(64),
            detail = EventText.detail(detail),
        )
        synchronized(lock) {
            buffer.addLast(entry)
            while (buffer.size > capacity) buffer.removeFirst()
        }
    }

    /** Newest last (A6). */
    fun snapshot(): List<EventEntry> = synchronized(lock) { buffer.toList() }
}

/** Detail hygiene for event lines: whitespace folded, 200 characters at most. */
object EventText {
    const val MAX_DETAIL_CHARS = 200

    fun detail(raw: String?): String =
        (raw ?: "").replace(Regex("\\s+"), " ").trim().take(MAX_DETAIL_CHARS)
}

/**
 * O5 (protocol A6): the day-keyed gate. At most one error report per
 * site, exception class and app version per UTC day. Pure and tested.
 */
object ErrorReportGate {

    fun key(site: String, exceptionClass: String, appVersion: String): String =
        "$site|$exceptionClass|$appVersion"

    fun dayOf(nowMs: Long): Long = nowMs / 86_400_000L

    /** The day stamp to record, or null when the same key already fired today. */
    fun allow(stored: Map<String, Long>, key: String, today: Long): Long? =
        if (stored[key] == today) null else today

    /**
     * an error report's requestId is DERIVED from site,
     * exception class, app version and the UTC day (`err-` + the first 32
     * hex characters of a SHA-256 of the four). A resend after a kill
     * between the outbox save and the day-stamp write reaches the server as
     * the same request and is deduplicated by its requestId rule.
     */
    fun stableRequestId(site: String, exceptionClass: String, appVersion: String, dayUtc: Long): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest("$site|$exceptionClass|$appVersion|$dayUtc".toByteArray())
        return "err-" + digest.take(16).joinToString("") { "%02x".format(it) }
    }
}

/** Where the app-wide observability events go (O5/O6). */
interface DiagnosticsSink {
    fun errorReport(site: String, error: Throwable)
    fun event(level: String, event: String, detail: String)
    fun eventLog(): List<EventEntry>
}

/**
 * The app-wide observability facade. Callers never hold a Context: the
 * real sink is installed once at app start; before that (and in any pure
 * JVM test) calls are no-ops, so reporting can never break a caller.
 */
object Diagnostics {

    private val noOp = object : DiagnosticsSink {
        override fun errorReport(site: String, error: Throwable) = Unit
        override fun event(level: String, event: String, detail: String) = Unit
        override fun eventLog(): List<EventEntry> = emptyList()
    }

    @Volatile
    private var sink: DiagnosticsSink = noOp

    fun install(sink: DiagnosticsSink) {
        this.sink = sink
    }

    /** A caught-and-hidden exception becomes one deduplicated error report (O5). */
    fun error(site: String, error: Throwable) = sink.errorReport(site, error)

    /** One structured event line (O6). */
    fun event(level: String, event: String, detail: String) = sink.event(level, event, detail)

    fun logEntries(): List<EventEntry> = sink.eventLog()

    /** Test seam: back to the no-op sink. */
    fun resetForTest() {
        sink = noOp
    }
}

/**
 * The real sink (O5/O6). Directory-injected so pure JVM tests drive it:
 * error reports go through the same [CrashOutbox] the A5 crash path uses,
 * the day gate persists beside the app's files, and events land in the
 * in-memory [EventLog] the `phone_diagnostics` job reports.
 */
class AndroidDiagnostics(
    filesDir: File,
    outboxDir: File,
    private val appVersion: String,
    private val deviceModel: String,
    private val sdkLevel: Int,
    private val now: () -> Long = System::currentTimeMillis,
) : DiagnosticsSink {

    private val gateFile = File(filesDir, "error-report-gate.json")
    private val outbox = CrashOutbox(outboxDir)
    private val log = EventLog(now = now)

    /** report save and day stamp are serialised. */
    private val gateLock = Any()

    override fun errorReport(site: String, error: Throwable) {
        try {
            synchronized(gateLock) {
                val stored = readGate()
                val key = ErrorReportGate.key(site, error.javaClass.name, appVersion)
                val stamp = ErrorReportGate.allow(stored, key, ErrorReportGate.dayOf(now()))
                if (stamp == null) {
                    log.log("info", "error_report_suppressed", "$site ${error.javaClass.simpleName}")
                    return
                }
                // Crash-safe order (correction 01-6): the REPORT is written
                // first, the day stamp second. A kill in between can at worst
                // produce one duplicate on the next occurrence — and because
                // the requestId is derived from site, class, app version and
                // the UTC day (correction 02-2), that duplicate reaches the
                // server as the SAME request and is deduplicated there.
                outbox.save(
                    CrashReports.errorReport(
                        site = site,
                        severity = CrashReports.SEVERITY_ERROR,
                        threadName = Thread.currentThread().name,
                        error = error,
                        appVersion = appVersion,
                        nowMs = now(),
                        device = CrashDevice(deviceModel, sdkLevel),
                        requestId = ErrorReportGate.stableRequestId(
                            site,
                            error.javaClass.name,
                            appVersion,
                            ErrorReportGate.dayOf(now()),
                        ),
                    ),
                )
                writeGate(stored + (key to stamp))
            }
            log.log("error", "error_report_stored", "$site ${error.javaClass.simpleName}")
        } catch (_: Throwable) {
            // Reporting must never take its caller down; a failed store is
            // retried on the next occurrence of the same site.
        }
    }

    override fun event(level: String, event: String, detail: String) = log.log(level, event, detail)

    override fun eventLog(): List<EventEntry> = log.snapshot()

    private fun readGate(): Map<String, Long> = try {
        ProtocolJsonLite.json.decodeFromString(
            MapSerializer(String.serializer(), Long.serializer()),
            gateFile.readText(),
        )
    } catch (_: Exception) {
        emptyMap()
    }

    private fun writeGate(stored: Map<String, Long>) {
        gateFile.parentFile?.mkdirs()
        gateFile.writeText(
            ProtocolJsonLite.json.encodeToString(
                MapSerializer(String.serializer(), Long.serializer()),
                stored,
            ),
        )
    }
}

/** The one Json instance the observability layer encodes with. */
private object ProtocolJsonLite {
    val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
}
