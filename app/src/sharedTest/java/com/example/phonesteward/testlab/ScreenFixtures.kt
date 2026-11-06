package com.example.phonesteward.testlab

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import com.example.phonesteward.logic.ActionResult
import com.example.phonesteward.logic.Onboarding
import com.example.phonesteward.logic.SpaceRow
import com.example.phonesteward.logic.chat.ChatVerbosity
import com.example.phonesteward.net.PhotoWindow
import com.example.phonesteward.net.Proposal
import com.example.phonesteward.net.ProposalItem
import com.example.phonesteward.net.RulesResponse
import com.example.phonesteward.ui.AppViewModel.AppState
import com.example.phonesteward.ui.AppViewModel.ChatEntry

/**
 * Invented data for screen tests: no owner file names, no real server, no
 * tokens. Shared by the Robolectric previews (src/test) and the Test Lab
 * instrumentation tests (src/androidTest).
 */
object ScreenFixtures {

    /** 2026-10-07 19:57 UTC. */
    const val REVIEW_AT_MS = 1_791_403_050_000L

    /**
     * The uninstall fixtures name packages that exist on every Android image, so the app finds them installed (N31: a proposal
     * whose apps are all gone would settle itself and leave the list). The cards show the invented labels; nothing in the
     * tests ever approves removing them.
     */
    private const val INSTALLED_A = "com.android.settings"
    private const val INSTALLED_B = "com.android.shell"

    val space = SpaceRow(freeBytes = 38_700_000_000L, totalBytes = 128_000_000_000L)

    val allGranted = Onboarding.Snapshot(
        batteryUnrestricted = true, usageAccess = true, allFiles = true, mediaRead = true, manageMedia = true, autoModeOn = false,
    )
    val someOff = Onboarding.Snapshot(
        batteryUnrestricted = true, usageAccess = true, allFiles = false, mediaRead = false, manageMedia = false, autoModeOn = false,
    )

    val rules = RulesResponse(
        ruleVersion = 3,
        photoWindow = PhotoWindow(
            enabled = true, reminderDays = 90, cutoffDays = 120, autoMode = false,
            folders = listOf("DCIM/Camera/", "Pictures/Screenshots/"), keepMarker = "Keep",
        ),
        batchesRunOneTap = 0,
    )

    val chat = listOf(
        ChatEntry("assistant", "Hello! I checked your storage. About 38.7 GB is free.", REVIEW_AT_MS),
        ChatEntry("user", "What is eating my storage?", REVIEW_AT_MS + 60_000),
    )

    // ---- F2 chat fixtures: a steward history with Markdown, a table, tool calls, an error, a long message ----

    private var chatSeq = 0L
    private fun nextSeq() = ++chatSeq
    private fun at(minutes: Int, seconds: Int = 0) = CHAT_AT_MS + minutes * 60_000L + seconds * 1_000L

    private fun say(role: String, text: String, atMs: Long) = ChatEntry(role, text, atMs, seq = nextSeq(), lastSeq = chatSeq)
    private fun tool(
        name: String, status: String, atMs: Long, args: String, result: String?, ms: Long?, callId: String,
    ) = ChatEntry(
        "system", "", atMs, "tool", seq = nextSeq(), lastSeq = chatSeq, name = name, status = status,
        callId = callId, argsSummary = args, resultSummary = result, durationMs = ms,
    )
    private fun runEvent(status: String, atMs: Long, ms: Long? = null) =
        ChatEntry("system", "", atMs, "run", seq = nextSeq(), lastSeq = chatSeq, status = status, reason = "owner", durationMs = ms)

    /** 2026-10-07 19:40 UTC. */
    const val CHAT_AT_MS = 1_791_402_000_000L

    private const val SUMMARY = """Here is what I found on your phone. About **38.7 GB is free** of 128 GB, and the *biggest wins* are easy ones.

## Where the space goes

| App | Cache | Last opened |
|---|---|---|
| Chrome | 612 MB | today |
| YouTube | 480 MB | yesterday |
| Instagram | 310 MB | 2 days ago |
| Retro Puzzle | 410 MB | 14 months ago |

- Temporary files (`cache`) can be rebuilt by each app, so clearing them is safe
- **Retro Puzzle** and *Photo Collage Maker* have not been opened this year
- Nothing in your `Keep` folder is touched

1. Clear temporary files, about 1.4 GB
2. Remove the two old apps, about 640 MB

```
free:  38.7 GB
total: 128 GB
```"""

