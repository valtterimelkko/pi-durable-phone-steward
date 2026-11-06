package com.example.phonesteward.e2e

import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import com.example.phonesteward.android.AppGraph
import com.example.phonesteward.logic.shizuku.ShellResult
import com.example.phonesteward.logic.shizuku.ShellRunner
import com.example.phonesteward.logic.shizuku.ShizukuAccess
import com.example.phonesteward.logic.shizuku.ShizukuState

/**
 * Flow G1, Allow with Shizuku (protocol A12) on the Phone use card of Status. Test Lab has no Shizuku and never restricts the
 * app, so the real command cannot be proved here (A12.3): a fake Shizuku stands in through the app graph's test seam and
 * records what the card asked it to run. The card's states, the exact argument arrays and the plain result lines are proved
 * here; the effect on Android is checked on a real phone.
 */
@RunWith(AndroidJUnit4::class)
class FlowG1AllowShizukuTest : E2eBase() {
    override val flow = "allow-shizuku"

    private class FakeShizuku(
        var current: ShizukuState,
        private val reply: (List<String>) -> ShellResult = { args ->
            if (args[1] == "get") ShellResult(0, "ACCESS_RESTRICTED_SETTINGS: allow\n", "") else ShellResult(0, "", "")
        },
    ) : ShizukuAccess {
        val commands = java.util.concurrent.CopyOnWriteArrayList<List<String>>()
        private val listeners = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()
        override fun state() = current
        override fun shell() = ShellRunner { args, _ -> commands += args; reply(args) }
        override fun requestPermission() = Unit
        override fun addChangeListener(onChange: () -> Unit): AutoCloseable {
            listeners += onChange
            return AutoCloseable { listeners -= onChange }
        }
        fun change(to: ShizukuState) { current = to; listeners.forEach { it() } }
    }

    private val own get() = context.packageName

    private fun land(shizuku: FakeShizuku? = null) {
        if (shizuku != null) AppGraph.get(context).shizuku = shizuku
        launchApp()
        pairAndLand()
        openTab("Status")
        waitForTag("update-card")
    }

    private fun toPhoneUse() {
        waitForTag("phone-use-restricted-note")
        runCatching { tag("phone-use-restricted-note").performScrollTo() }
    }

    @Test
    fun theStepsAreInOrderAndWithoutShizukuTheCardSaysWhatToDo() {
        land()
        toPhoneUse()
        waitForTag("phone-use-shizuku-note")
        waitForText("Shizuku is not installed", substring = true)
        assertTrue(hasText("Some OEM builds, e.g. OxygenOS, have no three-dot menu: use Allow with Shizuku.", substring = true))
        assertFalse("no Allow button without Shizuku", hasTag("phone-use-allow-shizuku"))
        assertFalse("no developer terms", hasText("appops", substring = true) || hasText("adb", substring = true))
        runCatching { tag("phone-use-shizuku-note").performScrollTo() }
        shot("1-no-shizuku")
    }

    @Test
    fun withShizukuReadyTheButtonRunsTheFixedPairAndSaysWhatToDoNext() {
        val shizuku = FakeShizuku(ShizukuState.READY)
        land(shizuku)
        toPhoneUse()
        waitForTag("phone-use-allow-shizuku")
        runCatching { tag("phone-use-allow-shizuku").performScrollTo() }
        shot("2-button")
        clickTag("phone-use-allow-shizuku")
        waitForText("Allowed. Now switch Phone Steward on in Accessibility.")
        assertEquals(
            listOf(
                listOf("/system/bin/appops", "set", own, "ACCESS_RESTRICTED_SETTINGS", "allow"),
                listOf("/system/bin/appops", "get", own, "ACCESS_RESTRICTED_SETTINGS"),
            ),
            shizuku.commands.toList(),
        )
        assertTrue("the Accessibility button is next to it", hasTag("phone-use-accessibility"))
        runCatching { tag("phone-use-allow-result").performScrollTo() }
        shot("3-allowed")
    }

    @Test
    fun aFailureShowsTheReasonAndKeepsTheButton() {
        val shizuku = FakeShizuku(ShizukuState.READY, reply = { ShellResult(255, "", "Error: Unknown operation string\nat Foo.bar(Foo.java:1)\n") })
        land(shizuku)
        toPhoneUse()
        clickTag("phone-use-allow-shizuku")
        waitForText("Could not allow it: exit 255: Error: Unknown operation string.")
        assertFalse("no raw dump", hasText("Foo.java", substring = true))
        assertTrue("another try is possible", hasTag("phone-use-allow-shizuku"))
        runCatching { tag("phone-use-allow-result").performScrollTo() }
        shot("4-failed")
        assertEquals("only the set was tried", 1, shizuku.commands.size)
    }

    @Test
    fun withShizukuStoppedTheCardSaysToStartItAndThenTheButtonAppearsWhenItRuns() {
        val shizuku = FakeShizuku(ShizukuState.NOT_RUNNING)
        land(shizuku)
        toPhoneUse()
        waitForText("Shizuku is not running", substring = true)
        assertTrue(hasTag("phone-use-shizuku-action"))
        assertFalse(hasTag("phone-use-allow-shizuku"))
        runCatching { tag("phone-use-shizuku-note").performScrollTo() }
        shot("5-stopped")
        shizuku.change(ShizukuState.READY)
        waitForTag("phone-use-allow-shizuku")
        assertFalse(hasTag("phone-use-shizuku-note"))
        assertEquals("nothing ran by itself", emptyList<List<String>>(), shizuku.commands.toList())
    }
}
