package com.example.phonesteward.logic

/**
 * Folder-scope matching (protocol A6): a folder entry matches
 * the items directly in it AND in every subfolder. The trailing `/` is
 * optional — both sides are normalised to end with `/` before a prefix
 * comparison. Case is compared as stored.
 */
object FolderScopes {

    /** `DCIM/Camera` and `DCIM/Camera/` both normalise to `DCIM/Camera/`. */
    fun normalise(path: String): String {
        val trimmed = path.trim()
        return if (trimmed.endsWith("/")) trimmed else "$trimmed/"
    }

    /** True when [relativePath] sits inside the subtree [scopeEntry] covers. */
    fun contains(scopeEntry: String, relativePath: String): Boolean =
        normalise(relativePath).startsWith(normalise(scopeEntry))

    /** True when any scope entry's subtree covers [relativePath]. */
    fun anyContains(scope: List<String>, relativePath: String): Boolean =
        scope.any { contains(it, relativePath) }
}
