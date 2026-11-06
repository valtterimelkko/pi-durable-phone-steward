package com.example.phonesteward.e2e

import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import com.example.phonesteward.screen.AppEntry
import com.example.phonesteward.screen.InstalledApps

/**
 * A14: the fake phone's installed apps for the Rules picker flows — invented names, flat-colour
 * icons (each app gets a colour derived from its id, so the picker screenshots show real icons).
 */
class FakeInstalledApps(
    private val apps: List<AppEntry>,
    /** A15.11: packages without a launcher entry, behind the Show system apps switch. */
    private val systemApps: List<AppEntry> = emptyList(),
) : InstalledApps {

    override fun launchable() = apps

    override fun installed() = apps + systemApps

    override fun label(packageName: String): String? =
        (apps.firstOrNull { it.packageName == packageName }
            ?: systemApps.firstOrNull { it.packageName == packageName })?.label

    override fun icon(packageName: String): Drawable? =
        if (apps.any { it.packageName == packageName }) ColorDrawable((packageName.hashCode() and 0xFFFFFF) or 0xFF000000.toInt()) else null
}
