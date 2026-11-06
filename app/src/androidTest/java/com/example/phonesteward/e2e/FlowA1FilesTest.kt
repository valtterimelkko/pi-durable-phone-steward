package com.example.phonesteward.e2e

import android.os.Environment
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import com.example.phonesteward.net.Proposal
import com.example.phonesteward.net.ProposalItem

/**
 * Flow A1, files (A10.1), on a real Android image: the steward asks about folders the test creates under /sdcard/ (a
 * `files_query` job answered from the real storage with all-files access), proposes moving two of them (`delete_files`),
 * and the owner approves: ONE in-app question, the folders move into `.StewardTrash/<date>/<proposal>/`, Status shows what
 * the trash holds, and Empty Steward trash (one question) gives the space back. Invented folder and file names only.
 */
@RunWith(AndroidJUnit4::class)
class FlowA1FilesTest : E2eBase() {
    override val flow = "files"

    private val pkg get() = context.packageName
    private val sdcard: File get() = Environment.getExternalStorageDirectory()
    private val testRoot get() = File(sdcard, "P7Test")
    private val trashRoot get() = File(sdcard, ".StewardTrash")

    @Before
    fun grantAndCreate() {
        shell("appops set $pkg MANAGE_EXTERNAL_STORAGE allow")
        shell("appops set $pkg GET_USAGE_STATS allow")
        cleanUp()
        make("OfflineMaps/230606/tiles-a.bin", 2_000_000)
        make("OfflineMaps/230606/tiles-b.bin", 1_000_000)
        make("OfflineMaps/230701/tiles-c.bin", 1_000_000)
        make("OfflineMaps/230805/tiles-d.bin", 200_000)
        make("Keep/precious.bin", 10_000)
    }

    @After
    fun cleanUp() {
        testRoot.deleteRecursively()
        trashRoot.deleteRecursively()
    }

    private fun make(rel: String, bytes: Int) {
        val f = File(testRoot, rel)
        f.parentFile!!.mkdirs()
        f.writeBytes(ByteArray(bytes) { 3 })
    }

    private fun land() {
        launchApp()
        pairAndLand()
    }

    private fun job(params: JsonObject): JsonObject {
        val id = fake.queueJob("files_query", params)
        eventually("the phone answered job $id") { fake.jobResults.containsKey(id) }
        return fake.jobResults.getValue(id)
    }

    private fun entriesOf(result: JsonObject, key: String = "entries"): List<JsonObject> =
        result["data"]!!.jsonObject[key]!!.jsonArray.map { it.jsonObject }

