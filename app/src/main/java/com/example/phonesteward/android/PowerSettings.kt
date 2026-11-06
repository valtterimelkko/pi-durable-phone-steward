package com.example.phonesteward.android

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings

/**
 * Vendor quirk (some OEM builds, e.g. OxygenOS): battery optimisation ("unrestricted" battery use).
 * Thin adapter with the check and the Settings intent.
 */
object PowerSettings {

    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        val pm = context.getSystemService(PowerManager::class.java) ?: return false
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    /** Asks Android to let the app run in the background (owner confirms). */
    fun requestIgnoreBatteryOptimizationsIntent(context: Context): Intent =
        Intent(
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Uri.parse("package:${context.packageName}"),
        )

    /** Launches an intent or throws; injected so tests can fail every launch. */
    fun interface Launcher {
        fun launch(intent: Intent)
    }

    /**
     * N1: the battery row tries, in order:
     * 1. the direct request dialog (needs REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
     *    in the manifest — its absence was the 0.2.1 bug);
     * 2. the system's battery-optimisation list (all apps);
     * 3. the app's own Details page, where some OEM builds (e.g. OxygenOS) keep the battery controls.
     */
    fun batteryIntents(context: Context): List<Intent> = listOf(
        requestIgnoreBatteryOptimizationsIntent(context),
        Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
        Permissions.appInfoIntent(context.packageName),
    )

    /**
     * Opens the first battery screen that launches. When every one throws
     * (never expected), the LAST exception surfaces through [onFailed] so
     * the site can report it (O5) — the button never fails silently.
     */
    fun openBatterySettings(context: Context, launcher: Launcher, onFailed: (Exception) -> Unit) {
        var last: Exception? = null
        for (intent in batteryIntents(context)) {
            // O6: what the owner's tap asked Android for, in order.
            com.example.phonesteward.logic.obs.Diagnostics.event(
                "info", "battery_intent", intent.action ?: "unknown",
            )
            try {
                launcher.launch(intent)
                return
            } catch (e: Exception) {
                last = e
            }
        }
        last?.let(onFailed)
    }
}
