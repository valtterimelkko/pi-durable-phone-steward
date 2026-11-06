package com.example.phonesteward.ui

import android.content.Context
import android.os.Looper
import androidx.startup.InitializationProvider
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import com.example.phonesteward.android.AppGraph
import com.example.phonesteward.android.KeystoreIdentityStore
import com.example.phonesteward.logic.IdentityStore
import com.example.phonesteward.logic.PairedIdentity
import com.example.phonesteward.net.Proposal
import com.example.phonesteward.net.ProposalItem

/**
 * Defects the L4 end-to-end sweep found (each also has a device flow in `androidTest/.../e2e`):
 * the first-run server address, a stale card after a superseded approval, a message that cannot be
 * sent, an unreachable server at pairing, a job poller that hammered a failing server, and a revoked
 * token that only the next tap noticed.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class L4DefectsTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private var server: MockWebServer? = null
    private val requested = java.util.Collections.synchronizedList(mutableListOf<String>())
    private var viewModel: AppViewModel? = null

    @Volatile private var jobsStatus = 200
    @Volatile private var streamStatus = 200
    @Volatile private var decisionStatus = 200
    @Volatile private var deviceStatusStatus = 200

    private class MemoryIdentityStore(var identity: PairedIdentity?) : IdentityStore {
        override fun read() = identity
        override fun write(identity: PairedIdentity) { this.identity = identity }
        override fun clear() { identity = null }
    }

    private val rulesJson = """
        {"ruleVersion":3,"photoWindow":{"enabled":false,"reminderDays":90,"cutoffDays":120,"autoMode":false,
         "folders":["DCIM/Camera/"],"keepMarker":"Keep"},"batchesRunOneTap":0}
    """.trimIndent()

    private fun errorBody(status: Int) = when (status) {
        401 -> """{"error":{"code":"TOKEN_REVOKED","message":"revoked"}}"""
        409 -> """{"error":{"code":"PROPOSAL_NOT_OPEN","message":"superseded"}}"""
        else -> """{"error":{"code":"UNAVAILABLE","message":"backend restarting"}}"""
    }

    @Before
    fun setUp() {
        Robolectric.setupContentProvider(InitializationProvider::class.java, "${context.packageName}.androidx-startup")
        AppGraph.resetForTest()
        val mock = MockWebServer()
        mock.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty().substringBefore('?')
                requested += "${request.method} $path"
                fun answer(status: Int, ok: String) = MockResponse().setResponseCode(status)
                    .setHeader("Content-Type", "application/json").setBody(if (status == 200) ok else errorBody(status))
                return when {
                    path == "/v1/jobs" -> answer(jobsStatus, """{"jobs":[]}""")
                    path == "/v1/chat/stream" -> answer(streamStatus, "")
                    path == "/v1/proposals" -> answer(200, """{"proposals":[]}""")
                    path == "/v1/rules" -> answer(200, rulesJson)
                    path == "/v1/chat/history" -> answer(200, """{"events":[]}""")
                    path == "/v1/device/status" -> answer(deviceStatusStatus, """{"accepted":true}""")
                    path.endsWith("/decision") -> answer(decisionStatus, """{"decisionId":"dc-1","approvedItemIds":[]}""")
                    else -> answer(200, """{"accepted":true}""")
                }
            }
        }
        mock.start()
        server = mock
        useIdentity(PairedIdentity(mock.url("/").toString().trimEnd('/'), "t"))
    }

    private fun useIdentity(identity: PairedIdentity?) {
        AppGraph.identityStoreFactory = { MemoryIdentityStore(identity) }
        AppGraph.resetForTest()
    }

    @After
    fun tearDown() {
        viewModel?.stop()
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

    private fun vm() = AppViewModel(ApplicationProvider.getApplicationContext()).also { viewModel = it }

    private fun proposal() = Proposal(
        proposalId = "pr-1", createdAtMs = 1, title = "Trash old recordings",
        items = listOf(ProposalItem(itemId = "it-1", kind = "trash_media")),
    )

    @Test
    fun `a fresh unpaired app offers the default server address`() {
        useIdentity(null)
        assertEquals(AppGraph.DEFAULT_BASE_URL, vm().state.value.baseUrl)
    }

    @Test
    fun `a superseded approval reloads the list so the stale card goes`() {
        decisionStatus = 409
        val viewModel = vm()
        viewModel.decide(proposal(), approve = true, listOf("it-1"))
        await { viewModel.state.value.result != null }
        assertEquals("This proposal is no longer open.", viewModel.state.value.result!!.text)
        await { requested.contains("GET /v1/proposals") }
        assertTrue("the open list was fetched again after the 409", requested.contains("GET /v1/proposals"))
    }

    @Test
    fun `a message that cannot be sent says so calmly in Chat`() {
        useIdentity(PairedIdentity("http://127.0.0.1:1", "t"))
        val viewModel = vm()
        viewModel.send("Are you there?")
        await { viewModel.state.value.chatNotice != null }
        val notice = viewModel.state.value.chatNotice!!
        assertTrue(notice, notice.startsWith("Could not reach the steward"))
        assertFalse("no raw exception text: $notice", notice.contains("Failed to connect") || notice.contains("java."))
        // Sending again clears the old notice before trying.
        viewModel.dismissChatNotice()
        assertEquals(null, viewModel.state.value.chatNotice)
    }

    @Test
    fun `pairing against an address nobody answers is calm`() {
        useIdentity(null)
        val viewModel = vm()
        viewModel.pair("http://127.0.0.1:1", "K7Q4-9MZD", "Phone")
        await { viewModel.state.value.notice != null }
        val notice = viewModel.state.value.notice!!
        assertTrue(notice, notice.startsWith("Could not reach the server"))
        assertFalse("no raw exception text: $notice", notice.contains("Failed to connect") || notice.contains("java."))
    }

    @Test
    fun `a failing server is not hammered by the job poller`() {
        jobsStatus = 503
        streamStatus = 503
        val viewModel = vm()
        viewModel.start()
        Thread.sleep(2_500)
        viewModel.stop()
        val polls = requested.count { it == "GET /v1/jobs" }
        assertTrue("the poller asked $polls times in 2.5 s; it should back off", polls <= 2)
    }

    @Test
    fun `a revoked token found by the job poller returns the app to pairing`() {
        jobsStatus = 401
        val viewModel = vm()
        // Everything else answers, so only the poller can notice.
        viewModel.start()
        await(8_000) { !viewModel.state.value.paired }
        assertFalse("the app must leave the paired screens", viewModel.state.value.paired)
        assertEquals("The server no longer accepts this device. Pair again.", viewModel.state.value.notice)
    }

    @Test
    fun `a revoked token found by the chat stream returns the app to pairing`() {
        streamStatus = 401
        val viewModel = vm()
        viewModel.start()
        await(8_000) { !viewModel.state.value.paired }
        assertFalse(viewModel.state.value.paired)
    }

    @Test
    fun `a revoked token found by the device status post returns the app to pairing`() {
        deviceStatusStatus = 401
        val viewModel = vm()
        // The last post said "everything granted"; the live phone differs, so resume posts again.
        viewModel.postGate.markPosted(com.example.phonesteward.net.DevicePermissions(true, true, true, true, true))
        viewModel.onAppResume(com.example.phonesteward.logic.Onboarding.Snapshot(false, false, false, false, false, autoModeOn = false))
        await(8_000) { !viewModel.state.value.paired }
        assertFalse(viewModel.state.value.paired)
    }
}
