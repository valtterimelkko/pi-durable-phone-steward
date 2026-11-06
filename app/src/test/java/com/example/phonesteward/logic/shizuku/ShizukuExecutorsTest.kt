package com.example.phonesteward.logic.shizuku

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import com.example.phonesteward.logic.jobs.JobOutcome
import com.example.phonesteward.net.Job

/** A10.2 `shell_info` and `force_stop` jobs, through a fake shell: Shizuku itself cannot run off a phone. */
class ShizukuExecutorsTest {

    private class FakeShell(var answer: (List<String>) -> ShellResult = { ShellResult(0, "", "") }) : ShellRunner {
        val calls = mutableListOf<List<String>>()
        override fun run(args: List<String>, timeoutMs: Long): ShellResult {
            calls += args
            return answer(args)
        }
    }

    private fun job(kind: String, params: JsonObject) = Job("pj-1", kind, params)
    private fun failed(o: JobOutcome) = o as? JobOutcome.Failed ?: error("expected Failed but was $o")
    private fun ok(o: JobOutcome) = (o as? JobOutcome.Ok ?: error("expected Ok but was $o")).data

    private val neverTouch = NeverTouch(keyboardInUse = { "com.invented.keyboard" }, launcher = { null })
    private val installed = setOf("com.example.puzzle", "com.android.systemui", "com.example.phonesteward", "com.invented.keyboard")

    // ---- shell_info ----

    private fun info(shell: FakeShell, state: ShizukuState = ShizukuState.READY) = ShellInfoExecutor({ state }, shell)

    @Test
    fun `shell_info without Shizuku ready fails with SHIZUKU_NOT_READY and runs nothing`() {
        for (s in listOf(ShizukuState.ABSENT, ShizukuState.NOT_RUNNING, ShizukuState.NO_PERMISSION)) {
            val shell = FakeShell()
            val f = failed(info(shell, s).execute(job("shell_info", buildJsonObject { put("op", "packages") })))
            assertEquals("SHIZUKU_NOT_READY", f.errorCode)
            assertTrue(shell.calls.isEmpty())
        }
    }

    @Test
    fun `packages runs the three fixed listings and answers one row per package`() {
        val shell = FakeShell { args ->
            ShellResult(0, when (args.last()) {
                "-i" -> "package:com.example.puzzle  installer=com.android.vending\npackage:com.android.settings  installer=null\n"
                "-d" -> "package:com.example.puzzle\n"
                else -> "package:com.android.settings\n"
            }, "")
        }
        val data = ok(info(shell).execute(job("shell_info", buildJsonObject { put("op", "packages") })))
        assertEquals(
            listOf(ShellCommands.listInstallers(), ShellCommands.listDisabled(), ShellCommands.listSystem()),
            shell.calls,
        )
        val rows = data["packages"]!!.jsonArray.map { it.jsonObject }
        assertEquals(2, rows.size)
        val puzzle = rows.single { it["packageName"]!!.jsonPrimitive.content == "com.example.puzzle" }
        assertEquals("false", puzzle["enabled"]!!.jsonPrimitive.content)
        assertEquals("false", puzzle["system"]!!.jsonPrimitive.content)
        assertEquals("com.android.vending", puzzle["installer"]!!.jsonPrimitive.content)
    }

    @Test
    fun `app_sizes answers exact code, data and cache bytes`() {
        val shell = FakeShell {
            ShellResult(0, """Package Names: ["com.example.puzzle"]
App Sizes: [100]
App Data Sizes: [22]
Cache Sizes: [3]""", "")
        }
        val data = ok(info(shell).execute(job("shell_info", buildJsonObject { put("op", "app_sizes") })))
        assertEquals(listOf(ShellCommands.diskstats()), shell.calls)
        val row = data["apps"]!!.jsonArray.single().jsonObject
        assertEquals("com.example.puzzle", row["packageName"]!!.jsonPrimitive.content)
        assertEquals("100", row["appBytes"]!!.jsonPrimitive.content)
        assertEquals("22", row["dataBytes"]!!.jsonPrimitive.content)
        assertEquals("3", row["cacheBytes"]!!.jsonPrimitive.content)
    }

