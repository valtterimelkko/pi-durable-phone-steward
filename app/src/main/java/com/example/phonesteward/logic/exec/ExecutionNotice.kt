package com.example.phonesteward.logic.exec

import com.example.phonesteward.logic.ActionResult
import com.example.phonesteward.net.ExecutionItemRequest
import com.example.phonesteward.net.Proposal

/**
 * The line the owner reads on Proposals after an approval (N16), in plain words and accurate per kind:
 * - uninstalls: how many apps really went ("Uninstalled 7 of 15 apps.");
 * - trash files: when every ticked file was refused before any dialog (the live row changed or is now
 *   protected), say that nothing was deleted and why, instead of the silence 0.2.3 gave;
 * - everything else keeps the established cache/retry wording ([AllCachesNotice]).
 * Pure; the planner has already decided what ran.
 */
object ExecutionNotice {

    fun result(
        proposal: Proposal,
        plan: ProposalExecutionPlanner.ExecutionPlan,
        items: List<ExecutionItemRequest>,
        allCachesVerdict: AllCachesOutcome.Verdict?,
    ): ActionResult {
        val outcomeOf = items.associateBy { it.itemId }
        val lines = mutableListOf<ActionResult>()

        trashLine(proposal, plan, outcomeOf)?.let { lines += it }
        uninstallLine(plan, outcomeOf)?.let { lines += it }
        deleteFilesLine(proposal, plan, outcomeOf)?.let { lines += it }
        appStateLine(proposal, plan, outcomeOf)?.let { lines += it }

        val doneCount = items.count { it.outcome == "done" }
        val allCachesDeniedReason = proposal.items
            .firstOrNull { it.kind == "clear_all_caches" || it.kind == "trim_caches" }
            ?.let { plan.details[it.itemId] }
        val cacheRelevant = plan.allCaches.isNotEmpty() || allCachesDeniedReason != null
        if (cacheRelevant || lines.isEmpty()) {
            // /01-3: a denial's reason is named; an unreadable reading reads "unknown".
            lines += AllCachesNotice.result(allCachesVerdict, allCachesDeniedReason, doneCount)
        }

        if (lines.size == 1) return lines.single()
        val kind = when {
            lines.any { it.kind == ActionResult.Kind.SUCCESS } -> ActionResult.Kind.SUCCESS
            lines.any { it.kind == ActionResult.Kind.ERROR } -> ActionResult.Kind.ERROR
            else -> ActionResult.Kind.INFO
        }
        return ActionResult(lines.joinToString(" ") { it.text }, kind)
    }

    /**
     * A10.1: "Moved 3 folders (2.6 GB) to the Steward trash. Empty it on Status to free the space now." Nothing
     * is freed yet, so the line says where the space is. Declining the confirmation leaves no line of its own
     * (the usual "Nothing was done" follows).
     */
    private fun deleteFilesLine(
        proposal: Proposal,
        plan: ProposalExecutionPlanner.ExecutionPlan,
        outcomeOf: Map<String, ExecutionItemRequest>,
    ): ActionResult? {
        val deleteIds = proposal.items.filter { it.kind == "delete_files" }.map { it.itemId }.toSet()
        val refusedAtPlan = plan.staticOutcomes.count { it.itemId in deleteIds && it.outcome == "failed" }
        val planned = plan.deleteFiles
        val ticked = planned.size + refusedAtPlan
        if (ticked == 0) return null
        if (planned.isEmpty()) {
            val text = if (ticked == 1) {
                "This item changed since the review, so nothing was moved. The steward will propose it again."
            } else {
                "These items changed since the review, so nothing was moved. The steward will propose them again."
            }
            return ActionResult(text, ActionResult.Kind.ERROR)
        }
        val moved = planned.filter { outcomeOf[it.itemId]?.outcome == "done" }
        if (planned.all { outcomeOf[it.itemId]?.outcome == "cancelled" }) return null // the owner declined
        if (moved.isEmpty()) {
            return ActionResult(
                if (ticked == 1) "The item could not be moved to the Steward trash. The steward will propose it again."
                else "None of the items could be moved to the Steward trash. The steward will propose them again.",
                ActionResult.Kind.ERROR,
            )
        }
        val size = com.example.phonesteward.logic.ByteFormat.format(moved.sumOf { outcomeOf[it.itemId]?.trashedBytes ?: 0L })
        val tail = " Empty it on Status to free the space now."
        if (moved.size == ticked) {
            val phrase = FilesWording.phrase(moved.count { it.type == "dir" }, moved.count { it.type != "dir" })
            return ActionResult("Moved $phrase ($size) to the Steward trash.$tail", ActionResult.Kind.SUCCESS)
        }
        val failedMove = planned.size - moved.size
        val extras = buildString {
            if (refusedAtPlan > 0) append(" $refusedAtPlan changed since the review and ${if (refusedAtPlan == 1) "was" else "were"} left alone.")
            if (failedMove > 0) append(" $failedMove could not be moved and ${if (failedMove == 1) "was" else "were"} left alone.")
        }
        return ActionResult("Moved ${moved.size} of $ticked items ($size) to the Steward trash.$extras$tail", ActionResult.Kind.INFO)
    }

