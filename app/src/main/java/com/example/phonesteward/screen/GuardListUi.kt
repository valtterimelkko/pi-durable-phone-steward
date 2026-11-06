package com.example.phonesteward.screen

import java.util.Locale

/**
 * Correction 1 (P14): how the Rules app lists read and order themselves for the owner.
 * Pure logic so the ordering and the pick-diff stay unit-tested.
 */
object GuardListUi {

    /**
     * Rows in the order the owner reads: the app name, case-insensitively, falling back to the
     * package id when the lookup has not answered (a just-picked app) or answered "not installed".
     * Ties sort by id.
     */
    fun sortedEntries(entries: Set<String>, labels: Map<String, String?>): List<String> =
        entries.sortedWith(compareBy({ (labels[it] ?: it).lowercase(Locale.ROOT) }, { it }))

    /**
     * Which package a pick just changed, as `(package, target)` — the section learns it by
     * diffing the reloaded lists against what it showed before (the picker only says it closed).
     * One pick changes one package (A14.2's move rule reads as an add to the list it moved to);
     * anything else (a remove, a reset) answers null.
     */
    fun addedEntry(old: GuardLists, new: GuardLists): Pair<String, GuardPickerTarget>? {
        val toAllow = (new.allow - old.allow).firstOrNull()
        val toDeny = (new.deny - old.deny).firstOrNull()
        return when {
            toAllow != null -> toAllow to GuardPickerTarget.ALLOW
            toDeny != null -> toDeny to GuardPickerTarget.DENY
            else -> null
        }
    }
}
