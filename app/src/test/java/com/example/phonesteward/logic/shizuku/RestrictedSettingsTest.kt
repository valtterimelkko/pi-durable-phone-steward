package com.example.phonesteward.logic.shizuku

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A12: Allow with Shizuku. One fixed command for the app's own package, a read-back, a plain outcome. */
class RestrictedSettingsTest {

    private val own = "com.example.phonesteward"

    private class FakeShizuku(
        var current: ShizukuState = ShizukuState.READY,
        private val reply: (List<String>) -> ShellResult = { ShellResult(0, "", "") },
    ) : ShizukuAccess {
        val commands = mutableListOf<List<String>>()
        val timeouts = mutableListOf<Long>()
        override fun state() = current
        override fun shell() = ShellRunner { args, timeoutMs -> commands += args; timeouts += timeoutMs; reply(args) }
        override fun requestPermission() = Unit
    }

    private fun restricted(shizuku: FakeShizuku, pkg: String = own, sdk: Int = 33) = RestrictedSettings(shizuku, pkg, sdk)

    private fun answering(readBack: ShellResult, set: ShellResult = ShellResult(0, "", "")): (List<String>) -> ShellResult =
        { args -> if (args[1] == "set") set else readBack }

    // ---- what the card offers ----

    @Test
    fun `below Android 13 the card offers nothing`() {
        for (sdk in listOf(21, 30, 32)) {
            assertEquals(RestrictedSettings.Offer.Hidden, restricted(FakeShizuku(), sdk = sdk).offer(serviceOn = false))
        }
    }

    @Test
    fun `with the service already on the card offers nothing`() {
        assertEquals(RestrictedSettings.Offer.Hidden, restricted(FakeShizuku()).offer(serviceOn = true))
    }

    @Test
    fun `with Shizuku ready on Android 13 or later the card offers the button`() {
        for (sdk in listOf(33, 34, 35)) {
            assertEquals(RestrictedSettings.Offer.Button, restricted(FakeShizuku(), sdk = sdk).offer(serviceOn = false))
        }
    }

    @Test
    fun `without Shizuku ready the card says what to do and names the one action`() {
        val absent = restricted(FakeShizuku(ShizukuState.ABSENT)).offer(serviceOn = false) as RestrictedSettings.Offer.NotReady
        assertEquals(ShizukuText.Action.GET_SHIZUKU, absent.action)
        val stopped = restricted(FakeShizuku(ShizukuState.NOT_RUNNING)).offer(serviceOn = false) as RestrictedSettings.Offer.NotReady
        assertEquals(ShizukuText.Action.OPEN_SHIZUKU, stopped.action)
        assertTrue(stopped.text, stopped.text.contains("Start Shizuku"))
        val unallowed = restricted(FakeShizuku(ShizukuState.NO_PERMISSION)).offer(serviceOn = false) as RestrictedSettings.Offer.NotReady
        assertEquals(ShizukuText.Action.ALLOW_SHIZUKU, unallowed.action)
        assertTrue(unallowed.text, unallowed.text.contains("Allow"))
        for (n in listOf(absent, stopped, unallowed)) {
            assertFalse("no developer terms: ${n.text}", Regex("adb|appops|shell|command", RegexOption.IGNORE_CASE).containsMatchIn(n.text))
        }
    }

    // ---- the command ----

    @Test
    fun `allow runs exactly the fixed set and then the read-back for the own package`() {
        val shizuku = FakeShizuku(reply = answering(ShellResult(0, "ACCESS_RESTRICTED_SETTINGS: allow\n", "")))
        val outcome = restricted(shizuku).allow()
        assertEquals(
            listOf(
                listOf("/system/bin/appops", "set", own, "ACCESS_RESTRICTED_SETTINGS", "allow"),
                listOf("/system/bin/appops", "get", own, "ACCESS_RESTRICTED_SETTINGS"),
            ),
            shizuku.commands,
        )
        assertEquals(RestrictedSettings.Outcome.Allowed, outcome)
        assertTrue("a sensible timeout", shizuku.timeouts.all { it in 5_000L..30_000L })
    }

    @Test
    fun `the package is whatever the app passes as its own, validated, and nothing else`() {
        val shizuku = FakeShizuku(reply = answering(ShellResult(0, "ACCESS_RESTRICTED_SETTINGS: allow", "")))
        restricted(shizuku, pkg = "com.example.phonesteward.debug").allow()
        assertEquals("com.example.phonesteward.debug", shizuku.commands.first()[2])
        assertEquals("com.example.phonesteward.debug", shizuku.commands.last()[2])
        for (bad in listOf("", "app", "com.x; reboot", "com.x --user 0", "-f")) {
            val e = runCatching { RestrictedSettings(FakeShizuku(), bad, 33) }.exceptionOrNull()
            assertTrue("'$bad' is refused", e is IllegalArgumentException)
        }
    }

    @Test
    fun `allow does nothing at all unless Shizuku is ready`() {
        for (state in listOf(ShizukuState.ABSENT, ShizukuState.NOT_RUNNING, ShizukuState.NO_PERMISSION)) {
            val shizuku = FakeShizuku(state)
            val outcome = restricted(shizuku).allow()
            assertTrue("$state: $outcome", outcome is RestrictedSettings.Outcome.NotReady)
            assertEquals(emptyList<List<String>>(), shizuku.commands)
        }
    }

    @Test
    fun `allow does nothing below Android 13`() {
        val shizuku = FakeShizuku()
        val outcome = restricted(shizuku, sdk = 32).allow()
        assertTrue(outcome is RestrictedSettings.Outcome.Failed)
        assertEquals(emptyList<List<String>>(), shizuku.commands)
    }

