package com.example.phonesteward.logic.files

import com.example.phonesteward.net.RestoredItem

/**
 * A11.3 / Correction 1 (review finding 1): the server refuses a restore report of more than [MAX_ITEMS] items with
 * 400, and the outbox drops a report the server refused — so a restore of more than [MAX_ITEMS] items must go up as
 * several reports of at most [MAX_ITEMS] each, every one with its own requestId (one refused chunk then cannot take
 * the others down with it). Pure, so the split is tested without a server.
 */
object RestoreReports {

    /** The server's own cap (`server/src/restored-files.ts`, `RESTORED_MAX_ITEMS`). */
    const val MAX_ITEMS = 500

    fun chunks(
        proposalId: String,
        items: List<RestoredItem>,
        atMs: Long,
        newRequestId: () -> String,
    ): List<RestoredReport> {
        if (items.isEmpty()) return emptyList()
        return items.chunked(MAX_ITEMS).map { part ->
            RestoredReport(requestId = newRequestId(), proposalId = proposalId, items = part, atMs = atMs)
        }
    }
}
