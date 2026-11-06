package com.example.phonesteward.ui

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** A11.3: the Steward trash screen's words. Pure. */
object TrashScreenText {

    /** The batch's dated folder as the owner reads it, e.g. "8 Oct 2026". */
    fun date(dateMs: Long, zone: ZoneId): String =
        Instant.ofEpochMilli(dateMs).atZone(zone).format(DateTimeFormatter.ofPattern("d MMM yyyy", Locale.UK))

    /** The proposal's title when the app still has it, else its id. */
    fun batchTitle(proposalId: String, title: String?): String = title?.takeIf { it.isNotBlank() } ?: proposalId

    fun itemsLabel(count: Int): String = if (count == 1) "1 item" else "$count items"

    const val EMPTY_TITLE = "The Steward trash is empty"

    const val EMPTY_BODY =
        "Files the steward moves for you wait here for 30 days, so you can always put them back."
}
