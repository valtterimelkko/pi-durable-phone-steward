package com.example.phonesteward.screen

import android.content.Context

/** The allow and deny lists the owner edits in Rules, in the app's private preferences. */
class PrefsGuardListStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("phone_steward_screen", Context.MODE_PRIVATE)

    fun load(): GuardLists = GuardLists(
        allow = prefs.getStringSet(KEY_ALLOW, null)?.let { GuardLists.normalise(it) } ?: DefaultGuards.allow,
        deny = prefs.getStringSet(KEY_DENY, null)?.let { GuardLists.normalise(it) } ?: DefaultGuards.deny,
    )

    fun save(lists: GuardLists) {
        prefs.edit().putStringSet(KEY_ALLOW, lists.allow).putStringSet(KEY_DENY, lists.deny).apply()
    }

    // Correction 1 (finding 2): the legacy helpers follow the picker's move rule (A14.2) —
    // adding to one list removes the app from the other, and a removal clears it from both.
    fun addAllow(pkg: String) = save(load().addingToAllow(pkg))
    fun addDeny(pkg: String) = save(load().addingToDeny(pkg))
    fun removeAllow(pkg: String) = save(load().without(pkg))
    fun removeDeny(pkg: String) = save(load().without(pkg))
    fun resetToDefaults() = prefs.edit().remove(KEY_ALLOW).remove(KEY_DENY).apply()

    private companion object {
        const val KEY_ALLOW = "allow"
        const val KEY_DENY = "deny"
    }
}