    /** A10.2: "Disabled both apps." and its failure forms. A refusal (no Shizuku, protected) is shown in its own words. */
    private fun appStateLine(
        proposal: Proposal,
        plan: ProposalExecutionPlanner.ExecutionPlan,
        outcomeOf: Map<String, ExecutionItemRequest>,
    ): ActionResult? {
        val ids = proposal.items.filter { it.kind == "disable_app" || it.kind == "enable_app" }.map { it.itemId }.toSet()
        val refused = plan.staticOutcomes.filter { it.itemId in ids && it.outcome == "failed" }
        val planned = plan.appStates
        val ticked = planned.size + refused.size
        if (ticked == 0) return null
        if (planned.isEmpty()) {
            val reasons = refused.mapNotNull { plan.details[it.itemId] }.distinct()
            val text = if (reasons.size == 1) reasons.single() else "These apps could not be changed, so nothing was changed."
            return ActionResult(text, ActionResult.Kind.ERROR)
        }
        val verb = if (planned.all { it.enable }) "Enabled" else if (planned.none { it.enable }) "Disabled" else "Changed"
        val done = planned.count { outcomeOf[it.itemId]?.outcome == "done" }
        val total = planned.size
        return when {
            done == total && ticked == total && total == 1 -> ActionResult("$verb the app.", ActionResult.Kind.SUCCESS)
            done == total && ticked == total && total == 2 -> ActionResult("$verb both apps.", ActionResult.Kind.SUCCESS)
            done == total && ticked == total -> ActionResult("$verb all $total apps.", ActionResult.Kind.SUCCESS)
            done == 0 && ticked == 1 -> ActionResult("The app was not changed \u2014 is Shizuku still running? Approve again to retry.", ActionResult.Kind.ERROR)
            done == 0 -> ActionResult("No app was changed \u2014 is Shizuku still running? Approve again to retry.", ActionResult.Kind.ERROR)
            ticked == 2 -> ActionResult("$verb $done of $ticked apps. The other could not be changed \u2014 is Shizuku still running?", ActionResult.Kind.INFO)
            else -> ActionResult("$verb $done of $ticked apps. The rest could not be changed \u2014 is Shizuku still running?", ActionResult.Kind.INFO)
        }
    }

    private fun uninstallLine(
        plan: ProposalExecutionPlanner.ExecutionPlan,
        outcomeOf: Map<String, ExecutionItemRequest>,
    ): ActionResult? {
        val total = plan.uninstalls.size
        if (total == 0) return null
        val gone = plan.uninstalls.count { outcomeOf[it.itemId]?.outcome == "done" }
        return when {
            gone == total && total == 1 -> ActionResult("Uninstalled the app.", ActionResult.Kind.SUCCESS)
            gone == total -> ActionResult("Uninstalled all $total apps.", ActionResult.Kind.SUCCESS)
            gone == 0 && total == 1 ->
                ActionResult("The app was not uninstalled — approve again to retry.", ActionResult.Kind.INFO)
            gone == 0 ->
                ActionResult("No apps were uninstalled — approve again to retry.", ActionResult.Kind.INFO)
            else -> ActionResult("Uninstalled $gone of $total apps.", ActionResult.Kind.INFO)
        }
    }

    private fun trashLine(
        proposal: Proposal,
        plan: ProposalExecutionPlanner.ExecutionPlan,
        outcomeOf: Map<String, ExecutionItemRequest>,
    ): ActionResult? {
        val trashIds = proposal.items.filter { it.kind == "trash_media" }.map { it.itemId }.toSet()
        val reachedDialog = plan.trashBatch.map { it.itemId }.toSet()
        // Ticked trash items refused before any dialog (an unticked item is not reported at all, S12).
        val refused = plan.staticOutcomes.filter {
            it.itemId in trashIds && (it.outcome == "failed" || it.outcome == "skipped_protected")
        }
        val ticked = reachedDialog.size + refused.size
        if (ticked == 0) return null
        val one = ticked == 1
        if (reachedDialog.isEmpty()) {
            val allProtected = refused.all { it.outcome == "skipped_protected" }
            val text = if (allProtected) {
                if (one) {
                    "This file is now protected (a favourite or marked Keep), so nothing was deleted."
                } else {
                    "These files are now protected (a favourite or marked Keep), so nothing was deleted."
                }
            } else if (one) {
                "This file changed since the review, so nothing was deleted. The steward will propose it again."
            } else {
                "These files changed since the review, so nothing was deleted. The steward will propose them again."
            }
            return ActionResult(text, ActionResult.Kind.ERROR)
        }
        val moved = reachedDialog.count { outcomeOf[it]?.outcome == "done" }
        if (moved == 0) return null // the owner cancelled Android's dialog: the usual retry line
        val noun = if (ticked == 1) "file" else "files"
        val base = "Moved $moved of $ticked $noun to the trash."
        return if (refused.isEmpty()) {
            ActionResult(base, if (moved == ticked) ActionResult.Kind.SUCCESS else ActionResult.Kind.INFO)
        } else {
            ActionResult("$base ${refused.size} changed since the review and ${if (refused.size == 1) "was" else "were"} left alone.", ActionResult.Kind.INFO)
        }
    }
}
