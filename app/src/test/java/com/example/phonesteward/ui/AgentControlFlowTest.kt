package com.example.phonesteward.ui

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import com.example.phonesteward.net.AgentModel
import com.example.phonesteward.net.ModelEntry

/** A10.4 and A10.9 on the phone through the real view model: the model picker's choices, Compact now, Start fresh and the footer. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class AgentControlFlowTest : VmFlowBase() {

    private val settingsJson = """
        {"conversationId":"main","model":{"provider":"zai","id":"glm-5.3-flash"},"allowlist":[],"usage":{"inputTokens":1,"outputTokens":2},
         "context":{"tokens":53760,"budget":128000,"lastCompactionMs":null,"lastFreshStartMs":null}}
    """.trimIndent()

    private val modelsJson = """
        {"current":{"provider":"zai","id":"glm-5.3-flash"},"models":[
          {"provider":"zai","id":"glm-5.3-flash","label":"GLM 5.3 Flash","billing":"subscription","available":true,"reasoning":false,"contextWindow":1000000},
          {"provider":"zai","id":"glm-5.3","label":"GLM 5.3","billing":"subscription","available":true,"reasoning":true,"contextWindow":200000},
          {"provider":"deepseek","id":"v4.1-flash","label":"DeepSeek V4.1 Flash","billing":"metered","available":true,"reasoning":false,"contextWindow":128000}]}
    """.trimIndent()

    private fun entry(provider: String, id: String, label: String, billing: String) =
        ModelEntry(provider, id, label, billing, available = true, reasoning = false, contextWindow = 128_000)

    private fun vm(): AppViewModel {
        answers["GET /v1/agent/settings"] = 200 to settingsJson
        answers["GET /v1/agent/models"] = 200 to modelsJson
        return newViewModel()
    }

    // ---- the footer ----

    @Test
    fun `a refresh reads the agent settings, so the footer has the model and the context`() {
        val viewModel = vm()
        viewModel.refresh()
        await { viewModel.state.value.agent != null }
        assertEquals("glm-5.3-flash", viewModel.state.value.agent!!.model.id)
        assertEquals(53_760L, viewModel.state.value.agent!!.context!!.tokens)
    }

    @Test
    fun `a refresh also loads the catalogue, so the footer can show the model's name`() {
        val viewModel = vm()
        viewModel.refresh()
        await { viewModel.state.value.agent != null && viewModel.state.value.models != null }
        assertEquals("GLM 5.3 Flash", com.example.phonesteward.logic.models.ModelPicker.footerLabel(
            viewModel.state.value.agent!!.model, viewModel.state.value.models,
        ))
        // Once loaded it is not asked for again on every refresh.
        val loads = countOf("GET /v1/agent/models")
        viewModel.refresh()
        Thread.sleep(800)
        await(1_000) { false }
        assertEquals(loads, countOf("GET /v1/agent/models"))
    }

    @Test
    fun `an old server without a context block still gives a footer without a percentage`() {
        answers["GET /v1/agent/settings"] = 200 to """{"conversationId":"main","model":{"provider":"zai","id":"glm-5.3-flash"}}"""
        val viewModel = newViewModel()
        viewModel.refresh()
        await { viewModel.state.value.agent != null }
        assertNull(viewModel.state.value.agent!!.context)
    }

    // ---- the picker ----

    @Test
    fun `opening the list loads the catalogue`() {
        val viewModel = vm()
        viewModel.loadModels()
        await { viewModel.state.value.models != null }
        assertEquals(3, viewModel.state.value.models!!.models.size)
    }

    @Test
    fun `choosing a subscription model sends it at once, without a warning`() {
        answers["PUT /v1/agent/settings"] = 200 to """{"model":{"provider":"zai","id":"glm-5.3"}}"""
        val viewModel = vm()
        viewModel.chooseModel(entry("zai", "glm-5.3", "GLM 5.3", "subscription"))
        await { countOf("PUT /v1/agent/settings") > 0 }
        assertNull(viewModel.state.value.confirm)
        val body = kotlinx.serialization.json.Json.parseToJsonElement(bodiesOf("PUT /v1/agent/settings").single()).jsonObject
        assertEquals("glm-5.3", body["model"]!!.jsonObject["id"]!!.jsonPrimitive.content)
        assertEquals("false", body["confirmMetered"]!!.jsonPrimitive.content)
        await { viewModel.state.value.agent?.model?.id == "glm-5.3" }
        assertEquals("glm-5.3", viewModel.state.value.agent!!.model.id)
    }

    @Test
    fun `choosing a pay-as-you-go model asks once, and sends confirmMetered only after yes`() {
        answers["PUT /v1/agent/settings"] = 200 to """{"model":{"provider":"deepseek","id":"v4.1-flash"}}"""
        val viewModel = vm()
        viewModel.chooseModel(entry("deepseek", "v4.1-flash", "DeepSeek V4.1 Flash", "metered"))
        await { viewModel.state.value.confirm != null }
        assertEquals("Use a pay-as-you-go model?", viewModel.state.value.confirm!!.title)
        assertEquals("This model is pay-as-you-go: each message costs money. Use DeepSeek V4.1 Flash?", viewModel.state.value.confirm!!.body)
        assertEquals("nothing is sent before the answer", 0, countOf("PUT /v1/agent/settings"))
        viewModel.answerConfirm(true)
        await { countOf("PUT /v1/agent/settings") > 0 }
        val body = kotlinx.serialization.json.Json.parseToJsonElement(bodiesOf("PUT /v1/agent/settings").single()).jsonObject
        assertEquals("true", body["confirmMetered"]!!.jsonPrimitive.content)
    }

    @Test
    fun `declining the pay-as-you-go warning changes nothing`() {
        val viewModel = vm()
        viewModel.chooseModel(entry("deepseek", "v4.1-flash", "DeepSeek V4.1 Flash", "metered"))
        await { viewModel.state.value.confirm != null }
        viewModel.answerConfirm(false)
        await { viewModel.state.value.confirm == null }
        Thread.sleep(200)
        assertEquals(0, countOf("PUT /v1/agent/settings"))
    }

    @Test
    fun `a busy steward is explained and the model stays`() {
        answers["PUT /v1/agent/settings"] = 409 to """{"error":{"code":"AGENT_BUSY","message":"working"}}"""
        val viewModel = vm()
        viewModel.chooseModel(entry("zai", "glm-5.3", "GLM 5.3", "subscription"))
        await { viewModel.state.value.chatNotice != null }
        assertEquals("The steward is working. Try again when it has finished.", viewModel.state.value.chatNotice)
    }

    // ---- N37: a refusal means the catalogue was stale ----

    @Test
    fun `a model the server cannot use any more refreshes the list`() {
        answers["PUT /v1/agent/settings"] = 409 to """{"error":{"code":"MODEL_UNAVAILABLE","message":"no sign-in"}}"""
        val viewModel = vm()
        viewModel.chooseModel(entry("zai", "glm-5.3", "GLM 5.3", "subscription"))
        await { viewModel.state.value.chatNotice != null }
        assertEquals("This server has no sign-in for that model's provider.", viewModel.state.value.chatNotice)
        await { countOf("GET /v1/agent/models") > 0 }
    }

    @Test
    fun `a model that turns out to be pay-as-you-go refreshes the list and asks the metered question`() {
        answers["PUT /v1/agent/settings"] = 400 to """{"error":{"code":"METERED_NEEDS_CONFIRM","message":"confirm"}}"""
        val viewModel = vm()
        viewModel.chooseModel(entry("zai", "glm-5.3", "GLM 5.3", "subscription"))
        await { viewModel.state.value.confirm != null }
        assertEquals("Use a pay-as-you-go model?", viewModel.state.value.confirm!!.title)
        assertEquals("This model is pay-as-you-go: each message costs money. Use GLM 5.3?", viewModel.state.value.confirm!!.body)
        assertNull("not the bare code", viewModel.state.value.chatNotice)
        await { countOf("GET /v1/agent/models") > 0 }
        viewModel.answerConfirm(true)
        await { countOf("PUT /v1/agent/settings") >= 2 }
        val second = kotlinx.serialization.json.Json.parseToJsonElement(bodiesOf("PUT /v1/agent/settings")[1]).jsonObject
        assertEquals("true", second["confirmMetered"]!!.jsonPrimitive.content)
    }

    @Test
    fun `declining the late metered question changes nothing and sends nothing more`() {
        answers["PUT /v1/agent/settings"] = 400 to """{"error":{"code":"METERED_NEEDS_CONFIRM","message":"confirm"}}"""
        val viewModel = vm()
        viewModel.chooseModel(entry("zai", "glm-5.3", "GLM 5.3", "subscription"))
        await { viewModel.state.value.confirm != null }
        viewModel.answerConfirm(false)
        await { viewModel.state.value.confirm == null }
        Thread.sleep(200)
        assertEquals(1, countOf("PUT /v1/agent/settings"))
    }

    // ---- Compact now and Start fresh ----

    @Test
    fun `Compact now posts the route and says it started`() {
        answers["POST /v1/chat/compact"] = 202 to """{"accepted":true}"""
        val viewModel = vm()
        viewModel.compactNow()
        await { countOf("POST /v1/chat/compact") > 0 }
        await { viewModel.state.value.chatInfo != null }
        assertEquals("Compacting the steward's memory of this conversation. The chat itself stays as it is.", viewModel.state.value.chatInfo)
        assertTrue(bodiesOf("POST /v1/chat/compact").single().contains("requestId"))
    }

    @Test
    fun `Start fresh asks once and then posts the route`() {
        answers["POST /v1/chat/fresh"] = 202 to """{"accepted":true}"""
        val viewModel = vm()
        viewModel.startFresh()
        await { viewModel.state.value.confirm != null }
        assertEquals("Start fresh?", viewModel.state.value.confirm!!.title)
        assertEquals(0, countOf("POST /v1/chat/fresh"))
        viewModel.answerConfirm(true)
        await { countOf("POST /v1/chat/fresh") > 0 }
        await { viewModel.state.value.chatInfo != null }
        assertTrue(viewModel.state.value.chatInfo!!.startsWith("Starting fresh"))
    }

    @Test
    fun `declining Start fresh posts nothing`() {
        val viewModel = vm()
        viewModel.startFresh()
        await { viewModel.state.value.confirm != null }
        viewModel.answerConfirm(false)
        Thread.sleep(300)
        assertEquals(0, countOf("POST /v1/chat/fresh"))
    }

    @Test
    fun `either route refused while the steward works says so`() {
        answers["POST /v1/chat/compact"] = 409 to """{"error":{"code":"AGENT_BUSY","message":"working"}}"""
        val viewModel = vm()
        viewModel.compactNow()
        await { viewModel.state.value.chatNotice != null }
        assertEquals("The steward is working. Try again when it has finished.", viewModel.state.value.chatNotice)
    }

    @Test
    fun `typing slash compact is the same as the menu and is not sent as a message`() {
        answers["POST /v1/chat/compact"] = 202 to """{"accepted":true}"""
        val viewModel = vm()
        viewModel.send("/compact")
        await { countOf("POST /v1/chat/compact") > 0 }
        assertEquals(0, countOf("POST /v1/chat"))
        assertTrue("no local echo of the command", viewModel.state.value.chat.none { it.text == "/compact" })
    }

    @Test
    fun `typing slash new asks first, as the menu does`() {
        answers["POST /v1/chat/fresh"] = 202 to """{"accepted":true}"""
        val viewModel = vm()
        viewModel.send("/new")
        await { viewModel.state.value.confirm != null }
        assertEquals("Start fresh?", viewModel.state.value.confirm!!.title)
        viewModel.answerConfirm(true)
        await { countOf("POST /v1/chat/fresh") > 0 }
        assertEquals(0, countOf("POST /v1/chat"))
    }

    @Test
    fun `ordinary text, even one that mentions a command, is sent as a message`() {
        val viewModel = vm()
        viewModel.send("what does /compact do?")
        await { countOf("POST /v1/chat") > 0 }
        assertNotNull(viewModel.state.value.chat.firstOrNull { it.text == "what does /compact do?" })
    }
}
