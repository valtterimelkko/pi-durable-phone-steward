package com.example.phonesteward.logic.context

import com.example.phonesteward.net.ContextInfo

enum class ChatCommand { Compact, Fresh }

/** `/compact` and `/new` typed in the chat box do what the Chat menu does (A10.9). Exact matches only. */
object ChatCommands {
    fun parse(text: String): ChatCommand? = when (text.trim().lowercase()) {
        "/compact" -> ChatCommand.Compact
        "/new" -> ChatCommand.Fresh
        else -> null
    }
}

/** The Chat footer's "Context NN %" and the plain-words outcomes of Compact now and Start fresh (A10.9). */
object ContextFooter {

    /** Tokens used over the working budget (not the model's window), rounded and capped at 100; null when unknown. */
    fun percent(context: ContextInfo?): Int? {
        if (context == null || context.budget <= 0) return null
        return Math.round(100.0 * context.tokens / context.budget).toInt().coerceIn(0, 100)
    }

    fun contextText(context: ContextInfo?): String? = percent(context)?.let { "Context $it %" }

    fun busyText() = "The steward is working. Try again when it has finished."

    fun compactStarted() = "Compacting the steward's memory of this conversation. The chat itself stays as it is."

    fun freshStarted() =
        "Starting fresh: the steward is rebuilding its working notes from its records. The chat itself stays as it is."
}
