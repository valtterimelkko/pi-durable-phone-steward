package com.example.phonesteward.logic

/**
 * Groups MediaStore rows into displayable media folders.
 *
 * Rules:
 * - `Android/media/<package>/…` groups at the package level, so all WhatsApp
 *   (or Telegram) media lands in one row;
 * - everything else groups at the first two path segments (DCIM/Camera,
 *   Pictures/Screenshots, …), single-segment paths keep that one segment;
 * - blank or unknown paths go to "(no folder)".
 *
 * The file name never survives grouping: [group] output carries folder keys,
 * byte sums and item counts only.
 */
object FolderGrouping {

    const val NO_FOLDER = "(no folder)"

    fun folderKey(relativePath: String?): String {
        if (relativePath.isNullOrBlank()) return NO_FOLDER
        val parts = relativePath.trim('/').split('/').filter { it.isNotBlank() }
        if (parts.isEmpty()) return NO_FOLDER
        return when {
            parts.size >= 3 && parts[0] == "Android" && parts[1] == "media" ->
                parts.take(3).joinToString("/")
            parts.size >= 2 -> parts.take(2).joinToString("/")
            // A lone segment containing a dot is a bare file name, not a
            // directory: keep it out of the keys so a name can never reach
            // the summary.
            parts[0].contains('.') -> NO_FOLDER
            else -> parts[0]
        }
    }

    fun group(rows: List<MediaRow>): List<FolderRow> =
        rows.groupBy { folderKey(it.relativePath) }
            .map { (key, groupRows) ->
                FolderRow(
                    folderKey = key,
                    bytes = groupRows.sumOf { it.sizeBytes },
                    itemCount = groupRows.size,
                )
            }
            .sortedWith(compareByDescending<FolderRow> { it.bytes }.thenBy { it.folderKey })
}
