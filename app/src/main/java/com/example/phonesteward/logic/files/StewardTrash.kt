package com.example.phonesteward.logic.files

import java.io.File
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import com.example.phonesteward.net.FileEntry
import com.example.phonesteward.net.StewardTrashStatus

/** A file or folder as it is on the phone now: a folder's size and count are recursive totals. */
data class FileStat(val type: String, val sizeBytes: Long, val modifiedMs: Long, val fileCount: Long?)

sealed interface MoveResult {
    data class Moved(val trashedBytes: Long) : MoveResult
    data class Failed(val reason: String) : MoveResult
}

data class PurgeResult(val bytesRemoved: Long, val foldersRemoved: Int)

/**
 * A11.3: one item the restore screen lists, rebuilt from the trash by the merge-descend walk ([StewardTrash.listTrash]).
 * [path] is the path the item had before it was moved (relative to the storage root).
 */
data class TrashItem(
    val path: String,
    /** `file` or `dir`. */
    val type: String,
    val sizeBytes: Long,
    /** True when something now stands at the original path, so the item stays in the trash. */
    val blocked: Boolean = false,
    /** Why it stays, in the owner's words; null when it can be restored. */
    val reason: String? = null,
)

/** A11.3: one dated proposal folder in the trash, as the restore screen lists it (newest first). */
data class TrashBatch(
    /** The dated folder's name, `yyyy-MM-dd`. */
    val date: String,
    val dateMs: Long,
    val proposalId: String,
    val items: List<TrashItem>,
) {
    val sizeBytes: Long get() = items.sumOf { it.sizeBytes }
}

/** A11.3: what one restore actually did. */
data class RestoreResult(val restored: List<TrashItem>, val blocked: List<TrashItem>) {
    val bytesRestored: Long get() = restored.sumOf { it.sizeBytes }
}

/** What the proposal planner needs from the phone's files; [StewardTrash] is the real one. */
interface FilesPort {
    fun stat(path: String): FileStat?
    fun move(proposalId: String, path: String): MoveResult
}

/**
 * The Steward trash (A10.1): `<root>/.StewardTrash/<yyyy-MM-dd>/<proposalId>/<original path>`. A delete is a
 * rename inside the same volume (instant, reversible by hand); the space returns when the trash is emptied or
 * a dated folder is more than 30 days old. Pure JVM over `java.io`, so it is tested on temporary folders.
 */
