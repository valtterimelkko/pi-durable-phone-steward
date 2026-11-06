package com.example.phonesteward.android

import android.content.ContentUris
import android.content.Context
import android.app.usage.StorageStatsManager
import android.os.Environment
import android.os.Process
import android.os.StatFs
import android.os.storage.StorageManager
import android.provider.MediaStore
import com.example.phonesteward.logic.AppSizeRow
import com.example.phonesteward.logic.AppSizeSource
import com.example.phonesteward.logic.MediaRow
import com.example.phonesteward.logic.MediaSource
import com.example.phonesteward.logic.SpaceRow
import com.example.phonesteward.logic.SpaceSource

/**
 * Thin Android adapters for the three pure source interfaces. Each adapter
 * does one Android call shape and nothing else; all analysis stays in the
 * logic package.
 */

/** Internal ("data") volume free and total space, as Settings shows it. */
class StatFsSpaceSource : SpaceSource {
    override fun load(): SpaceRow {
        val stat = StatFs(Environment.getDataDirectory().absolutePath)
        return SpaceRow(freeBytes = stat.availableBytes, totalBytes = stat.totalBytes)
    }
}

/** Per-app app/data/cache bytes via StorageStatsManager; needs usage access. */
class StorageStatsAppSizes(private val context: Context) : AppSizeSource {
    override fun load(): List<AppSizeRow> {
        val stats = context.getSystemService(StorageStatsManager::class.java) ?: return emptyList()
        val packageManager = context.packageManager
        val rows = mutableListOf<AppSizeRow>()
        for (pkg in packageManager.getInstalledPackages(0)) {
            try {
                val s = stats.queryStatsForPackage(
                    StorageManager.UUID_DEFAULT,
                    pkg.packageName,
                    Process.myUserHandle(),
                )
                rows += AppSizeRow(
                    packageName = pkg.packageName,
                    appBytes = s.appBytes,
                    dataBytes = s.dataBytes,
                    cacheBytes = s.cacheBytes,
                )
            } catch (_: Exception) {
                // Not measurable for this package (system or permission gap): skip.
            }
        }
        return rows
    }
}

/**
 * One pass over the MediaStore Files collection (images, videos and the
 * rest of shared storage, including Android/media with all-files access).
 */
class MediaStoreMediaSource(private val context: Context) : MediaSource {
    override fun load(): List<MediaRow> {
        val projection = arrayOf(
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.RELATIVE_PATH,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_TAKEN,
            MediaStore.MediaColumns.DATE_ADDED,
            MediaStore.MediaColumns.IS_FAVORITE,
            MediaStore.Files.FileColumns.MEDIA_TYPE,
        )
        val rows = mutableListOf<MediaRow>()
        val uri = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)
        try {
            context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                while (cursor.moveToNext()) {
                    val mediaType = cursor.getInt(6)
                    rows += MediaRow(
                        displayName = cursor.getString(0) ?: "",
                        relativePath = cursor.getString(1),
                        sizeBytes = cursor.getLong(2),
                        dateTakenMillis = cursor.getLong(3).takeIf { it > 0 },
                        dateAddedMillis = cursor.getLong(4).takeIf { it > 0 }?.times(1000L),
                        isFavourite = cursor.getInt(5) == 1,
                        isImage = mediaType == MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE,
                        isVideo = mediaType == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO,
                    )
                }
            }
        } catch (_: SecurityException) {
            // Media read not granted: return what we have (nothing).
        }
        return rows
    }
}

/** Resolves one own media item's id for messages; kept tiny. */
fun mediaItemId(uri: android.net.Uri): Long = ContentUris.parseId(uri)
