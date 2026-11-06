package com.example.phonesteward.ui

import android.content.Context
import android.os.Looper
import androidx.startup.InitializationProvider
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import com.example.phonesteward.android.AppGraph
import com.example.phonesteward.android.KeystoreIdentityStore
import com.example.phonesteward.logic.IdentityStore
import com.example.phonesteward.logic.Onboarding
import com.example.phonesteward.logic.PairedIdentity
import com.example.phonesteward.net.ChatEvent

/**
 * N2 + N7: the screens re-read permissions, proposals and rules on resume
 * and on the matching SSE events — a permission granted in the background
 * turns its row green after resume, and a new proposal appears WITHOUT a
 * full app restart.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class RefreshFlowTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private var server: MockWebServer? = null
    private val requested = java.util.Collections.synchronizedList(mutableListOf<String>())
    private val executionBodies = java.util.Collections.synchronizedList(mutableListOf<String>())

    /** The ids the mock steward's decision approves (a decision names the ticked items). */
    @Volatile private var approvedIds = listOf("it-1")
    @Volatile private var decidedProposal = "pr-ac"
    private fun approvedJson() = approvedIds.joinToString(",", "[", "]") { "\"$it\"" }

    private class MemoryIdentityStore(var identity: PairedIdentity?) : IdentityStore {
        override fun read() = identity
        override fun write(identity: PairedIdentity) { this.identity = identity }
        override fun clear() { identity = null }
    }

    @Volatile private var proposalJson = """
        {"proposals":[{"proposalId":"pr-live","createdAtMs":1,"source":"agent","title":"Fresh from the server",
         "bytesEstimate":0,"status":"open","items":[]}]}
    """.trimIndent()

    private val rulesJson = """
        {"ruleVersion":3,"photoWindow":{"enabled":false,"reminderDays":90,"cutoffDays":120,"autoMode":false,
         "folders":["DCIM/Camera/"],"keepMarker":"Keep"},"batchesRunOneTap":0}
    """.trimIndent()

    @Before
    fun setUp() {
        Robolectric.setupContentProvider(InitializationProvider::class.java, "${context.packageName}.androidx-startup")
        AppGraph.resetForTest()
        val mock = MockWebServer()
        mock.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty().substringBefore('?')
                requested += "${request.method} $path"
                val body = when {
                    path == "/v1/proposals" -> proposalJson
                    path == "/v1/rules" -> rulesJson
                    path == "/v1/chat/history" -> """{"events":[]}"""
                    path.endsWith("/decision") ->
                        """{"decisionId":"dc-ac","approvedItemIds":${approvedJson()},"expiresAtMs":9999999999999}"""
                    path.contains("/decisions/") ->
                        """{"valid":true,"proposalId":"$decidedProposal","approvedItemIds":${approvedJson()},"expiresAtMs":9999999999999}"""
                    path.endsWith("/execution") -> {
                        executionBodies += request.body.readUtf8()
                        """{"accepted":true}"""
                    }
                    else -> """{"accepted":true}"""
                }
                return MockResponse().setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody(body)
            }
        }
        mock.start()
        server = mock
        AppGraph.identityStoreFactory = {
            MemoryIdentityStore(PairedIdentity(mock.url("/").toString().trimEnd('/'), "t"))
        }
    }

    @After
    fun tearDown() {
        server?.shutdown()
        AppGraph.identityStoreFactory = { KeystoreIdentityStore(it) }
        AppGraph.resetForTest()
    }

    /** Drains the main looper until [condition] holds or the timeout passes. */
    private fun await(timeoutMs: Long = 5_000, condition: () -> Boolean) {
        val until = System.currentTimeMillis() + timeoutMs
        while (!condition() && System.currentTimeMillis() < until) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(50)
        }
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun vm() = AppViewModel(ApplicationProvider.getApplicationContext())

    @Test
    fun `an all-caches approval with all-files denied launches nothing and says why`() {
        // the RUNTIME planner gate must be the real
        // Permissions.allFilesGranted() — under Robolectric it reads denied,
        // so nothing may launch and the notice must carry the reason.
        val viewModel = vm()
        var allCachesLaunched = false
        viewModel.executionHooks = object : AppViewModel.ExecutionHooks {
            override fun launchTrash(uris: List<android.net.Uri>) =
                java.util.concurrent.CompletableFuture.completedFuture(0)
            override fun launchUninstall(packageName: String) =
                java.util.concurrent.CompletableFuture.completedFuture(0)
            override fun beginCacheClear(packageName: String) =
                java.util.concurrent.CompletableFuture.completedFuture(
                    com.example.phonesteward.logic.exec.CacheClearReading(null, null, false),
                )
            override fun beginAllCaches(item: com.example.phonesteward.logic.exec.ProposalExecutionPlanner.AllCachesPlan) =
                java.util.concurrent.CompletableFuture.completedFuture(
                    com.example.phonesteward.logic.exec.AllCachesOutcome.Verdict("done", -1, 1L),
                ).also { allCachesLaunched = true }
        }
        viewModel.decide(allCachesProposal(), approve = true, listOf("it-1"))
        await { viewModel.state.value.result != null }
        assertTrue("nothing may launch without all-files access", !allCachesLaunched)
        val result = viewModel.state.value.result!!
        // N16: the line is a result on Proposals (where the action happened), not a Status notice.
        assertTrue("the result names the missing permission: " + result.text, result.text.contains("all-files"))
        assertEquals(com.example.phonesteward.logic.ActionResult.Kind.ERROR, result.kind)
        assertEquals(null, viewModel.state.value.notice)
    }

    private fun allCachesProposal() = com.example.phonesteward.net.Proposal(
        proposalId = "pr-ac",
        createdAtMs = 1,
        source = "agent",
        title = "Clear caches",
        bytesEstimate = 0,
        status = "open",
        items = listOf(
            com.example.phonesteward.net.ProposalItem(
                itemId = "it-1",
                kind = "clear_all_caches",
                target = buildJsonObject {
                    put(
                        "apps",
                        kotlinx.serialization.json.JsonArray(emptyList()),
                    )
                },
            ),
        ),
    )

    // ---- 0.2.4: the outcome of an uninstall is the truth on the phone ----

    private fun noLaunchHooks(onUninstall: (String) -> Int) = object : AppViewModel.ExecutionHooks {
        override fun launchTrash(uris: List<android.net.Uri>) =
            java.util.concurrent.CompletableFuture.completedFuture(0)
        override fun launchUninstall(packageName: String) =
            java.util.concurrent.CompletableFuture.completedFuture(onUninstall(packageName))
        override fun beginCacheClear(packageName: String) =
            java.util.concurrent.CompletableFuture.completedFuture(
                com.example.phonesteward.logic.exec.CacheClearReading(null, null, false),
            )
        override fun beginAllCaches(item: com.example.phonesteward.logic.exec.ProposalExecutionPlanner.AllCachesPlan) =
            java.util.concurrent.CompletableFuture.completedFuture(
                com.example.phonesteward.logic.exec.AllCachesOutcome.Verdict("failed", null, null),
            )
    }

    private fun uninstallProposal(count: Int) = com.example.phonesteward.net.Proposal(
        proposalId = "pr-un", createdAtMs = 1, source = "agent", title = "Remove apps", bytesEstimate = 0, status = "open",
        items = (1..count).map {
            com.example.phonesteward.net.ProposalItem(
                itemId = "u$it", kind = "uninstall_app",
                target = buildJsonObject { put("packageName", "com.invented.app$it") },
            )
        },
    )

    private fun executionItems(): Map<String, String> {
        val body = kotlinx.serialization.json.Json.parseToJsonElement(executionBodies.last()) as kotlinx.serialization.json.JsonObject
        return (body["items"] as kotlinx.serialization.json.JsonArray).associate {
            val o = it as kotlinx.serialization.json.JsonObject
            (o["itemId"] as kotlinx.serialization.json.JsonPrimitive).content to (o["outcome"] as kotlinx.serialization.json.JsonPrimitive).content
        }
    }

    @Test
    fun `an approved uninstall batch reports what is really gone, whatever the dialog code says`() {
        // 5 apps: u1-u2 are removed by the owner, u3 is kept, u4 was already removed by hand, u5 is removed.
        val installed = mutableSetOf("com.invented.app1", "com.invented.app2", "com.invented.app3", "com.invented.app5")
        val dialogs = mutableListOf<String>()
        approvedIds = (1..5).map { "u$it" }
        decidedProposal = "pr-un"
        val viewModel = vm()
        viewModel.uninstallFlow = com.example.phonesteward.logic.exec.UninstallFlow(
            isInstalled = { it in installed },
            sizeBytes = { 1_000L },
            sleep = { },
            pollMs = 100, settleAfterCancelMs = 200, settleOtherwiseMs = 200,
        )
        viewModel.executionHooks = noLaunchHooks { pkg ->
            dialogs += pkg
            if (pkg != "com.invented.app3") installed -= pkg
            0 // RESULT_CANCELED, as ACTION_DELETE always answers
        }
        viewModel.decide(uninstallProposal(5), approve = true, approvedIds)
        await { viewModel.state.value.result != null }
        assertEquals("one dialog per installed app, none for the app already gone",
            listOf("com.invented.app1", "com.invented.app2", "com.invented.app3", "com.invented.app5"), dialogs)
        assertEquals(
            mapOf("u1" to "done", "u2" to "done", "u3" to "cancelled", "u4" to "done", "u5" to "done"),
            executionItems(),
        )
        assertEquals("Uninstalled 4 of 5 apps.", viewModel.state.value.result!!.text)
    }

    @Test
    fun `an approval whose every file fails before any dialog says nothing was deleted`() {
        // Robolectric has no MediaStore rows, so the identity check refuses every item (the owner's 12-file case).
        approvedIds = (1..12).map { "t$it" }
        decidedProposal = "pr-tr"
        val viewModel = vm()
        var trashLaunched = false
        viewModel.executionHooks = object : AppViewModel.ExecutionHooks by noLaunchHooks({ 0 }) {
            override fun launchTrash(uris: List<android.net.Uri>) =
                java.util.concurrent.CompletableFuture.completedFuture(0).also { trashLaunched = true }
        }
        val proposal = com.example.phonesteward.net.Proposal(
            proposalId = "pr-tr", createdAtMs = 1, source = "agent", title = "Trash old files", bytesEstimate = 0, status = "open",
            items = (1..12).map {
                com.example.phonesteward.net.ProposalItem(
                    itemId = "t$it", kind = "trash_media",
                    target = buildJsonObject {
                        put("mediaId", "external_primary:images:$it")
                        put("relativePath", "DCIM/Camera/")
                        put("displayName", "IMG_$it.jpg")
                        put("sizeBytes", 10)
                    },
                )
            },
        )
        viewModel.decide(proposal, approve = true, approvedIds)
        await { viewModel.state.value.result != null }
        assertTrue("no dialog may open for refused items", !trashLaunched)
        val result = viewModel.state.value.result!!
        assertEquals(
            "These files changed since the review, so nothing was deleted. The steward will propose them again.",
            result.text,
        )
        assertEquals(com.example.phonesteward.logic.ActionResult.Kind.ERROR, result.kind)
        assertEquals(12, executionItems().values.count { it == "failed" })
    }

    @Test
    fun `a proposal event on the stream reloads proposals without a restart`() {
        val viewModel = vm()
        assertEquals(0, viewModel.state.value.proposals.size)
        viewModel.onChatEvent(
            ChatEvent(
                id = 1,
                type = "proposal",
                data = buildJsonObject {
                    put("proposalId", "pr-live")
                    put("title", "Fresh from the server")
                },
            ),
        )
        await { viewModel.state.value.proposals.isNotEmpty() }
        assertEquals("pr-live", viewModel.state.value.proposals.single().proposalId)
    }

    @Test
    fun `a run event also re-reads the server data`() {
        val viewModel = vm()
        viewModel.onChatEvent(
            ChatEvent(
                id = 2,
                type = "run",
                data = buildJsonObject {
                    put("status", "ended")
                    put("reason", "owner")
                },
            ),
        )
        await { viewModel.state.value.rules != null }
        assertNotNull(viewModel.state.value.rules)
    }

    @Test
    fun `on resume the permission snapshot is re-read into state`() {
        val viewModel = vm()
        val granted = Onboarding.Snapshot(
            batteryUnrestricted = true,
            usageAccess = true,
            allFiles = true,
            mediaRead = true,
            manageMedia = true,
            autoModeOn = false,
        )
        viewModel.onAppResume(granted)
        await { viewModel.state.value.permissions != null }
        assertEquals(granted, viewModel.state.value.permissions)
    }

    @Test
    fun `resume also re-reads server data`() {
        val viewModel = vm()
        viewModel.onAppResume(
            Onboarding.Snapshot(true, true, true, true, false, autoModeOn = false),
        )
        await { viewModel.state.value.proposals.isNotEmpty() && viewModel.state.value.rules != null }
        assertEquals("pr-live", viewModel.state.value.proposals.single().proposalId)
    }

    @Test
    fun `N16 a rejected proposal says so on the Proposals result card`() {
        val viewModel = vm()
        viewModel.decide(allCachesProposal(), approve = false, listOf("it-1"))
        await { viewModel.state.value.result != null }
        val result = viewModel.state.value.result!!
        assertEquals("Proposal rejected.", result.text)
        assertEquals(com.example.phonesteward.logic.ActionResult.Kind.INFO, result.kind)
    }

    @Test
    fun `N16 the result can be dismissed`() {
        val viewModel = vm()
        viewModel.decide(allCachesProposal(), approve = false, listOf("it-1"))
        await { viewModel.state.value.result != null }
        viewModel.clearResult()
        assertEquals(null, viewModel.state.value.result)
    }

    @Test
    fun `N10 a burst of refreshes reads the server once now and once after the gap`() {
        val viewModel = vm()
        repeat(18) { viewModel.refresh() }
        // The trailing refresh waits on the (paused, virtual-clock) main looper.
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(700))
        await(2_000) { requested.count { it == "GET /v1/proposals" } >= 2 }
        Thread.sleep(300)
        assertEquals(
            "18 refresh requests -> one now, one trailing",
            2,
            requested.count { it == "GET /v1/proposals" },
        )
    }

    @Test
    fun `N9 permissions that changed since the last post are posted on resume`() {
        val viewModel = vm()
        // The last post said "everything granted"; the live phone (Robolectric) has nothing granted.
        viewModel.postGate.markPosted(
            com.example.phonesteward.net.DevicePermissions(true, true, true, true, true),
        )
        val live = Onboarding.Snapshot(false, false, false, false, false, autoModeOn = false)
        viewModel.onAppResume(live)
        await { requested.contains("POST /v1/device/status") }
        assertEquals(1, requested.count { it == "POST /v1/device/status" })
        // Same permissions again: no second post.
        viewModel.onAppResume(live)
        Thread.sleep(700)
        await(1_000) { false }
        assertEquals(1, requested.count { it == "POST /v1/device/status" })
    }

    // ---- N31 (A10.8): apps already gone ----

    private fun uninstallJson(vararg ids: Pair<String, String>) = """
        {"proposals":[{"proposalId":"pr-un","createdAtMs":1,"source":"agent","title":"Remove apps",
         "bytesEstimate":0,"status":"open","items":[${ids.joinToString(",") { (id, pkg) ->
            """{"itemId":"$id","kind":"uninstall_app","target":{"packageName":"$pkg"},"bytesEstimate":0}"""
        }}]}]}
    """.trimIndent()

    @Test
    fun `N31 refresh marks the uninstall items whose app is already gone`() {
        proposalJson = uninstallJson("u1" to "com.invented.app1", "u2" to "com.invented.app2", "u3" to "com.invented.app3")
        val viewModel = vm()
        viewModel.installedProbe = { it == "com.invented.app2" }
        viewModel.refresh()
        await { viewModel.state.value.proposals.isNotEmpty() }
        await { viewModel.state.value.removedItemIds.isNotEmpty() }
        assertEquals(setOf("u1", "u3"), viewModel.state.value.removedItemIds)
        assertTrue("one app is still installed: the owner decides, nothing is posted", executionBodies.isEmpty())
    }

    @Test
    fun `N31 a proposal whose apps are all gone settles itself with one quiet report and no dialog`() {
        proposalJson = uninstallJson("u1" to "com.invented.app1", "u2" to "com.invented.app2")
        approvedIds = listOf("u1", "u2")
        decidedProposal = "pr-un"
        val viewModel = vm()
        viewModel.installedProbe = { false }
        viewModel.uninstallFlow = com.example.phonesteward.logic.exec.UninstallFlow(
            isInstalled = { false }, sizeBytes = { null }, sleep = { },
        )
        var launched = false
        viewModel.executionHooks = noLaunchHooks { launched = true; 0 }
        viewModel.refresh()
        await { executionBodies.isNotEmpty() }
        assertEquals(mapOf("u1" to "done", "u2" to "done"), executionItems())
        assertTrue("no uninstall dialog for apps that are gone", !launched)
        assertEquals("quiet: no result card", null, viewModel.state.value.result)
        // The mock keeps listing the proposal; it is settled once, not on every refresh.
        viewModel.refresh()
        Thread.sleep(800)
        await(1_000) { false }
        assertEquals(1, executionBodies.size)
    }

    @Test
    fun `N31 a quiet settlement never opens a dialog even if the app came back`() {
        proposalJson = uninstallJson("u1" to "com.invented.app1")
        approvedIds = listOf("u1")
        decidedProposal = "pr-un"
        val viewModel = vm()
        // Gone at the first look (the probe), installed again when the settlement runs (the flow).
        viewModel.installedProbe = { false }
        var launched = false
        viewModel.uninstallFlow = com.example.phonesteward.logic.exec.UninstallFlow(
            isInstalled = { true }, sizeBytes = { null }, sleep = { }, settleAfterCancelMs = 0, settleAfterOkMs = 0, settleOtherwiseMs = 0,
        )
        viewModel.executionHooks = noLaunchHooks { launched = true; 0 }
        viewModel.refresh()
        await { executionBodies.isNotEmpty() }
        assertTrue("quiet mode has no hooks to launch with", !launched)
        assertEquals(mapOf("u1" to "cancelled"), executionItems())
    }
}