    @Test
    fun `an output that cannot be read is SHELL_PARSE, a failing command is SHELL_FAILED`() {
        val parse = failed(info(FakeShell { ShellResult(0, "no arrays here", "") }).execute(job("shell_info", buildJsonObject { put("op", "app_sizes") })))
        assertEquals("SHELL_PARSE", parse.errorCode)
        val fail = failed(info(FakeShell { ShellResult(1, "", "boom") }).execute(job("shell_info", buildJsonObject { put("op", "packages") })))
        assertEquals("SHELL_FAILED", fail.errorCode)
        val timeout = failed(info(FakeShell { ShellResult(-1, "", "", timedOut = true) }).execute(job("shell_info", buildJsonObject { put("op", "packages") })))
        assertEquals("SHELL_FAILED", timeout.errorCode)
    }

    @Test
    fun `an unknown op is BAD_PARAMS`() {
        assertEquals("BAD_PARAMS", failed(info(FakeShell()).execute(job("shell_info", buildJsonObject { put("op", "rm") }))).errorCode)
        assertEquals("BAD_PARAMS", failed(info(FakeShell()).execute(job("shell_info", buildJsonObject { }))).errorCode)
    }

    // ---- force_stop ----

    private fun stop(shell: FakeShell, state: ShizukuState = ShizukuState.READY) =
        ForceStopExecutor({ state }, shell, isInstalled = { it in installed }, neverTouch = neverTouch)

    private fun stopJob(pkg: String) = job("force_stop", buildJsonObject { put("packageName", pkg) })

    @Test
    fun `force_stop runs the fixed template and says it stopped`() {
        val shell = FakeShell()
        val data = ok(stop(shell).execute(stopJob("com.example.puzzle")))
        assertEquals(listOf(ShellCommands.forceStop("com.example.puzzle")), shell.calls)
        assertEquals("com.example.puzzle", data["packageName"]!!.jsonPrimitive.content)
        assertEquals("true", data["stopped"]!!.jsonPrimitive.content)
    }

    @Test
    fun `force_stop without Shizuku ready is SHIZUKU_NOT_READY`() {
        val shell = FakeShell()
        assertEquals("SHIZUKU_NOT_READY", failed(stop(shell, ShizukuState.NO_PERMISSION).execute(stopJob("com.example.puzzle"))).errorCode)
        assertTrue(shell.calls.isEmpty())
    }

    @Test
    fun `force_stop refuses what is not a package, not installed or never touched`() {
        val shell = FakeShell()
        assertEquals("BAD_PACKAGE", failed(stop(shell).execute(stopJob("com.example; reboot"))).errorCode)
        assertEquals("BAD_PACKAGE", failed(stop(shell).execute(job("force_stop", buildJsonObject { }))).errorCode)
        assertEquals("NOT_INSTALLED", failed(stop(shell).execute(stopJob("com.example.missing"))).errorCode)
        assertEquals("PROTECTED_PACKAGE", failed(stop(shell).execute(stopJob("com.android.systemui"))).errorCode)
        assertEquals("PROTECTED_PACKAGE", failed(stop(shell).execute(stopJob("com.example.phonesteward"))).errorCode)
        assertEquals("PROTECTED_PACKAGE", failed(stop(shell).execute(stopJob("com.invented.keyboard"))).errorCode)
        assertTrue("nothing ran", shell.calls.isEmpty())
    }

    @Test
    fun `force_stop reports a failing command`() {
        val f = failed(stop(FakeShell { ShellResult(255, "", "Error") }).execute(stopJob("com.example.puzzle")))
        assertEquals("SHELL_FAILED", f.errorCode)
    }
}
