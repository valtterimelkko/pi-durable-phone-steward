package com.example.phonesteward.ui

import android.content.Context
import android.os.Looper
import androidx.startup.InitializationProvider
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.time.ZoneOffset
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import com.example.phonesteward.android.AppGraph
import com.example.phonesteward.android.KeystoreIdentityStore
import com.example.phonesteward.logic.ActionResult
import com.example.phonesteward.logic.IdentityStore
import com.example.phonesteward.logic.PairedIdentity
import com.example.phonesteward.logic.files.RestoredOutbox
import com.example.phonesteward.logic.files.StewardTrash
import com.example.phonesteward.logic.files.TrashBatch
import com.example.phonesteward.logic.files.TrashItem

/**
 * A11.3 on the phone, through the real view model: the restore screen's data (the merge-descend dry run), restoring one
 * item or a whole batch with no question, the result line in plain words, the `POST /v1/files/restored` report, and a
 * failed report that never blocks the restore and is retried on the next sync.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class TrashRestoreFlowTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private var server: MockWebServer? = null
    private lateinit var root: File
    private lateinit var outboxDir: File
    private val now = 1_791_460_000_000L // 2026-10-08

    private val restoredBodies = java.util.Collections.synchronizedList(mutableListOf<String>())
    private val statusBodies = java.util.Collections.synchronizedList(mutableListOf<String>())
    @Volatile private var restoredStatus = 200

    /** Correction 1: the 1-based restore POST that answers 400, to prove one refused chunk does not stop the others. */
    @Volatile private var failRestoredAttempt: Int? = null

    private class MemoryIdentityStore(var identity: PairedIdentity?) : IdentityStore {
        override fun read() = identity
        override fun write(identity: PairedIdentity) { this.identity = identity }
        override fun clear() { identity = null }
    }

    @Before
    fun setUp() {
        root = tmp.newFolder("sdcard")
        outboxDir = File(tmp.newFolder("outbox"), "restored-outbox")
        Robolectric.setupContentProvider(InitializationProvider::class.java, "${context.packageName}.androidx-startup")
        AppGraph.resetForTest()
        val mock = MockWebServer()
        mock.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty().substringBefore('?')
                val body = when {
                    path == "/v1/proposals" -> """{"proposals":[]}"""
                    path == "/v1/rules" -> """{"ruleVersion":3,"photoWindow":{"enabled":false,"reminderDays":90,"cutoffDays":120,"autoMode":false,"folders":["DCIM/Camera/"],"keepMarker":"Keep"},"batchesRunOneTap":0}"""
                    path == "/v1/chat/history" -> """{"events":[]}"""
                    path == "/v1/agent/settings" -> """{"conversationId":"main","model":{"provider":"zai","id":"glm-5.3-flash"},"context":{"tokens":10,"budget":1000}}"""
                    path == "/v1/agent/models" -> """{"current":{"provider":"zai","id":"glm-5.3-flash"},"models":[]}"""
                    path == "/v1/device/status" -> { statusBodies += request.body.readUtf8(); """{"accepted":true}""" }
                    path == "/v1/files/restored" -> {
                        restoredBodies += request.body.readUtf8()
                        when {
                            failRestoredAttempt == restoredBodies.size -> """{"error":{"code":"BAD_REQUEST","message":"at most 500 items"}}"""
                            restoredStatus == 200 -> """{"recorded":1}"""
                            else -> """{"error":{"code":"UNAVAILABLE","message":"try later"}}"""
                        }
                    }
                    else -> """{"accepted":true}"""
                }
                val code = if (path == "/v1/files/restored" && failRestoredAttempt == restoredBodies.size) 400
                else if (path == "/v1/files/restored") restoredStatus else 200
                return MockResponse().setResponseCode(code)
                    .setHeader("Content-Type", "application/json").setBody(body)
            }
        }
        mock.start()
        server = mock
        AppGraph.identityStoreFactory = { MemoryIdentityStore(PairedIdentity(mock.url("/").toString().trimEnd('/'), "t")) }
    }

    @After
    fun tearDown() {
        server?.shutdown()
        AppGraph.identityStoreFactory = { KeystoreIdentityStore(it) }
        AppGraph.resetForTest()
    }

    private fun await(timeoutMs: Long = 5_000, condition: () -> Boolean) {
        val until = System.currentTimeMillis() + timeoutMs
        while (!condition() && System.currentTimeMillis() < until) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(50)
        }
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun vm(): AppViewModel = AppViewModel(ApplicationProvider.getApplicationContext()).also {
        it.stewardTrash = StewardTrash(root, ZoneOffset.UTC) { now }
        it.restoreOutbox = RestoredOutbox(outboxDir)
        it.allFilesProbe = { true }
    }

    private fun file(rel: String, bytes: Int) = File(root, rel).also { it.parentFile.mkdirs(); it.writeBytes(ByteArray(bytes) { 1 }) }

    private fun later(): String {
        val until = System.currentTimeMillis() + 3_000
        while (System.currentTimeMillis() < until) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(30)
        }
        shadowOf(Looper.getMainLooper()).idle()
        return ""
    }

    private fun openTrash(viewModel: AppViewModel): List<TrashBatch> {
        viewModel.openTrash()
        await { viewModel.state.value.trashBatches != null }
        return viewModel.state.value.trashBatches!!
    }

    @Test
    fun `opening the trash lists its batches newest first`() {
        file(".StewardTrash/2026-10-05/pr-a/Dl/a.bin", 100)
        file(".StewardTrash/2026-10-08/pr-b/Maps/230606/x.bin", 200)
        val viewModel = vm()
        val batches = openTrash(viewModel)
        assertTrue(viewModel.state.value.trashOpen)
        assertEquals(listOf("pr-b", "pr-a"), batches.map { it.proposalId })
        assertEquals("Maps", batches[0].items.single().path)
        assertEquals(200L, batches[0].sizeBytes)
    }

    @Test
    fun `an empty trash lists nothing and the screen opens all the same`() {
        val viewModel = vm()
        assertEquals(emptyList<TrashBatch>(), openTrash(viewModel))
        assertTrue(viewModel.state.value.trashOpen)
    }

    @Test
    fun `closing the trash returns to Status`() {
        val viewModel = vm()
        openTrash(viewModel)
        viewModel.closeTrash()
        assertFalse(viewModel.state.value.trashOpen)
    }

    @Test
    fun `restoring a batch puts the items back, tells the server and refreshes the trash`() {
        file(".StewardTrash/2026-10-08/pr-1/Maps/230606/a.bin", 1000)
        file("Maps/keep.txt", 30) // stayed: the batch has no manifest, so the descent merges into the folder that stayed
        val viewModel = vm()
        val batch = openTrash(viewModel).single()
        viewModel.restoreBatch(batch)
        await { viewModel.state.value.trashScreenResult != null }
        val result = viewModel.state.value.trashScreenResult!!
        assertEquals("Put back 1 folder (1.0 KB).", result.text)
        assertEquals(ActionResult.Kind.SUCCESS, result.kind)
        assertTrue(File(root, "Maps/230606/a.bin").exists())
        assertEquals(30L, File(root, "Maps/keep.txt").length())
        await { restoredBodies.isNotEmpty() }
        val body = restoredBodies.last()
        assertTrue(body, body.contains("\"requestId\":\"restore-"))
        assertTrue(body, body.contains("\"proposalId\":\"pr-1\""))
        assertTrue(body, body.contains("\"path\":\"Maps/230606\""))
        assertTrue(body, body.contains("\"bytes\":1000"))
        await { viewModel.state.value.stewardTrash.bytes == 0L }
        await { viewModel.state.value.trashBatches == emptyList<TrashBatch>() }
        await { statusBodies.any { it.contains("\"stewardTrash\":{\"bytes\":0") } }
    }

    @Test
    fun `restoring one item leaves the rest of its batch in the trash`() {
        file(".StewardTrash/2026-10-08/pr-1/Dl/a.bin", 100)
        file(".StewardTrash/2026-10-08/pr-1/Docs/b.bin", 200)
        val viewModel = vm()
        val batch = openTrash(viewModel).single()
        viewModel.restoreItem(batch, batch.items.single { it.path == "Dl" })
        await { viewModel.state.value.trashScreenResult != null }
        assertEquals("Put back 1 folder (100 B).", viewModel.state.value.trashScreenResult!!.text)
        assertTrue(File(root, "Dl/a.bin").exists())
        assertFalse(File(root, "Docs/b.bin").exists())
        await { viewModel.state.value.stewardTrash.bytes == 200L }
    }

    @Test
    fun `a conflicting item stays and the result line says so`() {
        file("Download/taken.bin", 5)
        file(".StewardTrash/2026-10-08/pr-1/Download/taken.bin", 200)
        val viewModel = vm()
        val batch = openTrash(viewModel).single()
        assertTrue(batch.items.single().blocked)
        viewModel.restoreBatch(batch)
        await { viewModel.state.value.trashScreenResult != null }
        val result = viewModel.state.value.trashScreenResult!!
        assertEquals("1 item stays in the trash: something is at that place now.", result.text)
        assertEquals(ActionResult.Kind.INFO, result.kind)
        assertTrue(File(root, ".StewardTrash/2026-10-08/pr-1/Download/taken.bin").exists())
        assertEquals(5L, File(root, "Download/taken.bin").length())
        later()
        assertTrue("nothing went back, so nothing is reported", restoredBodies.isEmpty())
    }

    @Test
    fun `a failed report never blocks the restore and is retried on the next sync`() {
        file(".StewardTrash/2026-10-08/pr-1/Dl/a.bin", 100)
        restoredStatus = 503
        val viewModel = vm()
        val batch = openTrash(viewModel).single()
        viewModel.restoreBatch(batch)
        await { viewModel.state.value.trashScreenResult != null }
        assertTrue("the files came back even though the report failed", File(root, "Dl/a.bin").exists())
        await { restoredBodies.size == 1 }
        assertEquals(1, viewModel.restoreOutbox.pending().size)
        val firstId = viewModel.restoreOutbox.pending().single().report.requestId
        // The server is back: the next sync sends the same report, with the same requestId.
        restoredStatus = 200
        viewModel.refresh()
        await { restoredBodies.size == 2 }
        assertTrue(restoredBodies[1], restoredBodies[1].contains("\"requestId\":\"$firstId\""))
        await { viewModel.restoreOutbox.pending().isEmpty() }
    }

    @Test
    fun `dismissing the restore result clears it`() {
        file(".StewardTrash/2026-10-08/pr-1/Dl/a.bin", 100)
        val viewModel = vm()
        viewModel.restoreBatch(openTrash(viewModel).single())
        await { viewModel.state.value.trashScreenResult != null }
        viewModel.dismissTrashScreenResult()
        assertNull(viewModel.state.value.trashScreenResult)
    }

    // ---- Correction 1 ----

    /** A live `Big/` with [count] free children in the trash: the descent legitimately yields [count] restore items. */
    private fun manyItems(count: Int): TrashBatch {
        file("Big/keep.txt", 10)
        for (i in 0 until count) {
            file(".StewardTrash/2026-10-08/pr-big/Big/f%04d.bin".format(i), 10)
        }
        return TrashBatch("2026-10-08", 0, "pr-big", (0 until count).map { TrashItem("Big/f%04d.bin".format(it), "file", 10) })
    }

    @Test
    fun `restoring more than the server's 500 items is reported in chunks of 500`() {
        val viewModel = vm()
        val batch = manyItems(1200)
        viewModel.restoreBatch(batch)
        await { viewModel.state.value.trashScreenResult != null }
        await { restoredBodies.size >= 3 }
        later()
        assertEquals(3, restoredBodies.size)
        val sizes = restoredBodies.map { body ->
            kotlinx.serialization.json.Json.parseToJsonElement(body).jsonObject["items"]!!.jsonArray.size
        }
        // All three chunks carry the same restore time, so the outbox's "oldest first" order between them is not
        // fixed; what matters is the split, not which chunk the network saw first.
        assertEquals(listOf(200, 500, 500), sizes.sorted())
        val ids = restoredBodies.map { body ->
            kotlinx.serialization.json.Json.parseToJsonElement(body).jsonObject["requestId"]!!.jsonPrimitive.content
        }
        assertEquals("each chunk is its own report", 3, ids.toSet().size)
        assertTrue(viewModel.restoreOutbox.pending().isEmpty())
        assertEquals(1200, File(root, "Big").listFiles()!!.count { it.name.startsWith("f") })
    }

    @Test
    fun `one chunk the server refuses does not drop the others`() {
        failRestoredAttempt = 2
        val viewModel = vm()
        viewModel.restoreBatch(manyItems(1200))
        await { viewModel.state.value.trashScreenResult != null }
        await { restoredBodies.size >= 3 }
        later()
        assertEquals("the refused chunk does not stop the pass", 3, restoredBodies.size)
        assertTrue("a refused chunk is dropped, the rest sent", viewModel.restoreOutbox.pending().isEmpty())
        assertEquals(1200, File(root, "Big").listFiles()!!.count { it.name.startsWith("f") })
    }

    @Test
    fun `a second tap while a restore runs is ignored and never names healthy items failed`() {
        file(".StewardTrash/2026-10-08/pr-1/Dl/a.bin", 100)
        val viewModel = vm()
        val batch = openTrash(viewModel).single()
        viewModel.restoreBatch(batch)
        assertTrue("the guard is set before any work starts", viewModel.state.value.trashRestoring)
        viewModel.restoreBatch(batch)
        viewModel.restoreItem(batch, batch.items.single())
        await { viewModel.state.value.trashScreenResult != null }
        await { !viewModel.state.value.trashRestoring }
        val result = viewModel.state.value.trashScreenResult!!
        assertEquals(ActionResult.Kind.SUCCESS, result.kind)
        assertEquals("Put back 1 folder (100 B).", result.text)
        assertEquals("one restore, one report", 1, restoredBodies.size)
        assertTrue(File(root, "Dl/a.bin").exists())
    }

    @Test
    fun `the in-flight guard clears once the restore is done`() {
        file(".StewardTrash/2026-10-08/pr-1/Dl/a.bin", 100)
        val viewModel = vm()
        assertFalse(viewModel.state.value.trashRestoring)
        viewModel.restoreBatch(openTrash(viewModel).single())
        await { viewModel.state.value.trashScreenResult != null }
        await { !viewModel.state.value.trashRestoring }
        assertFalse(viewModel.state.value.trashRestoring)
    }
}
