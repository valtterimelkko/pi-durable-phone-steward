package com.example.phonesteward.logic.exec

import com.example.phonesteward.net.DecisionCheckResponse
import com.example.phonesteward.net.RulesResponse

/**
 * Authority checks . Pure: executors refuse
 * unless the server has authorised exactly what they are about to do.
 */
object AuthorityGate {

    /**
     * The subset of [wanted] item ids the decision approves. Empty unless:
     * - the decision answers `valid: true`,
     * - it belongs to the proposal being executed,
     * - it has not expired, and
     * - the item is in `approvedItemIds`.
     */
    fun approvedItems(
        decision: DecisionCheckResponse,
        proposalId: String,
        nowMs: Long,
        wanted: Collection<String>,
    ): Set<String> {
        if (!decision.valid) return emptySet()
        if (decision.proposalId != proposalId) return emptySet()
        if (decision.expiresAtMs > 0 && nowMs >= decision.expiresAtMs) return emptySet()
        val approved = decision.approvedItemIds.toSet()
        return wanted.filterTo(mutableSetOf()) { it in approved }
    }

    /**
     * A rule batch may run only when the photo rule is enabled, auto mode is
     * on, and the batch was built for exactly this ruleVersion (protocol §3.3).
     */
    fun ruleAuthorises(rules: RulesResponse, batchRuleVersion: Int): Boolean =
        rules.photoWindow.enabled &&
            rules.photoWindow.autoMode &&
            rules.ruleVersion == batchRuleVersion
}

/**
 * The row behind a mediaId must still be exactly the item that was indexed
 * or proposed (fix 02-2): a recycled MediaStore id must never be trashed.
 * Used by the rule batch directly and by the proposal planner (whose target
 * arrives as raw JSON with the same three fields).
 */
fun liveMatches(
    relativePath: String?,
    displayName: String?,
    sizeBytes: Long,
    live: LiveMediaRow,
): Boolean =
    relativePath == live.relativePath &&
        displayName == live.displayName &&
        sizeBytes == live.sizeBytes

/**
 * One live MediaStore row, re-read just before acting (protocol §3.3).
 * Carries the identity fields too, so the caller can confirm the row is
 * still the item the proposal named (fix 01-2).
 */
data class LiveMediaRow(
    val isFavourite: Boolean,
    val inKeep: Boolean,
    val sizeBytes: Long = 0,
    val relativePath: String? = null,
    val displayName: String? = null,
) {
    val isProtected: Boolean get() = ProtectionCheck.isProtected(this)
}

/**
 * Protection re-check (protocol §3.3): the server never puts a protected
 * item into a batch, but the app re-checks `inKeep` and `isFavourite` on the
 * live row before it executes anything.
 */
object ProtectionCheck {
    fun isProtected(row: LiveMediaRow): Boolean = row.inKeep || row.isFavourite
}

/**
 * `inKeep` per protocol §3.3: true when any path segment of `relativePath`
 * equals `Keep`, ignoring case (a folder or album named Keep). The protocol
 * fixes the marker as "Keep"; the rule's keepMarker field currently carries
 * the same value.
 */
object InKeep {
    const val KEEP = "Keep"

    fun forPath(relativePath: String?, keepMarker: String = KEEP): Boolean {
        if (relativePath.isNullOrBlank()) return false
        return relativePath.split('/')
            .any { it.trim().equals(keepMarker, ignoreCase = true) }
    }
}
