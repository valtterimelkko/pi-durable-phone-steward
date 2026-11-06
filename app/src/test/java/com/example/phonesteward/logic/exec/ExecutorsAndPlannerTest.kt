package com.example.phonesteward.logic.exec

import com.example.phonesteward.logic.obs.Diagnostics
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.example.phonesteward.logic.SpaceRow
import com.example.phonesteward.logic.jobs.JobOutcome
import com.example.phonesteward.net.DecisionCheckResponse
import com.example.phonesteward.net.Job
import com.example.phonesteward.net.MediaRef
import com.example.phonesteward.net.PhotoWindow
import com.example.phonesteward.net.Proposal
import com.example.phonesteward.net.ProposalItem
import com.example.phonesteward.net.ProtocolJson
import com.example.phonesteward.net.RulesResponse
import com.example.phonesteward.net.StorageCategories
import com.example.phonesteward.net.StorageOverviewApp
import com.example.phonesteward.net.UninstallTarget

private fun job(kind: String, params: JsonObject = buildJsonObject { }): Job =
    Job(jobId = "pj-1", kind = kind, params = params, createdAtMs = 1, expiresAtMs = 2)

private fun trashTarget(mediaId: String = "external_primary:images:9"): JsonObject = buildJsonObject {
    put("mediaId", mediaId)
    put("relativePath", "DCIM/Camera/")
    put("displayName", "IMG.jpg")
    put("sizeBytes", 10)
}

/** A live row that still matches trashTarget exactly. */
private val matchingRow = LiveMediaRow(
    isFavourite = false, inKeep = false, sizeBytes = 10,
    relativePath = "DCIM/Camera/", displayName = "IMG.jpg",
)

private val lookupAllUnprotected = MediaLookup { matchingRow }

class StorageOverviewExecutorTest {

    @Test
    fun `builds the protocol data shape from the sources`() {
        val executor = StorageOverviewExecutor(
            space = { SpaceRow(freeBytes = 5, totalBytes = 100) },
            apps = { listOf(StorageOverviewApp("com.a", "A", 1, 2, 3, false)) },
            categories = { StorageCategories(imagesBytes = 10) },
        )
        val outcome = executor.execute(job("storage_overview"))
        assertTrue(outcome is JobOutcome.Ok)
        val data = (outcome as JobOutcome.Ok).data
        assertEquals(100, (data["totalBytes"] as kotlinx.serialization.json.JsonPrimitive).content.toLong())
        assertTrue(data.containsKey("categories"))
        assertTrue(data.containsKey("apps"))
    }

    @Test
    fun `a failing space source is an error result, not a crash`() {
        val executor = StorageOverviewExecutor(
            space = { error("StatFs broke") },
            apps = { emptyList() },
            categories = { StorageCategories() },
        )
        val outcome = executor.execute(job("storage_overview"))
        assertTrue(outcome is JobOutcome.Failed)
        assertEquals("SPACE_UNAVAILABLE", (outcome as JobOutcome.Failed).errorCode)
    }

    @Test
    fun `hidden app-list failures are reported with a stable site`() {
        val recording = RecordingSink()
        Diagnostics.install(recording)
        try {
            val executor = StorageOverviewExecutor(
                space = { SpaceRow(freeBytes = 5, totalBytes = 100) },
                apps = { error("package enumeration broke") },
                categories = { StorageCategories() },
            )
            val outcome = executor.execute(job("storage_overview"))
            assertTrue(outcome is JobOutcome.Ok) // the result still ships
            assertEquals(listOf("exec.storage_overview.apps"), recording.sites)
        } finally {
            Diagnostics.resetForTest()
        }
    }

    @Test
    fun `hidden category failures are reported with a stable site`() {
        val recording = RecordingSink()
        Diagnostics.install(recording)
        try {
            val executor = StorageOverviewExecutor(
                space = { SpaceRow(freeBytes = 5, totalBytes = 100) },
                apps = { emptyList() },
                categories = { error("category query broke") },
            )
            val outcome = executor.execute(job("storage_overview"))
            assertTrue(outcome is JobOutcome.Ok)
            assertEquals(listOf("exec.storage_overview.categories"), recording.sites)
        } finally {
            Diagnostics.resetForTest()
        }
    }
}

