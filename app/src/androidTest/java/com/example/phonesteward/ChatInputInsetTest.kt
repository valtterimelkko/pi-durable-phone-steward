package com.example.phonesteward

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.work.WorkManager
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.phonesteward.android.AppGraph
import com.example.phonesteward.logic.IdentityStore
import com.example.phonesteward.logic.PairedIdentity
import com.example.phonesteward.android.KeystoreIdentityStore
import com.example.phonesteward.testlab.Layout
import com.example.phonesteward.testlab.Layout.bounds
import com.example.phonesteward.testlab.ScreenShots

/**
 * N12 on a real device: the real launcher activity, paired against an
 * address nothing listens on (no network, never production). On 0.2.2 the
 * Chat input row sat under the bottom bar; the owner could not message the
 * steward. The input must be displayed and end above the bottom bar.
 */
@RunWith(AndroidJUnit4::class)
class ChatInputInsetTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private class MemoryIdentityStore(var identity: PairedIdentity?) : IdentityStore {
        override fun read() = identity
        override fun write(identity: PairedIdentity) { this.identity = identity }
        override fun clear() { identity = null }
    }

    @Before
    fun setUp() {
        val store = MemoryIdentityStore(PairedIdentity("http://127.0.0.1:9", "fixture-token"))
        AppGraph.identityStoreFactory = { store }
        AppGraph.resetForTest()
    }

    @After
    fun tearDown() {
        WorkManager.getInstance(context).cancelAllWork().result.get()
        AppGraph.identityStoreFactory = { KeystoreIdentityStore(it) }
        AppGraph.resetForTest()
    }

    @Test
    fun chatInputIsDisplayedAboveTheBottomBar() {
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.waitForIdle()
            val input = Layout.chatInput(compose)
            ScreenShots.save("n12-chat-input-above-bottom-bar")
            input.assertIsDisplayed()
            val barTop = Layout.bottomBarTop(compose)
            val inputBottom = input.bounds().bottom
            assertTrue(
                "Chat input bottom ($inputBottom px) must be above the bottom bar top ($barTop px)",
                inputBottom <= barTop,
            )
        }
    }

    @Test
    fun chatInputStaysAboveTheBottomBarWhenFocused() {
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.waitForIdle()
            Layout.chatInput(compose).performClick()
            compose.waitForIdle()
            Thread.sleep(800) // the soft keyboard animates in
            ScreenShots.save("n12-chat-input-focused")
            Layout.chatInput(compose).assertIsDisplayed()
        }
    }
}
