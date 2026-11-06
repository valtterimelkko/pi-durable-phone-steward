package com.example.phonesteward.ui

import android.graphics.Bitmap
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import com.example.phonesteward.logic.chat.ChatVerbosity
import com.example.phonesteward.testlab.ScreenFixtures
import com.example.phonesteward.ui.theme.PhoneStewardTheme

/**
 * Developer preview of Chat at each verbosity level, light and dark, from the fixture
 * history (Markdown, a table, tool calls, an error, a long message). Writes PNGs to
 * `app/build/previews/chat-<level>[-dark][-expanded].png`. The window is tall so the whole
 * history is visible in one image; Test Lab shows the real device.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [33], qualifiers = "w393dp-h1900dp-xxhdpi")
class ChatPreviewTest {

    @get:Rule
    val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    private fun render(
        name: String,
        level: ChatVerbosity,
        dark: Boolean = false,
        entries: List<AppViewModel.ChatEntry> = ScreenFixtures.chatHistory,
        before: () -> Unit = {},
    ) {
        compose.setContent {
            PhoneStewardTheme(darkTheme = dark) {
                StewardApp(
                    state = ScreenFixtures.chatState(level, entries),
                    tab = StewardTab.Chat,
                    onTab = {},
                    space = ScreenFixtures.space,
                    lastWorkerRunMs = ScreenFixtures.REVIEW_AT_MS,
                    onboarding = ScreenFixtures.allGranted,
                    statusActions = StatusActions(),
                    onPair = { _, _, _ -> },
                    onSend = {},
                    onDecide = { _, _, _ -> },
                    onDismissResult = {},
                    onSaveRules = {},
                )
            }
        }
        compose.waitForIdle()
        before()
        compose.waitForIdle()
        val view = compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(android.graphics.Canvas(bitmap))
        val dir = File("build/previews").also { it.mkdirs() }
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun low() = render("chat-low", ChatVerbosity.Low)
    @Test fun lowDark() = render("chat-low-dark", ChatVerbosity.Low, dark = true)
    @Test fun medium() = render("chat-medium", ChatVerbosity.Medium)
    @Test fun mediumDark() = render("chat-medium-dark", ChatVerbosity.Medium, dark = true)
    @Test fun high() = render("chat-high", ChatVerbosity.High)
    @Test fun highDark() = render("chat-high-dark", ChatVerbosity.High, dark = true)
    @Test fun lowWorking() = render("chat-low-working", ChatVerbosity.Low, entries = ScreenFixtures.chatHistoryWorking)
    @Test fun mediumWorking() = render("chat-medium-working", ChatVerbosity.Medium, entries = ScreenFixtures.chatHistoryWorking)

    @Test fun mediumGroupExpanded() = render("chat-medium-expanded", ChatVerbosity.Medium) {
        // The first group on screen: its header is the row with the "Checked 3 things" summary.
        compose.onNodeWithTagFirst("chat-tool-group").performClick()
    }

    @Test fun mediumGroupExpandedDark() = render("chat-medium-expanded-dark", ChatVerbosity.Medium, dark = true) {
        compose.onNodeWithTagFirst("chat-tool-group").performClick()
    }

    @Test fun thereIsNoRunEndedRowAtMedium() {
        render("chat-medium-tmp", ChatVerbosity.Medium)
        assertEquals(0, compose.onAllNodesWithTagCount("chat-run-row"))
    }
}

private fun androidx.compose.ui.test.junit4.ComposeTestRule.onNodeWithTagFirst(tag: String) =
    this.onAllNodes(androidx.compose.ui.test.hasTestTag(tag))[0]

private fun androidx.compose.ui.test.junit4.ComposeTestRule.onAllNodesWithTagCount(tag: String) =
    this.onAllNodes(androidx.compose.ui.test.hasTestTag(tag)).fetchSemanticsNodes().size