/** Records O5 sites; events ignored. */
class RecordingSink : com.example.phonesteward.logic.obs.DiagnosticsSink {
    val sites = mutableListOf<String>()
    override fun errorReport(site: String, error: Throwable) {
        sites += site
    }
    override fun event(level: String, event: String, detail: String) = Unit
    override fun eventLog(): List<com.example.phonesteward.logic.obs.EventEntry> = emptyList()
}

class RuleBatchExecutorTest {

    private fun rules(version: Int = 3, enabled: Boolean = true, auto: Boolean = true) = RulesResponse(
        ruleVersion = version,
        photoWindow = PhotoWindow(enabled = enabled, autoMode = auto, folders = listOf("DCIM/Camera/")),
    )

    private val params = ProtocolJson.encodeToString(
        com.example.phonesteward.net.RuleBatchParams.serializer(),
        com.example.phonesteward.net.RuleBatchParams(
            batchId = "rb-1",
            ruleVersion = 3,
            items = listOf(
                MediaRef("external_primary:images:1", "DCIM/Camera/", "a.jpg", "image/jpeg", 1, 1, 1, false, false),
                MediaRef("external_primary:images:2", "DCIM/Camera/", "b.jpg", "image/jpeg", 1, 1, 1, false, true), // inKeep live
                MediaRef("external_primary:images:3", "DCIM/Camera/", "c.jpg", "image/jpeg", 1, 1, 1, false, false), // gone
            ),
        ),
    )

    private fun executor(
        rules: RulesResponse,
        trashed: MutableList<String>,
    ): RuleBatchExecutor {
        // Rows 1 and 2 still match their indexed MediaRefs (a.jpg, size 1);
        // row 2 is now in Keep. Row 3 has no live row -> missing.
        val live = mapOf(
            "external_primary:images:1" to LiveMediaRow(false, false, 1, "DCIM/Camera/", "a.jpg"),
            "external_primary:images:2" to LiveMediaRow(false, true, 1, "DCIM/Camera/", "b.jpg"),
        )
        return RuleBatchExecutor(
            rules = { rules },
            lookup = { live[it] },
            autoTrash = { items ->
                items.forEach { trashed += it.mediaId }
                items.associate { it.mediaId to AutoTrashResult(-1, "ok") }
            },
        )
    }

    @Test
    fun `trashes unprotected items, skips protected live rows, reports missing`() {
        val trashed = mutableListOf<String>()
        val outcome = executor(rules(), trashed).execute(
            job("rule_batch", ProtocolJson.parseToJsonElement(params) as kotlinx.serialization.json.JsonObject),
        )
        assertTrue(outcome is JobOutcome.Ok)
        val items = kotlinx.serialization.json.Json.decodeFromString(
            com.example.phonesteward.net.RuleBatchData.serializer(),
            (outcome as JobOutcome.Ok).data.toString(),
        ).items
        assertEquals("trashed", items[0].outcome)
        assertEquals("skipped_protected", items[1].outcome)
        assertEquals("missing", items[2].outcome)
        assertEquals(listOf("external_primary:images:1"), trashed)
    }

    @Test
    fun `a recycled id fails closed in the rule batch and never trashes (fix 02-2)`() {
        val trashed = mutableListOf<String>()
        // The live row behind the id is a DIFFERENT item than the indexed MediaRef.
        val recycledExecutor = RuleBatchExecutor(
            rules = { rules() },
            lookup = { LiveMediaRow(false, false, 999, "Movies/", "SOMETHING-ELSE.mp4") },
            autoTrash = { items ->
                items.forEach { trashed += it.mediaId }
                items.associate { it.mediaId to AutoTrashResult(-1, "ok") }
            },
        )
        val outcome = recycledExecutor.execute(
            job(
                "rule_batch",
                ProtocolJson.encodeToJsonElement(
                    com.example.phonesteward.net.RuleBatchParams.serializer(),
                    com.example.phonesteward.net.RuleBatchParams(
                        "rb-10", 3,
                        listOf(
                            com.example.phonesteward.net.MediaRef(
                                "external_primary:images:1", "DCIM/Camera/", "a.jpg",
                                "image/jpeg", 10, 1, 1, false, false,
                            ),
                        ),
                    ),
                ).jsonObject,
            ),
        )
        val data = kotlinx.serialization.json.Json.decodeFromString(
            com.example.phonesteward.net.RuleBatchData.serializer(),
            (outcome as JobOutcome.Ok).data.toString(),
        )
        assertEquals("failed", data.items.single().outcome)
        assertNull(data.items.single().resultCode)
        assertTrue("a mismatched row is never trashed", trashed.isEmpty())
    }

