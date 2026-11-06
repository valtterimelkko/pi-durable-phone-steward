package com.example.phonesteward.logic.shizuku

/**
 * A12: Allow with Shizuku. On Android 13 or later a phone can refuse the Accessibility switch for an app installed outside
 * Google Play ("Restricted setting"), and some OEM builds (e.g. OxygenOS) then have no way to reach "Allow restricted
 * settings" in App info. With Shizuku the steward can allow it for itself.
 *
 * This is the only thing here that runs a command, and it runs one fixed pair for one package: the app's own. There is no
 * parameter to change what it does (the package is fixed when the object is made, and checked), no server job and no
 * model input. Pure, so the JVM tests drive it with a fake [ShizukuAccess].
 */
class RestrictedSettings(
    private val shizuku: ShizukuAccess,
    ownPackage: String,
    private val sdk: Int,
) {
    // The only commands this class runs are the two fixed templates, checked for the own package when built.
    private val setCommand = ShellCommands.allowRestrictedSettings(ownPackage)
    private val getCommand = ShellCommands.readRestrictedSettings(ownPackage)

    /** What the card shows. */
    sealed interface Offer {
        /** Nothing: below Android 13, or the service is already on. */
        data object Hidden : Offer

        /** Shizuku is ready: the Allow with Shizuku button. */
        data object Button : Offer

        /** Shizuku is not ready: [text] says what to do and [action] is the one way to do it (no dead button). */
        data class NotReady(val state: ShizukuState, val text: String, val action: ShizukuText.Action) : Offer
    }

    sealed interface Outcome {
        data object Allowed : Outcome
        data class Failed(val reason: String) : Outcome
        data class NotReady(val state: ShizukuState) : Outcome
    }

    fun offer(serviceOn: Boolean): Offer {
        if (serviceOn || sdk < MIN_SDK) return Offer.Hidden
        val state = shizuku.state()
        return if (state == ShizukuState.READY) Offer.Button else Offer.NotReady(state, notReadyText(state), notReadyAction(state))
    }

    /** Runs the set and the read-back. Blocking: call it off the main thread. */
    fun allow(): Outcome {
        if (sdk < MIN_SDK) return Outcome.Failed("needs Android 13 or later")
        val state = shizuku.state()
        if (state != ShizukuState.READY) return Outcome.NotReady(state)
        return try {
            val shell = shizuku.shell()
            val set = shell.run(setCommand, TIMEOUT_MS)
            failure(set)?.let { return Outcome.Failed(it) }
            val get = shell.run(getCommand, TIMEOUT_MS)
            failure(get)?.let { return Outcome.Failed(it) }
            when (val mode = modeOf(get.stdout)) {
                "allow" -> Outcome.Allowed
                null -> Outcome.Failed("Android did not confirm it")
                else -> Outcome.Failed("Android still says $mode")
            }
        } catch (e: Exception) {
            Outcome.Failed(e.javaClass.simpleName)
        }
    }

    companion object {
        const val OP = "ACCESS_RESTRICTED_SETTINGS"
        const val MIN_SDK = 33
        const val COULD_NOT_OPEN = "Could not open Shizuku: open it from your apps."
        const val TIMEOUT_MS = 15_000L
        private const val MAX_LINE = 120

        private val MODE = Regex("$OP:\\s*([A-Za-z_]+)")

        /** The mode in `appops get` output (`ACCESS_RESTRICTED_SETTINGS: allow`, optionally `; time=...`), or null (`No operations.`). */
        internal fun modeOf(output: String): String? =
            output.lineSequence().mapNotNull { MODE.find(it)?.groupValues?.get(1)?.lowercase() }.firstOrNull()

        /** null when the command worked; otherwise the exit code and the first line of the error, never a raw dump. */
        private fun failure(result: ShellResult): String? {
            if (result.timedOut) return "timed out"
            if (result.exitCode == 0) return null
            val line = result.stderr.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }?.take(MAX_LINE)
            return if (line == null) "exit ${result.exitCode}" else "exit ${result.exitCode}: $line"
        }

        fun notReadyText(state: ShizukuState): String = when (state) {
            ShizukuState.ABSENT -> "Shizuku is not installed. Install it and start it, then come back and tap Allow with Shizuku."
            ShizukuState.NOT_RUNNING -> "Shizuku is not running. Start Shizuku (it needs starting again after every restart of the phone), then come back here."
            ShizukuState.NO_PERMISSION -> "Shizuku is running. Allow the steward in Shizuku, then come back here."
            ShizukuState.READY -> ""
        }

        fun notReadyAction(state: ShizukuState): ShizukuText.Action = when (state) {
            ShizukuState.ABSENT -> ShizukuText.Action.GET_SHIZUKU
            ShizukuState.NOT_RUNNING -> ShizukuText.Action.OPEN_SHIZUKU
            else -> ShizukuText.Action.ALLOW_SHIZUKU
        }

        /** The result line on the card. */
        fun line(outcome: Outcome): String = when (outcome) {
            Outcome.Allowed -> "Allowed. Now switch Phone Steward on in Accessibility."
            is Outcome.Failed -> "Could not allow it: ${outcome.reason}."
            is Outcome.NotReady -> notReadyText(outcome.state)
        }
    }
}
