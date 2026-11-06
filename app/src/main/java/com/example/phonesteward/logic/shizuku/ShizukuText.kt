package com.example.phonesteward.logic.shizuku

/**
 * The Status card's words for Shizuku, in plain terms (tell the owner what to press, never send them hunting). No
 * developer commands: the Shizuku app itself shows the start steps. Pure.
 */
object ShizukuText {

    data class Card(
        val summary: String,
        /** Numbered steps; empty when there is nothing left to do. */
        val steps: List<String>,
        /** The main button, or null when nothing is left to press (no greyed, dead buttons). */
        val primary: Action?,
        val secondary: Action? = null,
    )

    enum class Action { GET_SHIZUKU, OPEN_SHIZUKU, ALLOW_SHIZUKU, CHECK_AGAIN }

    fun label(action: Action): String = when (action) {
        Action.GET_SHIZUKU -> "Get Shizuku"
        Action.OPEN_SHIZUKU -> "Open Shizuku"
        Action.ALLOW_SHIZUKU -> "Allow Shizuku for the steward"
        Action.CHECK_AGAIN -> "Check again"
    }

    const val WHAT_IT_DOES = "Optional. With Shizuku the steward can clear every app's cache in one go with no pop-ups, " +
        "read exact app sizes, and stop a misbehaving app."

    fun card(state: ShizukuState): Card = when (state) {
        ShizukuState.ABSENT -> Card(
            summary = "Not set up. $WHAT_IT_DOES",
            steps = listOf(
                "Install the Shizuku app.",
                "Open Shizuku and follow its steps to start it with wireless debugging (the phone must be on Wi-Fi).",
                "Come back here and tap Allow Shizuku for the steward.",
            ),
            primary = Action.GET_SHIZUKU,
            secondary = Action.CHECK_AGAIN,
        )
        ShizukuState.NOT_RUNNING -> Card(
            summary = "Shizuku is installed but not running. Start it again after every restart of the phone.",
            steps = listOf(
                "Open Shizuku.",
                "Tap Start under wireless debugging and follow its steps (the phone must be on Wi-Fi).",
                "Come back here.",
            ),
            primary = Action.OPEN_SHIZUKU,
            secondary = Action.CHECK_AGAIN,
        )
        ShizukuState.NO_PERMISSION -> Card(
            summary = "Shizuku is running. One tap lets the steward use it.",
            steps = listOf("Tap Allow Shizuku for the steward, then Allow in Shizuku's own question."),
            primary = Action.ALLOW_SHIZUKU,
        )
        ShizukuState.READY -> Card(
            summary = "Ready. The steward can clear every app's cache with no pop-ups and read exact app sizes. " +
                "After a restart of the phone you will need to start Shizuku again.",
            steps = emptyList(),
            primary = null,
        )
    }
}
