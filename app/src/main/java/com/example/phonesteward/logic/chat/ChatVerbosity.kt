package com.example.phonesteward.logic.chat

/** F2: how much of the agent's work the Chat tab shows. Medium is the Pi Web UI-like default. */
enum class ChatVerbosity(val label: String) {
    Low("Low"),
    Medium("Medium"),
    High("High"),
    ;

    companion object {
        val Default = Medium

        fun fromName(name: String?): ChatVerbosity = entries.firstOrNull { it.name == name } ?: Default
    }
}

/** Where the choice is kept (the phone's own preferences); the view model reads it once and writes on change. */
interface ChatSettings {
    fun read(): ChatVerbosity
    fun write(level: ChatVerbosity)
}
