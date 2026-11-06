package com.example.phonesteward.logic.exec

import com.example.phonesteward.net.Proposal

/**
 * N31 (A10.8): an uninstall proposal can outlive its apps (the owner removed some by hand, or an older
 * app version mis-reported them). Pure: "is this package installed?" arrives as a lambda.
 */
object RemovedApps {

    /** The ids of the `uninstall_app` items whose package is no longer installed. */
    fun removedItemIds(proposal: Proposal, isInstalled: (String) -> Boolean): Set<String> =
        proposal.items.filter { item ->
            item.kind == "uninstall_app" &&
                ProposalExecutionPlanner.packageNameOf(item.target).let { it.isNotEmpty() && !isInstalled(it) }
        }.map { it.itemId }.toSet()

    /**
     * True when the proposal holds only `uninstall_app` items and every app is gone: nothing is left for
     * the owner to decide, so the app reports it settled with one quiet execution (no dialog, no tap).
     */
    fun settleable(proposal: Proposal, isInstalled: (String) -> Boolean): Boolean =
        proposal.items.isNotEmpty() &&
            proposal.items.all { it.kind == "uninstall_app" } &&
            removedItemIds(proposal, isInstalled).size == proposal.items.size
}