    private const val LONG = """I have written two proposals for you to look at. The first clears temporary files across every app in one go. Android asks you once, and each app rebuilds what it needs the next time it runs, so nothing you care about is lost.

The second removes two apps that have not been opened for more than ten months. Both can be installed again from the Play Store whenever you want them back, and neither keeps anything on the phone that is not also in the app's own cloud account.

I did not propose anything about your photos. Your photo rule is switched on, but nothing in the rule folders is older than the cut-off yet, so there is nothing to move to the trash this week. I will look again at the next weekly check and tell you if that changes.

If you would rather I looked at something else first, such as which folders are growing fastest, just ask."""

    val chatHistory: List<ChatEntry> = run {
        chatSeq = 0
        listOf(
            say("user", "What is eating my storage?", at(0)),
            runEvent("started", at(0, 1)),
            say("assistant", "Let me look at the latest snapshot and the trend.", at(0, 3)),
            tool("phone_snapshot", "done", at(0, 4), "no arguments", "snapshot 3 h old", 380, "call_1"),
            tool("storage_trend", "done", at(0, 5), "no arguments", "trend available", 290, "call_2"),
            tool("phone_storage_overview", "done", at(0, 6), "no arguments", "queued job pj-1a2b3c", 410, "call_3"),
            say("user", "[phone result pj-1a2b3c] kind=storage_overview status=ok data={\"freeBytes\":38700000000}", at(0, 20)),
            say("assistant", SUMMARY, at(0, 24)),
            tool("decisions", "done", at(0, 25), "limit 10", "no entries yet", 120, "call_4"),
            tool("propose_cleanup", "error", at(0, 28), "2 items: 1 clear_all_caches, 1 advice", "error: item 2 has no target text", 90, "call_5"),
            tool("propose_cleanup", "done", at(0, 31), "2 items: 1 clear_all_caches, 1 uninstall_app", "proposal pr-cache written", 1180, "call_6"),
            say("assistant", LONG, at(0, 34)),
            ChatEntry("system", "Clear temporary files", at(0, 35), "proposal", seq = nextSeq(), lastSeq = chatSeq, proposalId = "pr-cache"),
            runEvent("ended", at(0, 36), 35_000),
            say("user", "Thanks, I will look at them now.", at(5)),
        )
    }

    /** The same history with a run open: the quiet "Working…" line. */
    val chatHistoryWorking: List<ChatEntry> = chatHistory + listOf(
        ChatEntry("system", "", 0, "run", seq = 9001, lastSeq = 9001, status = "started", reason = "owner"),
        ChatEntry("system", "", 0, "tool", seq = 9002, lastSeq = 9002, name = "phone_app_usage", status = "started", callId = "call_7", argsSummary = "no arguments"),
    )

    fun chatState(level: ChatVerbosity, entries: List<ChatEntry> = chatHistory) =
        pairedBase.copy(chat = entries, chatVerbosity = level)

    val pairedBase = AppState(
        paired = true,
        baseUrl = "https://steward.example.test",
        chat = chat,
        rules = rules,
        permissions = allGranted,
    )

    val unpaired = AppState(paired = false, baseUrl = "https://steward.example.test")

    private fun target(vararg pairs: Pair<String, String>): JsonObject = buildJsonObject {
        pairs.forEach { (k, v) -> put(k, v) }
    }

    private fun cacheApps(): JsonObject = buildJsonObject {
        put(
            "apps",
            JsonArray(
                listOf(
                    buildJsonObject { put("packageName", "com.example.browser"); put("label", "Chrome"); put("cacheBytes", 612_000_000L) },
                    buildJsonObject { put("packageName", "com.example.video"); put("label", "YouTube"); put("cacheBytes", 480_000_000L) },
                    buildJsonObject { put("packageName", "com.example.social"); put("label", "Instagram"); put("cacheBytes", 310_000_000L) },
                ),
            ),
        )
    }

    val cacheProposal = Proposal(
        proposalId = "pr-cache",
        createdAtMs = REVIEW_AT_MS,
        source = "agent",
        title = "Clear temporary files",
        reason = "Apps keep temporary files that they rebuild when needed. About 1.4 GB can be freed at no cost.",
        bytesEstimate = 1_402_000_000L,
        status = "open",
        items = listOf(
            ProposalItem(
                itemId = "it-cache",
                kind = "clear_all_caches",
                target = cacheApps(),
                bytesEstimate = 1_402_000_000L,
                reason = "One Android confirmation clears every app's cache.",
            ),
        ),
    )

