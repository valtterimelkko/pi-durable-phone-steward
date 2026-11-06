package com.example.phonesteward.e2e

import android.os.Environment
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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
 * Flow E1, restore (A11.3), on a real Android image: the steward proposes moving a folder (`delete_files`), the owner
 * approves (ONE in-app question), the folder really moves into `.StewardTrash/<date>/<proposal>/`, and Status →
 * Open Steward trash lists the batch from the trash itself (no manifest) with a Restore button. Restoring asks
 * nothing, puts the folder back, and reports `POST /v1/files/restored`. A file that reappears at the original path
 * keeps the item in the trash with a plain reason. Invented folder and file names only.
 */
@RunWith(AndroidJUnit4::class)
class FlowE1RestoreTest : E2eBase() {
    override val flow = "restore"

    private val pkg get() = context.packageName
    private val sdcard: File get() = Environment.getExternalStorageDirectory()
    private val testRoot get() = File(sdcard, "P8Test")
    private val trashRoot get() = File(sdcard, ".StewardTrash")

    @Before
    fun grantAndCreate() {
        shell("appops set $pkg MANAGE_EXTERNAL_STORAGE allow")
        shell("appops set $pkg GET_USAGE_STATS allow")
        cleanUp()
        make("OfflineMaps/230606/tiles-a.bin", 2_000_000)
        make("OfflineMaps/230606/tiles-b.bin", 1_000_000)
        make("OfflineMaps/230701/tiles-c.bin", 400_000)
    }

    @After
    fun cleanUp() {
        testRoot.deleteRecursively()
        trashRoot.deleteRecursively()
    }

    private fun make(rel: String, bytes: Int) {
        val f = File(testRoot, rel)
        f.parentFile!!.mkdirs()
        f.writeBytes(ByteArray(bytes) { 4 })
    }

    private fun land() {
        launchApp()
        pairAndLand()
    }

    /** The delete_files target the server would build from a files_query report (A10.1). */
    private fun targetFor(path: String): JsonObject {
        val id = fake.queueJob("files_query", buildJsonObject { put("op", "du"); put("paths", buildJsonArray { add(JsonPrimitive(path)) }) })
        eventually("the phone answered job $id") { fake.jobResults.containsKey(id) }
        val row = fake.jobResults.getValue(id)["data"]!!.jsonObject["paths"]!!.jsonArray.single().jsonObject
        return buildJsonObject {
            put("path", row["path"]!!.jsonPrimitive.content)
            put("type", row["type"]!!.jsonPrimitive.content)
            put("sizeBytes", row["bytes"]!!.jsonPrimitive.content.toLong())
            put("modifiedMs", row["modifiedMs"]!!.jsonPrimitive.content.toLong())
            if (row["type"]!!.jsonPrimitive.content == "dir") put("fileCount", row["files"]!!.jsonPrimitive.content.toLong())
        }
    }

    private fun proposalFor(path: String): Proposal = Proposal(
        proposalId = "pr-restore",
        createdAtMs = System.currentTimeMillis() - 3_600_000L,
        source = "agent",
        title = "Move old map downloads to the Steward trash",
        reason = "Invented folders for the restore test.",
        bytesEstimate = 3_000_000L,
        status = "open",
        items = listOf(ProposalItem("it-r0", "delete_files", targetFor(path), bytesEstimate = 3_000_000L, reason = "Not opened for months.")),
    )

    private fun offer(p: Proposal) {
        fake.addProposal(p)
        fake.proposalEvent(p.proposalId, p.title)
    }

    /** Approves the delete proposal through the real screen and waits for the folder to be in the trash. */
    private fun moveToTheTrash() {
        val p = proposalFor("P8Test/OfflineMaps/230606")
        offer(p)
        openTab("Proposals")
        waitForText(p.title)
        click(tag("approve-button"))
        waitForTag("confirm-dialog")
        clickTag("confirm-yes")
        waitForText("Moved 1 folder", substring = true)
        assertFalse(File(testRoot, "OfflineMaps/230606").exists())
        assertTrue(trashRoot.walkTopDown().any { it.isFile && it.name == "tiles-a.bin" })
    }

    private fun openTheTrashScreen() {
        openTab("Status")
        waitForTag("open-trash-button")
        clickTag("open-trash-button")
        waitForTag("trash-list")
    }

    @Test
    fun restoringABatchPutsTheFolderBackAndTellsTheSteward() {
        land()
        moveToTheTrash()
        openTheTrashScreen()
        waitForText("P8Test/OfflineMaps/230606")
        shot("trash")
        clickTag("restore-all")
        assertFalse("a restore asks nothing: it only puts things back", hasTag("confirm-dialog"))
        waitForText("Put back 1 folder", substring = true)
        shot("restored")
        assertTrue("the folder is back", File(testRoot, "OfflineMaps/230606/tiles-a.bin").exists())
        assertTrue(File(testRoot, "OfflineMaps/230606/tiles-b.bin").exists())
        assertFalse("the trash gave it up", trashRoot.walkTopDown().any { it.isFile && it.name == "tiles-a.bin" })
        eventually("the restored report reached the steward") { fake.requestsTo("POST", "/v1/files/restored").isNotEmpty() }
        val body = fake.requestsTo("POST", "/v1/files/restored").last()
        assertEquals("pr-restore", body.str("proposalId"))
        assertTrue(body.str("requestId").orEmpty().startsWith("restore-"))
        val items = body.json["items"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("P8Test/OfflineMaps/230606"), items.map { it["path"]!!.jsonPrimitive.content })
        assertEquals(3_000_000L, items.single()["bytes"]!!.jsonPrimitive.content.toLong())
        // The server was told what the trash holds now.
        eventually("the status post names an empty trash") {
            fake.requestsTo("POST", "/v1/device/status").any { it.body.contains("\"stewardTrash\":{\"bytes\":0") }
        }
    }

    @Test
    fun aFileThatReappearedKeepsTheItemInTheTrashWithAPlainReason() {
        land()
        moveToTheTrash()
        // Something now stands exactly where the folder was: the restore must not go through it.
        File(testRoot, "OfflineMaps/230606").writeBytes(ByteArray(10))
        openTheTrashScreen()
        waitForText("something is at that place now")
        shot("trash-blocked")
        clickTag("restore-all")
        waitForText("1 item stays in the trash: something is at that place now.")
        shot("restore-blocked")
        assertTrue("the file that appeared is untouched", File(testRoot, "OfflineMaps/230606").isFile)
        assertEquals(10L, File(testRoot, "OfflineMaps/230606").length())
        assertTrue("the folder stays in the trash", trashRoot.walkTopDown().any { it.isFile && it.name == "tiles-a.bin" })
        assertTrue("nothing went back, so nothing is reported", fake.requestsTo("POST", "/v1/files/restored").isEmpty())
    }
}
