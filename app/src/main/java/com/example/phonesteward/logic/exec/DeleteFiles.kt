package com.example.phonesteward.logic.exec

import com.example.phonesteward.logic.ByteFormat

/** One approved `delete_files` item that passed the identity re-stat and awaits the owner's single confirmation. */
class DeleteFilesPlan(
    val itemId: String,
    /** The target as the proposal named it: re-checked against the phone again right before the move. */
    val entry: com.example.phonesteward.net.FileEntry,
    val path: String,
    val type: String,
    val sizeBytes: Long,
)

/** "2 folders and 1 file" in the owner's words. */
internal object FilesWording {
    fun phrase(dirs: Int, files: Int): String {
        val d = if (dirs == 1) "1 folder" else "$dirs folders"
        val f = if (files == 1) "1 file" else "$files files"
        return when {
            dirs > 0 && files > 0 -> "$d and $f"
            dirs > 0 -> d
            else -> f
        }
    }
}

/**
 * The one in-app confirmation for a whole `delete_files` approval (A10.1; there is no Android dialog for
 * files). It names what moves and its size, and says plainly that nothing is gone yet. No file names: the owner
 * has just reviewed them on the card.
 */
class DeleteFilesConfirm(val title: String, val body: String) {
    companion object {
        fun of(plans: List<DeleteFilesPlan>): DeleteFilesConfirm {
            val dirs = plans.count { it.type == "dir" }
            val files = plans.size - dirs
            val size = ByteFormat.format(plans.sumOf { it.sizeBytes })
            return DeleteFilesConfirm(
                title = "Move ${FilesWording.phrase(dirs, files)} ($size) to the Steward trash?",
                body = "Nothing is deleted yet. The items wait in the Steward trash for 30 days, and you can free the " +
                    "space at once with Empty Steward trash on the Status tab.",
            )
        }
    }
}
