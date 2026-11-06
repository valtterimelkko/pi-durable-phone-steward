package com.example.phonesteward.logic.files

import java.io.File
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import com.example.phonesteward.logic.jobs.JobOutcome
import com.example.phonesteward.net.Job

/** A10.1 `files_query`: list, find and du over the primary shared storage, with all-files access, path safety and caps. */
class FilesQueryExecutorTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var root: File
    private var granted = true
    private var now = 1_791_400_000_000L // 2026-10-07

    private fun exec(maxEntries: Int = 500, budgetMs: Long = 25_000, clock: () -> Long = { 0L }) =
        FilesQueryExecutor(root, accessGranted = { granted }, nowMillis = { now }, maxEntries = maxEntries, budgetMs = budgetMs, clockMs = clock)

    private fun job(params: JsonObject) = Job(jobId = "pj-1", kind = "files_query", params = params)
    private fun run(params: JsonObject, e: FilesQueryExecutor = exec()) = e.execute(job(params))
    private fun ok(o: JobOutcome): JsonObject = (o as? JobOutcome.Ok ?: error("expected Ok but was $o")).data
    private fun failed(o: JobOutcome): JobOutcome.Failed = o as? JobOutcome.Failed ?: error("expected Failed but was $o")

    private fun file(rel: String, bytes: Int, modified: Long? = null): File =
        File(root, rel).also {
            it.parentFile.mkdirs()
            it.writeBytes(ByteArray(bytes) { 1 })
            if (modified != null) it.setLastModified(modified)
        }

    private fun list(path: String = "", depth: Int? = null) = buildJsonObject {
        put("op", "list"); put("path", path); if (depth != null) put("depth", depth)
    }

    private fun JsonObject.entries(key: String = "entries") = this[key]!!.jsonArray.map { it.jsonObject }
    private fun JsonObject.str(k: String) = this[k]!!.jsonPrimitive.content
    private fun JsonObject.num(k: String) = this[k]!!.jsonPrimitive.long

    @Before
    fun setUp() {
        root = tmp.newFolder("sdcard")
        file("MapsWithMe/230606/a.mwm", 1000)
        file("MapsWithMe/230606/b.mwm", 2000)
        file("MapsWithMe/230701/c.mwm", 500)
        file("MapsWithMe/settings.ini", 10)
        file("Download/installer.apk", 4000)
        file("Download/notes.txt", 20)
        file("Documents/Keep/precious.pdf", 700)
        file("top.txt", 5)
    }

    // ---- access and params ----

    @Test
    fun `without all-files access the job fails and says what to grant`() {
        granted = false
        val f = failed(run(list()))
        assertEquals("NO_ALL_FILES_ACCESS", f.errorCode)
        assertTrue(f.message, f.message.contains("all-files access"))
    }

    @Test
    fun `an unknown op or unreadable params is BAD_PARAMS`() {
        assertEquals("BAD_PARAMS", failed(run(buildJsonObject { put("op", "format") })).errorCode)
        assertEquals("BAD_PARAMS", failed(run(buildJsonObject { })).errorCode)
    }

    @Test
    fun `absolute, dot-dot and symlink-escaping paths are BAD_PATH`() {
        assertEquals("BAD_PATH", failed(run(list("/storage/emulated/0"))).errorCode)
        assertEquals("BAD_PATH", failed(run(list("../etc"))).errorCode)
        assertEquals("BAD_PATH", failed(run(list("Download/../../x"))).errorCode)
        val outside = tmp.newFolder("outside")
        File(outside, "secret.txt").writeText("x")
        java.nio.file.Files.createSymbolicLink(File(root, "escape").toPath(), outside.toPath())
        assertEquals("BAD_PATH", failed(run(list("escape"))).errorCode)
        assertEquals("BAD_PATH", failed(run(buildJsonObject {
            put("op", "find"); put("root", "escape"); put("namePattern", "*")
        })).errorCode)
        assertEquals("BAD_PATH", failed(run(buildJsonObject {
            put("op", "du"); put("paths", buildJsonArray { add(JsonPrimitive("escape")) })
        })).errorCode)
    }

    // ---- list ----

    @Test
    fun `list of the root gives recursive folder totals, biggest first`() {
        val data = ok(run(list("")))
        assertEquals("", data.str("path"))
        assertFalse(data["truncated"]!!.jsonPrimitive.boolean)
        val entries = data.entries()
        val names = entries.map { it.str("path") }
        // MapsWithMe 3510, Download 4020, Documents 700, top.txt 5  -> by size desc
        assertEquals(listOf("Download", "MapsWithMe", "Documents", "top.txt"), names)
        val maps = entries.single { it.str("path") == "MapsWithMe" }
        assertEquals("dir", maps.str("type"))
        assertEquals(3510L, maps.num("sizeBytes"))
        assertEquals(4L, maps.num("fileCount"))
        val top = entries.single { it.str("path") == "top.txt" }
        assertEquals("file", top.str("type"))
        assertEquals(5L, top.num("sizeBytes"))
        assertNull("a file has no fileCount", top["fileCount"])
        assertTrue(maps.num("modifiedMs") > 0)
    }

    @Test
    fun `list of a folder names children by their path from the root`() {
        val entries = ok(run(list("MapsWithMe"))).entries()
        assertEquals(listOf("MapsWithMe/230606", "MapsWithMe/230701", "MapsWithMe/settings.ini"), entries.map { it.str("path") })
        assertEquals(3000L, entries[0].num("sizeBytes"))
        assertEquals(2L, entries[0].num("fileCount"))
    }

    @Test
    fun `list depth 2 walks one level deeper, folders before their children`() {
        val names = ok(run(list("MapsWithMe", depth = 2))).entries().map { it.str("path") }
        assertEquals(
            listOf(
                "MapsWithMe/230606", "MapsWithMe/230606/b.mwm", "MapsWithMe/230606/a.mwm",
                "MapsWithMe/230701", "MapsWithMe/230701/c.mwm", "MapsWithMe/settings.ini",
            ),
            names,
        )
    }

    @Test
    fun `list depth outside 1 to 3 is BAD_PARAMS`() {
        assertEquals("BAD_PARAMS", failed(run(list("", depth = 0))).errorCode)
        assertEquals("BAD_PARAMS", failed(run(list("", depth = 4))).errorCode)
    }

    @Test
    fun `list of a missing folder is NOT_FOUND and of a file is BAD_PARAMS`() {
        assertEquals("NOT_FOUND", failed(run(list("Nowhere"))).errorCode)
        assertEquals("NOT_FOUND", failed(run(list("top.txt"))).errorCode)
    }

    @Test
    fun `the Steward trash is not listed`() {
        file(".StewardTrash/2026-10-01/pr-1/x.bin", 99)
        val names = ok(run(list(""))).entries().map { it.str("path") }
        assertFalse(names.any { it.startsWith(".StewardTrash") })
    }

    @Test
    fun `Android data and obb appear as unreadable and are not walked`() {
        file("Android/data/com.example.app/files/big.bin", 9999)
        file("Android/obb/com.example.app/main.obb", 8888)
        file("Android/media/com.example.app/pic.jpg", 123)
        val entries = ok(run(list("Android"))).entries()
        val data = entries.single { it.str("path") == "Android/data" }
        val obb = entries.single { it.str("path") == "Android/obb" }
        val media = entries.single { it.str("path") == "Android/media" }
        assertTrue(data["unreadable"]!!.jsonPrimitive.boolean)
        assertTrue(obb["unreadable"]!!.jsonPrimitive.boolean)
        assertEquals(0L, data.num("sizeBytes"))
        assertNull("a readable folder carries no unreadable flag", media["unreadable"])
        assertEquals(123L, media.num("sizeBytes"))
        // And the root's total does not count them.
        val android = ok(run(list(""))).entries().single { it.str("path") == "Android" }
        assertEquals(123L, android.num("sizeBytes"))
    }

    @Test
    fun `symlinks inside are skipped, not followed`() {
        val outside = tmp.newFolder("outside")
        File(outside, "huge.bin").writeBytes(ByteArray(50_000))
        java.nio.file.Files.createSymbolicLink(File(root, "Download/linked").toPath(), outside.toPath())
        val entries = ok(run(list("Download"))).entries()
        assertFalse(entries.any { it.str("path") == "Download/linked" })
        assertEquals(4020L, ok(run(list(""))).entries().single { it.str("path") == "Download" }.num("sizeBytes"))
    }

    @Test
    fun `the entry cap cuts a list and says so`() {
        for (i in 1..12) file("Many/f$i.bin", i)
        val data = ok(run(list("Many"), exec(maxEntries = 5)))
        assertEquals(5, data.entries().size)
        assertTrue(data["truncated"]!!.jsonPrimitive.boolean)
        // The largest five survive the cut.
        assertEquals(listOf("Many/f12.bin", "Many/f11.bin", "Many/f10.bin", "Many/f9.bin", "Many/f8.bin"), data.entries().map { it.str("path") })
    }

    @Test
    fun `the time budget stops a list and marks it truncated, never with a made-up size`() {
        for (i in 1..6) file("Slow/d$i/x.bin", 100)
        var t = 0L
        // Every look at the clock costs 40 ms against a 100 ms budget: a few folders get done, then it stops.
        val data = ok(run(list("Slow"), exec(budgetMs = 100, clock = { t += 40; t })))
        assertTrue(data["truncated"]!!.jsonPrimitive.boolean)
        val entries = data.entries()
        assertTrue("fewer folders than exist", entries.size < 6)
        entries.forEach { assertEquals("a listed folder has its exact size", 100L, it.num("sizeBytes")) }
    }

    // ---- find ----

    private fun find(
        pattern: String? = "*", root: String = "", type: String? = null, minBytes: Long? = null,
        olderThanDays: Int? = null, limit: Int? = null,
    ) = buildJsonObject {
        put("op", "find"); put("root", root)
        if (pattern != null) put("namePattern", pattern)
        if (type != null) put("type", type)
        if (minBytes != null) put("minBytes", minBytes)
        if (olderThanDays != null) put("olderThanDays", olderThanDays)
        if (limit != null) put("limit", limit)
    }

    @Test
    fun `find matches a glob on the name, case-insensitively`() {
        val items = ok(run(find("*.MWM"))).entries("items")
        assertEquals(setOf("MapsWithMe/230606/a.mwm", "MapsWithMe/230606/b.mwm", "MapsWithMe/230701/c.mwm"), items.map { it.str("path") }.toSet())
        assertEquals(3, items.size)
    }

    @Test
    fun `find with a question mark matches one character`() {
        val items = ok(run(find("a.m?m"))).entries("items")
        assertEquals(listOf("MapsWithMe/230606/a.mwm"), items.map { it.str("path") })
    }

    @Test
    fun `find type dir returns folders with recursive totals`() {
        val items = ok(run(find("2306*", type = "dir"))).entries("items")
        assertEquals(listOf("MapsWithMe/230606"), items.map { it.str("path") })
        assertEquals(3000L, items.single().num("sizeBytes"))
        assertEquals(2L, items.single().num("fileCount"))
    }

    @Test
    fun `find type file skips folders and any skips nothing`() {
        file("Download/2306-docs/x.txt", 1)
        val files = ok(run(find("2306*", type = "file"))).entries("items")
        assertTrue(files.isEmpty())
        val any = ok(run(find("2306*", type = "any"))).entries("items").map { it.str("path") }.toSet()
        assertEquals(setOf("MapsWithMe/230606", "Download/2306-docs"), any)
    }

    @Test
    fun `find minBytes filters by size`() {
        val items = ok(run(find("*", type = "file", minBytes = 1500))).entries("items")
        assertEquals(setOf("MapsWithMe/230606/b.mwm", "Download/installer.apk"), items.map { it.str("path") }.toSet())
    }

    @Test
    fun `find olderThanDays filters by modified time`() {
        val day = 86_400_000L
        file("Old/ancient.bin", 10, modified = now - 400 * day)
        file("Old/recent.bin", 10, modified = now - 2 * day)
        val items = ok(run(find("*.bin", olderThanDays = 90))).entries("items")
        assertEquals(listOf("Old/ancient.bin"), items.map { it.str("path") })
    }

    @Test
    fun `find from a root folder only looks under it`() {
        val items = ok(run(find("*", root = "Download", type = "file"))).entries("items")
        assertEquals(setOf("Download/installer.apk", "Download/notes.txt"), items.map { it.str("path") }.toSet())
    }

    @Test
    fun `find limit cuts and says so, biggest first`() {
        for (i in 1..10) file("Bulk/f$i.dat", i * 10)
        val data = ok(run(find("*.dat", limit = 3)))
        assertEquals(3, data.entries("items").size)
        assertTrue(data["truncated"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun `find does not look inside the Steward trash or Android data`() {
        file(".StewardTrash/2026-10-01/pr-1/ghost.mwm", 99)
        file("Android/data/com.example/x.mwm", 99)
        val paths = ok(run(find("*.mwm"))).entries("items").map { it.str("path") }
        assertFalse(paths.any { it.contains("ghost") || it.startsWith("Android/data") })
    }

    @Test
    fun `find without a namePattern is BAD_PARAMS`() {
        assertEquals("BAD_PARAMS", failed(run(find(pattern = null))).errorCode)
    }

    // ---- du ----

    private fun du(vararg paths: String) = buildJsonObject {
        put("op", "du"); put("paths", buildJsonArray { paths.forEach { add(JsonPrimitive(it)) } })
    }

    @Test
    fun `du gives bytes, files and folders for each path`() {
        val paths = ok(run(du("MapsWithMe", "Download", "top.txt"))).entries("paths")
        val maps = paths.single { it.str("path") == "MapsWithMe" }
        assertEquals(3510L, maps.num("bytes"))
        assertEquals(4L, maps.num("files"))
        assertEquals(2L, maps.num("dirs"))
        assertFalse(maps["unreadable"]!!.jsonPrimitive.boolean)
        val top = paths.single { it.str("path") == "top.txt" }
        assertEquals(5L, top.num("bytes"))
        assertEquals(1L, top.num("files"))
        assertEquals(0L, top.num("dirs"))
        // Protocol A10.1 (parent's amendment): a du row also carries type and modifiedMs, so a delete_files item
        // completed from a du-only report passes the identity check.
        assertEquals("dir", maps.str("type"))
        assertEquals("file", top.str("type"))
        assertEquals(File(root, "MapsWithMe").lastModified(), maps.num("modifiedMs"))
        assertEquals(File(root, "top.txt").lastModified(), top.num("modifiedMs"))
        assertEquals("Download", paths.single { it.str("path") == "Download" }.str("path"))
        assertEquals("dir", paths.single { it.str("path") == "Download" }.str("type"))
    }

    @Test
    fun `du of an unreadable folder still names its type`() {
        file("Android/data/com.example/x.bin", 99)
        val p = ok(run(du("Android/data"))).entries("paths").single()
        assertEquals("dir", p.str("type"))
    }

    @Test
    fun `du of Android data is unreadable`() {
        file("Android/data/com.example/x.bin", 99)
        val p = ok(run(du("Android/data"))).entries("paths").single()
        assertTrue(p["unreadable"]!!.jsonPrimitive.boolean)
        assertEquals(0L, p.num("bytes"))
    }

    @Test
    fun `du of a missing path is NOT_FOUND and of nothing is BAD_PARAMS`() {
        assertEquals("NOT_FOUND", failed(run(du("Nowhere"))).errorCode)
        assertEquals("BAD_PARAMS", failed(run(du())).errorCode)
    }

    @Test
    fun `du of the root counts everything readable`() {
        val p = ok(run(du(""))).entries("paths").single()
        assertEquals(3510L + 4020 + 700 + 5, p.num("bytes"))
    }

    @Test
    fun `du over its time budget drops the unfinished path and says truncated`() {
        for (i in 1..5) file("Big$i/a.bin", 10)
        var t = 0L
        val data = ok(run(du("Big1", "Big2", "Big3", "Big4", "Big5"), exec(budgetMs = 100, clock = { t += 60; t })))
        assertTrue(data["truncated"]!!.jsonPrimitive.boolean)
        assertTrue(data.entries("paths").size < 5)
        data.entries("paths").forEach { assertEquals(10L, it.num("bytes")) }
    }
}
