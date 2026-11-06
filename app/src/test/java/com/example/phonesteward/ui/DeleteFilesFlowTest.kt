package com.example.phonesteward.ui

import android.content.Context
import android.os.Looper
import androidx.startup.InitializationProvider
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.time.ZoneOffset
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import com.example.phonesteward.android.AppGraph
import com.example.phonesteward.android.KeystoreIdentityStore
import com.example.phonesteward.logic.ActionResult
import com.example.phonesteward.logic.IdentityStore
import com.example.phonesteward.logic.PairedIdentity
import com.example.phonesteward.logic.files.StewardTrash
import com.example.phonesteward.net.Proposal
import com.example.phonesteward.net.ProposalItem

/**
 * A10.1 on the phone, through the real view model: approving a `delete_files` proposal asks ONE in-app question, moves the
 * items into the Steward trash, reports `done` with `trashedBytes`, and says so in plain words; Empty Steward trash asks once.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class DeleteFilesFlowTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private var server: MockWebServer? = null
    private lateinit var root: File
    private val now = 1_791_460_000_000L // 2026-10-08

    private val executionBodies = java.util.Collections.synchronizedList(mutableListOf<String>())
    private val statusBodies = java.util.Collections.synchronizedList(mutableListOf<String>())
    @Volatile private var approvedIds = listOf<String>()
    private fun approvedJson() = approvedIds.joinToString(",", "[", "]") { "\"$it\"" }

    private class MemoryIdentityStore(var identity: PairedIdentity?) : IdentityStore {
        override fun read() = identity
        override fun write(identity: PairedIdentity) { this.identity = identity }
        override fun clear() { identity = null }
    }

    @Before
    fun setUp() {
        root = tmp.newFolder("sdcard")
        Robolectric.setupContentProvider(InitializationProvider::class.java, "${context.packageName}.androidx-startup")
        AppGraph.resetForTest()
        val mock = MockWebServer()
        mock.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty().substringBefore('?')
                val body = when {
                    path == "/v1/proposals" -> """{"proposals":[]}"""
                    path == "/v1/rules" -> """{"ruleVersion":3,"photoWindow":{"enabled":false,"reminderDays":90,"cutoffDays":120,"autoMode":false,"folders":["DCIM/Camera/"],"keepMarker":"Keep"},"batchesRunOneTap":0}"""
                    path == "/v1/chat/history" -> """{"events":[]}"""
                    path.endsWith("/decision") -> """{"decisionId":"dc-f","approvedItemIds":${approvedJson()},"expiresAtMs":9999999999999}"""
                    path.contains("/decisions/") -> """{"valid":true,"proposalId":"pr-files","approvedItemIds":${approvedJson()},"expiresAtMs":9999999999999}"""
                    path.endsWith("/execution") -> { executionBodies += request.body.readUtf8(); """{"accepted":true}""" }
                    path == "/v1/device/status" -> { statusBodies += request.body.readUtf8(); """{"accepted":true}""" }
                    else -> """{"accepted":true}"""
                }
                return MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json").setBody(body)
            }
        }
        mock.start()
        server = mock
        AppGraph.identityStoreFactory = { MemoryIdentityStore(PairedIdentity(mock.url("/").toString().trimEnd('/'), "t")) }
    }

    @After
    fun tearDown() {
        server?.shutdown()
        AppGraph.identityStoreFactory = { KeystoreIdentityStore(it) }
        AppGraph.resetForTest()
    }

    private fun await(timeoutMs: Long = 5_000, condition: () -> Boolean) {
        val until = System.currentTimeMillis() + timeoutMs
        while (!condition() && System.currentTimeMillis() < until) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(50)
        }
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun vm(): AppViewModel = AppViewModel(ApplicationProvider.getApplicationContext()).also {
        it.stewardTrash = StewardTrash(root, ZoneOffset.UTC) { now }
        it.allFilesProbe = { true }
    }

    private fun file(rel: String, bytes: Int) = File(root, rel).also { it.parentFile.mkdirs(); it.writeBytes(ByteArray(bytes) { 1 }) }

    private fun item(id: String, path: String, trash: StewardTrash = StewardTrash(root, ZoneOffset.UTC) { now }): ProposalItem {
        val s = trash.stat(path)!!
        return ProposalItem(id, "delete_files", buildJsonObject {
            put("path", path); put("type", s.type); put("sizeBytes", s.sizeBytes); put("modifiedMs", s.modifiedMs)
            s.fileCount?.let { put("fileCount", it) }
        })
    }

    private fun proposal(vararg items: ProposalItem) = Proposal(proposalId = "pr-files", title = "Free maps", items = items.toList())

    private fun reported(): Map<String, JsonObject> {
        val body = kotlinx.serialization.json.Json.parseToJsonElement(executionBodies.last()).jsonObject
        return body["items"]!!.jsonArray.associate { it.jsonObject["itemId"]!!.jsonPrimitive.content to it.jsonObject }
    }

    @Test
    fun `approving files asks once, moves them to the Steward trash and reports done with their size`() {
        file("OfflineMaps/230606/a.bin", 1000)
        file("OfflineMaps/230606/b.bin", 1600)
        file("OfflineMaps/230701/c.bin", 400)
        val p = proposal(item("f1", "OfflineMaps/230606"), item("f2", "OfflineMaps/230701"))
        approvedIds = listOf("f1", "f2")
        val viewModel = vm()
        viewModel.decide(p, approve = true, approvedIds)
        await { viewModel.state.value.confirm != null }
        val confirm = viewModel.state.value.confirm!!
        assertEquals("Move 2 folders (3.0 KB) to the Steward trash?", confirm.title)
        assertEquals("nothing moves before the owner answers", true, File(root, "OfflineMaps/230606/a.bin").exists())
        viewModel.answerConfirm(true)
        await { viewModel.state.value.result != null }
        assertFalse(File(root, "OfflineMaps/230606").exists())
        assertFalse(File(root, "OfflineMaps/230701").exists())
        assertEquals(1000L, File(root, ".StewardTrash/2026-10-08/pr-files/OfflineMaps/230606/a.bin").length())
        val r = reported()
        assertEquals("done", r.getValue("f1")["outcome"]!!.jsonPrimitive.content)
        assertEquals("2600", r.getValue("f1")["trashedBytes"]!!.jsonPrimitive.content)
        assertEquals("0", r.getValue("f1")["bytesFreed"]!!.jsonPrimitive.content)
        assertEquals("400", r.getValue("f2")["trashedBytes"]!!.jsonPrimitive.content)
        assertEquals(
            "Moved 2 folders (3.0 KB) to the Steward trash. Empty it on Status to free the space now.",
            viewModel.state.value.result!!.text,
        )
        assertNull("the dialog is gone", viewModel.state.value.confirm)
        // Status now knows the trash holds 3000 bytes, and told the server.
        await { viewModel.state.value.stewardTrash.bytes == 3000L }
        assertEquals(3000L, viewModel.state.value.stewardTrash.bytes)
        await { statusBodies.any { it.contains("\"bytes\":3000") } }
        assertTrue(statusBodies.toString(), statusBodies.any { it.contains("\"stewardTrash\":{\"bytes\":3000") })
    }

    @Test
    fun `declining the question moves nothing and reports cancelled`() {
        file("OfflineMaps/230606/a.bin", 1000)
        val p = proposal(item("f1", "OfflineMaps/230606"))
        approvedIds = listOf("f1")
        val viewModel = vm()
        viewModel.decide(p, approve = true, approvedIds)
        await { viewModel.state.value.confirm != null }
        viewModel.answerConfirm(false)
        await { viewModel.state.value.result != null }
        assertTrue(File(root, "OfflineMaps/230606/a.bin").exists())
        assertFalse(File(root, ".StewardTrash").exists())
        assertEquals("cancelled", reported().getValue("f1")["outcome"]!!.jsonPrimitive.content)
        assertEquals("Nothing was done — approve again to retry.", viewModel.state.value.result!!.text)
    }

    @Test
    fun `an item that changed after the review is refused before any question`() {
        file("OfflineMaps/230606/a.bin", 1000)
        val p = proposal(item("f1", "OfflineMaps/230606"))
        file("OfflineMaps/230606/b.bin", 10) // arrives after the review
        approvedIds = listOf("f1")
        val viewModel = vm()
        viewModel.decide(p, approve = true, approvedIds)
        await { viewModel.state.value.result != null }
        assertNull("no question for a refused item", viewModel.state.value.confirm)
        assertEquals("failed", reported().getValue("f1")["outcome"]!!.jsonPrimitive.content)
        assertEquals(
            "This item changed since the review, so nothing was moved. The steward will propose it again.",
            viewModel.state.value.result!!.text,
        )
        assertTrue(File(root, "OfflineMaps/230606/a.bin").exists())
    }

    @Test
    fun `an item that changes while the owner reads the question is left alone`() {
        file("OfflineMaps/230606/a.bin", 1000)
        val p = proposal(item("f1", "OfflineMaps/230606"))
        approvedIds = listOf("f1")
        val viewModel = vm()
        viewModel.decide(p, approve = true, approvedIds)
        await { viewModel.state.value.confirm != null }
        file("OfflineMaps/230606/late.bin", 5)
        viewModel.answerConfirm(true)
        await { viewModel.state.value.result != null }
        assertTrue(File(root, "OfflineMaps/230606/late.bin").exists())
        assertEquals("failed", reported().getValue("f1")["outcome"]!!.jsonPrimitive.content)
        assertEquals(ActionResult.Kind.ERROR, viewModel.state.value.result!!.kind)
    }

    @Test
    fun `an unticked file is neither moved nor reported`() {
        file("Download/a.bin", 100)
        file("Download/b.bin", 100)
        val p = proposal(item("f1", "Download/a.bin"), item("f2", "Download/b.bin"))
        approvedIds = listOf("f1")
        val viewModel = vm()
        viewModel.decide(p, approve = true, approvedIds)
        await { viewModel.state.value.confirm != null }
        viewModel.answerConfirm(true)
        await { viewModel.state.value.result != null }
        assertTrue(File(root, "Download/b.bin").exists())
        assertEquals(setOf("f1"), reported().keys)
    }

    // ---- Empty Steward trash ----

    @Test
    fun `Empty Steward trash asks once, deletes everything in it and says how much came back`() {
        file(".StewardTrash/2026-10-01/pr-old/OfflineMaps/x.bin", 1500)
        file("Download/keep.bin", 77)
        val viewModel = vm()
        viewModel.refreshTrash()
        await { viewModel.state.value.stewardTrash.bytes == 1500L }
        viewModel.emptyTrash()
        await { viewModel.state.value.confirm != null }
        val confirm = viewModel.state.value.confirm!!
        assertEquals("Empty the Steward trash?", confirm.title)
        assertTrue(confirm.body, confirm.body.contains("1.5 KB"))
        assertTrue(confirm.destructive)
        viewModel.answerConfirm(true)
        await { viewModel.state.value.trashResult != null }
        assertEquals("Steward trash emptied: 1.5 KB freed.", viewModel.state.value.trashResult!!.text)
        assertEquals(0L, viewModel.state.value.stewardTrash.bytes)
        assertFalse(File(root, ".StewardTrash/2026-10-01").exists())
        assertEquals(77L, File(root, "Download/keep.bin").length())
        await { statusBodies.any { it.contains("\"stewardTrash\":{\"bytes\":0") } }
        assertTrue(statusBodies.toString(), statusBodies.any { it.contains("\"stewardTrash\":{\"bytes\":0") })
    }

    @Test
    fun `declining Empty Steward trash keeps everything`() {
        file(".StewardTrash/2026-10-01/pr-old/x.bin", 1500)
        val viewModel = vm()
        viewModel.emptyTrash()
        await { viewModel.state.value.confirm != null }
        viewModel.answerConfirm(false)
        await { viewModel.state.value.confirm == null }
        Thread.sleep(200)
        assertTrue(File(root, ".StewardTrash/2026-10-01/pr-old/x.bin").exists())
        assertNull(viewModel.state.value.trashResult)
    }

    @Test
    fun `Empty Steward trash on an empty trash does not ask`() {
        val viewModel = vm()
        viewModel.emptyTrash()
        await { viewModel.state.value.trashResult != null }
        assertEquals("The Steward trash is already empty.", viewModel.state.value.trashResult!!.text)
        assertNull(viewModel.state.value.confirm)
    }
}