    val uninstallProposal = Proposal(
        proposalId = "pr-apps",
        createdAtMs = REVIEW_AT_MS - 86_400_000L,
        source = "agent",
        title = "Remove two apps you have not opened this year",
        reason = "Neither has been opened since January. Both can be installed again from the Play Store.",
        bytesEstimate = 640_000_000L,
        status = "open",
        items = listOf(
            ProposalItem(
                itemId = "it-app-1", kind = "uninstall_app",
                target = target("packageName" to INSTALLED_A, "label" to "Retro Puzzle"),
                bytesEstimate = 410_000_000L, reason = "Last used 14 months ago.",
            ),
            ProposalItem(
                itemId = "it-app-2", kind = "uninstall_app",
                target = target("packageName" to INSTALLED_B, "label" to "Photo Collage Maker"),
                bytesEstimate = 230_000_000L, reason = "Last used 11 months ago.",
            ),
        ),
    )

    val mediaProposal = Proposal(
        proposalId = "pr-media",
        createdAtMs = REVIEW_AT_MS - 2 * 86_400_000L,
        source = "agent",
        title = "Move old screen recordings to the trash",
        reason = "Four recordings older than the cut-off. They stay recoverable for about 30 days.",
        bytesEstimate = 2_150_000_000L,
        status = "open",
        items = listOf(
            ProposalItem(
                itemId = "it-m1", kind = "trash_media",
                target = target("displayName" to "screen_recording_2025_11_02.mp4"),
                bytesEstimate = 1_200_000_000L, reason = "11 months old.",
            ),
            ProposalItem(
                itemId = "it-m2", kind = "trash_media",
                target = target("displayName" to "screen_recording_2025_11_09.mp4"),
                bytesEstimate = 950_000_000L, reason = "11 months old.",
            ),
            ProposalItem(
                itemId = "it-note", kind = "advice",
                target = target("text" to "Anything in a folder named Keep is never listed."),
            ),
        ),
    )

    /** A10.1: three invented map folders the steward found and wants to move to the Steward trash. */
    val filesProposal = Proposal(
        proposalId = "pr-files",
        createdAtMs = REVIEW_AT_MS,
        source = "agent",
        title = "Move three old map downloads to the Steward trash",
        reason = "The three folders hold map files for a trip you made last year. About 2.6 GB comes back once the trash is emptied.",
        bytesEstimate = 2_600_000_000L,
        status = "open",
        items = listOf(
            ProposalItem(
                itemId = "it-f1", kind = "delete_files",
                target = buildJsonObject {
                    put("path", "OfflineMaps/230606"); put("type", "dir"); put("sizeBytes", 1_000_000_000L)
                    put("modifiedMs", 1_790_000_000_000L); put("fileCount", 12L)
                },
                bytesEstimate = 1_000_000_000L, reason = "Last changed 4 months ago.",
            ),
            ProposalItem(
                itemId = "it-f2", kind = "delete_files",
                target = buildJsonObject {
                    put("path", "OfflineMaps/230701"); put("type", "dir"); put("sizeBytes", 1_000_000_000L)
                    put("modifiedMs", 1_790_000_000_000L); put("fileCount", 9L)
                },
                bytesEstimate = 1_000_000_000L, reason = "Last changed 3 months ago.",
            ),
            ProposalItem(
                itemId = "it-f3", kind = "delete_files",
                target = buildJsonObject {
                    put("path", "OfflineMaps/230805"); put("type", "dir"); put("sizeBytes", 600_000_000L)
                    put("modifiedMs", 1_790_000_000_000L); put("fileCount", 7L)
                },
                bytesEstimate = 600_000_000L, reason = "Last changed 2 months ago.",
            ),
        ),
    )

    // ---- P7 fixtures: files, Steward trash, Shizuku, model picker, context ----

    val filesState = pairedBase.copy(proposals = listOf(filesProposal))

    val trashStatus = com.example.phonesteward.net.StewardTrashStatus(bytes = 2_600_000_000L, oldestMs = REVIEW_AT_MS - 5 * 86_400_000L)

    val trashEmptiedResult = ActionResult("Steward trash emptied: 2.6 GB freed.", ActionResult.Kind.SUCCESS)

    fun statusState(
        shizuku: com.example.phonesteward.logic.shizuku.ShizukuState,
        trash: com.example.phonesteward.net.StewardTrashStatus = trashStatus,
        trashResult: ActionResult? = null,
    ) = pairedBase.copy(shizuku = shizuku, stewardTrash = trash, trashResult = trashResult)

    val agentSettings = com.example.phonesteward.net.AgentSettingsResponse(
        conversationId = "main",
        model = com.example.phonesteward.net.AgentModel("zai", "glm-5.3-flash"),
        context = com.example.phonesteward.net.ContextInfo(tokens = 53_760, budget = 128_000),
    )

