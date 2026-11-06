package com.example.phonesteward.logic

import com.example.phonesteward.net.ApiException
import com.example.phonesteward.net.PhotoWindow
import com.example.phonesteward.net.RulesPutRequest
import com.example.phonesteward.net.RulesResponse

/**
 * Rules form validation and the optimistic PUT flow (protocol §5).
 * Only the app can change rules; the model can only suggest in chat.
 */
object RulesForm {

    /** Validation failures, worded for the screen. */
    data class Problems(
        val reminderNotBeforeCutoff: Boolean = false,
        val foldersEmpty: Boolean = false,
        val keepMarkerWrong: Boolean = false,
    ) {
        val none: Boolean get() = !(reminderNotBeforeCutoff || foldersEmpty || keepMarkerWrong)

        fun messages(): List<String> = buildList {
            if (reminderNotBeforeCutoff) add("The reminder must come before the cut-off.")
            if (foldersEmpty) add("Choose at least one folder for the rule to cover.")
            if (keepMarkerWrong) add("The Keep marker is fixed at Keep in this version (protocol amendment A4).")
        }
    }

    /** The marker is fixed at Keep in the MVP (A4); the server 400s anything else. */
    const val FIXED_KEEP_MARKER = "Keep"

    fun validate(window: PhotoWindow): Problems = Problems(
        reminderNotBeforeCutoff = window.reminderDays >= window.cutoffDays,
        foldersEmpty = window.folders.isEmpty(),
        keepMarkerWrong = window.keepMarker != FIXED_KEEP_MARKER,
    )

    /** Auto mode may switch on only after at least one one-tap batch ran (§5). */
    fun autoModeAllowed(batchesRunOneTap: Int): Boolean = batchesRunOneTap > 0

    /** Builds the PUT body; the caller supplies the requestId. */
    fun put(current: RulesResponse, window: PhotoWindow, requestId: String): RulesPutRequest =
        RulesPutRequest(requestId = requestId, ruleVersion = current.ruleVersion, photoWindow = window)

    /**
     * What to do after a failed PUT. A 409 RULE_VERSION_CONFLICT means the
     * server moved on: re-fetch and let the owner re-apply.
     */
    sealed interface PutOutcome {
        data class Saved(val updated: RulesResponse) : PutOutcome
        data class VersionConflict(val current: RulesResponse) : PutOutcome
        data class Rejected(val message: String) : PutOutcome
    }

    fun classifyPutError(e: ApiException, refetched: RulesResponse?): PutOutcome = when {
        e.code == "RULE_VERSION_CONFLICT" -> PutOutcome.VersionConflict(
            refetched ?: RulesResponse(0, PhotoWindow()),
        )
        e.code == "AUTO_MODE_NOT_YET" -> PutOutcome.Rejected(
            "Auto mode unlocks after the first one-tap batch has run.",
        )
        e.code == "BAD_REQUEST" -> PutOutcome.Rejected(e.message ?: "The server rejected these settings.")
        else -> PutOutcome.Rejected(e.message ?: "Could not save: ${e.code}")
    }
}
