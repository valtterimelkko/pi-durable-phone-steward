package com.example.phonesteward.testlab

import android.content.ContentValues
import android.graphics.Bitmap
import android.os.Environment
import android.provider.MediaStore
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File

/**
 * Saves a full-device screenshot (system bars and keyboard included) for the
 * test that called it, as `/sdcard/Pictures/steward-screens/<name>.png`.
 *
 * Test Lab pulls that folder with `--directories-to-pull`. On API 29+ an app
 * may not create plain files under `/sdcard/Pictures`, so the PNG is inserted
 * through MediaStore with `RELATIVE_PATH = Pictures/steward-screens`, which
 * needs no permission for a file the app creates itself. If MediaStore
 * refuses, the PNG goes to the app's own external files directory instead
 * (`/sdcard/Android/data/<package>/files/Pictures/steward-screens`), and the
 * failure is logged to logcat.
 */
object ScreenShots {

    const val RELATIVE_DIR = "Pictures/steward-screens"

    fun save(name: String) {
        val bitmap = takeScreenshot() ?: return
        val safe = name.replace(Regex("[^A-Za-z0-9._-]"), "_")
        if (!saveToMediaStore(safe, bitmap)) saveToAppFiles(safe, bitmap)
    }

    private fun takeScreenshot(): Bitmap? =
        // The "do not suppress" connection (see ProofShots): a plain UiAutomation would switch the
        // steward's accessibility service off mid-test in the phone-use flows.
        InstrumentationRegistry.getInstrumentation()
            .getUiAutomation(android.app.UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
            .takeScreenshot()

    private fun saveToMediaStore(name: String, bitmap: Bitmap): Boolean = try {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val resolver = context.contentResolver
        // A rerun of the same test replaces its earlier shot instead of writing "name (1).png".
        resolver.delete(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            "${MediaStore.MediaColumns.DISPLAY_NAME} = ? AND ${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?",
            arrayOf("$name.png", "$RELATIVE_DIR%"),
        )
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "$name.png")
            put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
            put(MediaStore.MediaColumns.RELATIVE_PATH, RELATIVE_DIR)
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: error("MediaStore insert returned null")
        resolver.openOutputStream(uri)!!.use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        true
    } catch (e: Exception) {
        android.util.Log.w("StewardScreens", "MediaStore save failed for $name: $e")
        false
    }

    private fun saveToAppFiles(name: String, bitmap: Bitmap) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = File(context.getExternalFilesDir(Environment.DIRECTORY_PICTURES), "steward-screens")
        dir.mkdirs()
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
