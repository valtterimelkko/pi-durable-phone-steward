package com.example.phonesteward.logic.files

/**
 * Paths for the A10.1 file tools. Every path is relative to the primary shared storage root
 * (`/storage/emulated/0`), with `/` separators; `""` is the root. Pure JVM.
 */
object FilePaths {

    /** The name of the Steward trash folder at the storage root. */
    const val TRASH_DIR = ".StewardTrash"

    /**
     * The tidy relative form of [raw] (no `.` segments, no empty segments, no trailing slash), or null when
     * the path is not safe: absolute, containing `..`, a backslash or a NUL.
     */
    fun normalise(raw: String): String? {
        if (raw.startsWith("/") || raw.contains('\\') || raw.contains('\u0000')) return null
        val parts = raw.split('/').filter { it.isNotEmpty() && it != "." }
        if (parts.any { it == ".." }) return null
        return parts.joinToString("/")
    }

    fun segments(normalised: String): List<String> = if (normalised.isEmpty()) emptyList() else normalised.split('/')

    /** Android 13 blocks other apps from `Android/data` and `Android/obb`: they are shown as unreadable, never walked. */
    fun isAndroidPrivate(normalised: String): Boolean {
        val seg = segments(normalised)
        return seg.size >= 2 && seg[0].equals("Android", ignoreCase = true) &&
            (seg[1].equals("data", ignoreCase = true) || seg[1].equals("obb", ignoreCase = true))
    }

    /**
     * Why [path] can never be the target of `delete_files` (A10.1), or null when it may be. Checked on the
     * server and again here: the root, `Android/` and anything under it, the Steward trash, any path with a
     * segment named `Keep` (any case), and the media folders `DCIM/` and `Pictures/` (photos and videos go
     * through `trash_media`).
     */
    fun deleteRefusal(path: String): String? {
        val n = normalise(path) ?: return "the path is not a safe relative path"
        if (n.isEmpty()) return "the storage root is never deleted"
        val seg = segments(n)
        val first = seg[0]
        return when {
            first.equals("Android", ignoreCase = true) -> "Android/ is never touched (it holds the apps' own data)"
            first.equals(TRASH_DIR, ignoreCase = true) -> "the Steward trash is not a delete target"
            seg.any { it.equals("Keep", ignoreCase = true) } -> "anything in a Keep folder is protected"
            first.equals("DCIM", ignoreCase = true) || first.equals("Pictures", ignoreCase = true) ->
                "photos and videos go through the media trash, not file deletion"
            else -> null
        }
    }
}
