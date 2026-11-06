package com.example.phonesteward.android

import android.content.ContentUris
import android.content.Context
import android.app.usage.UsageStatsManager
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import java.time.ZoneId
import com.example.phonesteward.logic.AppSizeRow
import com.example.phonesteward.logic.exec.AutoTrashResult
import com.example.phonesteward.logic.exec.IndexedMedia
import com.example.phonesteward.logic.exec.InKeep
import com.example.phonesteward.logic.exec.LiveMediaRow
import com.example.phonesteward.logic.exec.MediaLookup
import com.example.phonesteward.net.AppUsageData
import com.example.phonesteward.net.AppUsageEntry
import com.example.phonesteward.net.MediaRef
import com.example.phonesteward.net.StorageCategories
import com.example.phonesteward.net.StorageOverviewApp
import com.example.phonesteward.net.TopLevelFile

/**
 * MediaStore-backed [MediaIndexSource]: one Files pass over
 * external_primary, metadata only . `mediaId` follows protocol §3.3:
 * `<volume>:<images|video|files>:<_ID>`.
 */
class MediaStoreIndexSource(private val context: Context) :
    com.example.phonesteward.logic.exec.MediaIndexSource {

    override fun load(): List<IndexedMedia> {
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.RELATIVE_PATH,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_TAKEN,
            MediaStore.MediaColumns.DATE_ADDED,
            MediaStore.MediaColumns.IS_FAVORITE,
            MediaStore.Files.FileColumns.MEDIA_TYPE,
        )
        val rows = mutableListOf<IndexedMedia>()
        val uri = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        try {
            context.contentResolver.query(uri, projection, null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getLong(0)
                    val mediaType = c.getInt(8)
                    val (isImage, isVideo, kind) = when (mediaType) {
                        MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE -> Triple(true, false, "images")
                        MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO -> Triple(false, true, "video")
                        else -> Triple(false, false, "files")
                    }
                    val relativePath = c.getString(2) ?: ""
                    rows += IndexedMedia(
                        ref = MediaRef(
                            mediaId = "external_primary:$kind:$id",
                            relativePath = relativePath,
                            displayName = c.getString(1) ?: "",
                            mimeType = c.getString(3),
                            sizeBytes = c.getLong(4),
                            dateTakenMs = c.getLong(5).takeIf { it > 0 },
                            dateAddedMs = c.getLong(6).takeIf { it > 0 }?.times(1000L),
                            isFavourite = c.getInt(7) == 1,
                            inKeep = InKeep.forPath(relativePath),
                        ),
                        isImage = isImage,
                        isVideo = isVideo,
                    )
                }
            }
        } catch (_: SecurityException) {
            // Media read not granted: empty; the executor reports what it saw.
        }
        return rows
    }
}

/** Files directly in the root of shared storage (R1: topLevelFiles). */
class RootFilesAdapter : com.example.phonesteward.logic.exec.RootFilesSource {

    override fun load(): List<TopLevelFile> {
        val root = Environment.getExternalStorageDirectory() ?: return emptyList()
        return root.listFiles()
            ?.filter { it.isFile }
            ?.map { TopLevelFile(it.name, it.length(), it.lastModified()) }
            .orEmpty()
    }
}

/** Per-app sizes with labels, for the storage overview (needs usage access). */
class OverviewAppsAdapter(private val context: Context) :
    com.example.phonesteward.logic.exec.OverviewAppSource {

    override fun load(): List<StorageOverviewApp> {
        val stats = context.getSystemService(android.app.usage.StorageStatsManager::class.java)
            ?: return emptyList()
        val pm = context.packageManager
        val out = mutableListOf<StorageOverviewApp>()
        for (pkg in pm.getInstalledPackages(0)) {
            try {
                val appInfo: android.content.pm.ApplicationInfo = pkg.applicationInfo ?: continue
                val s = stats.queryStatsForPackage(
                    android.os.storage.StorageManager.UUID_DEFAULT,
                    pkg.packageName,
                    android.os.Process.myUserHandle(),
                )
                out += StorageOverviewApp(
                    packageName = pkg.packageName,
                    label = pm.getApplicationLabel(appInfo).toString(),
                    appBytes = s.appBytes,
                    dataBytes = s.dataBytes,
                    cacheBytes = s.cacheBytes,
                    isSystem = (appInfo.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0,
                )
            } catch (_: Exception) {
                // Not measurable (system package or permission gap): skip.
            }
        }
        return out
    }

    /** P1 shape kept for the facts screen. */
    fun loadRows(): List<AppSizeRow> = load().map {
        AppSizeRow(it.packageName, it.appBytes, it.dataBytes, it.cacheBytes)
    }
}

/** Shared-storage bytes per category (images/video/audio/documents/other). */
class CategoryBytesAdapter(private val context: Context) :
    com.example.phonesteward.logic.exec.CategorySource {

    override fun load(): StorageCategories {
        val projection = arrayOf(
            MediaStore.Files.FileColumns.MEDIA_TYPE,
            MediaStore.MediaColumns.SIZE,
        )
        var images = 0L; var video = 0L; var audio = 0L; var docs = 0L; var other = 0L
        val uri = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        try {
            context.contentResolver.query(uri, projection, null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    val size = c.getLong(1)
                    when (c.getInt(0)) {
                        MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE -> images += size
                        MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO -> video += size
                        MediaStore.Files.FileColumns.MEDIA_TYPE_AUDIO -> audio += size
                        MediaStore.Files.FileColumns.MEDIA_TYPE_DOCUMENT -> docs += size
                        else -> other += size
                    }
                }
            }
        } catch (_: SecurityException) {
            // No media read: zeros; the overview still shows space and apps.
        }
        return StorageCategories(
            imagesBytes = images,
            videoBytes = video,
            audioBytes = audio,
            documentsBytes = docs,
            otherBytes = other,
        )
    }
}

