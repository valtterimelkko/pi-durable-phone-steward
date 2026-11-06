package com.example.phonesteward.e2e

import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Test
import org.junit.runner.RunWith
import com.example.phonesteward.android.AppGraph
import com.example.phonesteward.logic.chat.ChatVerbosity
import com.example.phonesteward.testlab.ScreenFixtures

/**
 * The look-and-feel tour: every screen of the real app against the fake steward, in light and dark,
 * saved as `e2e-look-<mode>-<screen>.png` for the review (and, on SmallPhone, for cut-off text).
 * It asserts nothing beyond the app staying up; the flows above assert behaviour.
 */
@RunWith(AndroidJUnit4::class)
class Flow8LookTest : E2eBase() {
    override val flow = "look"

    private val sdcard get() = android.os.Environment.getExternalStorageDirectory()
    private val lookRoot get() = java.io.File(sdcard, "LookTest")
    private val lookTrash get() = java.io.File(sdcard, ".StewardTrash")

    private fun make(rel: String, bytes: Int) {
        val f = java.io.File(lookRoot, rel)
        f.parentFile!!.mkdirs()
        f.writeBytes(ByteArray(bytes) { 3 })
    }

    private fun trashed(rel: String, bytes: Int) {
        val f = java.io.File(lookTrash, rel)
        f.parentFile!!.mkdirs()
        f.writeBytes(ByteArray(bytes) { 3 })
        java.io.File(lookTrash, ".nomedia").createNewFile()
    }

    /**
     * A11.3: a real Steward trash for the look review — one batch that merges into the folder that stayed, and one
     * item something now stands at (shown with its plain reason and no button). Invented names only.
     */
    private fun seedLookTrash() {
        shell("appops set ${context.packageName} MANAGE_EXTERNAL_STORAGE allow")
        lookTrash.deleteRecursively()
        lookRoot.deleteRecursively()
        make("OfflineMaps/230701/tiles.bin", 400_000)
        trashed("2026-10-08/pr-files/LookTest/OfflineMaps/230606/tiles.bin", 2_000_000)
        make("old.apk", 3_000_000) // something now stands where the trashed item was
        trashed("2026-10-05/pr-old/LookTest/old.apk", 3_000_000)
    }

    private fun cleanLookTrash() {
        lookTrash.deleteRecursively()
        lookRoot.deleteRecursively()
    }

    private fun seed() {
        fake.addProposal(ScreenFixtures.cacheProposal)
        fake.addProposal(ScreenFixtures.uninstallProposal)
        fake.addProposal(ScreenFixtures.mediaProposal)
        fake.addProposal(ScreenFixtures.filesProposal)
        fake.say("user", "What is eating my storage?")
        fake.runEvent("started")
        fake.toolEvent("phone_snapshot", "started", "call_1", "no arguments")
        fake.toolEvent("phone_snapshot", "done", "call_1", "no arguments", "snapshot 3 h old", 380)
        fake.toolEvent("storage_trend", "started", "call_2", "no arguments")
        fake.toolEvent("storage_trend", "done", "call_2", "no arguments", "trend available", 290)
        fake.toolEvent("propose_cleanup", "error", "call_3", "2 items: 1 clear_all_caches, 1 advice", "error: item 2 has no target text", 90)
        fake.say("user", "[phone result pj-1a2b3c] kind=storage_overview status=ok data={\"freeBytes\":1}")
        fake.say(
            "assistant",
            """Here is what I found. About **38.7 GB is free** of 128 GB.

## Where the space goes

| App | Cache | Last opened |
|---|---|---|
| Chrome | 612 MB | today |
| YouTube | 480 MB | yesterday |
| Retro Puzzle | 410 MB | 14 months ago |

- Temporary files (`cache`) rebuild themselves
- Nothing in your `Keep` folder is touched

1. Clear temporary files, about 1.4 GB
2. Remove the two old apps, about 640 MB

```
free:  38.7 GB
total: 128 GB
```""",
        )
        fake.proposalEvent("pr-cache", "Clear temporary files")
        fake.runEvent("ended", durationMs = 35_000)
        fake.say("user", "Thanks, I will look at them now.")
        fake.latest = buildJsonObject {
            put("versionName", "0.2.4"); put("versionCode", 6L); put("sha256", "0".repeat(64))
            put("sizeBytes", 26_571_749L); put("publishedAtMs", System.currentTimeMillis())
        }
    }

