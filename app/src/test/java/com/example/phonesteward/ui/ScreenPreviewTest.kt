package com.example.phonesteward.ui

import android.graphics.Bitmap
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import com.example.phonesteward.testlab.ScreenFixtures
import com.example.phonesteward.ui.theme.PhoneStewardTheme

/**
 * Developer preview, not a gate: renders each screen from the shared fixtures with
 * Robolectric's native graphics and writes PNGs to `app/build/previews/`. It shows
 * layout, colour and type quickly on the host; it does NOT show system bars, real
 * insets or the keyboard, which is what Firebase Test Lab  is for.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [33], qualifiers = "w393dp-h830dp-xxhdpi")
class ScreenPreviewTest {

    @get:Rule
    val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    private fun render(name: String, dark: Boolean = false, state: AppViewModel.AppState, tab: StewardTab) {
        compose.setContent {
            PhoneStewardTheme(darkTheme = dark) {
                StewardApp(
                    state = state,
                    tab = tab,
                    onTab = {},
                    space = ScreenFixtures.space,
                    lastWorkerRunMs = ScreenFixtures.REVIEW_AT_MS,
                    onboarding = ScreenFixtures.someOff,
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
        val view = compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(android.graphics.Canvas(bitmap))
        val dir = File("build/previews").also { it.mkdirs() }
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun pair() = render("pair", state = ScreenFixtures.unpaired, tab = StewardTab.Chat)
    @Test fun pairDark() = render("pair-dark", dark = true, state = ScreenFixtures.unpaired, tab = StewardTab.Chat)
    @Test fun proposalsEmpty() = render("proposals-empty", state = ScreenFixtures.noProposals, tab = StewardTab.Proposals)
    @Test fun proposalsSeveral() = render("proposals-several", state = ScreenFixtures.severalProposals, tab = StewardTab.Proposals)
    @Test fun proposalsSeveralDark() =
        render("proposals-several-dark", dark = true, state = ScreenFixtures.severalProposals, tab = StewardTab.Proposals)
    @Test fun proposalsCleared() = render(
        "proposals-cleared",
        state = ScreenFixtures.noProposals.copy(result = ScreenFixtures.clearedResult),
        tab = StewardTab.Proposals,
    )
    @Test fun proposalsNothingDone() = render(
        "proposals-nothing-done",
        state = ScreenFixtures.oneProposal.copy(result = ScreenFixtures.nothingDoneResult),
        tab = StewardTab.Proposals,
    )
    @Test fun rules() = render("rules", state = ScreenFixtures.pairedBase, tab = StewardTab.Rules)
    @Test fun rulesDark() = render("rules-dark", dark = true, state = ScreenFixtures.pairedBase, tab = StewardTab.Rules)
    @Test fun status() = render("status", state = ScreenFixtures.pairedBase, tab = StewardTab.Status)
    @Test fun statusDark() = render("status-dark", dark = true, state = ScreenFixtures.pairedBase, tab = StewardTab.Status)
    @Test fun chat() = render("chat", state = ScreenFixtures.pairedBase, tab = StewardTab.Chat)

    // ---- P7 ----
    @Test fun proposalsFiles() = render("proposals-files", state = ScreenFixtures.filesState, tab = StewardTab.Proposals)
    @Test fun proposalsFilesDark() = render("proposals-files-dark", dark = true, state = ScreenFixtures.filesState, tab = StewardTab.Proposals)
    @Test fun statusTrashNotRunning() = render(
        "status-trash", state = ScreenFixtures.statusState(com.example.phonesteward.logic.shizuku.ShizukuState.NOT_RUNNING), tab = StewardTab.Status,
    )
    @Test fun statusTrashDark() = render(
        "status-trash-dark", dark = true,
        state = ScreenFixtures.statusState(com.example.phonesteward.logic.shizuku.ShizukuState.ABSENT, trashResult = ScreenFixtures.trashEmptiedResult),
        tab = StewardTab.Status,
    )
    @Test fun statusShizukuReady() = render(
        "status-shizuku-ready",
        state = ScreenFixtures.statusState(com.example.phonesteward.logic.shizuku.ShizukuState.READY, com.example.phonesteward.net.StewardTrashStatus()),
        tab = StewardTab.Status,
    )
    @Test fun chatAgent() = render("chat-agent", state = ScreenFixtures.chatWithAgent, tab = StewardTab.Chat)
    @Test fun chatAgentDark() = render("chat-agent-dark", dark = true, state = ScreenFixtures.chatWithAgent, tab = StewardTab.Chat)
    @Test fun chatDivider() = render(
        "chat-divider", state = ScreenFixtures.chatWithAgent.copy(chat = ScreenFixtures.chatWithDivider), tab = StewardTab.Chat,
    )

    // ---- A11.3 ----
    @Test fun trashScreen() = render("trash", state = ScreenFixtures.trashScreenState, tab = StewardTab.Status)
    @Test fun trashScreenDark() = render("trash-dark", dark = true, state = ScreenFixtures.trashScreenState, tab = StewardTab.Status)
    @Test fun trashScreenEmpty() = render("trash-empty", state = ScreenFixtures.trashScreenEmptyState, tab = StewardTab.Status)
}
