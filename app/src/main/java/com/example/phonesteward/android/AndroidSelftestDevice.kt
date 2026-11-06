package com.example.phonesteward.android

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.os.storage.StorageManager
import android.provider.MediaStore
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import com.example.phonesteward.logic.selftest.CheckResult
import com.example.phonesteward.logic.selftest.SelftestDevice
import com.example.phonesteward.logic.selftest.TestItem

/**
 * Registry through which the self-test asks the foreground activity to
 * launch Android's own dialogs (trash, clear-cache). The owner taps; the
 * result code flows back here. When no activity is registered the checks
 * report `skipped` — the app must be open for a self-test (R1: the owner
 * only taps system dialogs).
 */
object SelftestDialogs {

    interface Launcher {
        /** Launches the IntentSender and completes with the activity result code. */
        fun launch(intentSender: android.content.IntentSender): CompletableFuture<Int>
    }

    @Volatile
    private var launcher: Launcher? = null

    fun register(l: Launcher) {
        launcher = l
    }

    fun unregister(l: Launcher) {
        if (launcher === l) launcher = null
    }

    fun launch(intentSender: android.content.IntentSender): CheckResult {
        val l = launcher ?: return CheckResult(null, "no launcher: the app is closed")
        val startedAt = android.os.SystemClock.elapsedRealtime()
        return try {
            val code = l.launch(intentSender).get(120, TimeUnit.SECONDS)
            val elapsedMs = android.os.SystemClock.elapsedRealtime() - startedAt
            CheckResult(code, "system dialog answered", elapsedMs = elapsedMs)
        } catch (_: Exception) {
            CheckResult(null, "dialog not answered in time")
        }
    }

    fun available(): Boolean = launcher != null
}

/**
 * The Android half of `phone_selftest`: creates the app's own test images
 * through the P1 [TestImages] guard and drives the five checks with real
 * MediaStore calls. Item creation is guarded twice — here (only own items
 * are ever created) and in the pure [com.example.phonesteward.logic.selftest.SelftestRunner]
 * (every descriptor re-validated before use).
 */
class AndroidSelftestDevice(
    private val context: Context,
    private val testImages: TestImages,
) : SelftestDevice {

    override fun canManageMedia(): Boolean = Permissions.canManageMedia(context)

    override fun createTestImages(count: Int): List<TestItem> {
        val out = mutableListOf<TestItem>()
        repeat(count) {
            val uri = testImages.createTestImage() ?: return@repeat
            out += describe(uri)
        }
        return out
    }

    /** Reads the markers the pure guard validates (owner, path, prefix). */
    private fun describe(uriString: String): TestItem {
        val uri = Uri.parse(uriString)
        val projection = arrayOf(
            MediaStore.MediaColumns.OWNER_PACKAGE_NAME,
            MediaStore.MediaColumns.RELATIVE_PATH,
            MediaStore.MediaColumns.DISPLAY_NAME,
        )
        return try {
            context.contentResolver.query(uri, projection, null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    TestItem(uriString, c.getString(0), c.getString(1), c.getString(2))
                } else {
                    TestItem(uriString, null, null, null)
                }
            } ?: TestItem(uriString, null, null, null)
        } catch (_: Exception) {
            TestItem(uriString, null, null, null)
        }
    }

    override fun trashRequest(items: List<TestItem>, expectDialog: Boolean): CheckResult {
        val uris = items.mapNotNull { runCatching { Uri.parse(it.uri) }.getOrNull() }
        if (uris.size != items.size) {
            return CheckResult(null, "unreadable uri in batch")
        }
        return try {
            val request = MediaStore.createTrashRequest(context.contentResolver, uris, true)
            val result = SelftestDialogs.launch(request.intentSender)
            result.copy(
                detail = "batch of ${uris.size}${if (expectDialog) "" else " (MANAGE_MEDIA path)"}; ${result.detail}",
            )
        } catch (e: ActivityNotFoundException) {
            CheckResult(null, "trash request not launchable: ${e.message}")
        } catch (e: Exception) {
            CheckResult(null, e.message ?: "createTrashRequest failed")
        }
    }

    override fun restoreTrashed(item: TestItem): CheckResult {
        val uri = try {
            Uri.parse(item.uri)
        } catch (_: Exception) {
            return CheckResult(null, "unreadable uri")
        }
        return try {
            val resolver = context.contentResolver
            // 1. Queryable AND marked trashed (QUERY_ARG_MATCH_TRASHED, IS_TRASHED=1)?
            val trashedBefore = queryMarked(uri, matchTrashed = true)
            // 2. Untrash request brings it back. 3. Confirm the row is present
            // with IS_TRASHED=0 under the DEFAULT match (fix 02-4). The pure
            // RestoreVerdict decides; pass only when both states were proven.
            val restore = MediaStore.createTrashRequest(resolver, listOf(uri), false)
            val launchResult = SelftestDialogs.launch(restore.intentSender)
            val restoredAfter =
                launchResult.resultCode == CheckResult.RESULT_OK && queryMarked(uri, matchTrashed = false)
            val verdict = com.example.phonesteward.logic.selftest.RestoreVerdict.decide(
                trashedBefore,
                restoredAfter,
            )
            CheckResult(
                if (verdict.outcome == "pass") CheckResult.RESULT_OK else 0,
                verdict.detail,
                elapsedMs = launchResult.elapsedMs,
            )
        } catch (e: Exception) {
            CheckResult(null, e.message ?: "restore failed")
        }
    }

    /**
     * True when the row is present and IS_TRASHED equals [expectTrashed].
     * `matchTrashed = true` queries with MATCH_INCLUDE (trash included);
     * `false` uses the DEFAULT match, so the row must be visible normally
     * with IS_TRASHED = 0 (fix 02-4).
     */
    private fun queryMarked(uri: Uri, matchTrashed: Boolean): Boolean {
        val resolver = context.contentResolver
        return resolver.query(
            MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
            arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.IS_TRASHED),
            android.os.Bundle().apply {
                putInt(
                    MediaStore.QUERY_ARG_MATCH_TRASHED,
                    if (matchTrashed) MediaStore.MATCH_INCLUDE else MediaStore.MATCH_DEFAULT,
                )
            },
            null,
        )?.use { c ->
            val idIndex = c.getColumnIndex(MediaStore.MediaColumns._ID)
            val trashedIndex = c.getColumnIndex(MediaStore.MediaColumns.IS_TRASHED)
            val wanted = uri.lastPathSegment?.toLongOrNull()
            while (c.moveToNext()) {
                if (wanted != null && c.getLong(idIndex) == wanted) {
                    return@use (c.getInt(trashedIndex) == 1) == matchTrashed
                }
            }
            false
        } ?: false
    }

    override fun clearCacheProbe(): CheckResult {
        val intent = Intent(StorageManager.ACTION_CLEAR_APP_CACHE)
        return try {
            SelftestDialogs.launch(
                android.app.PendingIntent.getActivity(
                    context,
                    0,
                    intent,
                    android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE,
                ).intentSender,
            )
        } catch (_: Exception) {
            CheckResult(null, "ACTION_CLEAR_APP_CACHE not launchable")
        }
    }

    override fun lastWorkerRunMs(): Long? =
        AppGraph.get(context).lastWorkerRunMs().takeIf { it > 0 }

    companion object {
        /** The folder the test images live in; mirrors TestItemCheck. */
        val TEST_FOLDER = "${Environment.DIRECTORY_PICTURES}/PhoneStewardTest"
    }
}