/** App usage via UsageStatsManager; `lastUsedMs` null when Android has no record. */
class UsageStatsAdapter(private val context: Context) :
    com.example.phonesteward.logic.exec.UsageSource {

    override fun load(): AppUsageData {
        val usage = context.getSystemService(UsageStatsManager::class.java)
            ?: return AppUsageData(emptyList())
        val pm = context.packageManager
        val now = System.currentTimeMillis()
        val yearAgo = now - 365L * 24 * 3600 * 1000
        val lastUsed = mutableMapOf<String, Long>()
        try {
            val events = usage.queryEvents(yearAgo, now)
            val event = android.app.usage.UsageEvents.Event()
            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                if (event.eventType == android.app.usage.UsageEvents.Event.MOVE_TO_FOREGROUND) {
                    event.packageName?.let { pkg ->
                        lastUsed[pkg] = maxOf(lastUsed[pkg] ?: 0L, event.timeStamp)
                    }
                }
            }
        } catch (_: Exception) {
            // Usage access not granted: everything reports null last-used.
        }
        val entries = mutableListOf<AppUsageEntry>()
        for (pkg in pm.getInstalledPackages(0)) {
            entries += AppUsageEntry(
                packageName = pkg.packageName,
                label = pkg.applicationInfo?.let {
                    try {
                        pm.getApplicationLabel(it).toString()
                    } catch (_: Exception) {
                        pkg.packageName
                    }
                } ?: pkg.packageName,
                lastUsedMs = lastUsed[pkg.packageName],
                installedMs = pkg.firstInstallTime,
                isSystem = ((pkg.applicationInfo?.flags ?: 0) and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0,
            )
        }
        return AppUsageData(entries)
    }
}

/** Re-reads one live MediaStore row before an action (protocol §3.3). */
class MediaStoreLiveLookup(private val context: Context) : MediaLookup {

    override fun byId(mediaId: String): LiveMediaRow? {
        val parts = mediaId.split(":")
        if (parts.size != 3) return null
        val (_, kind, rawId) = parts
        val id = rawId.toLongOrNull() ?: return null
        val collection = when (kind) {
            "images" -> MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            "video" -> MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            "files" -> MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            else -> return null
        }
        val projection = arrayOf(
            MediaStore.MediaColumns.IS_FAVORITE,
            MediaStore.MediaColumns.RELATIVE_PATH,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.SIZE,
        )
        return try {
            context.contentResolver.query(
                collection,
                projection,
                "${MediaStore.MediaColumns._ID} = ?",
                arrayOf(id.toString()),
                null,
            )?.use { c ->
                if (c.moveToFirst()) {
                    val path = c.getString(1)
                    LiveMediaRow(
                        isFavourite = c.getInt(0) == 1,
                        inKeep = InKeep.forPath(path),
                        sizeBytes = c.getLong(3),
                        relativePath = path,
                        displayName = c.getString(2),
                    )
                } else {
                    null
                }
            }
        } catch (e: Exception) {
            com.example.phonesteward.logic.obs.Diagnostics.error("lookup.row_read", e)
            null // unreadable: treated as missing, never as free to act
        }
    }
}

/**
 * Auto-mode trash for `rule_batch`: with `MANAGE_MEDIA` the app marks
 * IS_TRASHED directly, with no dialog (protocol §3.3). Without it the
 * update fails and the item reports `failed` — never a silent no-op.
 */
class AndroidAutoTrash(private val context: Context) {

    fun trash(items: List<MediaRef>): Map<String, AutoTrashResult> {
        val out = mutableMapOf<String, AutoTrashResult>()
        for (item in items) {
            out[item.mediaId] = trashOne(item)
        }
        return out
    }

    private fun trashOne(item: MediaRef): AutoTrashResult {
        if (!Permissions.canManageMedia(context)) {
            return AutoTrashResult(0, "MANAGE_MEDIA not granted")
        }
        val uri = uriFor(item) ?: return AutoTrashResult(0, "unresolvable mediaId")
        return try {
            val values = android.content.ContentValues().apply {
                put(MediaStore.MediaColumns.IS_TRASHED, 1)
            }
            val updated = context.contentResolver.update(uri, values, null, null)
            if (updated == 1) AutoTrashResult(-1, "trashed") else AutoTrashResult(0, "row not updated")
        } catch (e: Exception) {
            AutoTrashResult(0, e.message ?: "update failed")
        }
    }

    private fun uriFor(item: MediaRef): Uri? {
        val parts = item.mediaId.split(":")
        if (parts.size != 3) return null
        val kind = parts[1]
        val id = parts[2].toLongOrNull() ?: return null
        val collection = when (kind) {
            "images" -> MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            "video" -> MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            "files" -> MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            else -> return null
        }
        return ContentUris.withAppendedId(collection, id)
    }
}
