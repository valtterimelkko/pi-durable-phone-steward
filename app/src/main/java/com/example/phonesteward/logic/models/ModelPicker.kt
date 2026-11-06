package com.example.phonesteward.logic.models

import com.example.phonesteward.net.AgentModel
import com.example.phonesteward.net.ModelCatalogueResponse
import com.example.phonesteward.net.ModelEntry

/**
 * The model picker's logic (A10.4): the full Pi catalogue as a searchable list grouped by provider, unavailable models
 * greyed with the reason, pay-as-you-go ones marked, and the one warning before a model that may cost money. Pure.
 */
object ModelPicker {

    data class Row(
        val entry: ModelEntry,
        val isCurrent: Boolean,
        /** False for a model the server has no sign-in for: shown greyed, not tappable. */
        val selectable: Boolean,
        /** "Pay-as-you-go" or "Billing not known"; null for a subscription model. */
        val badge: String?,
        /** The reason an unavailable model cannot be chosen. */
        val note: String?,
        /** A15.3: the catalogue's short reason this model currently fails (its sign-in, a plan); the model stays choosable. */
        val problem: String?,
        /** A15.10: an id that follows whatever is newest; the picker marks it "(alias)". */
        val alias: Boolean,
    )

    data class Group(val title: String, val rows: List<Row>)

    private val TITLES = mapOf(
        "zai" to "Z.AI", "zai-coding-cn" to "Z.AI (China)", "anthropic" to "Anthropic", "openai" to "OpenAI",
        "openai-codex" to "OpenAI Codex", "google" to "Google", "google-vertex" to "Google Vertex", "deepseek" to "DeepSeek",
        "openrouter" to "OpenRouter", "github-copilot" to "GitHub Copilot", "mistral" to "Mistral", "xai" to "xAI",
        "groq" to "Groq", "together" to "Together", "fireworks" to "Fireworks", "cerebras" to "Cerebras", "kimi-coding" to "Kimi",
    )

    fun providerTitle(provider: String): String =
        TITLES[provider] ?: provider.replaceFirstChar { it.uppercase() }

    fun groups(catalogue: ModelCatalogueResponse, query: String): List<Group> {
        val words = query.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
        val current = catalogue.current
        val rows = catalogue.models
            .filter { m ->
                val hay = "${m.label} ${m.id} ${m.provider} ${providerTitle(m.provider)}".lowercase()
                words.all { hay.contains(it) }
            }
            .map { m ->
                Row(
                    entry = m,
                    isCurrent = m.provider == current.provider && m.id == current.id,
                    selectable = m.available,
                    badge = when (m.billing) {
                        "metered" -> "Pay-as-you-go"
                        "subscription" -> null
                        else -> "Billing not known"
                    },
                    note = if (m.available) null else "Not available: the steward server has no sign-in for ${providerTitle(m.provider)}.",
                    problem = m.problem?.takeIf { it.isNotBlank() },
                    alias = m.alias,
                )
            }
        val byProvider = rows.groupBy { it.entry.provider }.map { (provider, list) ->
            val sorted = list.sortedWith(
                compareBy<Row>({ !it.isCurrent }, { !it.selectable }, { it.entry.label.lowercase() }),
            )
            provider to Group(providerTitle(provider), sorted)
        }
        return byProvider.sortedWith(
            compareBy<Pair<String, Group>>(
                { (_, g) -> if (g.rows.any { it.isCurrent }) 0 else if (g.rows.any { it.selectable }) 1 else 2 },
                { (_, g) -> g.title.lowercase() },
            ),
        ).map { it.second }
    }

    /** A15.10: the label as the picker shows it — an alias model is marked "(alias)" so its id is never a surprise. */
    fun displayLabel(row: Row): String = row.entry.label.ifBlank { row.entry.id } + if (row.alias) " (alias)" else ""

    /** Only a subscription model switches without a question; metered and unclassified ones ask first. */
    fun needsWarning(entry: ModelEntry): Boolean = entry.billing != "subscription"

    /** (title, body) of the one question before a model that may cost money. */
    fun warning(entry: ModelEntry): Pair<String, String> =
        if (entry.billing == "metered") {
            "Use a pay-as-you-go model?" to "This model is pay-as-you-go: each message costs money. Use ${entry.label}?"
        } else {
            "Use a model with unknown billing?" to
                "This model's billing is not known to the steward; it may be pay-as-you-go: each message could cost money. Use ${entry.label}?"
        }

    fun footerLabel(current: AgentModel, catalogue: ModelCatalogueResponse?): String =
        catalogue?.models?.firstOrNull { it.provider == current.provider && it.id == current.id }?.label
            ?.takeIf { it.isNotBlank() } ?: current.id

    fun errorText(code: String): String = when (code) {
        "AGENT_BUSY" -> "The steward is working. Try again when it has finished."
        "MODEL_UNAVAILABLE" -> "This server has no sign-in for that model's provider."
        "UNKNOWN_MODEL" -> "The steward does not know that model any more. The list has been refreshed."
        "METERED_NEEDS_CONFIRM" -> "That model is pay-as-you-go, so it needs your yes first. Nothing was changed."
        else -> "The model was not changed ($code)."
    }
}