    @Test
    fun `auto-trash outcomes report what Android returned (fix 01-3)`() {
        // Android OK -> trashed; anything else -> failed with its code; nothing -> failed.
        assertEquals("trashed" to -1, AutoTrashOutcome.outcomeFor(AutoTrashResult(-1, "trashed")))
        assertEquals("failed" to 0, AutoTrashOutcome.outcomeFor(AutoTrashResult(0, "MANAGE_MEDIA not granted")))
        assertEquals("failed" to 0, AutoTrashOutcome.outcomeFor(AutoTrashResult(0, "unresolvable mediaId")))
        assertEquals("failed" to null, AutoTrashOutcome.outcomeFor(null))

        // And the executor maps a failed update to `failed`, with the code on the item.
        val failedExecutor = RuleBatchExecutor(
            rules = { rules() },
            lookup = { LiveMediaRow(false, false, 10, "DCIM/Camera/", "a.jpg") },
            autoTrash = { items ->
                items.associate { it.mediaId to AutoTrashResult(0, "row not updated") }
            },
        )
        val outcome = failedExecutor.execute(
            job(
                "rule_batch",
                ProtocolJson.encodeToJsonElement(
                    com.example.phonesteward.net.RuleBatchParams.serializer(),
                    com.example.phonesteward.net.RuleBatchParams(
                        "rb-9", 3,
                        listOf(
                            com.example.phonesteward.net.MediaRef(
                                "external_primary:images:1", "DCIM/Camera/", "a.jpg",
                                "image/jpeg", 10, 1, 1, false, false,
                            ),
                        ),
                    ),
                ).jsonObject,
            ),
        )
        val data = kotlinx.serialization.json.Json.decodeFromString(
            com.example.phonesteward.net.RuleBatchData.serializer(),
            (outcome as JobOutcome.Ok).data.toString(),
        )
        assertEquals("failed", data.items.single().outcome)
        assertEquals(0, data.items.single().resultCode)
    }

    @Test
    fun `a version or mode mismatch answers RULE_CHANGED and acts on nothing`() {
        val trashed = mutableListOf<String>()
        for (wrong in listOf(rules(version = 4), rules(enabled = false), rules(auto = false))) {
            val outcome = executor(wrong, trashed).execute(
                job("rule_batch", ProtocolJson.parseToJsonElement(params) as kotlinx.serialization.json.JsonObject),
            )
            assertTrue(outcome is JobOutcome.Failed)
            assertEquals("RULE_CHANGED", (outcome as JobOutcome.Failed).errorCode)
        }
        assertTrue("nothing was trashed under any mismatch", trashed.isEmpty())
    }
}

class ProposalExecutionPlannerTest {

    private val now = 5_000L

    private fun proposal(vararg items: ProposalItem) = Proposal(
        proposalId = "pr-1",
        title = "t",
        items = items.toList(),
    )

    private val decision = DecisionCheckResponse(
        valid = true, proposalId = "pr-1",
        approvedItemIds = listOf("it-trash", "it-un", "it-cache", "it-advice"),
        expiresAtMs = 9_000,
    )

    @Test
    fun `a refused decision plans nothing`() {
        val planner = ProposalExecutionPlanner(lookup = lookupAllUnprotected, nowMillis = { now })
        val plan = planner.plan(
            proposal(ProposalItem("it-trash", "trash_media", trashTarget())),
            decisionId = "dc-1",
            decision = decision.copy(valid = false),
        )
        assertFalse(plan.allowed)
        assertTrue(plan.trashBatch.isEmpty())
        assertTrue(plan.staticOutcomes.isEmpty())
        assertEquals("decision is not valid", plan.refusalReason)
    }

    @Test
    fun `an expired decision is refused`() {
        val planner = ProposalExecutionPlanner(lookup = lookupAllUnprotected, nowMillis = { now })
        val plan = planner.plan(
            proposal(ProposalItem("it-trash", "trash_media", trashTarget())),
            decisionId = "dc-1",
            decision = decision.copy(expiresAtMs = 1_000),
        )
        assertFalse(plan.allowed)
        assertTrue(plan.refusalReason!!.startsWith("decision expired"))
    }

