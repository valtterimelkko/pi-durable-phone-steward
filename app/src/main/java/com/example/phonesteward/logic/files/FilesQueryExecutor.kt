package com.example.phonesteward.logic.files

import java.io.File
import kotlinx.serialization.json.JsonObject
import com.example.phonesteward.logic.exec.body
import com.example.phonesteward.logic.jobs.JobExecutor
import com.example.phonesteward.logic.jobs.JobOutcome
import com.example.phonesteward.net.DuPath
import com.example.phonesteward.net.FileEntry
import com.example.phonesteward.net.FilesDuData
import com.example.phonesteward.net.FilesFindData
import com.example.phonesteward.net.FilesListData
import com.example.phonesteward.net.FilesQueryParams
import com.example.phonesteward.net.Job
import com.example.phonesteward.net.ProtocolJson

/**
 * `files_query` (protocol A10.1): `list`, `find` and `du` over the primary shared storage, with all-files
 * access. Read only. Paths are relative to [root] (`""` is the root); absolute paths, `..` and symlink escapes
 * are `BAD_PATH`. At most [maxEntries] entries per answer and [budgetMs] per job; `truncated: true` when a cap
 * cut an answer. A size is always exact or absent, never a partial total.
 *
 * Pure JVM over `java.io`: the tests run it on temporary folders.
 */
