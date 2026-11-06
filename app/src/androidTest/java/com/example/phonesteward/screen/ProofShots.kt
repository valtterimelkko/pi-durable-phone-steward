package com.example.phonesteward.screen

import android.app.UiAutomation
import android.content.ContentValues
import android.graphics.Bitmap
import android.provider.MediaStore
import androidx.test.platform.app.InstrumentationRegistry

/**
 * Screenshots for the phone-use proof. The shared ScreenShots helper asks for the default UiAutomation, which
 * would switch the steward's accessibility service off (Android suppresses other services while a plain
 * UiAutomation is connected), so this one always uses the "do not suppress" connection.
 */
object ProofShots {
    const val RELATIVE_DIR = "Pictures/steward-screens"

    fun automation(): UiAutomation =
        InstrumentationRegistry.getInstrumentation().getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)

    fun save(name: String) {
        val bitmap = automation().takeScreenshot() ?: return
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val safe = name.replace(Regex("[^A-Za-z0-9._-]"), "_")
        runCatching {
            context.contentResolver.delete(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                "${MediaStore.MediaColumns.DISPLAY_NAME} = ? AND ${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?",
                arrayOf("$safe.png", "$RELATIVE_DIR%"),
            )
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, "$safe.png")
                put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
                put(MediaStore.MediaColumns.RELATIVE_PATH, RELATIVE_DIR)
            }
            val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: error("insert returned null")
            context.contentResolver.openOutputStream(uri)!!.use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }.onFailure { android.util.Log.w("StewardScreens", "ProofShots save failed for $name: $it") }
    }
}
