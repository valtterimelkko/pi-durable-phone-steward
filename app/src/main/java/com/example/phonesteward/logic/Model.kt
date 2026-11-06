package com.example.phonesteward.logic

import java.time.ZoneId

/**
 * Pure data model and source interfaces for the storage facts screen.
 * Everything here is plain Kotlin: the Android calls sit in adapters that
 * implement the three source interfaces.
 */

/**  age rule: remind at 3 months, trash at 4 months. */
enum class AgeBucket {
    UNDER_3_MONTHS,
    MONTHS_3_TO_4,
    OVER_4_MONTHS,
    UNKNOWN,
}

fun AgeBucket.label(): String = when (this) {
    AgeBucket.UNDER_3_MONTHS -> "Under 3 months"
    AgeBucket.MONTHS_3_TO_4 -> "3–4 months"
    AgeBucket.OVER_4_MONTHS -> "Over 4 months"
    AgeBucket.UNKNOWN -> "Unknown date"
}

/** One row from a MediaStore query. `relativePath` is the directory, not the file. */
data class MediaRow(
    val displayName: String,
    val relativePath: String?,
    val sizeBytes: Long,
    val dateTakenMillis: Long?,
    val dateAddedMillis: Long?,
    val isImage: Boolean,
    val isVideo: Boolean,
    val isFavourite: Boolean,
)

data class AppSizeRow(
    val packageName: String,
    val appBytes: Long,
    val dataBytes: Long,
    val cacheBytes: Long,
) {
    val totalBytes: Long get() = appBytes + dataBytes + cacheBytes
}

data class FolderRow(
    val folderKey: String,
    val bytes: Long,
    val itemCount: Int,
)

data class BucketRow(
    val photos: Int,
    val videos: Int,
    val bytes: Long,
)

data class SpaceRow(
    val freeBytes: Long,
    val totalBytes: Long,
)

data class Facts(
    val space: SpaceRow,
    val topApps: List<AppSizeRow>,
    val topFolders: List<FolderRow>,
    val buckets: Map<AgeBucket, BucketRow>,
    val favouriteCount: Int,
    val generatedAtMillis: Long,
)

/** Thin adapter interface: internal storage free/total. */
interface SpaceSource {
    fun load(): SpaceRow
}

/** Thin adapter interface: per-app sizes via StorageStatsManager. */
interface AppSizeSource {
    fun load(): List<AppSizeRow>
}

/** Thin adapter interface: shared-storage media and file rows via MediaStore. */
interface MediaSource {
    fun load(): List<MediaRow>
}

/**
 * Combines the three sources into one [Facts] value. Pure: sources are
 * interfaces, so fakes can stand in for Android.
 */
fun collect(
    space: SpaceSource,
    apps: AppSizeSource,
    media: MediaSource,
    nowMillis: Long,
    zone: ZoneId,
    topAppsN: Int = 15,
    topFoldersN: Int = 12,
): Facts {
    val appRows = TopN.topN(apps.load(), topAppsN) { it.totalBytes }
    val mediaRows = media.load()
    val folderRows = TopN.topN(FolderGrouping.group(mediaRows), topFoldersN) { it.bytes }

    val buckets = AgeBucket.entries.associateWith { BucketRow(photos = 0, videos = 0, bytes = 0) }.toMutableMap()
    var favourites = 0
    for (row in mediaRows) {
        if (!row.isImage && !row.isVideo) continue
        if (row.isFavourite) favourites++
        val bucket = AgeBuckets.bucketFor(row.dateTakenMillis, row.dateAddedMillis, nowMillis, zone)
        val current = buckets.getValue(bucket)
        buckets[bucket] = current.copy(
            photos = current.photos + if (row.isImage) 1 else 0,
            videos = current.videos + if (row.isVideo) 1 else 0,
            bytes = current.bytes + row.sizeBytes,
        )
    }

    return Facts(
        space = space.load(),
        topApps = appRows,
        topFolders = folderRows,
        buckets = buckets,
        favouriteCount = favourites,
        generatedAtMillis = nowMillis,
    )
}
