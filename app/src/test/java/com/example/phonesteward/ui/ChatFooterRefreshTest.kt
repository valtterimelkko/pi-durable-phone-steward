package com.example.phonesteward.ui

import android.content.Context
import android.os.Looper
import androidx.startup.InitializationProvider
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
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
import com.example.phonesteward.logic.PairedIdentity
import com.example.phonesteward.net.ChatEvent

/**
 * A11.4: the Chat footer never shows a reading from before a compaction. It reloads when a steward turn ends, after
 * Compact now and Start fresh, and when Chat comes back to the foreground.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class ChatFooterRefreshTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private var server: MockWebServer? = null
    private val settingsReads = AtomicInteger(0)

    @Volatile private var settingsTokens = 768_000L
    @Volatile private var proposalsStatus = 200

    private class MemoryIdentityStore(var identity: PairedIdentity?) : IdentityStore {
        override fun read() = identity
        override fun write(identity: PairedIdentity) { this.identity = identity }
        override fun clear() { identity = null }
    }

    @Before
    fun setUp() {
        Robolectric.setupContentProvider(InitializationProvider::class.java, "${context.packageName}.androidx-startup")
        AppGraph.resetForTest()
        val mock = MockWebServer()
        mock.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty().substringBefore('?')
                if (path == "/v1/proposals" && proposalsStatus != 200) {
                    return MockResponse().setResponseCode(proposalsStatus).setHeader("Content-Type", "application/json")
                        .setBody("""{"error":{"code":"UNAVAILABLE","message":"restarting"}}""")
                }
                val body = when (path) {
                    "/v1/proposals" -> """{"proposals":[]}"""
                    "/v1/agent/settings" -> {
                        settingsReads.incrementAndGet()
                        """{"conversationId":"main","model":{"provider":"zai","id":"glm-5.3-flash"},
                           "context":{"tokens":$settingsTokens,"budget":1000000,"compactAtTokens":750000}}"""
                    }
                    "/v1/agent/models" -> """{"current":{"provider":"zai","id":"glm-5.3-flash"},"models":[]}"""
                    "/v1/rules" -> """{"ruleVersion":3,"photoWindow":{"enabled":false,"reminderDays":90,"cutoffDays":120,"autoMode":false,"folders":["DCIM/Camera/"],"keepMarker":"Keep"},"batchesRunOneTap":0}"""
                    "/v1/chat/history" -> """{"events":[]}"""
                    else -> """{"accepted":true}"""
                }
                val code = if (path == "/v1/chat/compact" || path == "/v1/chat/fresh") 202 else 200
                return MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)
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

    private fun newViewModel() = AppViewModel(ApplicationProvider.getApplicationContext())

    private fun chatEvent(type: String, status: String? = null) = ChatEvent(
        id = 1L, type = type,
        data = status?.let { buildJsonObject { put("status", it) } } ?: buildJsonObject { },
    )

    @Test
    fun `a finished turn reloads the footer even when the proposals refresh fails`() {
        val viewModel = newViewModel()
        viewModel.refresh()
        await { viewModel.state.value.agent?.context?.tokens == 768_000L }
        // The proposals refresh the run event triggers now fails; the footer must still catch up.
        proposalsStatus = 500
        settingsTokens = 192_000L
        viewModel.onChatEvent(chatEvent("run", "ended"))
        await { viewModel.state.value.agent?.context?.tokens == 192_000L }
        assertEquals("always a whole share of the window", 19, ContextFooterPercent(viewModel))
    }

    private fun ContextFooterPercent(viewModel: AppViewModel): Int =
        com.example.phonesteward.logic.context.ContextFooter.percent(viewModel.state.value.agent?.context)!!

    @Test
    fun `Compact now reloads the footer with the compacted reading`() {
        val viewModel = newViewModel()
        viewModel.refresh()
        await { viewModel.state.value.agent?.context?.tokens == 768_000L }
        settingsTokens = 256_000L
        viewModel.compactNow()
        await { viewModel.state.value.agent?.context?.tokens == 256_000L }
        assertEquals("always a whole share of the window", 26, ContextFooterPercent(viewModel))
    }

    @Test
    fun `Start fresh reloads the footer with the fresh reading`() {
        val viewModel = newViewModel()
        viewModel.refresh()
        await { viewModel.state.value.agent?.context?.tokens == 768_000L }
        settingsTokens = 2_000L
        viewModel.startFresh()
        await { viewModel.state.value.confirm != null }
        viewModel.answerConfirm(true)
        await { viewModel.state.value.agent?.context?.tokens == 2_000L }
    }

    @Test
    fun `two foreground triggers in the same moment reload once`() {
        val viewModel = newViewModel()
        var now = 0L
        viewModel.chatForegroundGate = com.example.phonesteward.logic.context.ChatForegroundGate(
            nowMs = { now },
            minGapMs = 1_000,
        )
        viewModel.onChatForeground()
        await { settingsReads.get() == 1 }
        // The tab trigger and the resume trigger are one foreground event.
        viewModel.onChatForeground()
        Thread.sleep(200)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("one reload per foreground event", 1, settingsReads.get())
        now = 2_000
        viewModel.onChatForeground()
        await { settingsReads.get() == 2 }
    }

    @Test
    fun `Chat coming back to the foreground reloads the footer`() {
        val viewModel = newViewModel()
        viewModel.refresh()
        await { viewModel.state.value.agent?.context?.tokens == 768_000L }
        settingsTokens = 128_000L
        viewModel.onChatForeground()
        await { viewModel.state.value.agent?.context?.tokens == 128_000L }
    }
}
