package com.example.phonesteward.logic

/**
 * Onboarding, with vendor quirks named (some OEM builds, e.g. OxygenOS): each step names what the owner must
 * allow, and carries its own status check. Pure: the snapshot arrives as
 * plain booleans; the screens turn each step into the right Settings intent.
 */
object Onboarding {

    enum class Step(val title: String, val how: String) {
        BATTERY(
            "Unrestricted battery",
            "Lets the app sync in the background. Some OEM builds otherwise stop it.",
        ),
        USAGE_ACCESS(
            "Usage access",
            "Needed for app sizes and last-used times.",
        ),
        ALL_FILES(
            "All-files access",
            "Needed to see every folder, including Android/media, and to clear temporary files.",
        ),
        MEDIA_READ(
            "Media read",
            "Needed for the media index and the photo rule.",
        ),
        MANAGE_MEDIA(
            "Manage media (auto mode only)",
            "Lets the app trash media without asking each time. Only needed once you switch on auto mode.",
        ),
    }

    data class Snapshot(
        val batteryUnrestricted: Boolean,
        val usageAccess: Boolean,
        val allFiles: Boolean,
        val mediaRead: Boolean,
        val manageMedia: Boolean,
        val autoModeOn: Boolean,
    ) {
        val coreDone: Boolean
            get() = batteryUnrestricted && usageAccess && allFiles && mediaRead
    }

    /**
     * How one permission row should present itself (N3): granted (green),
     * not needed yet (neutral — manage media while auto mode is off), or
     * action needed.
     */
    enum class RowState { GRANTED, NOT_NEEDED, ACTION_NEEDED }

    /** Each step with its presentation state (N3). */
    fun rowStates(s: Snapshot): List<Pair<Step, RowState>> = listOf(
        Step.BATTERY to s.batteryUnrestricted.asRowState(),
        Step.USAGE_ACCESS to s.usageAccess.asRowState(),
        Step.ALL_FILES to s.allFiles.asRowState(),
        Step.MEDIA_READ to s.mediaRead.asRowState(),
        Step.MANAGE_MEDIA to when {
            s.manageMedia -> RowState.GRANTED
            s.autoModeOn -> RowState.ACTION_NEEDED
            else -> RowState.NOT_NEEDED
        },
    )

    private fun Boolean.asRowState(): RowState =
        if (this) RowState.GRANTED else RowState.ACTION_NEEDED

    /** Each step with its current status; MANAGE_MEDIA is checked only when auto mode is on. */
    fun steps(s: Snapshot): List<Pair<Step, Boolean>> = listOf(
        Step.BATTERY to s.batteryUnrestricted,
        Step.USAGE_ACCESS to s.usageAccess,
        Step.ALL_FILES to s.allFiles,
        Step.MEDIA_READ to s.mediaRead,
        Step.MANAGE_MEDIA to (!s.autoModeOn || s.manageMedia),
    )

    /** The first unfinished step, or null when onboarding is complete. */
    fun nextStep(s: Snapshot): Step? = steps(s).firstOrNull { !it.second }?.first
}
