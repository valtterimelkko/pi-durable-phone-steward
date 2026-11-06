package com.example.phonesteward.logic.files

import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import com.example.phonesteward.net.RestoredItem

/**
 * A11.3: one restore waiting to be told to the server. [requestId] is fixed when the restore happens and is the one
 * every retry sends, so a lost answer can never double-apply.
 */
@Serializable
data class RestoredReport(
    val requestId: String,
    val proposalId: String,
    val items: List<RestoredItem>,
    val atMs: Long,
)

/** What one upload attempt means for the stored report. */
enum class RestoreUpload {
    /** Accepted (or already recorded): delete it. */
    SENT,

    /** The server refused it for good (a bad path, a malformed body): delete it. */
    REFUSED,

    /** Offline or a server error: keep it for the next sync. */
    RETRY_LATER,
}

/**
 * The restore reports waiting on the phone for the next sync (A11.3). One small JSON file each, written atomically;
 * a file that does not parse is deleted, never sent; only the newest [MAX_PENDING] are kept.
 */
class RestoredOutbox(private val dir: File) {

    fun save(report: RestoredReport) {
        dir.mkdirs()
        val name = "restored-${report.atMs}-${report.requestId.filter { it.isLetterOrDigit() || it == '-' }}.json"
        val tmp = File(dir, "$name.tmp")
        tmp.writeText(JSON.encodeToString(RestoredReport.serializer(), report))
        if (!tmp.renameTo(File(dir, name))) tmp.delete()
        pending().dropLast(MAX_PENDING).forEach { it.file.delete() }
    }

    /** Oldest first. */
    fun pending(): List<PendingRestore> {
        val files = dir.listFiles { file -> file.name.startsWith("restored-") && file.name.endsWith(".json") }.orEmpty()
        return files.mapNotNull { file ->
            try {
                PendingRestore(file, JSON.decodeFromString(RestoredReport.serializer(), file.readText()))
            } catch (_: Exception) {
                file.delete()
                null
            }
        }.sortedBy { it.report.atMs }
    }

    fun remove(pending: PendingRestore) {
        pending.file.delete()
    }

    companion object {
        const val MAX_PENDING = 20
        private val JSON = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    }
}

/** A stored restore report and the file it lives in. */
data class PendingRestore(val file: File, val report: RestoredReport)

/** Sends the waiting restore reports oldest first; stops at the first retry-later. Returns how many were sent. */
class RestoreReporter(
    private val outbox: RestoredOutbox,
    private val send: (RestoredReport) -> RestoreUpload,
) {
    fun flush(): Int {
        var sent = 0
        for (pending in outbox.pending()) {
            when (send(pending.report)) {
                RestoreUpload.SENT -> {
                    outbox.remove(pending)
                    sent++
                }
                RestoreUpload.REFUSED -> outbox.remove(pending)
                RestoreUpload.RETRY_LATER -> return sent
            }
        }
        return sent
    }
}
