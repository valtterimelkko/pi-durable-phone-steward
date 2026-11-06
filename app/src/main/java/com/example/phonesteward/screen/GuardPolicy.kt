package com.example.phonesteward.screen

import java.util.Locale

/** The two lists the owner edits in Rules. */
data class GuardLists(val allow: Set<String>, val deny: Set<String>) {
    companion object {
        fun normalise(entries: Iterable<String>): Set<String> =
            entries.map { it.trim().lowercase(Locale.ROOT) }.filter { it.isNotEmpty() }.toCollection(LinkedHashSet())
    }

    /** A14.2: adding to one list removes the app from the other — it cannot be on both. */
    fun addingToAllow(pkg: String): GuardLists {
        val p = normalise(listOf(pkg))
        return copy(allow = allow + p, deny = deny - p)
    }

    /** A14.2: the other direction of the move rule. */
    fun addingToDeny(pkg: String): GuardLists {
        val p = normalise(listOf(pkg))
        return copy(deny = deny + p, allow = allow - p)
    }

    /** Removing an entry clears it from both lists. */
    fun without(pkg: String): GuardLists = copy(allow = allow - pkg, deny = deny - pkg)
}

object DefaultGuards {
    /** Settings, Files, WhatsApp, Telegram, Chrome and the Play Store, with the common OEM variants. */
    val allow: Set<String> = linkedSetOf(
        "com.android.settings", "com.oneplus.settings",
        "com.android.documentsui", "com.google.android.documentsui", "com.google.android.apps.nbu.files", "com.oneplus.filemanager",
        "com.whatsapp", "com.whatsapp.w4b",
        "org.telegram.messenger", "org.telegram.messenger.web",
        "com.android.chrome",
        "com.android.vending",
    )

    /** Banking and trading, authenticators, password managers, Photos and Gallery, email. The owner may add to this. */
    val deny: Set<String> = linkedSetOf(
        "com.google.android.gm", "com.google.android.apps.gmail", "com.microsoft.office.outlook",
        "com.android.email", "com.fsck.k9", "ch.protonmail.android", "com.google.android.apps.photos", "com.amazon.clouddrive.photos",
        "com.oneplus.gallery", "com.google.android.apps.authenticator2", "com.authy.authy", "com.azure.authenticator",
        "com.lastpass.lpandroid", "com.x8bit.bitwarden", "com.onepassword.android", "com.agilebits.onepassword",
        "org.keepassdroid", "com.paypal.android.p2pmobile", "com.revolut.revolut", "com.coinbase.android", "com.binance.dev",
        "com.google.android.apps.walletnfcrel", "com.android.keyguard",
    )

    fun lists() = GuardLists(allow, deny)
}

/**
 * Which apps and screens a session may see and touch. Decided in the app; the model cannot change it.
 *
 * - The deny list wins over the allow list.
 * - A built-in floor of sensitive names (banks, wallets, authenticators, password managers) is denied
 *   whatever the lists say, so a wrongly edited list cannot open them.
 * - This app's own screen can be observed (the owner sees what the agent sees) but never operated:
 *   the agent must not be able to tap Approve on its own proposals.
 */
class GuardPolicy(private val lists: GuardLists, private val ownPackage: String) {

    enum class Access { ALLOWED, DENIED, NOT_LISTED, OWN_OBSERVE_ONLY }

    /**
     * A15.11: the apps the owner allowed for this session only, through the allow-for-this-task
     * question. It lasts as long as this policy (one session) and is never written to the stored
     * lists; the deny list, the built-in floor and the steward's own screen still win (see [access]).
     */
    private val sessionAllowed: MutableSet<String> = java.util.Collections.synchronizedSet(linkedSetOf())

    /** A15.11: the owner allowed one unlisted app for the rest of this session. */
    fun allowForThisSession(pkg: String) {
        GuardLists.normalise(listOf(pkg)).forEach { sessionAllowed.add(it) }
    }

    fun access(pkg: String): Access {
        val p = pkg.lowercase(Locale.ROOT)
        if (p == ownPackage.lowercase(Locale.ROOT)) return Access.OWN_OBSERVE_ONLY
        if (p in lists.deny || sensitiveFloor(p)) return Access.DENIED
        if (p in lists.allow || p in sessionAllowed) return Access.ALLOWED
        return Access.NOT_LISTED
    }

    fun mayObserve(pkg: String): Boolean = access(pkg).let { it == Access.ALLOWED || it == Access.OWN_OBSERVE_ONLY }

    fun mayAct(pkg: String): Boolean = access(pkg) == Access.ALLOWED

    fun isDenied(pkg: String): Boolean = access(pkg) == Access.DENIED

    /** True for a page that must end the session: lock screen, biometrics, security, accessibility, device admin. */
    fun screenDenied(pkg: String, activity: String?): Boolean {
        if (isDenied(pkg)) return true
        val a = activity?.lowercase(Locale.ROOT) ?: return false
        return SENSITIVE_SCREENS.any { a.contains(it) }
    }

    companion object {
        private val SENSITIVE_PACKAGE_TOKENS = listOf(
            "bank", "banking", "wallet", "paypal", "revolut", "crypto", "trading", "broker", "authenticator", "authy",
            "keepass", "bitwarden", "lastpass", "onepassword", "1password", "dashlane",
        )

        private val SENSITIVE_SCREENS = listOf(
            "password", "chooselock", "confirmlock", "lockscreen", "lockpattern", "security", "biometric", "fingerprint",
            "faceenroll", "deviceadmin", "accessibilitysettings", "credential", "simlock",
        )

        private fun sensitiveFloor(pkg: String): Boolean {
            val segments = pkg.split('.')
            return SENSITIVE_PACKAGE_TOKENS.any { token -> segments.any { it.contains(token) } }
        }

        /** A14.3: an app on the built-in floor is always denied, whatever the lists say; the allow picker may not add it. */
        fun alwaysDenied(pkg: String): Boolean = sensitiveFloor(pkg.lowercase(Locale.ROOT))
    }
}

/** 40 steps and 15 minutes per session (A13.4 raised the time limit from 10). */
class SessionBudget(private val clock: () -> Long = { System.currentTimeMillis() }) {
    enum class Exhausted { STEPS, TIME }

    private val startedAt = clock()
    private var steps = 0

    fun exhausted(): Exhausted? = when {
        clock() - startedAt >= MAX_MILLIS -> Exhausted.TIME
        steps >= MAX_STEPS -> Exhausted.STEPS
        else -> null
    }

    /** Count one step; false (and no count) when the budget is spent. */
    fun takeStep(): Boolean {
        if (exhausted() != null) return false
        steps++
        return true
    }

    companion object {
        const val MAX_STEPS = 40
        const val MAX_MILLIS = 15 * 60_000L
    }
}
