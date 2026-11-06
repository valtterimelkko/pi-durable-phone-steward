package com.example.phonesteward.logic.models

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.example.phonesteward.net.AgentModel
import com.example.phonesteward.net.ModelCatalogueResponse
import com.example.phonesteward.net.ModelEntry

/** A10.4 app half: a searchable list grouped by provider; unavailable ones greyed with the reason; metered ones marked. */
class ModelPickerTest {

    private fun m(provider: String, id: String, label: String, billing: String = "subscription", available: Boolean = true) =
        ModelEntry(provider, id, label, billing, available, reasoning = false, contextWindow = 128_000)

    private val catalogue = ModelCatalogueResponse(
        current = AgentModel("zai", "glm-5.3-flash"),
        models = listOf(
            m("openai", "gpt-x", "GPT X", "metered", available = false),
            m("zai", "glm-5.3-flash", "GLM 5.3 Flash"),
            m("zai", "glm-5.3", "GLM 5.3"),
            m("deepseek", "v4.1-flash", "DeepSeek V4.1 Flash", "metered"),
            m("custom", "local-1", "Local One", "unknown"),
            m("anthropic", "sonnet", "Sonnet", "subscription", available = false),
        ),
    )

    @Test
    fun `models are grouped by provider, usable providers first, the current model first in its group`() {
        val groups = ModelPicker.groups(catalogue, "")
        // zai holds the current model, then the other available providers by name, then providers with nothing usable.
        assertEquals(listOf("Z.AI", "Custom", "DeepSeek", "Anthropic", "OpenAI"), groups.map { it.title })
        assertEquals(listOf("GLM 5.3 Flash", "GLM 5.3"), groups[0].rows.map { it.entry.label })
        assertTrue(groups[0].rows[0].isCurrent)
        assertFalse(groups[0].rows[1].isCurrent)
    }

    @Test
    fun `search matches the label, id or provider, every word, ignoring case`() {
        assertEquals(listOf("GLM 5.3 Flash", "GLM 5.3"), ModelPicker.groups(catalogue, "glm").flatMap { it.rows }.map { it.entry.label })
        assertEquals(listOf("DeepSeek V4.1 Flash"), ModelPicker.groups(catalogue, "deepseek flash").flatMap { it.rows }.map { it.entry.label })
        assertEquals(listOf("GPT X"), ModelPicker.groups(catalogue, "OPENAI").flatMap { it.rows }.map { it.entry.label })
        assertTrue(ModelPicker.groups(catalogue, "nothing like this").isEmpty())
        assertEquals(2, ModelPicker.groups(catalogue, "  flash  ").flatMap { it.rows }.size)
    }

    @Test
    fun `an unavailable model says why and cannot be chosen`() {
        val gpt = ModelPicker.groups(catalogue, "gpt").single().rows.single()
        assertFalse(gpt.selectable)
        assertEquals("Not available: the steward server has no sign-in for OpenAI.", gpt.note)
    }

    @Test
    fun `a model with a problem shows the catalogue's reason and stays choosable`() {
        val withProblem = catalogue.copy(
            models = catalogue.models + ModelEntry(
                "openai-codex", "gpt-6.1-sol", "GPT 6.1 Sol", billing = "subscription",
                available = true, reasoning = false, contextWindow = 128_000,
                problem = "its sign-in on the server has expired",
            ),
        )
        val row = ModelPicker.groups(withProblem, "6.1").single().rows.single()
        assertEquals("its sign-in on the server has expired", row.problem)
        assertTrue("a problem alone does not stop the owner picking it", row.selectable)
    }

    @Test
    fun `an alias model is marked (alias) and keeps its label`() {
        val alias = ModelEntry(
            "anthropic", "~claude-haiku-latest", "Claude Haiku (follows the newest Claude Haiku)",
            billing = "subscription", available = true, reasoning = false, contextWindow = 200_000,
            alias = true,
        )
        val row = ModelPicker.groups(catalogue.copy(models = catalogue.models + alias), "haiku").single().rows.single()
        assertTrue(row.alias)
        assertEquals("Claude Haiku (follows the newest Claude Haiku) (alias)", ModelPicker.displayLabel(row))
        assertTrue("an alias is still selectable", row.selectable)
    }

    @Test
    fun `a plain model is not marked (alias)`() {
        val row = ModelPicker.groups(catalogue, "glm-5.3 flash").single().rows.single()
        assertFalse(row.alias)
        assertEquals("GLM 5.3 Flash", ModelPicker.displayLabel(row))
    }

    @Test
    fun `a model without a problem shows none`() {
        val row = ModelPicker.groups(catalogue, "glm-5.3 flash").single().rows.single()
        assertNull(row.problem)
    }

    @Test
    fun `metered and unknown billing are marked, subscription is not`() {
        val rows = ModelPicker.groups(catalogue, "").flatMap { it.rows }.associateBy { it.entry.id }
        assertEquals("Pay-as-you-go", rows.getValue("v4.1-flash").badge)
        assertEquals("Billing not known", rows.getValue("local-1").badge)
        assertNull(rows.getValue("glm-5.3").badge)
        assertTrue(rows.getValue("v4.1-flash").selectable)
    }

    @Test
    fun `only subscription models switch without a warning`() {
        assertFalse(ModelPicker.needsWarning(m("zai", "a", "A", "subscription")))
        assertTrue(ModelPicker.needsWarning(m("x", "a", "A", "metered")))
        assertTrue(ModelPicker.needsWarning(m("x", "a", "A", "unknown")))
    }

    @Test
    fun `the warning says each message costs money and names the model`() {
        val metered = ModelPicker.warning(m("deepseek", "v", "DeepSeek V4.1 Flash", "metered"))
        assertEquals("Use a pay-as-you-go model?", metered.first)
        assertEquals("This model is pay-as-you-go: each message costs money. Use DeepSeek V4.1 Flash?", metered.second)
        val unknown = ModelPicker.warning(m("custom", "v", "Local One", "unknown"))
        assertTrue(unknown.second, unknown.second.contains("may be pay-as-you-go"))
    }

    @Test
    fun `the footer shows the current model's label, falling back to its id`() {
        assertEquals("GLM 5.3 Flash", ModelPicker.footerLabel(AgentModel("zai", "glm-5.3-flash"), catalogue))
        assertEquals("glm-9", ModelPicker.footerLabel(AgentModel("zai", "glm-9"), catalogue))
        assertEquals("glm-5.3-flash", ModelPicker.footerLabel(AgentModel("zai", "glm-5.3-flash"), null))
    }

    @Test
    fun `a failed choice is explained in plain words`() {
        assertEquals("The steward is working. Try again when it has finished.", ModelPicker.errorText("AGENT_BUSY"))
        assertEquals("This server has no sign-in for that model's provider.", ModelPicker.errorText("MODEL_UNAVAILABLE"))
        assertEquals("The steward does not know that model any more. The list has been refreshed.", ModelPicker.errorText("UNKNOWN_MODEL"))
        assertEquals("The model was not changed (SOMETHING).", ModelPicker.errorText("SOMETHING"))
    }
}