    @Test
    fun `S12 only approved items are planned, unticked items are not reported at all`() {
        val planner = ProposalExecutionPlanner(lookup = lookupAllUnprotected, nowMillis = { now })
        val plan = planner.plan(
            proposal(
                ProposalItem("it-trash", "trash_media", trashTarget()),
                ProposalItem("it-un", "uninstall_app", buildJsonObject { put("packageName", "com.old") }),
                ProposalItem("it-unticked", "trash_media", trashTarget("external_primary:images:8")),
            ),
            decisionId = "dc-1",
            decision = decision.copy(approvedItemIds = listOf("it-trash", "it-un")),
        )
        assertTrue(plan.allowed)
        assertEquals(listOf("it-trash"), plan.trashBatch.map { it.itemId })
        assertEquals(listOf("com.old"), plan.uninstalls.map { it.packageName })
        // The decision already recorded the unticked item as kept; the app says nothing about it.
        assertTrue("an unticked item has no outcome", plan.staticOutcomes.none { it.itemId == "it-unticked" })
        assertTrue(plan.staticOutcomes.isEmpty())
    }

    @Test
    fun `a protected live row is skipped before any dialog`() {
        val protected = MediaLookup { if (it.endsWith(":9")) matchingRow.copy(isFavourite = true) else null }
        val planner = ProposalExecutionPlanner(lookup = protected, nowMillis = { now })
        val plan = planner.plan(
            proposal(ProposalItem("it-trash", "trash_media", trashTarget())),
            decisionId = "dc-1",
            decision = decision,
        )
        assertTrue(plan.allowed)
        assertTrue("protected item never reaches the trash batch", plan.trashBatch.isEmpty())
        assertEquals("skipped_protected", plan.staticOutcomes.single().outcome)
    }

    @Test
    fun `an inKeep live row is skipped too`() {
        val protected = MediaLookup { matchingRow.copy(inKeep = true) }
        val planner = ProposalExecutionPlanner(lookup = protected, nowMillis = { now })
        val plan = planner.plan(
            proposal(ProposalItem("it-trash", "trash_media", trashTarget())),
            decisionId = "dc-1",
            decision = decision,
        )
        assertTrue(plan.trashBatch.isEmpty())
        assertEquals("skipped_protected", plan.staticOutcomes.single().outcome)
    }

    @Test
    fun `a missing live row fails closed and never reaches the dialog (fix 01-2)`() {
        val planner = ProposalExecutionPlanner(lookup = MediaLookup { null }, nowMillis = { now })
        val plan = planner.plan(
            proposal(ProposalItem("it-trash", "trash_media", trashTarget())),
            decisionId = "dc-1",
            decision = decision,
        )
        assertTrue(plan.allowed)
        assertTrue("no trash attempt without a live row", plan.trashBatch.isEmpty())
        val outcome = plan.staticOutcomes.single()
        assertEquals("failed", outcome.outcome)
        assertEquals("live row missing", plan.details[outcome.itemId])
    }

    @Test
    fun `a live row that no longer matches the proposal fails closed (fix 01-2)`() {
        // The MediaStore id was recycled: the row behind it is a different item.
        val recycled = matchingRow.copy(displayName = "SOMETHING-ELSE.jpg", sizeBytes = 999)
        val planner = ProposalExecutionPlanner(lookup = MediaLookup { recycled }, nowMillis = { now })
        val plan = planner.plan(
            proposal(ProposalItem("it-trash", "trash_media", trashTarget())),
            decisionId = "dc-1",
            decision = decision,
        )
        assertTrue(plan.trashBatch.isEmpty())
        val outcome = plan.staticOutcomes.single()
        assertEquals("failed", outcome.outcome)
        assertEquals("live row no longer matches the proposed item", plan.details[outcome.itemId])

        // A size drift alone is also a mismatch.
        val resized = matchingRow.copy(sizeBytes = 11)
        val plan2 = ProposalExecutionPlanner(lookup = MediaLookup { resized }, nowMillis = { now }).plan(
            proposal(ProposalItem("it-trash", "trash_media", trashTarget())),
            decisionId = "dc-1",
            decision = decision,
        )
        assertTrue(plan2.trashBatch.isEmpty())
        assertEquals("failed", plan2.staticOutcomes.single().outcome)
    }

