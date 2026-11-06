package com.example.phonesteward

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.annotation.Config
import androidx.startup.InitializationProvider
import com.example.phonesteward.android.AppGraph
import com.example.phonesteward.android.PowerSettings

/**
 * N1: the Status tab's battery button opens Android's battery-optimisation
 * request. The manifest must declare REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
 * (its absence was the 0.2.1 bug), the direct request comes first, and two
 * Settings fallbacks follow it so the button can never do nothing.
 *
 * The launch behaviour is tested through the same launcher seam the button
 * wires (`startActivity`), because Robolectric cannot drive MainActivity's
 * Compose click layer under this Gradle setup — the reason already recorded
 * on CacheClearFlow.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class BatteryButtonTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        Robolectric.setupContentProvider(InitializationProvider::class.java, "${context.packageName}.androidx-startup")
        AppGraph.resetForTest()
    }

    private class RecordingLauncher : PowerSettings.Launcher {
        val started = mutableListOf<Intent>()
        var failAll = false
        override fun launch(intent: Intent) {
            if (failAll) throw IllegalArgumentException("no activity for ${intent.action}")
            started += intent
        }
    }

    @Test
    fun `the manifest declares REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`() {
        val requested = context.packageManager
            .getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
            .requestedPermissions.orEmpty()
        assertTrue(
            "wanted REQUEST_IGNORE_BATTERY_OPTIMIZATIONS in $requested",
            requested.contains("android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS"),
        )
    }

    @Test
    fun `the battery button launches the direct request first`() {
        val launcher = RecordingLauncher()
        var failed: Exception? = null
        PowerSettings.openBatterySettings(context, launcher) { failed = it }
        assertEquals(
            listOf(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS),
            launcher.started.map { it.action },
        )
        assertNull(failed)
    }

    @Test
    fun `the fallback chain is direct request, settings list, then the app page`() {
        val intents = PowerSettings.batteryIntents(context)
        assertEquals(3, intents.size)
        assertEquals(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, intents[0].action)
        assertEquals(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS, intents[1].action)
        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, intents[2].action)
    }

    @Test
    fun `when every launch throws the failure is reported, never hidden`() {
        var reported: Exception? = null
        val throwing = RecordingLauncher().apply { failAll = true }
        PowerSettings.openBatterySettings(context, throwing) { reported = it }
        assertTrue("no launch can have succeeded", throwing.started.isEmpty())
        assertTrue("the last failure must surface", reported is IllegalArgumentException)
    }
}