    @Test
    fun `the pair is the ShellCommands templates, with absolute paths`() {
        assertEquals(listOf("/system/bin/appops", "set", own, "ACCESS_RESTRICTED_SETTINGS", "allow"), ShellCommands.allowRestrictedSettings(own))
        assertEquals(listOf("/system/bin/appops", "get", own, "ACCESS_RESTRICTED_SETTINGS"), ShellCommands.readRestrictedSettings(own))
        for (bad in listOf("", "app", "com.x; reboot", "-f")) {
            assertTrue(runCatching { ShellCommands.allowRestrictedSettings(bad) }.exceptionOrNull() is IllegalArgumentException)
            assertTrue(runCatching { ShellCommands.readRestrictedSettings(bad) }.exceptionOrNull() is IllegalArgumentException)
        }
    }

    // ---- the read-back forms ----

    @Test
    fun `the documented read-back forms that mean allowed`() {
        for (text in listOf(
            "ACCESS_RESTRICTED_SETTINGS: allow\n",
            "ACCESS_RESTRICTED_SETTINGS: allow\r\n",
            "ACCESS_RESTRICTED_SETTINGS: allow; time=+3s120ms ago\n",
            "  ACCESS_RESTRICTED_SETTINGS: allow; rejectTime=+1d2h ago\n",
            "Uid mode: ACCESS_RESTRICTED_SETTINGS: allow\n",
        )) {
            val shizuku = FakeShizuku(reply = answering(ShellResult(0, text, "")))
            assertEquals("'$text'", RestrictedSettings.Outcome.Allowed, restricted(shizuku).allow())
        }
    }

    @Test
    fun `read-back forms that do not mean allowed are a failure that says what Android reports`() {
        val cases = mapOf(
            "ACCESS_RESTRICTED_SETTINGS: ignore\n" to "ignore",
            "ACCESS_RESTRICTED_SETTINGS: deny; time=+1m ago\n" to "deny",
            "ACCESS_RESTRICTED_SETTINGS: default\n" to "default",
        )
        for ((text, mode) in cases) {
            val outcome = restricted(FakeShizuku(reply = answering(ShellResult(0, text, "")))).allow()
            val failed = outcome as RestrictedSettings.Outcome.Failed
            assertTrue("'${failed.reason}' mentions $mode", failed.reason.contains(mode))
        }
    }

    @Test
    fun `No operations, an empty or an unrelated read-back is not allowed`() {
        for (text in listOf("No operations.\n", "", "\n\n", "Error: unknown operation\n", "SOME_OTHER_OP: allow\n")) {
            val outcome = restricted(FakeShizuku(reply = answering(ShellResult(0, text, "")))).allow()
            assertTrue("'$text' -> $outcome", outcome is RestrictedSettings.Outcome.Failed)
        }
    }

    // ---- the failure forms ----

    @Test
    fun `a failing set stops there with the exit code and the first line of the error, trimmed`() {
        val shizuku = FakeShizuku(reply = { ShellResult(255, "", "\n  Error: Unknown operation string: ACCESS_RESTRICTED_SETTINGS  \nat com.android.Foo.bar(Foo.java:1)\n") })
        val failed = restricted(shizuku).allow() as RestrictedSettings.Outcome.Failed
        assertEquals("exit 255: Error: Unknown operation string: ACCESS_RESTRICTED_SETTINGS", failed.reason)
        assertFalse("no raw dump", failed.reason.contains("Foo.java"))
        assertEquals("the read-back is not tried after a failed set", 1, shizuku.commands.size)
    }

    @Test
    fun `a failing set with no error text says only the exit code`() {
        val failed = restricted(FakeShizuku(reply = { ShellResult(1, "", "") })).allow() as RestrictedSettings.Outcome.Failed
        assertEquals("exit 1", failed.reason)
    }

    @Test
    fun `a very long error line is cut`() {
        val failed = restricted(FakeShizuku(reply = { ShellResult(1, "", "x".repeat(900)) })).allow() as RestrictedSettings.Outcome.Failed
        assertTrue(failed.reason.length < 200)
    }

    @Test
    fun `a timeout is a failure that says so`() {
        val set = restricted(FakeShizuku(reply = { ShellResult(-1, "", "", timedOut = true) })).allow() as RestrictedSettings.Outcome.Failed
        assertEquals("timed out", set.reason)
        val get = restricted(FakeShizuku(reply = answering(ShellResult(-1, "", "", timedOut = true)))).allow() as RestrictedSettings.Outcome.Failed
        assertEquals("timed out", get.reason)
    }

    @Test
    fun `a failing read-back is a failure with its exit code`() {
        val failed = restricted(FakeShizuku(reply = answering(ShellResult(1, "", "Error: bad\n")))).allow() as RestrictedSettings.Outcome.Failed
        assertEquals("exit 1: Error: bad", failed.reason)
    }

    @Test
    fun `a shell that throws is a failure, not a crash`() {
        val failed = restricted(FakeShizuku(reply = { throw IllegalStateException("binder died") })).allow() as RestrictedSettings.Outcome.Failed
        assertEquals("IllegalStateException", failed.reason)
    }

    // ---- the words ----

    @Test
    fun `the result line is plain`() {
        assertEquals("Allowed. Now switch Phone Steward on in Accessibility.", RestrictedSettings.line(RestrictedSettings.Outcome.Allowed))
        assertEquals("Could not allow it: exit 1.", RestrictedSettings.line(RestrictedSettings.Outcome.Failed("exit 1")))
        val notReady = RestrictedSettings.line(RestrictedSettings.Outcome.NotReady(ShizukuState.NOT_RUNNING))
        assertTrue(notReady, notReady.contains("Start Shizuku"))
    }
}
