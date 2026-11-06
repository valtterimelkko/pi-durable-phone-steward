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
import java.util.Collections
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
 * regression : the APK crashed on every launch on a real phone
 * while hundreds of JVM tests passed, because no test ever started the app
 * the way Android does. These tests launch the real launcher activity with
 * the merged manifest (start-up providers included) and require it to reach
 * RESUMED and stay up, with no uncaught exception on any thread: unpaired,
 * over the R1 app's leftover data, and paired (server unreachable, token
 * revoked) through every tab.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class AppLaunchTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    /** Read per use: Robolectric gives every test a fresh Application. */
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    /** Anything that would have killed the process on a phone. */
    private val uncaught = Collections.synchronizedList(mutableListOf<Throwable>())
    private var previousHandler: Thread.UncaughtExceptionHandler? = null
    private var server: MockWebServer? = null

    private class MemoryIdentityStore(var identity: PairedIdentity?) : IdentityStore {
        override fun read() = identity
        override fun write(identity: PairedIdentity) { this.identity = identity }
        override fun clear() { identity = null }
    }

    @Before
    fun setUp() {
        // Android runs the manifest's start-up provider (WorkManager and the
        // other androidx initialisers) before any activity; Robolectric does
        // not, so run the merged manifest's provider the same way here.
        Robolectric.setupContentProvider(InitializationProvider::class.java, "${context.packageName}.androidx-startup")
        AppGraph.resetForTest()
        previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { _, e -> uncaught += e }
    }

    @After
    fun tearDown() {
        // The paired tests schedule the periodic sync worker; stop it so it
        // cannot rebuild the app graph inside the next test.
        WorkManager.getInstance(context).cancelAllWork().result.get()
        Thread.setDefaultUncaughtExceptionHandler(previousHandler)
        AppGraph.identityStoreFactory = { KeystoreIdentityStore(it) }
        AppGraph.resetForTest()
        server?.shutdown()
    }

    /** Lets the background loops run against the server, then drains the main thread. */
    private fun settle(millis: Long = 3_000) {
        val until = System.currentTimeMillis() + millis
        while (System.currentTimeMillis() < until) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(100)
        }
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun assertStillUp(scenario: ActivityScenario<MainActivity>) {
        assertEquals(Lifecycle.State.RESUMED, scenario.state)
        assertTrue("uncaught: ${uncaught.map { it.stackTraceToString().take(2_000) }}", uncaught.isEmpty())
    }

    private fun startPaired(baseUrl: String) {
        val store = MemoryIdentityStore(PairedIdentity(baseUrl, "test-token"))
        AppGraph.identityStoreFactory = { store }
    }

    private fun serverAnswering(status: Int, body: String): String {
        val mock = MockWebServer()
        mock.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) =
                MockResponse().setResponseCode(status).setHeader("Content-Type", "application/json").setBody(body)
        }
        mock.start()
        server = mock
        return mock.url("/").toString().trimEnd('/')
    }

    @Test
    fun `a fresh install launches to the pairing screen and stays up`() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            settle(500)
            compose.onNodeWithText("Pair").assertExists()
            assertStillUp(scenario)
        }
    }

    @Test
    fun `an install over the R1 app's data launches and stays up`() {
        // All the R1 spike persisted: its recorded test-image URIs. (Whether
        // the guard restores them is not asserted here: under Robolectric the
        // answer depends on which tests ran before, not on the app.)
        context.getSharedPreferences("phone_steward_test_images", Context.MODE_PRIVATE)
            .edit()
            .putStringSet("uris", setOf("content://media/external/images/media/1000"))
            .commit()
        AppGraph.resetForTest()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            settle(500)
            assertStillUp(scenario)
        }
    }

    @Test
    fun `a paired app with the server unreachable stays up on every tab`() {
        // Nothing listens on the discard port: every call fails at the transport.
        startPaired("http://127.0.0.1:9")
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            settle()
            for (tab in listOf("Proposals", "Rules", "Status", "Chat")) {
                compose.onNodeWithText(tab).performClick()
                settle(500)
                assertStillUp(scenario)
            }
        }
    }

    @Test
    fun `a paired app whose server errors stays up on every tab`() {
        startPaired(serverAnswering(500, "not json"))
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            settle()
            for (tab in listOf("Proposals", "Rules", "Status", "Chat")) {
                compose.onNodeWithText(tab).performClick()
                settle(500)
                assertStillUp(scenario)
            }
        }
    }

    @Test
    fun `a revoked token returns the app to pairing without a crash`() {
        startPaired(serverAnswering(401, """{"error":{"code":"TOKEN_REVOKED","message":"revoked"}}"""))
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            settle()
            compose.onNodeWithText("Pair").assertExists()
            assertStillUp(scenario)
        }
    }
}
