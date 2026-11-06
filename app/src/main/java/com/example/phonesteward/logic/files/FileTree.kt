package com.example.phonesteward.logic.files

import java.io.File
import java.nio.file.Files

/** The time budget of one query ran out. Carries no stack: it is control flow. */
internal class BudgetExceeded : RuntimeException(null, null, false, false)

/** A wall-clock budget; [check] throws [BudgetExceeded] once [limitMs] has passed since it was created. */
internal class Budget(private val limitMs: Long, private val clock: () -> Long) {
    private val start = clock()
    fun check() {
        if (clock() - start > limitMs) throw BudgetExceeded()
    }
}

/** Recursive totals of a folder (not counting the folder itself in `dirs`). */
internal class Totals(var bytes: Long = 0, var files: Long = 0, var dirs: Long = 0)

/**
 * The walks behind `files_query` and the Steward trash. Symbolic links are never followed and never counted;
 * `Android/data` and `Android/obb` are never entered; the Steward trash at the storage root is not part of the
 * root's contents.
 */
internal object FileTree {

    fun isLink(f: File): Boolean = Files.isSymbolicLink(f.toPath())

    /** True when [rel] or any folder above it (inside [root]) is a symbolic link. */
    fun hasLinkInPath(root: File, rel: String): Boolean {
        var current = root
        for (seg in FilePaths.segments(rel)) {
            current = File(current, seg)
            if (isLink(current)) return true
        }
        return false
    }

    /** The children of [dir] without symlinks, or null when Android will not list it. [rel] is [dir]'s path from the root. */
    fun children(dir: File, rel: String): List<File>? {
        if (FilePaths.isAndroidPrivate(rel)) return null
        val all = try {
            dir.listFiles()
        } catch (_: SecurityException) {
            null
        } ?: return null
        return all.filter { !isLink(it) && !(rel.isEmpty() && it.name.equals(FilePaths.TRASH_DIR, ignoreCase = true)) }
    }

    fun join(rel: String, name: String) = if (rel.isEmpty()) name else "$rel/$name"

    /** Recursive totals of [dir]. [budget] is checked once per folder visited. */
    fun totals(dir: File, rel: String, budget: Budget?): Totals {
        val t = Totals()
        fun walk(d: File, r: String) {
            budget?.check()
            val kids = children(d, r) ?: return
            for (k in kids) {
                if (k.isDirectory) {
                    t.dirs++
                    walk(k, join(r, k.name))
                } else {
                    t.files++
                    t.bytes += k.length()
                }
            }
        }
        walk(dir, rel)
        return t
    }
}
