package com.example.phonesteward.logic.shizuku

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A10.2: the Shizuku state, the fixed command templates with validated arguments, and the never-touch list. */
class ShizukuLogicTest {

    // ---- the four states of permissions.shizuku ----

    @Test
    fun `no Shizuku app and no service is absent`() {
        assertEquals(ShizukuState.ABSENT, ShizukuState.of(installed = false, binderAlive = false, permissionGranted = false))
        assertEquals("absent", ShizukuState.ABSENT.wire)
    }

    @Test
    fun `the app is installed but its service is off`() {
        assertEquals(ShizukuState.NOT_RUNNING, ShizukuState.of(installed = true, binderAlive = false, permissionGranted = false))
        assertEquals("not_running", ShizukuState.NOT_RUNNING.wire)
    }

    @Test
    fun `the service runs but the steward has not been allowed`() {
        assertEquals(ShizukuState.NO_PERMISSION, ShizukuState.of(installed = true, binderAlive = true, permissionGranted = false))
        assertEquals("no_permission", ShizukuState.NO_PERMISSION.wire)
    }

    @Test
    fun `running and allowed is ready`() {
        assertEquals(ShizukuState.READY, ShizukuState.of(installed = true, binderAlive = true, permissionGranted = true))
        assertEquals("ready", ShizukuState.READY.wire)
    }

    @Test
    fun `a live service counts even when the app is hidden from the package list`() {
        assertEquals(ShizukuState.NO_PERMISSION, ShizukuState.of(installed = false, binderAlive = true, permissionGranted = false))
    }

    // ---- package names ----

    @Test
    fun `plain package names are valid`() {
        assertTrue(PackageNames.valid("com.example.app"))
        assertTrue(PackageNames.valid("com.example.e2ehelper.alpha"))
        assertTrue(PackageNames.valid("com.example.app_2.v3"))
    }

    @Test
    fun `anything that could carry a command is not a package name`() {
        for (bad in listOf("", "app", "com.example;reboot", "com.example app", "com.example\napp", "-f", "com..x", ".com.x",
            "com.x.", "com.example/.Main", "com.example&&id", "com.exa\$(id).app", "../x", "1com.example", "com.example.\u0000", "a".repeat(300) + ".b")) {
            assertFalse("'$bad' must not be a package name", PackageNames.valid(bad))
        }
    }

    // ---- fixed templates ----

    @Test
    fun `each template is a fixed argument array`() {
        assertEquals(listOf("/system/bin/am", "force-stop", "com.example.app"), ShellCommands.forceStop("com.example.app"))
        assertEquals(listOf("/system/bin/pm", "disable-user", "--user", "0", "com.example.app"), ShellCommands.disable("com.example.app"))
        assertEquals(listOf("/system/bin/pm", "enable", "--user", "0", "com.example.app"), ShellCommands.enable("com.example.app"))
        assertEquals(listOf("/system/bin/pm", "trim-caches", "999G"), ShellCommands.trimCaches())
        assertEquals(listOf("/system/bin/pm", "list", "packages", "-i"), ShellCommands.listInstallers())
        assertEquals(listOf("/system/bin/pm", "list", "packages", "-d"), ShellCommands.listDisabled())
        assertEquals(listOf("/system/bin/pm", "list", "packages", "-s"), ShellCommands.listSystem())
        assertEquals(listOf("/system/bin/dumpsys", "diskstats"), ShellCommands.diskstats())
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a template refuses an argument that is not a package name`() {
        ShellCommands.forceStop("com.example.app; reboot")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `disable refuses a flag-shaped argument`() {
        ShellCommands.disable("--user")
    }

    // ---- the never-touch list ----

    private val neverTouch = NeverTouch(keyboardInUse = { "com.invented.keyboard" }, launcher = { "com.invented.launcher" })

    @Test
    fun `the steward, the system and the owner's essentials are never touched`() {
        for (pkg in listOf(
            "com.example.phonesteward", "android", "com.android.systemui", "com.android.settings", "com.android.phone",
            "com.google.android.gms", "com.android.vending", "moe.shizuku.privileged.api", "com.android.providers.contacts",
            "com.google.android.inputmethod.latin", "com.android.inputmethod.latin", "com.android.server.telecom",
        )) {
            assertTrue("$pkg is protected", neverTouch.isProtected(pkg))
        }
    }

    @Test
    fun `the keyboard in use and the current launcher are never touched`() {
        assertTrue(neverTouch.isProtected("com.invented.keyboard"))
        assertTrue(neverTouch.isProtected("com.invented.launcher"))
    }

    @Test
    fun `comparison ignores case, and an ordinary app is free to touch`() {
        assertTrue(neverTouch.isProtected("Com.Android.SystemUI"))
        assertFalse(neverTouch.isProtected("com.example.puzzle"))
        assertFalse(neverTouch.isProtected("com.androidish.tool"))
    }

    @Test
    fun `an unreadable keyboard setting does not unprotect anything else`() {
        val n = NeverTouch(keyboardInUse = { null }, launcher = { null })
        assertTrue(n.isProtected("com.android.settings"))
        assertFalse(n.isProtected("com.invented.keyboard"))
    }

    // ---- shell_info parsers ----

    @Test
    fun `packages combine installer, disabled and system lists`() {
        val installers = """
            package:com.example.puzzle  installer=com.android.vending
            package:com.android.settings  installer=null
            package:com.example.sideloaded  installer=null
            package:com.example.old  installer=com.android.vending
        """.trimIndent()
        val disabled = "package:com.example.old\n"
        val system = "package:com.android.settings\n"
        val rows = ShellInfo.parsePackages(installers, disabled, system)
        assertEquals(4, rows.size)
        val puzzle = rows.single { it.packageName == "com.example.puzzle" }
        assertEquals(true, puzzle.enabled); assertEquals(false, puzzle.system); assertEquals("com.android.vending", puzzle.installer)
        val settings = rows.single { it.packageName == "com.android.settings" }
        assertEquals(true, settings.system); assertNull(settings.installer)
        assertEquals(false, rows.single { it.packageName == "com.example.old" }.enabled)
        assertNull(rows.single { it.packageName == "com.example.sideloaded" }.installer)
    }

    @Test
    fun `package lines that are not packages are ignored`() {
        val rows = ShellInfo.parsePackages("garbage\npackage:com.example.a  installer=null\nWarning: x\n", "", "")
        assertEquals(listOf("com.example.a"), rows.map { it.packageName })
    }

    @Test
    fun `diskstats arrays become one row per package`() {
        val out = """
            Latency: 0ms [512B Data Write]
            Data-Free: 12000000K / 100000000K total = 12% free
            App Size: 5000
            Package Names: ["com.example.puzzle","com.example.collage","com.android.settings"]
            App Sizes: [100,200,300]
            App Data Sizes: [11,22,33]
            Cache Sizes: [1,2,3]
        """.trimIndent()
        val rows = ShellInfo.parseDiskstats(out)!!
        assertEquals(3, rows.size)
        assertEquals(AppSizeRow("com.example.collage", 200, 22, 2), rows[1])
    }

    @Test
    fun `diskstats without the arrays or with mismatched lengths is not parsed`() {
        assertNull(ShellInfo.parseDiskstats("nothing useful here"))
        assertNull(ShellInfo.parseDiskstats("""Package Names: ["a.b","c.d"]
App Sizes: [1]
App Data Sizes: [1,2]
Cache Sizes: [1,2]"""))
    }

    @Test
    fun `an empty install list parses to no rows`() {
        assertEquals(0, ShellInfo.parsePackages("", "", "").size)
    }
}
