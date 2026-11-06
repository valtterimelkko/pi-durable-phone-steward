package com.example.phonesteward.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import com.example.phonesteward.android.AppGraph

/**
 * A15.7: what a copy does — writing the clipboard and showing the short "Copied" confirmation,
 * mirroring Pi Web UI's `copyToClipboard` (its toast, minus the web fallbacks a native app does
 * not need). A port, so an end-to-end test can see the text the app asked to copy: since API 29
 * only the focused app may *read* the clipboard, and the test process is not the focused app.
 */
interface ClipboardPort {

    /** Writes [text] to the clipboard and shows the confirmation; false when it could not. */
    fun copy(label: String, text: String): Boolean
}

/** The phone's clipboard and toast. */
class AndroidClipboard(private val context: Context) : ClipboardPort {

    override fun copy(label: String, text: String): Boolean {
        if (text.isEmpty()) return false
        val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return false
        return try {
            manager.setPrimaryClip(ClipData.newPlainText(label, text))
            Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
            true
        } catch (e: Exception) {
            com.example.phonesteward.logic.obs.Diagnostics.error("clipboard.copy", e)
            false
        }
    }
}

object ClipboardCopy {

    /** Writes [text] to the clipboard and shows the "Copied" toast; false when it could not. */
    fun copy(context: Context, label: String, text: String): Boolean =
        AppGraph.get(context).clipboard.copy(label, text)
}