    @Test
    fun `advice items are done without any Android call`() {
        val planner = ProposalExecutionPlanner(lookup = lookupAllUnprotected, nowMillis = { now })
        val plan = planner.plan(
            proposal(ProposalItem("it-advice", "advice", buildJsonObject { put("text", "hi") })),
            decisionId = "dc-1",
            decision = decision,
        )
        assertEquals("done", plan.staticOutcomes.single().outcome)
    }

    @Test
    fun `outcomes map the one trash dialog result to the whole batch`() {
        val planner = ProposalExecutionPlanner(lookup = lookupAllUnprotected, nowMillis = { now })
        val plan = planner.plan(
            proposal(
                ProposalItem("it-trash", "trash_media", trashTarget()),
                ProposalItem("it-un", "uninstall_app", buildJsonObject { put("packageName", "com.old") }),
                ProposalItem("it-cache", "clear_app_cache", buildJsonObject { put("packageName", "com.c") }),
                ProposalItem("it-advice", "advice", buildJsonObject { put("text", "hi") }),
            ),
            decisionId = "dc-1",
            decision = decision,
        )
        val ok = planner.outcomes(
            plan,
            trashResultCode = ActivityResults.OK,
            uninstallVerdicts = mapOf("it-un" to UninstallOutcome.Verdict("done", null, 4_096L)),
            cacheResults = mapOf("it-cache" to CacheClearOutcome.Verdict("done", 1_234L)),
        ).associateBy { it.itemId }
        assertEquals("done", ok["it-trash"]!!.outcome)
        assertEquals(-1, ok["it-trash"]!!.resultCode)
        assertEquals("done", ok["it-un"]!!.outcome)
        assertEquals("the uninstall reports the size measured before", 4_096L, ok["it-un"]!!.bytesFreed)
        assertEquals("done", ok["it-cache"]!!.outcome)
        assertEquals(1_234L, ok["it-cache"]!!.bytesFreed)
        assertEquals("done", ok["it-advice"]!!.outcome)

        val cancelled = planner.outcomes(
            plan,
            trashResultCode = ActivityResults.CANCELLED,
            uninstallVerdicts = mapOf("it-un" to UninstallOutcome.Verdict("cancelled", ActivityResults.CANCELLED, null)),
            cacheResults = emptyMap(),
        ).associateBy { it.itemId }
        assertEquals("cancelled", cancelled["it-trash"]!!.outcome)
        assertEquals("cancelled", cancelled["it-un"]!!.outcome)
        assertEquals("cancelled", cancelled["it-cache"]!!.outcome)
    }

    @Test
    fun `the cache verdict is decided by the before and after readings (fix 01-6)`() {
        // fell -> done with bytesFreed
        assertEquals(CacheClearOutcome.Verdict("done", 700L), CacheClearOutcome.decide(1_000L, 300L, launched = true))
        // unchanged or grew -> cancelled
        assertEquals(CacheClearOutcome.Verdict("cancelled", null), CacheClearOutcome.decide(1_000L, 1_000L, launched = true))
        assertEquals(CacheClearOutcome.Verdict("cancelled", null), CacheClearOutcome.decide(1_000L, 1_100L, launched = true))
        // a missing reading is not a success
        assertEquals(CacheClearOutcome.Verdict("cancelled", null), CacheClearOutcome.decide(null, 300L, launched = true))
        assertEquals(CacheClearOutcome.Verdict("cancelled", null), CacheClearOutcome.decide(1_000L, null, launched = true))
        // a failed launch is failed, never done
        assertEquals(CacheClearOutcome.Verdict("failed", null), CacheClearOutcome.decide(1_000L, 100L, launched = false))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `outcomes refuse a refused plan`() {
        val planner = ProposalExecutionPlanner(lookup = lookupAllUnprotected, nowMillis = { now })
        val plan = planner.plan(
            proposal(ProposalItem("it-trash", "trash_media", trashTarget())),
            decisionId = "dc-1",
            decision = decision.copy(valid = false),
        )
        planner.outcomes(plan, -1, emptyMap(), emptyMap())
    }
}
