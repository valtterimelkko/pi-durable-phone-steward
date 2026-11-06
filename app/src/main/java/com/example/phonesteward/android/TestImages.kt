package com.example.phonesteward.android

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Environment
import android.provider.MediaStore
import android.net.Uri
import com.example.phonesteward.logic.OwnItemsGuard
import com.example.phonesteward.logic.TestItemCheck

/**
 * Creates the app's own test images (a small generated PNG in
 * Pictures/PhoneStewardTest) and keeps their URIs. The [guard] is the
 * structural rule: every destructive action passes through
 * `guard.requireOwned(uri)`, so nothing the app did not create can be
 * reached — that is unit-tested in OwnItemsGuardTest.
 */
class TestImages(private val context: Context) {

    val guard = OwnItemsGuard()

    private val prefs =
        context.getSharedPreferences("phone_steward_test_images", Context.MODE_PRIVATE)

    /** Loads the recorded URIs from the previous runs into the guard. */
    fun restore() {
        prefs.getStringSet(PREF_URIS, emptySet()).orEmpty().sorted().forEach { guard.record(it) }
    }

    private fun persist() {
        prefs.edit().putStringSet(PREF_URIS, guard.ownedUris.toSet()).apply()
    }

    /**
     * Writes one small generated PNG through MediaStore into
     * Pictures/PhoneStewardTest and records its URI. Returns the URI string
     * or null when MediaStore refused.
     */
    fun createTestImage(): String? {
        val resolver = context.contentResolver
        val name = "phone-steward-test-${System.currentTimeMillis()}.png"
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/PhoneStewardTest")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: return null
        try {
            resolver.openOutputStream(uri)?.use { out ->
                val bmp = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
                bmp.eraseColor(0xFF1B5E20.toInt())
                val canvas = Canvas(bmp)
                val paint = Paint().apply {
                    color = 0xFFFFFFFF.toInt()
                    textSize = 12f
                    isAntiAlias = true
                }
                canvas.drawText("PS test", 8f, 36f, paint)
                bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
                bmp.recycle()
            } ?: return null
        } catch (_: Exception) {
            return null
        }
        val done = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
        resolver.update(uri, done, null, null)
        guard.record(uri.toString())
        persist()
        return uri.toString()
    }

    /** Removes a trashed item from the owned list once the dialog finished. */
    fun onTrashed(uriString: String) {
        guard.forget(uriString)
        persist()
    }

    /**
     * re-reads the MediaStore row behind [uriString]
     * and accepts it only if every marker matches (owner package, test folder,
     * test name prefix). On any mismatch or a missing row the URI is forgotten
     * and false is returned, so the trash path can refuse without acting.
     */
    fun revalidate(uriString: String): Boolean {
        val projection = arrayOf(
            MediaStore.MediaColumns.OWNER_PACKAGE_NAME,
            MediaStore.MediaColumns.RELATIVE_PATH,
            MediaStore.MediaColumns.DISPLAY_NAME,
        )
        var accepted = false
        try {
            context.contentResolver.query(Uri.parse(uriString), projection, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    accepted = TestItemCheck.isOwnTestItem(
                        ownerPackageName = cursor.getString(0),
                        relativePath = cursor.getString(1),
                        displayName = cursor.getString(2),
                        appPackageName = context.packageName,
                    )
                }
            }
        } catch (e: Exception) {
            com.example.phonesteward.logic.obs.Diagnostics.error("testimages.restore", e)
            accepted = false
        }
        if (!accepted) {
            guard.forget(uriString)
            persist()
        }
        return accepted
    }

    companion object {
        private const val PREF_URIS = "uris"

        /** Folder the test images live in, for the owner script. */
        val FOLDER: String = "${Environment.DIRECTORY_PICTURES}/PhoneStewardTest"

        fun parse(uriString: String): Uri = Uri.parse(uriString)
    }
}
