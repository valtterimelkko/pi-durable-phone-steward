package com.example.phonesteward.logic

/**
 * Builds the "Share summary" text. Input is [Facts], which by construction
 * carries no file names and no paths below folder level: app rows hold
 * package names, folder rows hold folder keys, buckets hold counts.
 */
object SummaryText {

    fun build(facts: Facts): String {
        val sb = StringBuilder()
        sb.appendLine("Phone Steward — storage summary")
        sb.appendLine(
            "Free ${ByteFormat.format(facts.space.freeBytes)} of ${ByteFormat.format(facts.space.totalBytes)}",
        )
        sb.appendLine()
        sb.appendLine("Top apps by size:")
        facts.topApps.forEachIndexed { index, app ->
            sb.appendLine(
                "${index + 1}. ${app.packageName} — app ${ByteFormat.format(app.appBytes)}, " +
                    "data ${ByteFormat.format(app.dataBytes)}, cache ${ByteFormat.format(app.cacheBytes)} " +
                    "(total ${ByteFormat.format(app.totalBytes)})",
            )
        }
        sb.appendLine()
        sb.appendLine("Top media folders:")
        facts.topFolders.forEach { folder ->
            sb.appendLine("- ${folder.folderKey}: ${ByteFormat.format(folder.bytes)} (${folder.itemCount} items)")
        }
        sb.appendLine()
        sb.appendLine("Photos and videos by age:")
        AgeBucket.entries.forEach { bucket ->
            val row = facts.buckets[bucket] ?: BucketRow(0, 0, 0)
            sb.appendLine(
                "- ${bucket.label()}: ${row.photos} photos, ${row.videos} videos, ${ByteFormat.format(row.bytes)}",
            )
        }
        sb.appendLine()
        sb.appendLine("Favourites: ${facts.favouriteCount}")
        return sb.toString()
    }
}
