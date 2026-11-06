package com.example.phonesteward.logic.exec

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import com.example.phonesteward.logic.files.FilePaths
import com.example.phonesteward.logic.files.StewardTrash
import com.example.phonesteward.net.ClearCacheTarget
import com.example.phonesteward.net.DecisionCheckResponse
import com.example.phonesteward.net.ExecutionItemRequest
import com.example.phonesteward.net.Proposal
import com.example.phonesteward.net.ProtocolJson
import com.example.phonesteward.net.UninstallTarget

/**
 * Turns an approved decision into what Android will actually be asked to do
 * (protocol §4). Pure logic:
 * - nothing is planned unless GET /v1/decisions/{id} says `valid` and the
 *   item is in `approvedItemIds` (the P3 authority test);
 * - trash items are re-checked on the live row first: protected ones become
 *   `skipped_protected` and are never sent to the dialog;
 * - the whole ticked trash batch goes through ONE createTrashRequest dialog,
 *   or none when MANAGE_MEDIA is granted in auto mode (the adapter decides).
 */
class ProposalExecutionPlanner(
    private val lookup: MediaLookup,
    private val nowMillis: () -> Long,
    private val allFilesGranted: () -> Boolean = { true },
    private val files: com.example.phonesteward.logic.files.FilesPort? = null,
    /** A10.2: whether Shizuku is ready now (trim_caches, disable_app, enable_app need it). */
    private val shizukuReady: () -> Boolean = { false },
    private val isInstalled: (String) -> Boolean = { true },
    private val neverTouch: com.example.phonesteward.logic.shizuku.NeverTouch? = null,
) {

    sealed interface PlannedItem {
        val itemId: String
    }

    data class TrashPlan(override val itemId: String, val mediaId: String, val displayName: String) : PlannedItem
    data class UninstallPlan(override val itemId: String, val packageName: String) : PlannedItem
    data class ClearCachePlan(override val itemId: String, val packageName: String) : PlannedItem

    /** A6 (N6): one all-apps cache clear, carrying the biggest caches for the approval screen. */
    data class AllCachesPlan(
        override val itemId: String,
        val apps: List<com.example.phonesteward.net.CacheApp>,
        /** A10.2: a `trim_caches` item runs as a shell command through Shizuku instead of Android's confirmation. */
        val viaShizuku: Boolean = false,
    ) : PlannedItem

    /** A10.2: `disable_app` (enable = false) or `enable_app` (enable = true) through Shizuku. */
    data class AppStatePlan(override val itemId: String, val packageName: String, val enable: Boolean) : PlannedItem
    data object AdvicePlan : PlannedItem {
        override val itemId: String get() = ""
    }

    data class ExecutionPlan(
        val decisionId: String,
        val allowed: Boolean,
        val refusalReason: String?,
        /** One trash batch: one dialog for all of them, or none under MANAGE_MEDIA. */
        val trashBatch: List<TrashPlan>,
        val uninstalls: List<UninstallPlan>,
        val clearCaches: List<ClearCachePlan>,
        val allCaches: List<AllCachesPlan>,
        /** Already-decided outcomes that need no Android interaction. */
        val staticOutcomes: List<ExecutionItemRequest>,
        /** A10.1: `delete_files` items that passed the identity re-stat; the owner confirms once, then they are moved. */
        val deleteFiles: List<DeleteFilesPlan> = emptyList(),
        val appStates: List<AppStatePlan> = emptyList(),
        /** Per-item human-readable reasons for the refused/skipped outcomes (fix 01-2). */
        val details: Map<String, String> = emptyMap(),
    )

    fun plan(
        proposal: Proposal,
        decisionId: String,
        decision: DecisionCheckResponse,
    ): ExecutionPlan {
        val wanted = proposal.items.map { it.itemId }
        val approved = AuthorityGate.approvedItems(decision, proposal.proposalId, nowMillis(), wanted)
        if (decision.expiresAtMs > 0 && nowMillis() >= decision.expiresAtMs) {
            return refused(decisionId, "decision expired at ${decision.expiresAtMs}")
        }
        if (approved.isEmpty()) {
            return refused(
                decisionId,
                if (decision.valid) "no wanted item is in approvedItemIds" else "decision is not valid",
            )
        }

        val trash = mutableListOf<TrashPlan>()
        val uninstalls = mutableListOf<UninstallPlan>()
        val caches = mutableListOf<ClearCachePlan>()
        val allCaches = mutableListOf<AllCachesPlan>()
        val deleteFiles = mutableListOf<DeleteFilesPlan>()
        val appStates = mutableListOf<AppStatePlan>()
        val static = mutableListOf<ExecutionItemRequest>()
        val detail = mutableListOf<Pair<String, String>>()

        for (item in proposal.items) {
            if (item.itemId !in approved) {
                // S12: the owner unticked it (or the server excluded it). The decision already recorded it
                // as kept, so the execution report says nothing about it.
                continue
            }
            when (item.kind) {
                "trash_media" -> {
                    val live = lookup.byId(mediaIdOf(item.target))
                    when {
                        // Fail closed (fix 01-2): an unreadable or vanished live
                        // row means NO action, never a trash attempt.
                        live == null -> {
                            static += ExecutionItemRequest(item.itemId, "failed", null, null)
                            detail += item.itemId to "live row missing"
                        }
                        // Protection re-check on the live row (protocol §3.3).
                        live.isProtected -> {
                            static += ExecutionItemRequest(item.itemId, "skipped_protected", null, null)
                            detail += item.itemId to "live row is protected (favourite or Keep)"
                        }
                        // A recycled MediaStore id must not trash a different item.
                        !liveMatches(item.target, live) -> {
                            static += ExecutionItemRequest(item.itemId, "failed", null, null)
                            detail += item.itemId to "live row no longer matches the proposed item"
                        }
                        else -> trash += TrashPlan(item.itemId, mediaIdOf(item.target), displayNameOf(item.target))
                    }
                }
                "uninstall_app" -> uninstalls += UninstallPlan(item.itemId, packageNameOf(item.target))
                "clear_app_cache" -> caches += ClearCachePlan(item.itemId, packageNameOf(item.target))
                "clear_all_caches" -> {
                    // A6: without all-files access the outcome is failed with
                    // a short reason — nothing launches, the report says why.
                    if (!allFilesGranted()) {
                        static += ExecutionItemRequest(item.itemId, "failed", null, null)
                        detail += item.itemId to "all-files access is needed to clear every app's cache (grant it on the Status tab)"
                    } else {
                        val target = try {
                            ProtocolJson.decodeFromJsonElement(
                                com.example.phonesteward.net.ClearAllCachesTarget.serializer(),
                                item.target,
                            )
                        } catch (_: Exception) {
                            null
                        }
                        if (target == null) {
                            static += ExecutionItemRequest(item.itemId, "failed", null, null)
                            detail += item.itemId to "clear_all_caches target unreadable"
                        } else {
                            allCaches += AllCachesPlan(item.itemId, target.apps)
                        }
                    }
                }
                "trim_caches" -> {
                    // A10.2: one `pm trim-caches 999G` through Shizuku: every app's cache, no Android dialog.
                    if (!shizukuReady()) {
                        static += ExecutionItemRequest(item.itemId, "failed", null, null)
                        detail += item.itemId to NOT_SET_UP
                    } else {
                        val target = try {
                            ProtocolJson.decodeFromJsonElement(
                                com.example.phonesteward.net.ClearAllCachesTarget.serializer(),
                                item.target,
                            )
                        } catch (_: Exception) {
                            null
                        }
                        if (target == null) {
                            static += ExecutionItemRequest(item.itemId, "failed", null, null)
                            detail += item.itemId to "trim_caches target unreadable"
                        } else {
                            allCaches += AllCachesPlan(item.itemId, target.apps, viaShizuku = true)
                        }
                    }
                }
                "disable_app", "enable_app" -> {
                    val enable = item.kind == "enable_app"
                    val pkg = packageNameOf(item.target)
                    val refusal = when {
                        !com.example.phonesteward.logic.shizuku.PackageNames.valid(pkg) -> "that is not a valid app name"
                        !shizukuReady() -> NOT_SET_UP
                        !isInstalled(pkg) -> "That app is not installed any more, so nothing was changed."
                        // Enabling can never break the phone; disabling the essentials can.
                        !enable && neverTouch?.isProtected(pkg) != false -> "That app is protected, so the steward does not disable it."
                        else -> null
                    }
                    if (refusal != null) {
                        static += ExecutionItemRequest(item.itemId, "failed", null, null)
                        detail += item.itemId to refusal
                    } else {
                        appStates += AppStatePlan(item.itemId, pkg, enable)
                    }
                }
                "delete_files" -> {
                    val reason = deleteFilesRefusal(item)
                    if (reason.first != null) {
                        static += ExecutionItemRequest(item.itemId, "failed", null, null)
                        detail += item.itemId to reason.first!!
                    } else {
                        deleteFiles += reason.second!!
                    }
                }
                "advice" -> static += ExecutionItemRequest(item.itemId, "done", null, null)
                else -> static += ExecutionItemRequest(item.itemId, "failed", null, null)
            }
        }

        return ExecutionPlan(
            decisionId = decisionId,
            allowed = true,
            refusalReason = null,
            trashBatch = trash.toList(),
            uninstalls = uninstalls.toList(),
            clearCaches = caches.toList(),
            allCaches = allCaches.toList(),
            staticOutcomes = static.toList(),
            deleteFiles = deleteFiles.toList(),
            appStates = appStates.toList(),
            details = detail.toMap(),
        )
    }

    /**
     * A10.1: a `delete_files` item is planned only when its path is allowed, the phone still has exactly what the
     * proposal named (the A6 identity rule) and the app may read the files. Returns (reason it is refused, null)
     * or (null, the plan).
     */
    private fun deleteFilesRefusal(item: com.example.phonesteward.net.ProposalItem): Pair<String?, DeleteFilesPlan?> {
        val entry = try {
            ProtocolJson.decodeFromJsonElement(com.example.phonesteward.net.FileEntry.serializer(), item.target)
        } catch (_: Exception) {
            return "delete_files target unreadable" to null
        }
        val port = files ?: return "file access is not available in this app" to null
        FilePaths.deleteRefusal(entry.path)?.let { return "refused: $it" to null }
        if (!allFilesGranted()) return "all-files access is needed to move files (grant it on the Status tab)" to null
        val live = port.stat(entry.path) ?: return "the path is no longer there" to null
        if (!StewardTrash.matches(entry, live)) return "the item changed since the review" to null
        return null to DeleteFilesPlan(item.itemId, entry, FilePaths.normalise(entry.path)!!, live.type, live.sizeBytes)
    }

    /** The live row must still be exactly the item the proposal named. */
    private fun liveMatches(target: JsonObject, live: LiveMediaRow): Boolean =
        com.example.phonesteward.logic.exec.liveMatches(
            (target["relativePath"] as? JsonPrimitive)?.content,
            (target["displayName"] as? JsonPrimitive)?.content,
            (target["sizeBytes"] as? JsonPrimitive)?.content?.toLongOrNull() ?: Long.MIN_VALUE,
            live,
        )

    private fun refused(decisionId: String, reason: String): ExecutionPlan =
        ExecutionPlan(
            decisionId = decisionId,
            allowed = false,
            refusalReason = reason,
            trashBatch = emptyList(),
            uninstalls = emptyList(),
            clearCaches = emptyList(),
            allCaches = emptyList(),
            staticOutcomes = emptyList(),
        )

    /**
     * Maps Android's results to the execution report. The trash dialog has
     * ONE result code for the whole batch: OK trashes every item in it,
     * cancel leaves them (outcome `cancelled`). Uninstalls report what is on
     * the phone afterwards (0.2.4). Cache clears report what
     * the before/after readings showed (fix 01-6).
     */
    fun outcomes(
        plan: ExecutionPlan,
        trashResultCode: Int?,
        uninstallVerdicts: Map<String, UninstallOutcome.Verdict>,
        cacheResults: Map<String, CacheClearOutcome.Verdict>,
        allCachesVerdict: AllCachesOutcome.Verdict? = null,
        deleteResults: Map<String, com.example.phonesteward.logic.files.MoveResult> = emptyMap(),
        deleteConfirmed: Boolean = true,
        appStateResults: Map<String, Boolean> = emptyMap(),
    ): List<ExecutionItemRequest> {
        require(plan.allowed) { "outcomes() on a refused plan" }
        val out = mutableListOf<ExecutionItemRequest>()
        out += plan.staticOutcomes
        for (item in plan.trashBatch) {
            val done = trashResultCode == ActivityResults.OK
            out += ExecutionItemRequest(
                item.itemId,
                if (done) "done" else "cancelled",
                trashResultCode,
                null,
            )
        }
        for (item in plan.uninstalls) {
            // 0.2.4: the verdict comes from the phone (UninstallFlow), never from the dialog's code.
            val verdict = uninstallVerdicts[item.itemId] ?: UninstallOutcome.Verdict("cancelled", null, null)
            out += ExecutionItemRequest(item.itemId, verdict.outcome, verdict.resultCode, verdict.bytesFreed)
        }
        for (item in plan.clearCaches) {
            val verdict = cacheResults[item.itemId] ?: CacheClearOutcome.Verdict("cancelled", null)
            out += ExecutionItemRequest(item.itemId, verdict.outcome, null, verdict.bytesFreed)
        }
        // A10.1: moved to the Steward trash = done with nothing freed yet (trashedBytes carries the size).
        for (item in plan.deleteFiles) {
            out += when {
                !deleteConfirmed -> ExecutionItemRequest(item.itemId, "cancelled", null, null)
                else -> when (val r = deleteResults[item.itemId]) {
                    is com.example.phonesteward.logic.files.MoveResult.Moved ->
                        ExecutionItemRequest(item.itemId, "done", null, 0L, trashedBytes = r.trashedBytes)
                    else -> ExecutionItemRequest(item.itemId, "failed", null, null)
                }
            }
        }
        // A10.2: disable_app / enable_app report what the phone shows afterwards.
        for (item in plan.appStates) {
            out += ExecutionItemRequest(item.itemId, if (appStateResults[item.itemId] == true) "done" else "failed", null, null)
        }
        // A6: the all-apps clear reports exactly ONE item.
        for (item in plan.allCaches) {
            val verdict = allCachesVerdict ?: AllCachesOutcome.Verdict("failed", null, null)
            out += ExecutionItemRequest(item.itemId, verdict.outcome, verdict.resultCode, verdict.bytesFreed)
        }
        return out
    }

    companion object {
        /** The plain reason shown to the owner when an approved item needs Shizuku and it is not ready. */
        const val NOT_SET_UP = "Shizuku is not set up, so nothing was changed. Open the Status tab to set it up, then approve again."

        fun mediaIdOf(target: JsonObject): String =
            (target["mediaId"] as? JsonPrimitive)?.content ?: ""

        fun displayNameOf(target: JsonObject): String =
            (target["displayName"] as? JsonPrimitive)?.content ?: ""

        fun packageNameOf(target: JsonObject): String =
            (target["packageName"] as? JsonPrimitive)?.content ?: ""
    }
}

/** Android activity result codes without an Android import. */
object ActivityResults {
    const val OK = -1
    const val CANCELLED = 0
    /** RESULT_FIRST_USER: PackageInstaller's answer when the removal failed. */
    const val FIRST_USER = 1
}