    private fun m(provider: String, id: String, label: String, billing: String, available: Boolean = true) =
        com.example.phonesteward.net.ModelEntry(provider, id, label, billing, available, reasoning = false, contextWindow = 128_000)

    /** An invented catalogue: a current subscription model, another, two pay-as-you-go ones and one with no sign-in. */
    val modelCatalogue = com.example.phonesteward.net.ModelCatalogueResponse(
        current = com.example.phonesteward.net.AgentModel("zai", "glm-5.3-flash"),
        models = listOf(
            m("zai", "glm-5.3-flash", "GLM 5.3 Flash", "subscription"),
            m("zai", "glm-5.3", "GLM 5.3", "subscription"),
            m("deepseek", "v4.1-flash", "DeepSeek V4.1 Flash", "metered"),
            m("deepseek", "v4.1-pro", "DeepSeek V4.1 Pro", "metered"),
            m("openrouter", "mix-large", "Mix Large", "metered"),
            m("anthropic", "sonnet", "Sonnet", "subscription", available = false),
            m("custom", "local-1", "Local One", "unknown"),
        ),
    )

    val chatWithAgent = chatState(ChatVerbosity.Medium).copy(agent = agentSettings, models = modelCatalogue)

    // ---- A11.3: the Steward trash restore screen ----

    /** A batch's date folder is stored as the day's start in the device zone; noon UTC keeps the look stable in the common zones. */
    private fun dayNoon(year: Int, month: Int, day: Int): Long =
        java.time.LocalDate.of(year, month, day).atTime(12, 0)
            .atZone(java.time.ZoneOffset.UTC).toInstant().toEpochMilli()

    val trashBatches = listOf(
        com.example.phonesteward.logic.files.TrashBatch(
            date = "2026-10-08",
            dateMs = dayNoon(2026, 10, 8),
            proposalId = "pr-files",
            items = listOf(
                com.example.phonesteward.logic.files.TrashItem("OfflineMaps/230606", "dir", 1_000_000_000),
                com.example.phonesteward.logic.files.TrashItem("OfflineMaps/230701", "dir", 1_000_000_000),
                com.example.phonesteward.logic.files.TrashItem("OfflineMaps/230805", "dir", 600_000_000),
            ),
        ),
        com.example.phonesteward.logic.files.TrashBatch(
            date = "2026-10-05",
            dateMs = dayNoon(2026, 10, 5),
            proposalId = "pr-old",
            items = listOf(
                com.example.phonesteward.logic.files.TrashItem(
                    "Download/old.apk", "file", 26_000_000,
                    blocked = true, reason = com.example.phonesteward.logic.files.StewardTrash.BLOCKED_REASON,
                ),
            ),
        ),
    )

    /** A11.3: the trash screen open over the fixtures above. */
    val trashScreenState = filesState.copy(trashOpen = true, trashBatches = trashBatches)

    /** A11.3: the trash screen open and empty. */
    val trashScreenEmptyState = pairedBase.copy(trashOpen = true, trashBatches = emptyList())

    /** Correction 1: a restore is running, so the Restore buttons are held. */
    val trashScreenBusyState = trashScreenState.copy(trashRestoring = true)

    /** A chat with a fresh-start divider in the middle. */
    val chatWithDivider: List<ChatEntry> = listOf(
        ChatEntry("user", "What is eating my storage?", CHAT_AT_MS, seq = 101, lastSeq = 101),
        ChatEntry("assistant", "About 38.7 GB is free. The biggest wins are cached files and two old apps.", CHAT_AT_MS + 30_000, seq = 102, lastSeq = 102),
        ChatEntry(
            "system", "Fresh start: the steward now works from its records, not this conversation.", CHAT_AT_MS + 3_600_000,
            kind = "context", seq = 103, lastSeq = 103,
        ),
        ChatEntry("user", "What is still open?", CHAT_AT_MS + 3_700_000, seq = 104, lastSeq = 104),
        ChatEntry("assistant", "Two proposals are waiting for you: temporary files and two old apps.", CHAT_AT_MS + 3_720_000, seq = 105, lastSeq = 105),
    )

    val severalProposals = pairedBase.copy(proposals = listOf(cacheProposal, uninstallProposal, mediaProposal))
    val oneProposal = pairedBase.copy(proposals = listOf(cacheProposal))
    val noProposals = pairedBase.copy(proposals = emptyList())

    val clearedResult = ActionResult(
        "Done: every app's cache cleared — about 1.4 GB freed.",
        ActionResult.Kind.SUCCESS,
    )
    val nothingDoneResult = ActionResult(
        "Nothing was done — approve again to retry.",
        ActionResult.Kind.INFO,
    )
}
