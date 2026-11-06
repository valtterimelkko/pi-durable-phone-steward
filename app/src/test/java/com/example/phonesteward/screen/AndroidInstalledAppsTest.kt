package com.example.phonesteward.screen

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * A14.2: the real adapter reads PackageManager's launcher entries and labels.
 * Under Robolectric the package manager is built from the merged manifest, so the
 * app's own entry is the fixture. Native graphics so the adaptive launcher icon loads.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [33])
class AndroidInstalledAppsTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `the app's own launcher entry is found with its label`() {
        val apps = AndroidInstalledApps(context)
        val own = apps.launchable().filter { it.packageName == context.packageName }
        assertTrue("the app itself must be launchable", own.isNotEmpty())
        assertEquals("Phone Steward", own.first().label)
    }

    @Test
    fun `a package that is not installed has no label and no icon`() {
        val apps = AndroidInstalledApps(context)
        assertNull(apps.label("com.example.missing"))
        assertNull(apps.icon("com.example.missing"))
    }

    @Test
    fun `an installed package has a label and an icon`() {
        val apps = AndroidInstalledApps(context)
        assertEquals("Phone Steward", apps.label(context.packageName))
        assertNotNull(apps.icon(context.packageName))
    }

    @Test
    fun `every launchable entry carries a non-empty label`() {
        val apps = AndroidInstalledApps(context)
        assertTrue(apps.launchable().isNotEmpty())
        assertTrue(apps.launchable().all { it.label.isNotEmpty() && it.packageName.isNotEmpty() })
    }
}