    /** The delete_files target the server would build from a files_query report (A10.1). */
    private fun targetFor(path: String): JsonObject {
        val result = job(buildJsonObject { put("op", "du"); put("paths", buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive(path)) }) })
        val row = result["data"]!!.jsonObject["paths"]!!.jsonArray.single().jsonObject
        return buildJsonObject {
            put("path", row["path"]!!.jsonPrimitive.content)
            put("type", row["type"]!!.jsonPrimitive.content)
            put("sizeBytes", row["bytes"]!!.jsonPrimitive.content.toLong())
            put("modifiedMs", row["modifiedMs"]!!.jsonPrimitive.content.toLong())
            if (row["type"]!!.jsonPrimitive.content == "dir") put("fileCount", row["files"]!!.jsonPrimitive.content.toLong())
        }
    }

    private fun proposalFor(vararg paths: String): Proposal = Proposal(
        proposalId = "pr-files",
        createdAtMs = System.currentTimeMillis() - 3_600_000L,
        source = "agent",
        title = "Move old map downloads to the Steward trash",
        reason = "Invented folders for the file test.",
        bytesEstimate = 1_700_000L,
        status = "open",
        items = paths.mapIndexed { i, p ->
            ProposalItem("it-f$i", "delete_files", targetFor(p), bytesEstimate = 1_000_000L, reason = "Not opened for months.")
        },
    )

    /** Adds the proposal and tells the phone through the stream, as the server does when the steward writes one. */
    private fun offer(p: Proposal) {
        fake.addProposal(p)
        fake.proposalEvent(p.proposalId, p.title)
    }

    private fun reportedItems(): Map<String, JsonObject> {
        eventually("the execution report reached the steward") { fake.requestsTo("POST", "/v1/proposals/pr-files/execution").isNotEmpty() }
        return fake.requestsTo("POST", "/v1/proposals/pr-files/execution").last().json["items"]!!.jsonArray
            .associate { it.jsonObject["itemId"]!!.jsonPrimitive.content to it.jsonObject }
    }

    // ---- the query ----

    @Test
    fun filesQueryListsFindsAndMeasuresTheRealStorage() {
        land()
        val list = job(buildJsonObject { put("op", "list"); put("path", "P7Test/OfflineMaps"); put("depth", 1) })
        assertEquals("ok", list["status"]!!.jsonPrimitive.content)
        val entries = entriesOf(list)
        assertEquals(setOf("P7Test/OfflineMaps/230606", "P7Test/OfflineMaps/230701", "P7Test/OfflineMaps/230805"), entries.map { it["path"]!!.jsonPrimitive.content }.toSet())
        val first = entries.single { it["path"]!!.jsonPrimitive.content.endsWith("230606") }
        assertEquals("dir", first["type"]!!.jsonPrimitive.content)
        assertEquals("3000000", first["sizeBytes"]!!.jsonPrimitive.content)
        assertEquals("2", first["fileCount"]!!.jsonPrimitive.content)

        val find = job(buildJsonObject { put("op", "find"); put("root", "P7Test"); put("namePattern", "TILES-*.BIN"); put("type", "file"); put("minBytes", 1_500_000) })
        assertEquals(setOf("tiles-a.bin"), entriesOf(find, "items").map { it["path"]!!.jsonPrimitive.content.substringAfterLast('/') }.toSet())

        val du = job(buildJsonObject { put("op", "du"); put("paths", buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive("P7Test")) }) })
        val row = du["data"]!!.jsonObject["paths"]!!.jsonArray.single().jsonObject
        assertEquals("4210000", row["bytes"]!!.jsonPrimitive.content)
        assertEquals("dir", row["type"]!!.jsonPrimitive.content)
        assertTrue(row["modifiedMs"]!!.jsonPrimitive.content.toLong() > 0)

        val android = job(buildJsonObject { put("op", "list"); put("path", "Android") })
        val data = entriesOf(android).firstOrNull { it["path"]!!.jsonPrimitive.content == "Android/data" }
        assertTrue("Android/data is reported as unreadable, not walked", data == null || data["unreadable"]?.jsonPrimitive?.content == "true")
    }

    @Test
    fun badPathsAreRefusedWithBAD_PATH() {
        land()
        for (p in listOf("../etc", "/sdcard", "P7Test/../..")) {
            val r = job(buildJsonObject { put("op", "list"); put("path", p) })
            assertEquals("error", r["status"]!!.jsonPrimitive.content)
            assertEquals("BAD_PATH", r["error"]!!.jsonObject["code"]!!.jsonPrimitive.content)
        }
    }

    // ---- delete_files ----

    @Test
    fun approvingFoldersAsksOnceMovesThemToTheStewardTrashAndEmptyingFreesTheSpace() {
        land()
        val p = proposalFor("P7Test/OfflineMaps/230606", "P7Test/OfflineMaps/230701")
        offer(p)
        openTab("Proposals")
        waitForText(p.title)
        waitForText("Move to the Steward trash", substring = true)
        shot("proposal")
        click(tag("approve-button"))
        // ONE in-app question for the whole approval; nothing has moved yet.
        waitForTag("confirm-dialog")
        waitForText("Move 2 folders (4 MB) to the Steward trash?")
        assertTrue("nothing moves before the owner answers", File(testRoot, "OfflineMaps/230606/tiles-a.bin").exists())
        shot("confirm")
        clickTag("confirm-yes")
        waitForText("Moved 2 folders (4 MB) to the Steward trash. Empty it on Status to free the space now.")
        shot("result")
        assertFalse(File(testRoot, "OfflineMaps/230606").exists())
        assertFalse(File(testRoot, "OfflineMaps/230701").exists())
        assertTrue("the third folder was not in the proposal", File(testRoot, "OfflineMaps/230805/tiles-d.bin").exists())
        assertTrue("the Keep folder is untouched", File(testRoot, "Keep/precious.bin").exists())
        val moved = trashRoot.walkTopDown().filter { it.isFile && it.name.startsWith("tiles-") }.map { it.relativeTo(trashRoot).path }.toList()
        assertEquals(3, moved.size)
        assertTrue(moved.toString(), moved.all { it.contains("/pr-files/P7Test/OfflineMaps/") })
        val items = reportedItems()
        assertEquals("done", items.getValue("it-f0")["outcome"]!!.jsonPrimitive.content)
        assertEquals("3000000", items.getValue("it-f0")["trashedBytes"]!!.jsonPrimitive.content)
        assertEquals("0", items.getValue("it-f0")["bytesFreed"]!!.jsonPrimitive.content)
        assertEquals("executed", fake.statusOf("pr-files"))

        // The server was told what the trash holds, and Status shows it.
        eventually("the status post names the trash") {
            fake.requestsTo("POST", "/v1/device/status").any { it.body.contains("\"stewardTrash\":{\"bytes\":4000000") }
        }
        openTab("Status")
        waitForTag("empty-trash-button")
        waitForText("Holds 4 MB", substring = true)
        shot("status-trash")
        click(tag("empty-trash-button"))
        waitForTag("confirm-dialog")
        waitForText("Empty the Steward trash?")
        shot("empty-confirm")
        clickTag("confirm-yes")
        waitForText("Steward trash emptied: 4 MB freed.")
        shot("status-emptied")
        assertFalse("the trash is empty", trashRoot.walkTopDown().any { it.isFile && it.name.startsWith("tiles-") })
        assertFalse("no greyed button is left", hasTag("empty-trash-button"))
        assertTrue("the third folder still stands", File(testRoot, "OfflineMaps/230805/tiles-d.bin").exists())
    }

    @Test
    fun decliningTheQuestionMovesNothing() {
        land()
        val p = proposalFor("P7Test/OfflineMaps/230805")
        offer(p)
        openTab("Proposals")
        waitForText(p.title)
        click(tag("approve-button"))
        waitForTag("confirm-dialog")
        clickTag("confirm-no")
        waitForText("Nothing was done", substring = true)
        assertTrue(File(testRoot, "OfflineMaps/230805/tiles-d.bin").exists())
        assertFalse(trashRoot.exists() && trashRoot.walkTopDown().any { it.isFile && it.name.startsWith("tiles-") })
        assertEquals("cancelled", reportedItems().getValue("it-f0")["outcome"]!!.jsonPrimitive.content)
    }

    @Test
    fun aFolderThatChangedSinceTheReviewIsRefusedBeforeAnyQuestion() {
        land()
        val p = proposalFor("P7Test/OfflineMaps/230805")
        make("OfflineMaps/230805/tiles-new.bin", 1_000) // arrives after the review
        offer(p)
        openTab("Proposals")
        waitForText(p.title)
        click(tag("approve-button"))
        waitForText("This item changed since the review, so nothing was moved. The steward will propose it again.")
        assertFalse("no question for a refused item", hasTag("confirm-dialog"))
        assertTrue(File(testRoot, "OfflineMaps/230805/tiles-d.bin").exists())
        assertEquals("failed", reportedItems().getValue("it-f0")["outcome"]!!.jsonPrimitive.content)
        shot("changed")
    }

    @Test
    fun theKeepFolderCanNeverBeMoved() {
        land()
        val p = proposalFor("P7Test/Keep")
        offer(p)
        openTab("Proposals")
        waitForText(p.title)
        click(tag("approve-button"))
        waitForText("changed since the review", substring = true)
        assertTrue(File(testRoot, "Keep/precious.bin").exists())
        assertEquals("failed", reportedItems().getValue("it-f0")["outcome"]!!.jsonPrimitive.content)
    }
}
