package com.example.phonesteward.screen

/**
 * What the session notification says the steward is doing (each action is shown). Short, built only from
 * fixed words plus a trimmed node label, and masked with the tree's code rule: nothing the model typed or wrote is
 * echoed, and an unknown action gets a neutral sentence.
 */
object ActionText {
    private const val MAX_LABEL = 30

    fun describe(tool: String, args: Map<String, String>, label: String?): String {
        if (tool == "screen_observe") return "Looking at the screen"
        val named = label?.let { quote(it) }
        return when (args["action"].orEmpty()) {
            "tap" -> if (named != null) "Tapping $named" else "Tapping an item"
            "type" -> if (named != null) "Typing in $named" else "Typing in a field"
            "scroll" -> when (args["direction"]) {
                "up" -> "Scrolling up"
                "left" -> "Scrolling left"
                "right" -> "Scrolling right"
                else -> "Scrolling down"
            }
            "back" -> "Going back"
            "home" -> "Going to the home screen"
            "open_app" -> "Opening ${args["packageName"].orEmpty().filter { it.isLetterOrDigit() || it == '.' || it == '_' }.take(60)}"
            else -> "Working on the screen"
        }
    }

    private fun quote(label: String): String? {
        val clean = TreeSerialiser.redactCodes(label.replace(Regex("\\s+"), " ").trim())
        if (clean.isEmpty()) return null
        val cut = if (clean.length > MAX_LABEL) clean.take(MAX_LABEL).trimEnd() + "…" else clean
        return "“$cut”"
    }
}
