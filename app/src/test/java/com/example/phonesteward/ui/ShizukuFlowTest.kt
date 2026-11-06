package com.example.phonesteward.ui

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import com.example.phonesteward.logic.shizuku.ShellCommands
import com.example.phonesteward.logic.shizuku.ShellResult
import com.example.phonesteward.logic.shizuku.ShellRunner
import com.example.phonesteward.logic.shizuku.ShizukuAccess
import com.example.phonesteward.logic.shizuku.ShizukuState
import com.example.phonesteward.net.Proposal
import com.example.phonesteward.net.ProposalItem

/** A10.2 on the phone through the real view model: Shizuku itself is a fake (it cannot run off a phone). */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class ShizukuFlowTest : VmFlowBase() {

    private class FakeShizuku(var current: ShizukuState = ShizukuState.READY) : ShizukuAccess {
        val commands = mutableListOf<List<String>>()
        var permissionAsks = 0
        var reply: (List<String>) -> ShellResult = { ShellResult(0, "", "") }
        override fun state() = current
        override fun shell() = ShellRunner { args, _ -> commands += args; reply(args) }
        override fun requestPermission() { permissionAsks++ }
    }

    private val shizuku = FakeShizuku()

    @Before
    fun wire() {
        decidedProposal = "pr-1"
    }

    private fun vm(): AppViewModel = newViewModel().also {
        it.graphForTest().shizuku = shizuku
        it.installedProbe = { true }
        it.neverTouchForTest = com.example.phonesteward.logic.shizuku.NeverTouch({ null }, { null })
    }

    private fun trimProposal() = Proposal(
        "pr-1", title = "Clear caches",
        items = listOf(ProposalItem("t1", "trim_caches", buildJsonObject {
            put("apps", buildJsonArray { add(buildJsonObject { put("packageName", "com.example.puzzle"); put("label", "Puzzle"); put("cacheBytes", 400_000_000L) }) })
        })),
    )

    private fun disableProposal(vararg pkgs: String) = Proposal(
        "pr-1", title = "Disable apps",
        items = pkgs.mapIndexed { i, p -> ProposalItem("d$i", "disable_app", buildJsonObject { put("packageName", p); put("label", "Invented") }) },
    )

    private fun reported(): Map<String, String> =
        kotlinx.serialization.json.Json.parseToJsonElement(bodiesOf("POST /v1/proposals/pr-1/execution").last()).jsonObject["items"]!!
            .jsonArray.associate { it.jsonObject["itemId"]!!.jsonPrimitive.content to it.jsonObject["outcome"]!!.jsonPrimitive.content }

    @Test
    fun `trim_caches runs one shell command, measures the cache before and after and says what came back`() {
        approvedIds = listOf("t1")
        val viewModel = vm()
        val readings = ArrayDeque(listOf<Long?>(1_700_000_000L, 300_000_000L))
        viewModel.cacheMeter = { readings.removeFirst() }
        viewModel.decide(trimProposal(), approve = true, approvedIds)
        await { viewModel.state.value.result != null }
        assertEquals(listOf(ShellCommands.trimCaches()), shizuku.commands)
        assertEquals(mapOf("t1" to "done"), reported())
        val body = kotlinx.serialization.json.Json.parseToJsonElement(bodiesOf("POST /v1/proposals/pr-1/execution").last())
        assertEquals("1400000000", body.jsonObject["items"]!!.jsonArray[0].jsonObject["bytesFreed"]!!.jsonPrimitive.content)
        assertEquals("Done: every app's cache cleared — about 1.4 GB freed.", viewModel.state.value.result!!.text)
    }

    @Test
    fun `trim_caches without Shizuku runs nothing and says how to set it up`() {
        shizuku.current = ShizukuState.NOT_RUNNING
        approvedIds = listOf("t1")
        val viewModel = vm()
        viewModel.decide(trimProposal(), approve = true, approvedIds)
        await { viewModel.state.value.result != null }
        assertTrue(shizuku.commands.isEmpty())
        assertEquals(mapOf("t1" to "failed"), reported())
        assertTrue(viewModel.state.value.result!!.text.contains("Shizuku is not set up"))
    }

    @Test
    fun `a failing trim reports failed`() {
        shizuku.reply = { ShellResult(1, "", "no") }
        approvedIds = listOf("t1")
        val viewModel = vm()
        viewModel.cacheMeter = { 100L }
        viewModel.decide(trimProposal(), approve = true, approvedIds)
        await { viewModel.state.value.result != null }
        assertEquals(mapOf("t1" to "failed"), reported())
    }

    @Test
    fun `disable_app runs the fixed command and trusts what the phone shows afterwards`() {
        approvedIds = listOf("d0", "d1")
        val viewModel = vm()
        viewModel.appEnabledProbe = { pkg -> pkg != "com.example.puzzle" } // the puzzle app is now disabled, the other stayed enabled
        viewModel.decide(disableProposal("com.example.puzzle", "com.example.collage"), approve = true, approvedIds)
        await { viewModel.state.value.result != null }
        assertEquals(
            listOf(ShellCommands.disable("com.example.puzzle"), ShellCommands.disable("com.example.collage")),
            shizuku.commands,
        )
        assertEquals(mapOf("d0" to "done", "d1" to "failed"), reported())
        assertTrue(viewModel.state.value.result!!.text, viewModel.state.value.result!!.text.startsWith("Disabled 1 of 2 apps"))
    }

    @Test
    fun `disable_app refuses the keyboard in use without running anything`() {
        approvedIds = listOf("d0")
        val viewModel = vm()
        viewModel.neverTouchForTest = com.example.phonesteward.logic.shizuku.NeverTouch({ "com.invented.keyboard" }, { null })
        viewModel.decide(disableProposal("com.invented.keyboard"), approve = true, approvedIds)
        await { viewModel.state.value.result != null }
        assertTrue(shizuku.commands.isEmpty())
        assertEquals(mapOf("d0" to "failed"), reported())
        assertEquals("That app is protected, so the steward does not disable it.", viewModel.state.value.result!!.text)
    }

    // ---- state and setup ----

    @Test
    fun `the Shizuku state is read into the screen state and posted with the device status`() {
        shizuku.current = ShizukuState.NO_PERMISSION
        val viewModel = vm()
        viewModel.refresh()
        await { viewModel.state.value.shizuku == ShizukuState.NO_PERMISSION }
        assertEquals(ShizukuState.NO_PERMISSION, viewModel.state.value.shizuku)
        viewModel.onAppResume()
        await { bodiesOf("POST /v1/device/status").isNotEmpty() }
        assertTrue(bodiesOf("POST /v1/device/status").last(), bodiesOf("POST /v1/device/status").last().contains("\"shizuku\":\"no_permission\""))
    }

    @Test
    fun `allowing Shizuku asks Shizuku's own dialog and re-reads the state`() {
        shizuku.current = ShizukuState.NO_PERMISSION
        val viewModel = vm()
        viewModel.requestShizukuPermission()
        await { shizuku.permissionAsks > 0 }
        assertEquals(1, shizuku.permissionAsks)
        shizuku.current = ShizukuState.READY
        viewModel.refreshShizuku()
        await { viewModel.state.value.shizuku == ShizukuState.READY }
        assertEquals(ShizukuState.READY, viewModel.state.value.shizuku)
    }
}
