package com.example.phonesteward.logic.files

import com.example.phonesteward.logic.ByteFormat
import com.example.phonesteward.logic.exec.FilesWording

/**
 * A11.3: the restore result line, in the owner's words. Pure, so the view model and the tests share it.
 * Examples: "Put back 2 folders (2.6 GB)." / "1 item stays in the trash: something is at that place now."
 */
object RestoreText {

    fun result(result: RestoreResult): String {
        val put = if (result.restored.isEmpty()) {
            null
        } else {
            val dirs = result.restored.count { it.type == "dir" }
            val files = result.restored.size - dirs
            "Put back ${FilesWording.phrase(dirs, files)} (${ByteFormat.format(result.bytesRestored)})."
        }
        val stays = if (result.blocked.isEmpty()) {
            null
        } else {
            val count = result.blocked.size
            val noun = if (count == 1) "1 item stays" else "$count items stay"
            val reasons = result.blocked.mapNotNull { it.reason }.distinct().joinToString("; ")
            "$noun in the trash: $reasons."
        }
        return listOfNotNull(put, stays).joinToString(" ").ifEmpty { "Nothing was in the trash to put back." }
    }
}
