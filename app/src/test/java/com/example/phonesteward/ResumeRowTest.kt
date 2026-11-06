package com.example.phonesteward

import android.Manifest
import android.content.Context
import android.os.Looper
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.lifecycle.Lifecycle
import androidx.startup.InitializationProvider
import androidx.test.core.app.ActivityScenario
import androidx.compose.ui.test.onAllNodesWithText
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
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
 * a REAL resume test for N2. The app launches with media
 * read OFF, the scenario drops to CREATED, the permission is granted on the
 * platform (a Robolectric shadow grant — the one permission this
 * environment can grant; battery/all-files are Settings-page grants
 * Robolectric cannot flip), the scenario returns to RESUMED, and the row
 * turns green WITHOUT a restart: the button disappears.
 *
 * Owner rule, changed on 2026-10-08: a granted row shows no button at all
 * (it used to stay as a greyed, disabled control), so "green" now means the
 * action button is gone.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class ResumeRowTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    private val context: Context get() = ApplicationProvider.getApplicationContext()
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
        Robolectric.setupContentProvider(InitializationProvider::class.java, "${context.packageName}.androidx-startup")
        AppGraph.resetForTest()
        previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { _, e -> uncaught += e }
        val mock = MockWebServer()
        mock.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty().substringBefore('?')
                val body = when (path) {
                    "/v1/proposals" -> """{"proposals":[]}"""
                    "/v1/rules" -> """{"ruleVersion":3,"photoWindow":{"enabled":false,"reminderDays":90,"cutoffDays":120,"autoMode":false,"folders":["DCIM/Camera/"],"keepMarker":"Keep"},"batchesRunOneTap":0}"""
                    "/v1/chat/history" -> """{"events":[]}"""
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
        Thread.setDefaultUncaughtExceptionHandler(previousHandler)
        AppGraph.identityStoreFactory = { KeystoreIdentityStore(it) }
        AppGraph.resetForTest()
    }

    private fun settle(millis: Long) {
        val until = System.currentTimeMillis() + millis
        while (System.currentTimeMillis() < until) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(50)
        }
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test
    fun `a permission granted in the background turns its row green after resume`() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            settle(1_500)
            // Status tab via the test seam (clicks do not dispatch here).
            scenario.onActivity { it.bottomTab = 3 }
            settle(500)
            fun mediaButtonShown(): Boolean =
                compose.onAllNodesWithText("Allow photos and videos").fetchSemanticsNodes().isNotEmpty()

            // Media read is OFF: the row's action button is shown.
            assertTrue("before the grant the button must be shown", mediaButtonShown())

            // Grant in the background, then come back.
            scenario.moveToState(Lifecycle.State.CREATED)
            shadowOf(context.applicationContext as android.app.Application).grantPermissions(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VIDEO,
            )
            scenario.moveToState(Lifecycle.State.RESUMED)

            // The ON_RESUME observer re-reads the permissions; poll until the
            // recomposition lands (no restart, no Refresh tap). A granted row
            // shows no button, so the node disappears.
            var green = false
            val until = System.currentTimeMillis() + 5_000
            while (!green && System.currentTimeMillis() < until) {
                settle(100)
                if (!mediaButtonShown()) green = true
            }
            assertTrue("the row must turn green after resume", green)
            assertEquals(Lifecycle.State.RESUMED, scenario.state)
            assertTrue("uncaught: ${uncaught.map { it.stackTraceToString().take(500) }}", uncaught.isEmpty())
        }
    }
}