class StewardTrash(
    private val root: File,
    private val zone: ZoneId,
    private val nowMs: () -> Long,
) : FilesPort {

    /**
     * Test seam (Correction 1): runs just before each entry is moved back, so a test can make one entry vanish
     * mid-restore exactly as a second restore of the same entries does. Never set outside tests, like
     * [com.example.phonesteward.ui.AppViewModel.neverTouchForTest].
     */
    internal var onBeforeMoveForTest: ((File) -> Unit)? = null

    private val trashDir = File(root, FilePaths.TRASH_DIR)

    private fun today(): LocalDate = Instant.ofEpochMilli(nowMs()).atZone(zone).toLocalDate()

    override fun stat(path: String): FileStat? {
        val n = FilePaths.normalise(path)?.takeIf { it.isNotEmpty() } ?: return null
        if (FileTree.hasLinkInPath(root, n)) return null
        val f = File(root, n)
        return when {
            f.isFile -> FileStat("file", f.length(), f.lastModified(), null)
            f.isDirectory -> FileTree.totals(f, n, null).let { FileStat("dir", it.bytes, f.lastModified(), it.files) }
            else -> null
        }
    }

    override fun move(proposalId: String, path: String): MoveResult {
        if (!SAFE_ID.matches(proposalId) || proposalId == "." || proposalId == "..") return MoveResult.Failed("the proposal id is not a plain name")
        FilePaths.deleteRefusal(path)?.let { return MoveResult.Failed("refused: $it") }
        val n = FilePaths.normalise(path) ?: return MoveResult.Failed("the path is not safe")
        if (FileTree.hasLinkInPath(root, n)) return MoveResult.Failed("a symbolic link is not moved")
        val src = File(root, n)
        val live = stat(n) ?: return MoveResult.Failed("the path is no longer there")
        val dst = File(trashDir, "${today()}/$proposalId/$n")
        if (dst.exists()) return MoveResult.Failed("it is already in the trash for this proposal")
        return try {
            dst.parentFile?.mkdirs()
            File(trashDir, ".nomedia").let { if (!it.exists()) it.createNewFile() } // keep gallery apps out of the trash
            Files.move(src.toPath(), dst.toPath(), StandardCopyOption.ATOMIC_MOVE)
            MoveResult.Moved(live.sizeBytes)
        } catch (e: Exception) {
            MoveResult.Failed("could not move it (${e.javaClass.simpleName})")
        }
    }

    /**
     * Moves [entry]'s path only if it is still exactly what the proposal named (re-stat, the A6 identity rule): the owner
     * may have spent a while on the confirmation. A changed or vanished item is `Failed` and nothing moves.
     */
    fun moveIfUnchanged(proposalId: String, entry: FileEntry): MoveResult {
        val live = stat(entry.path) ?: return MoveResult.Failed("the path is no longer there")
        if (!matches(entry, live)) return MoveResult.Failed("the item changed since the review")
        return move(proposalId, entry.path)
    }

    /** Bytes held, and the start of the oldest dated folder (null when empty). */
    fun status(): StewardTrashStatus {
        if (!trashDir.isDirectory) return StewardTrashStatus()
        val bytes = FileTree.totals(trashDir, FilePaths.TRASH_DIR, null).bytes
        val oldest = datedFolders().minOfOrNull { it.second }?.atStartOfDay(zone)?.toInstant()?.toEpochMilli()
        return StewardTrashStatus(bytes = bytes, oldestMs = oldest)
    }

    /** Removes dated folders more than [days] days old. */
    fun purgeOlderThan(days: Int): PurgeResult {
        val today = today()
        var bytes = 0L
        var folders = 0
        for ((dir, date) in datedFolders()) {
            if (ChronoUnit.DAYS.between(date, today) <= days) continue
            bytes += FileTree.totals(dir, FilePaths.TRASH_DIR + "/" + dir.name, null).bytes
            deleteTree(dir.toPath())
            folders++
        }
        return PurgeResult(bytes, folders)
    }

    /** Empties the trash at once; returns the bytes that came back. */
    fun empty(): Long {
        if (!trashDir.isDirectory) return 0L
        val bytes = FileTree.totals(trashDir, FilePaths.TRASH_DIR, null).bytes
        trashDir.listFiles()?.filter { it.name != ".nomedia" }?.forEach { deleteTree(it.toPath()) }
        return bytes
    }

    // ---- A11.3 restore --------------------------------------------------

    /**
     * The trash as the restore screen lists it: one batch per dated proposal folder, newest first, each item
     * rebuilt by running the merge-descend rule without moving anything. A batch moved before A11 (no manifest)
     * therefore shows correctly.
     */
    fun listTrash(): List<TrashBatch> {
        if (!trashDir.isDirectory) return emptyList()
        val out = mutableListOf<TrashBatch>()
        for ((dateDir, date) in datedFolders()) {
            for (proposalDir in dateDir.listFiles().orEmpty().filter { it.isDirectory && !FileTree.isLink(it) }) {
                if (!SAFE_ID.matches(proposalDir.name)) continue
                val items = mutableListOf<TrashItem>()
                for (child in proposalDir.listFiles().orEmpty().sortedBy { it.name }) {
                    collect(child, child.name, items)
                }
                if (items.isEmpty()) continue
                out += TrashBatch(
                    date = dateDir.name,
                    dateMs = date.atStartOfDay(zone).toInstant().toEpochMilli(),
                    proposalId = proposalDir.name,
                    items = items.sortedBy { it.path },
                )
            }
        }
        return out.sortedWith(compareByDescending<TrashBatch> { it.dateMs }.thenBy { it.proposalId })
    }

    /** Restores one item of [batch], applying the merge-descend rule from that item's path. */
    fun restoreItem(batch: TrashBatch, item: TrashItem): RestoreResult {
        val rel = FilePaths.normalise(item.path)?.takeIf { trimmable(it) } ?: return RestoreResult(emptyList(), emptyList())
        val proposalDir = proposalDirOf(batch) ?: return RestoreResult(emptyList(), emptyList())
        val entry = File(proposalDir, rel)
        if (!entry.exists()) return RestoreResult(emptyList(), emptyList())
        return restore(proposalDir) { collect(entry, rel, it) }
    }

    /** Restores every item of [batch] that the merge-descend rule can put back. */
    fun restoreBatch(batch: TrashBatch): RestoreResult {
        val proposalDir = proposalDirOf(batch) ?: return RestoreResult(emptyList(), emptyList())
        return restore(proposalDir) { out ->
            for (child in proposalDir.listFiles().orEmpty().sortedBy { it.name }) collect(child, child.name, out)
        }
    }

    /**
     * The merge-descend rule without moving: an entry whose original path is free is an item; a folder that now
     * stands where the entry was is descended into; anything else is an item that stays (blocked, with a plain
     * reason). Symbolic links are never followed, inside the trash or on the live side.
     */
    private fun collect(trashFile: File, rel: String, out: MutableList<TrashItem>) {
        if (FileTree.isLink(trashFile)) {
            out += TrashItem(rel, "file", 0, blocked = true, reason = LINK_REASON)
            return
        }
        val isDir = trashFile.isDirectory
        val size = if (isDir) FileTree.totals(trashFile, rel, null).bytes else trashFile.length()
        val type = if (isDir) "dir" else "file"
        val first = FilePaths.segments(rel).firstOrNull()
        if (rel.isEmpty() || first.equals(FilePaths.TRASH_DIR, ignoreCase = true) || FileTree.hasLinkInPath(root, rel)) {
            out += TrashItem(rel, type, size, blocked = true, reason = BLOCKED_REASON)
            return
        }
        val live = File(root, rel)
        if (!live.exists()) {
            out += TrashItem(rel, type, size)
            return
        }
        if (isDir && live.isDirectory) {
            for (child in trashFile.listFiles().orEmpty().sortedBy { it.name }) {
                collect(child, FileTree.join(rel, child.name), out)
            }
            return
        }
        out += TrashItem(rel, type, size, blocked = true, reason = BLOCKED_REASON)
    }

    private fun restore(proposalDir: File, gather: (MutableList<TrashItem>) -> Unit): RestoreResult {
        val found = mutableListOf<TrashItem>()
        gather(found)
        val restored = mutableListOf<TrashItem>()
        val blocked = mutableListOf<TrashItem>()
        for (candidate in found) {
            if (candidate.blocked) {
                blocked += candidate
                continue
            }
            val entry = File(proposalDir, candidate.path)
            onBeforeMoveForTest?.invoke(entry)
            // Gone while this pass ran (a second restore of the same entries took it): nothing to put back, and saying
            // "it could not be put back" would name a healthy item as failed. Correction 1 (review finding 3).
            if (!entry.exists()) continue
            if (moveBack(entry, candidate.path)) restored += candidate
            else blocked += candidate.copy(blocked = true, reason = MOVE_FAILED_REASON)
        }
        pruneEmpty(proposalDir)
        return RestoreResult(restored, blocked)
    }

    /** A same-volume rename back to the original path, with its parents recreated. Never follows a link. */
    private fun moveBack(entry: File, rel: String): Boolean {
        return try {
            if (FileTree.isLink(entry) || FileTree.hasLinkInPath(root, rel)) return false
            val live = File(root, rel)
            if (live.exists()) return false
            live.parentFile?.mkdirs()
            Files.move(entry.toPath(), live.toPath(), StandardCopyOption.ATOMIC_MOVE)
            true
        } catch (_: Exception) {
            false
        }
    }

    /** Removes the folders a restore left empty, up to the dated folder; the trash root and `.nomedia` stay. */
    private fun pruneEmpty(proposalDir: File) {
        fun sweep(dir: File) {
            for (child in dir.listFiles().orEmpty()) {
                if (child.isDirectory && !FileTree.isLink(child)) sweep(child)
            }
            if (dir.listFiles().orEmpty().isEmpty()) dir.delete()
        }
        sweep(proposalDir)
        val dateDir = proposalDir.parentFile
        if (dateDir != null && dateDir.isDirectory && dateDir.parentFile?.absolutePath == trashDir.absolutePath) {
            if (dateDir.listFiles().orEmpty().isEmpty()) dateDir.delete()
        }
    }

    /** The real dated proposal folder of [batch], or null when the batch does not name one inside the trash. */
    private fun proposalDirOf(batch: TrashBatch): File? {
        if (!DATE.matches(batch.date) || !SAFE_ID.matches(batch.proposalId)) return null
        if (batch.proposalId == "." || batch.proposalId == "..") return null
        val dir = File(trashDir, "${batch.date}/${batch.proposalId}")
        if (!dir.isDirectory || FileTree.isLink(dir)) return null
        return dir
    }

    private fun trimmable(rel: String): Boolean =
        rel.isNotEmpty() && !rel.startsWith("/") && rel.split('/').none { it == ".." || it.isEmpty() || it == "." }

    private fun datedFolders(): List<Pair<File, LocalDate>> =
        trashDir.listFiles()?.filter { it.isDirectory && !FileTree.isLink(it) }?.mapNotNull { dir ->
            if (!DATE.matches(dir.name)) null else try {
                dir to LocalDate.parse(dir.name)
            } catch (_: Exception) {
                null
            }
        }.orEmpty()

    /** Deletes a file or folder tree without ever following a symbolic link. */
    private fun deleteTree(start: Path) {
        try {
            Files.walkFileTree(start, object : SimpleFileVisitor<Path>() {
                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    Files.deleteIfExists(file)
                    return FileVisitResult.CONTINUE
                }

                override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult = FileVisitResult.CONTINUE

                override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                    try {
                        Files.deleteIfExists(dir)
                    } catch (_: IOException) {
                    }
                    return FileVisitResult.CONTINUE
                }
            })
        } catch (_: IOException) {
        }
    }

    companion object {
        private val SAFE_ID = Regex("[A-Za-z0-9._-]{1,128}")
        private val DATE = Regex("\\d{4}-\\d{2}-\\d{2}")

        /** The plain reason the owner sees when something now stands at an item's original path. */
        const val BLOCKED_REASON = "something is at that place now"

        /** The plain reason for a symbolic link the restore will never follow. */
        const val LINK_REASON = "a symbolic link is never restored"

        /** The plain reason when the rename back itself failed. */
        const val MOVE_FAILED_REASON = "it could not be put back"

        /** The A6 identity rule: the path must still be exactly the file or folder the proposal named. */
        fun matches(target: FileEntry, live: FileStat): Boolean =
            target.type == live.type &&
                target.sizeBytes == live.sizeBytes &&
                target.modifiedMs == live.modifiedMs &&
                (live.type == "file" || target.fileCount == live.fileCount)
    }
}