    private fun tour(mode: String) {
        shell("cmd uimode night ${if (mode == "dark") "yes" else "no"}")
        Thread.sleep(1_500)
        AppGraph.get(context).pairing.forget()
        relaunchApp()
        waitForText("Pair with your steward server", substring = true)
        shot("$mode-1-pair")
        compose.onAllNodes(hasSetTextAction())[1].performTextInput("K7Q4")
        Thread.sleep(1_200)
        shot("$mode-2-pair-keyboard")
        pairThroughUiFromTyped()
        waitForTag("chat-list")
        Thread.sleep(1_500)
        for (level in ChatVerbosity.entries) {
            clickTag("verbosity-${level.name}")
            Thread.sleep(600)
            shot("$mode-3-chat-${level.name.lowercase()}")
        }
        clickTag("verbosity-Medium")
        openTab("Proposals")
        waitForText("Clear temporary files")
        Thread.sleep(500)
        shot("$mode-4-proposals")
        runCatching { text("Move old files to the Steward trash").performScrollTo() }
        runCatching { text("Move three old map downloads to the Steward trash").performScrollTo() }
        Thread.sleep(400)
        shot("$mode-4b-proposals-files")
        openTab("Rules")
        waitForText("Photo window")
        Thread.sleep(500)
        shot("$mode-5-rules")
        openTab("Status")
        waitForTag("update-card")
        eventually("update offered") { hasText("Update available: 0.2.4") }
        shot("$mode-6-status-top")
        // A11.3: the Steward trash screen, with a real batch and a real blocked item.
        clickTag("open-trash-button")
        waitForTag("trash-list")
        Thread.sleep(600)
        shot("$mode-6c-trash")
        runCatching { text("LookTest/old.apk").performScrollTo() }
        Thread.sleep(400)
        shot("$mode-6d-trash-blocked")
        clickTag("trash-back")
        waitForTag("update-card")
        runCatching { text("Shizuku (optional)").performScrollTo() }
        Thread.sleep(400)
        shot("$mode-6b-status-shizuku")
        runCatching { text("Forget this pairing on the phone").performScrollTo() }
        Thread.sleep(400)
        shot("$mode-7-status-bottom")
        // P7: the model picker and the Chat menu.
        openTab("Chat")
        waitForTag("chat-model")
        Thread.sleep(500)
        shot("$mode-8-chat-footer")
        clickTag("chat-menu")
        waitForText("Compact now")
        shot("$mode-9-chat-menu")
        clickText("Compact now")
        Thread.sleep(800)
        clickTag("chat-model")
        waitForTag("model-picker")
        waitForText("DeepSeek V4.1 Flash")
        Thread.sleep(500)
        shot("$mode-9b-model-picker")
        device.pressBack()
        waitForNoText("Choose a model")
    }

    @Test
    fun lightAndDarkTour() {
        seed()
        seedLookTrash()
        try {
            launchApp()
            tour("light")
            tour("dark")
        } finally {
            shell("cmd uimode night no")
            cleanLookTrash()
        }
    }

    /** The pairing form already holds a half-typed code and the server address; finish it. */
    private fun pairThroughUiFromTyped() {
        val fields = compose.onAllNodes(hasSetTextAction())
        fields[0].performTextReplacement(fake.baseUrl)
        fields[1].performTextReplacement(FakeSteward.PAIRING_CODE)
        compose.waitForIdle()
        click(text("Pair"))
    }
}
