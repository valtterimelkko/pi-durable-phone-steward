package com.example.phonesteward.net

import kotlinx.serialization.Serializable

/**
 * A11.3: the owner asked for items to go back from the Steward trash.
 * `POST /v1/files/restored` body; `items` carry the original path and the bytes that went back.
 */
@Serializable
data class RestoredItem(val path: String, val bytes: Long)

@Serializable
data class RestoredRequest(val requestId: String, val proposalId: String, val items: List<RestoredItem>)

/** A11.3: the server's answer, one decisions-ledger entry per item that was new. */
@Serializable
data class RestoredResponse(val recorded: Int = 0)
