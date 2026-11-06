package com.example.phonesteward.logic.exec

import java.io.File
import java.time.ZoneOffset
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import com.example.phonesteward.logic.ActionResult
import com.example.phonesteward.logic.files.FilesPort
import com.example.phonesteward.logic.files.MoveResult
import com.example.phonesteward.logic.files.StewardTrash
import com.example.phonesteward.net.DecisionCheckResponse
import com.example.phonesteward.net.Proposal
import com.example.phonesteward.net.ProposalItem

/**
 * A10.1 `delete_files` on the phone: the plan re-stats each path (the A6 identity rule), the paths that can
 * never be deleted are refused here too, the owner confirms once, and the result reads in plain words.
 */
class DeleteFilesTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var root: File
    private val now = 1_791_460_000_000L // 2026-10-08

    private fun trash() = StewardTrash(root, ZoneOffset.UTC) { now }

    @Before
    fun setUp() {
        root = tmp.newFolder("sdcard")
    }

    private fun file(rel: String, bytes: Int) = File(root, rel).also { it.parentFile.mkdirs(); it.writeBytes(ByteArray(bytes) { 1 }) }

    /** The target as the server relays it from an earlier files_query report. */
    private fun targetOf(path: String): JsonObject {
        val s = trash().stat(path)!!
        return buildJsonObject {
            put("path", path); put("type", s.type); put("sizeBytes", s.sizeBytes); put("modifiedMs", s.modifiedMs)
            s.fileCount?.let { put("fileCount", it) }
        }
    }

    private fun item(id: String, path: String, target: JsonObject = targetOf(path)) = ProposalItem(id, "delete_files", target, bytesEstimate = 0)

    private fun planner(files: FilesPort? = trash()) =
        ProposalExecutionPlanner(lookup = { null }, nowMillis = { 5_000L }, files = files)

    private fun decision(vararg ids: String) =
        DecisionCheckResponse(valid = true, proposalId = "pr-7", approvedItemIds = ids.toList(), expiresAtMs = 9_000)

    private fun proposal(vararg items: ProposalItem) = Proposal(proposalId = "pr-7", title = "Free the maps", items = items.toList())

    // ---- planning ----

    @Test
    fun `an unchanged folder is planned for the trash`() {
        file("MapsWithMe/230606/a.mwm", 1000)
        file("MapsWithMe/230606/b.mwm", 1600)
        val p = proposal(item("it-1", "MapsWithMe/230606"))
        val plan = planner().plan(p, "dc-1", decision("it-1"))
        assertEquals(listOf("MapsWithMe/230606"), plan.deleteFiles.map { it.path })
        assertEquals(2600L, plan.deleteFiles.single().sizeBytes)
        assertEquals("dir", plan.deleteFiles.single().type)
        assertTrue(plan.staticOutcomes.isEmpty())
    }

    @Test
    fun `a changed size fails the item with no action`() {
        file("Download/a.bin", 100)
        val p = proposal(item("it-1", "Download/a.bin"))
        file("Download/a.bin", 150) // the file grew after the review
        val plan = planner().plan(p, "dc-1", decision("it-1"))
        assertTrue(plan.deleteFiles.isEmpty())
        assertEquals("failed", plan.staticOutcomes.single().outcome)
        assertTrue(plan.details["it-1"]!!.contains("changed"))
        assertTrue(File(root, "Download/a.bin").exists())
    }

    @Test
    fun `a folder with a new file fails on the file count`() {
        file("Maps/a.mwm", 100)
        val p = proposal(item("it-1", "Maps"))
        file("Maps/new.mwm", 100)
        val plan = planner().plan(p, "dc-1", decision("it-1"))
        assertEquals("failed", plan.staticOutcomes.single().outcome)
    }

    @Test
    fun `a missing path fails the item`() {
        file("Download/a.bin", 100)
        val p = proposal(item("it-1", "Download/a.bin"))
        File(root, "Download/a.bin").delete()
        val plan = planner().plan(p, "dc-1", decision("it-1"))
        assertEquals("failed", plan.staticOutcomes.single().outcome)
        assertTrue(plan.details["it-1"]!!.contains("no longer"))
    }

    @Test
    fun `paths that can never be deleted are refused on the phone too`() {
        file("Android/media/x/1.bin", 5)
        file("DCIM/Camera/1.jpg", 5)
        file("Docs/Keep/a.txt", 5)
        val items = listOf("Android/media", "DCIM/Camera", "Docs/Keep").mapIndexed { i, path -> item("it-$i", path) }
        val plan = planner().plan(proposal(*items.toTypedArray()), "dc-1", decision("it-0", "it-1", "it-2"))
        assertTrue(plan.deleteFiles.isEmpty())
        assertEquals(listOf("failed", "failed", "failed"), plan.staticOutcomes.map { it.outcome })
        assertTrue(plan.details.values.all { it.contains("never") || it.contains("not allowed") || it.contains("refused") })
    }

    @Test
    fun `the root and an unreadable target are refused`() {
        val bad = buildJsonObject { put("path", ""); put("type", "dir"); put("sizeBytes", 1); put("modifiedMs", 1) }
        val plan = planner().plan(proposal(item("it-1", "", bad)), "dc-1", decision("it-1"))
        assertEquals("failed", plan.staticOutcomes.single().outcome)
        val junk = buildJsonObject { put("nonsense", true) }
        val plan2 = planner().plan(proposal(item("it-2", "x", junk)), "dc-1", decision("it-2"))
        assertEquals("failed", plan2.staticOutcomes.single().outcome)
    }

    @Test
    fun `without a files port every delete_files item fails`() {
        file("Download/a.bin", 100)
        val p = proposal(item("it-1", "Download/a.bin"))
        val plan = planner(files = null).plan(p, "dc-1", decision("it-1"))
        assertEquals("failed", plan.staticOutcomes.single().outcome)
    }

    @Test
    fun `an unticked file is not reported and not planned`() {
        file("Download/a.bin", 100)
        file("Download/b.bin", 100)
        val p = proposal(item("it-1", "Download/a.bin"), item("it-2", "Download/b.bin"))
        val plan = planner().plan(p, "dc-1", decision("it-1"))
        assertEquals(listOf("Download/a.bin"), plan.deleteFiles.map { it.path })
        assertTrue(plan.staticOutcomes.isEmpty())
    }

    // ---- outcomes ----

    @Test
    fun `moved items report done with the size in trashedBytes and nothing freed yet`() {
        file("Maps/a.mwm", 1000)
        val p = proposal(item("it-1", "Maps"))
        val pl = planner()
        val plan = pl.plan(p, "dc-1", decision("it-1"))
        val out = pl.outcomes(plan, null, emptyMap(), emptyMap(), deleteResults = mapOf("it-1" to MoveResult.Moved(1000)))
        val o = out.single()
        assertEquals("done", o.outcome)
        assertEquals(0L, o.bytesFreed)
        assertEquals(1000L, o.trashedBytes)
    }

    @Test
    fun `a failed move reports failed and a declined confirmation reports cancelled`() {
        file("Maps/a.mwm", 1000)
        val p = proposal(item("it-1", "Maps"))
        val pl = planner()
        val plan = pl.plan(p, "dc-1", decision("it-1"))
        val failed = pl.outcomes(plan, null, emptyMap(), emptyMap(), deleteResults = mapOf("it-1" to MoveResult.Failed("busy"))).single()
        assertEquals("failed", failed.outcome)
        assertNull(failed.trashedBytes)
        val declined = pl.outcomes(plan, null, emptyMap(), emptyMap(), deleteConfirmed = false).single()
        assertEquals("cancelled", declined.outcome)
    }

    // ---- the owner's words ----

    private fun notice(p: Proposal, ticked: List<String>, moves: Map<String, MoveResult> = emptyMap(), confirmed: Boolean = true): ActionResult {
        val pl = planner()
        val plan = pl.plan(p, "dc-1", decision(*ticked.toTypedArray()))
        val outcomes = pl.outcomes(plan, null, emptyMap(), emptyMap(), deleteResults = moves, deleteConfirmed = confirmed)
        return ExecutionNotice.result(p, plan, outcomes, allCachesVerdict = null)
    }

    @Test
    fun `three folders moved read exactly as the owner was promised`() {
        listOf("A", "B", "C").forEach { file("Maps/$it/x.bin", 10) }
        val p = proposal(item("i1", "Maps/A"), item("i2", "Maps/B"), item("i3", "Maps/C"))
        // Make the sizes 2.6 GB in total without writing 2.6 GB: the notice reads the moves' sizes.
        val moves = mapOf("i1" to MoveResult.Moved(1_000_000_000), "i2" to MoveResult.Moved(1_000_000_000), "i3" to MoveResult.Moved(600_000_000))
        val r = notice(p, listOf("i1", "i2", "i3"), moves)
        assertEquals("Moved 3 folders (2.6 GB) to the Steward trash. Empty it on Status to free the space now.", r.text)
        assertEquals(ActionResult.Kind.SUCCESS, r.kind)
    }

    @Test
    fun `one file reads in the singular`() {
        file("Download/a.bin", 10)
        val r = notice(proposal(item("i1", "Download/a.bin")), listOf("i1"), mapOf("i1" to MoveResult.Moved(5_000_000)))
        assertEquals("Moved 1 file (5 MB) to the Steward trash. Empty it on Status to free the space now.", r.text)
    }

    @Test
    fun `a mix of folders and files names both`() {
        file("Maps/A/x.bin", 10)
        file("Maps/B/x.bin", 10)
        file("Download/a.bin", 10)
        val p = proposal(item("i1", "Maps/A"), item("i2", "Maps/B"), item("i3", "Download/a.bin"))
        val moves = mapOf("i1" to MoveResult.Moved(1_000_000), "i2" to MoveResult.Moved(1_000_000), "i3" to MoveResult.Moved(1_000_000))
        assertEquals(
            "Moved 2 folders and 1 file (3 MB) to the Steward trash. Empty it on Status to free the space now.",
            notice(p, listOf("i1", "i2", "i3"), moves).text,
        )
    }

    @Test
    fun `some moved and some refused says what happened to each`() {
        file("Maps/A/x.bin", 10)
        file("Maps/B/x.bin", 10)
        val p = proposal(item("i1", "Maps/A"), item("i2", "Maps/B"))
        val moves = mapOf("i1" to MoveResult.Moved(2_000_000), "i2" to MoveResult.Failed("busy"))
        val r = notice(p, listOf("i1", "i2"), moves)
        assertEquals(
            "Moved 1 of 2 items (2 MB) to the Steward trash. 1 could not be moved and was left alone. Empty it on Status to free the space now.",
            r.text,
        )
        assertEquals(ActionResult.Kind.INFO, r.kind)
    }

    @Test
    fun `everything refused before the confirmation says nothing was moved and why`() {
        file("Download/a.bin", 10)
        val p = proposal(item("i1", "Download/a.bin"))
        file("Download/a.bin", 99)
        val r = notice(p, listOf("i1"))
        assertEquals("This item changed since the review, so nothing was moved. The steward will propose it again.", r.text)
        assertEquals(ActionResult.Kind.ERROR, r.kind)
        file("Download/b.bin", 10)
        val p2 = proposal(item("i2", "Download/b.bin"), item("i3", "Download/a.bin"))
        file("Download/b.bin", 11)
        file("Download/a.bin", 100)
        assertEquals(
            "These items changed since the review, so nothing was moved. The steward will propose them again.",
            notice(p2, listOf("i2", "i3")).text,
        )
    }

    @Test
    fun `declining the confirmation is the usual nothing done line`() {
        file("Download/a.bin", 10)
        val r = notice(proposal(item("i1", "Download/a.bin")), listOf("i1"), confirmed = false)
        assertEquals(AllCachesNotice.NOTHING_DONE, r.text)
    }

    // ---- the one confirmation ----

    @Test
    fun `the confirmation names what moves, its size, and that it is not gone yet`() {
        file("Maps/A/x.bin", 1000)
        file("Maps/B/x.bin", 1600)
        val p = proposal(item("i1", "Maps/A"), item("i2", "Maps/B"))
        val pl = planner()
        val plan = pl.plan(p, "dc-1", decision("i1", "i2"))
        val c = DeleteFilesConfirm.of(plan.deleteFiles)
        assertEquals("Move 2 folders (2.6 KB) to the Steward trash?", c.title)
        assertTrue(c.body, c.body.contains("30 days"))
        assertTrue(c.body, c.body.contains("Empty Steward trash"))
        assertFalse(c.body.contains("MapsWithMe")) // no file names in logs or titles beyond what the owner already reviewed
    }
}
