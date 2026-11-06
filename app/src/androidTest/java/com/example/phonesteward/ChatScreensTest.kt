package com.example.phonesteward

import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import com.example.phonesteward.logic.chat.ChatVerbosity
import com.example.phonesteward.testlab.ChatChecks
import com.example.phonesteward.testlab.ChatHost
import com.example.phonesteward.testlab.ScreenFixtures
import com.example.phonesteward.testlab.ScreenShots

/**
 * F2 on a real Android image: Chat at each verbosity level from the invented fixture
 * history, light and dark, with a screenshot per state, and the message box against a
 * fake host (no network, never production).
 */
@RunWith(AndroidJUnit4::class)
class ChatScreensTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private fun edgeToEdge() = compose.activity.runOnUiThread { compose.activity.enableEdgeToEdge() }

    private fun level(level: ChatVerbosity) {
        edgeToEdge()
        ChatChecks.checkLevel(compose, level) { ScreenShots.save(it) }
    }

    @Test fun low() = level(ChatVerbosity.Low)
    @Test fun medium() = level(ChatVerbosity.Medium)
    @Test fun high() = level(ChatVerbosity.High)

    private fun working(level: ChatVerbosity) {
        edgeToEdge()
        ChatChecks.checkWorking(compose, level) { ScreenShots.save(it) }
    }

    @Test fun workingLow() = working(ChatVerbosity.Low)
    @Test fun workingMedium() = working(ChatVerbosity.Medium)
    @Test fun workingHigh() = working(ChatVerbosity.High)

    @Test fun verbosityControl() {
        edgeToEdge()
        ChatChecks.checkVerbosityControl(compose) { ScreenShots.save(it) }
    }

    @Test fun messageBox() {
        edgeToEdge()
        ChatChecks.checkMessageBox(compose) { ScreenShots.save(it) }
    }

    private fun dark(level: ChatVerbosity) {
        edgeToEdge()
        ChatChecks.show(compose, ChatHost(ScreenFixtures.chatState(level)), dark = true)
        ScreenShots.save("chat-${level.name.lowercase()}-dark-bottom")
    }

    @Test fun darkLow() = dark(ChatVerbosity.Low)
    @Test fun darkMedium() = dark(ChatVerbosity.Medium)
    @Test fun darkHigh() = dark(ChatVerbosity.High)
}