class FilesQueryExecutor(
    private val root: File,
    private val accessGranted: () -> Boolean,
    private val nowMillis: () -> Long,
    private val maxEntries: Int = 500,
    private val budgetMs: Long = 25_000,
    private val clockMs: () -> Long = System::currentTimeMillis,
) : JobExecutor {

    private class Refused(val code: String, val reason: String) : RuntimeException(null, null, false, false)

    override fun execute(job: Job): JobOutcome {
        if (!accessGranted()) {
            return JobOutcome.Failed(
                "NO_ALL_FILES_ACCESS",
                "The steward needs all-files access to look at folders. The owner can grant it on the Status tab.",
            )
        }
        val params = try {
            ProtocolJson.decodeFromJsonElement(FilesQueryParams.serializer(), job.params)
        } catch (_: Exception) {
            return JobOutcome.Failed("BAD_PARAMS", "files_query params unreadable")
        }
        val budget = Budget(budgetMs, clockMs)
        return try {
            when (params.op) {
                "list" -> list(params, budget)
                "find" -> find(params, budget)
                "du" -> du(params, budget)
                else -> JobOutcome.Failed("BAD_PARAMS", "unknown files_query op ${params.op}")
            }
        } catch (r: Refused) {
            JobOutcome.Failed(r.code, r.reason)
        }
    }

    // ---- paths ----

    private class Target(val rel: String, val file: File)

    /** The safe, existing target for [raw], or a [Refused]. */
    private fun resolve(raw: String): Target {
        val rel = FilePaths.normalise(raw) ?: throw Refused("BAD_PATH", "not a safe relative path: use a path from the storage root, with no leading / and no ..")
        if (FileTree.hasLinkInPath(root, rel)) throw Refused("BAD_PATH", "symbolic links are not followed")
        val file = if (rel.isEmpty()) root else File(root, rel)
        // Belt and braces: the canonical path must still sit inside the canonical root.
        val canonRoot = root.canonicalFile.toPath()
        if (!file.canonicalFile.toPath().startsWith(canonRoot)) throw Refused("BAD_PATH", "the path leaves the storage root")
        return Target(rel, file)
    }

    private fun existing(raw: String): Target {
        val t = resolve(raw)
        if (!t.file.exists()) throw Refused("NOT_FOUND", "no such path")
        return t
    }

    // ---- entries ----

    private class Sizes(val budget: Budget) {
        private val cache = HashMap<String, Totals>()
        fun of(dir: File, rel: String): Totals = cache.getOrPut(rel) { FileTree.totals(dir, rel, budget) }
    }

    private fun entryOf(file: File, rel: String, sizes: Sizes): FileEntry {
        if (!file.isDirectory) return FileEntry(rel, "file", file.length(), file.lastModified())
        if (FilePaths.isAndroidPrivate(rel) || FileTree.children(file, rel) == null) {
            return FileEntry(rel, "dir", 0, file.lastModified(), fileCount = 0, unreadable = true)
        }
        val t = sizes.of(file, rel)
        return FileEntry(rel, "dir", t.bytes, file.lastModified(), fileCount = t.files)
    }

    private val bySize = compareByDescending<FileEntry> { it.sizeBytes }.thenBy { it.path.lowercase() }

    // ---- list ----

    private fun list(p: FilesQueryParams, budget: Budget): JobOutcome {
        if (p.depth !in 1..3) return JobOutcome.Failed("BAD_PARAMS", "depth must be 1 to 3")
        val target = existing(p.path)
        if (!target.file.isDirectory) throw Refused("NOT_FOUND", "not a folder")
        val sizes = Sizes(budget)
        val out = mutableListOf<FileEntry>()
        var truncated = false

        fun level(dir: File, rel: String, depth: Int): Boolean { // false = stop everything
            val kids = FileTree.children(dir, rel) ?: return true
            val entries = mutableListOf<Pair<FileEntry, File>>()
            var ranOut = false
            try {
                for (k in kids) entries += entryOf(k, FileTree.join(rel, k.name), sizes) to k
            } catch (_: BudgetExceeded) {
                ranOut = true
            }
            entries.sortWith { a, b -> bySize.compare(a.first, b.first) }
            for ((entry, file) in entries) {
                if (out.size >= maxEntries) {
                    truncated = true
                    return false
                }
                out += entry
                if (entry.type == "dir" && entry.unreadable != true && depth < p.depth) {
                    if (!level(file, entry.path, depth + 1)) return false
                }
            }
            if (ranOut || entries.size < kids.size) {
                truncated = true
                return false
            }
            return true
        }
        level(target.file, target.rel, 1)
        return JobOutcome.Ok(body(FilesListData(target.rel, out, truncated)))
    }

    // ---- find ----

    private fun find(p: FilesQueryParams, budget: Budget): JobOutcome {
        val pattern = p.namePattern?.takeIf { it.isNotEmpty() }
            ?: return JobOutcome.Failed("BAD_PARAMS", "find needs a namePattern")
        if (p.type !in setOf("file", "dir", "any")) return JobOutcome.Failed("BAD_PARAMS", "type must be file, dir or any")
        val start = existing(p.root)
        if (!start.file.isDirectory) throw Refused("NOT_FOUND", "not a folder")
        val limit = p.limit.coerceIn(1, maxEntries)
        val regex = globToRegex(pattern)
        val cutoff = p.olderThanDays?.let { nowMillis() - it * 86_400_000L }
        val sizes = Sizes(budget)
        val found = mutableListOf<FileEntry>()
        var truncated = false

        fun visit(dir: File, rel: String): Boolean { // false = stop
            val kids = FileTree.children(dir, rel)?.sortedBy { it.name.lowercase() } ?: return true
            for (k in kids) {
                budget.check()
                val krel = FileTree.join(rel, k.name)
                val isDir = k.isDirectory
                val typeOk = p.type == "any" || (p.type == "dir") == isDir
                if (typeOk && regex.matches(k.name) && (cutoff == null || k.lastModified() < cutoff)) {
                    val entry = entryOf(k, krel, sizes)
                    if (entry.sizeBytes >= p.minBytes) {
                        if (found.size >= limit) {
                            truncated = true
                            return false
                        }
                        found += entry
                    }
                }
                if (isDir && !visit(k, krel)) return false
            }
            return true
        }
        try {
            visit(start.file, start.rel)
        } catch (_: BudgetExceeded) {
            truncated = true
        }
        found.sortWith(bySize)
        return JobOutcome.Ok(body(FilesFindData(found, truncated)))
    }

    /** A case-insensitive glob on a file name: `*` any run, `?` one character. */
    private fun globToRegex(glob: String): Regex {
        val sb = StringBuilder()
        for (c in glob) when (c) {
            '*' -> sb.append(".*")
            '?' -> sb.append('.')
            else -> sb.append(Regex.escape(c.toString()))
        }
        return Regex(sb.toString(), setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    }

    // ---- du ----

    private fun du(p: FilesQueryParams, budget: Budget): JobOutcome {
        if (p.paths.isEmpty() || p.paths.size > 50) return JobOutcome.Failed("BAD_PARAMS", "du needs 1 to 50 paths")
        val results = mutableListOf<DuPath>()
        var truncated = false
        try {
            for (raw in p.paths) {
                val t = existing(raw)
                val type = if (t.file.isDirectory) "dir" else "file"
                val modified = t.file.lastModified()
                results += when {
                    !t.file.isDirectory -> DuPath(t.rel, type, t.file.length(), 1, 0, modified, false)
                    FilePaths.isAndroidPrivate(t.rel) -> DuPath(t.rel, type, 0, 0, 0, modified, true)
                    else -> FileTree.totals(t.file, t.rel, budget).let { DuPath(t.rel, type, it.bytes, it.files, it.dirs, modified, false) }
                }
            }
        } catch (_: BudgetExceeded) {
            truncated = true
        }
        return JobOutcome.Ok(body(FilesDuData(results, if (truncated) true else null)))
    }
}
