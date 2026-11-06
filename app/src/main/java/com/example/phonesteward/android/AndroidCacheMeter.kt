package com.example.phonesteward.android

import android.content.Context
import android.content.pm.PackageManager
import com.example.phonesteward.logic.exec.AllCachesBytes
import com.example.phonesteward.logic.obs.Diagnostics

/** Phone readings the Shizuku actions are judged by (A10.2): the all-apps cache total, and whether an app is enabled. */
object AndroidCacheMeter {

    /**
     * The summed cacheBytes of every installed package, ALL-OR-NULL as in A6: one failed package makes the whole pass
     * unreadable, so the app shows "unknown" rather than a partial total.
     */
    fun total(context: Context): Long? = try {
        val stats = context.getSystemService(android.app.usage.StorageStatsManager::class.java)
        if (stats == null) {
            null
        } else {
            var failed = 0
            val readings = context.packageManager.getInstalledPackages(0).map { pkg ->
                try {
                    stats.queryStatsForPackage(
                        android.os.storage.StorageManager.UUID_DEFAULT, pkg.packageName, android.os.Process.myUserHandle(),
                    ).cacheBytes
                } catch (_: Exception) {
                    failed += 1
                    null
                }
            }
            if (failed > 0) {
                Diagnostics.error("exec.trim_caches.measure", IllegalStateException("$failed of ${readings.size} package readings failed"))
                null
            } else {
                AllCachesBytes.total(readings)
            }
        }
    } catch (e: Exception) {
        Diagnostics.error("exec.trim_caches.measure", e)
        null
    }

    /** True when the app is enabled for the owner, false when disabled; null when it cannot be read. */
    fun isEnabled(context: Context, packageName: String): Boolean? = try {
        when (context.packageManager.getApplicationEnabledSetting(packageName)) {
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER,
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED,
            -> false
            else -> true
        }
    } catch (_: Exception) {
        null
    }
}
