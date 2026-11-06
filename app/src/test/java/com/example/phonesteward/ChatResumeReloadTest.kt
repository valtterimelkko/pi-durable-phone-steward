package com.example.phonesteward

import android.content.Context
import android.os.Looper
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.Lifecycle
import androidx.startup.InitializationProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.WorkManager
import java.util.concurrent.atomic.AtomicInteger
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import com.example.phonesteward.android.AppGraph
import com.example.phonesteward.android.KeystoreIdentityStore
import com.example.phonesteward.logic.IdentityStore
import com.example.phonesteward.logic.PairedIdentity

/**
 * Correction 1 (review finding 2): A11.4's footer reload also has to fire when the app returns to the foreground with
 * Chat already showing — the case the coalesced proposal refresh cannot cover, because a failing proposals call cuts
 * its `readAgent` short. The proposals route answers 500 here on purpose, so a settings read can only come from the
 * dedicated trigger.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class ChatResumeReloadTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private var server: MockWebServer? = null
    private val settingsReads = AtomicInteger(0)

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
                return when (path) {
                    // The failing proposals call is the point: doRefresh returns here and never reaches readAgent.
                    "/v1/proposals" -> json(500, """{"error":{"code":"UNAVAILABLE","message":"restarting"}}""")
                    "/v1/agent/settings" -> {
                        settingsReads.incrementAndGet()
                        json(200, """{"conversationId":"main","model":{"provider":"zai","id":"glm-5.3-flash"},"context":{"tokens":10,"budget":1000}}""")
                    }
                    "/v1/agent/models" -> json(200, """{"current":{"provider":"zai","id":"glm-5.3-flash"},"models":[]}""")
                    "/v1/rules" -> json(200, """{"ruleVersion":3,"photoWindow":{"enabled":false,"reminderDays":90,"cutoffDays":120,"autoMode":false,"folders":["DCIM/Camera/"],"keepMarker":"Keep"},"batchesRunOneTap":0}""")
                    "/v1/chat/history" -> json(200, """{"events":[]}""")
                    "/v1/chat/stream" -> MockResponse().setResponseCode(200)
                        .setHeader("Content-Type", "text/event-stream").setBody(": heartbeat\n\n")
                    else -> json(200, """{"accepted":true,"jobs":[],"serverTimeMs":0}""")
                }
            }
        }
        mock.start()
        server = mock
        AppGraph.identityStoreFactory = { MemoryIdentityStore(PairedIdentity(mock.url("/").toString().trimEnd('/'), "t")) }
    }

    @After
    fun tearDown() {
        runCatching { WorkManager.getInstance(context).cancelAllWork().result.get() }
        AppGraph.identityStoreFactory = { KeystoreIdentityStore(it) }
        AppGraph.resetForTest()
        server?.shutdown()
    }

    private fun json(status: Int, body: String) =
        MockResponse().setResponseCode(status).setHeader("Content-Type", "application/json").setBody(body)

    private fun settle(millis: Long) {
        val until = System.currentTimeMillis() + millis
        while (System.currentTimeMillis() < until) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(100)
        }
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test
    fun `returning to the app on Chat re-reads the settings even when the proposals call fails`() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            // Longer than the duplicate guard's window, so the resume is a fresh foreground event.
            settle(2_500)
            val before = settingsReads.get()
            assertTrue("the footer was read at launch", before >= 1)
            scenario.moveToState(Lifecycle.State.CREATED)
            settle(200)
            scenario.moveToState(Lifecycle.State.RESUMED)
            settle(900)
            assertTrue(
                "a settings read after the resume: $before -> ${settingsReads.get()}",
                settingsReads.get() > before,
            )
        }
    }

    @Test
    fun `a resume on another tab does not reload the Chat settings`() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            settle(2_500)
            compose.onNodeWithText("Status").performClick()
            settle(900)
            val before = settingsReads.get()
            scenario.moveToState(Lifecycle.State.CREATED)
            settle(200)
            scenario.moveToState(Lifecycle.State.RESUMED)
            settle(900)
            assertEquals("Chat is not showing, so the Chat trigger stays quiet", before, settingsReads.get())
        }
    }
}
