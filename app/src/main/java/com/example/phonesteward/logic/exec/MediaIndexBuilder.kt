package com.example.phonesteward.logic.exec

import java.time.ZoneId
import com.example.phonesteward.logic.AgeBuckets
import com.example.phonesteward.logic.FolderScopes
import com.example.phonesteward.logic.AgeBucket
import com.example.phonesteward.net.AgeBucketsData
import com.example.phonesteward.net.BucketStat
import com.example.phonesteward.net.FolderIndex
import com.example.phonesteward.net.MediaIndexCandidatesData
import com.example.phonesteward.net.MediaIndexSummaryData
import com.example.phonesteward.net.MediaRef
import com.example.phonesteward.net.TopLevelFile

/**
 * One scanned media row with its protocol identity. `ref.mediaId` follows
 * §3.3: `<volume>:<images|video|files>:<MediaStore _ID>`. Pure data.
 */
data class IndexedMedia(
    val ref: MediaRef,
    val isImage: Boolean,
    val isVideo: Boolean,
)

/**
 * Builds `media_index` result data (protocol §3.3):
 * - `folders` covers all of shared storage, grouped as in P1;
 * - age buckets are counted ONLY for folders inside the rule's `scope`;
 *   folders outside it carry `buckets: null`;
 * - `topLevelFiles` lists the files directly in the root of shared storage;
 * - `largest` is capped at 50;
 * - `oldApks` lists stray `.apk` files found in the scan (installer leftovers),
 *   capped at 50 (recorded engineering decision — the protocol names the
 *   field but not its source).
 */
object MediaIndexBuilder {

    fun summary(
        scope: List<String>,
        rows: List<IndexedMedia>,
        topLevelFiles: List<TopLevelFile>,
        nowMillis: Long,
        zone: ZoneId,
        largestCap: Int = 50,
        oldApksCap: Int = 50,
    ): MediaIndexSummaryData {
        val folders = rows
            .groupBy { it.ref.relativePath.trimEnd('/') }
            .map { (folderKey, inFolder) ->
                val bytes = inFolder.sumOf { it.ref.sizeBytes }
                // A6: buckets for every folder a scope entry's subtree covers.
                val scoped = FolderScopes.anyContains(scope, folderKey)
                FolderIndex(
                    folderKey = folderKey,
                    bytes = bytes,
                    count = inFolder.size,
                    buckets = if (scoped) bucketsFor(inFolder, nowMillis, zone) else null,
                )
            }
            .sortedBy { it.folderKey }

        val largest = rows
            .filter { it.isImage || it.isVideo }
            .sortedByDescending { it.ref.sizeBytes }
            .take(largestCap)
            .map { it.ref }

        val apks = rows
            .filter { !it.isImage && !it.isVideo && it.ref.displayName.endsWith(".apk", ignoreCase = true) }
            .distinctBy { it.ref.displayName }
            .take(oldApksCap)
            .map {
                TopLevelFile(
                    name = it.ref.displayName,
                    sizeBytes = it.ref.sizeBytes,
                    modifiedMs = it.ref.dateAddedMs ?: 0,
                )
            }

        return MediaIndexSummaryData(
            scope = scope,
            folders = folders,
            largest = largest,
            topLevelFiles = topLevelFiles,
            oldApks = apks,
        )
    }

    /** Every image and video in scope past `olderThanDays`; protected ones included, each marked. */
    fun candidates(
        scope: List<String>,
        rows: List<IndexedMedia>,
        olderThanDays: Int,
        nowMillis: Long,
        zone: ZoneId,
    ): MediaIndexCandidatesData {
        val cutoff = nowMillis - olderThanDays * 24L * 60 * 60 * 1000
        val items = rows
            .filter { it.isImage || it.isVideo }
            .filter { row -> FolderScopes.anyContains(scope, row.ref.relativePath) } // A6: subtree match
            .filter { row ->
                val date = row.ref.dateTakenMs?.takeIf { it > 0 }
                    ?: row.ref.dateAddedMs?.takeIf { it > 0 }
                date != null && date <= cutoff // unknown age is never provably old
            }
            .map { it.ref }
        return MediaIndexCandidatesData(scope = scope, items = items)
    }

    private fun bucketsFor(rows: List<IndexedMedia>, nowMillis: Long, zone: ZoneId): AgeBucketsData {
        var under3m = BucketStat()
        var m3to4 = BucketStat()
        var over4m = BucketStat()
        var unknown = BucketStat()
        for (row in rows) {
            if (!row.isImage && !row.isVideo) continue
            val bucket = AgeBuckets.bucketFor(
                row.ref.dateTakenMs,
                row.ref.dateAddedMs,
                nowMillis,
                zone,
            )
            val stat = BucketStat(count = 1, bytes = row.ref.sizeBytes)
            when (bucket) {
                AgeBucket.UNDER_3_MONTHS -> under3m += stat
                AgeBucket.MONTHS_3_TO_4 -> m3to4 += stat
                AgeBucket.OVER_4_MONTHS -> over4m += stat
                AgeBucket.UNKNOWN -> unknown += stat
            }
        }
        return AgeBucketsData(under3m, m3to4, over4m, unknown)
    }

    private operator fun BucketStat.plus(other: BucketStat): BucketStat =
        BucketStat(count + other.count, bytes + other.bytes)
}
