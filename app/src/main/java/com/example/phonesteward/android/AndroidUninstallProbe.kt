package com.example.phonesteward.android

import android.content.Context
import android.content.pm.PackageManager
import com.example.phonesteward.logic.exec.UninstallFlow

/**
 * The two readings [UninstallFlow] needs from the phone (0.2.4): is the package still installed, and
 * how big was it (app + data + cache, as the storage overview counts it; null when Android will not say,
 * for example without usage access).
 */
object AndroidUninstallProbe {

    fun isInstalled(context: Context, packageName: String): Boolean = try {
        context.packageManager.getPackageInfo(packageName, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    fun sizeBytes(context: Context, packageName: String): Long? = try {
        val stats = context.getSystemService(android.app.usage.StorageStatsManager::class.java)
        stats?.queryStatsForPackage(
            android.os.storage.StorageManager.UUID_DEFAULT,
            packageName,
            android.os.Process.myUserHandle(),
        )?.let { it.appBytes + it.dataBytes + it.cacheBytes }
    } catch (_: Exception) {
        null
    }

    fun flow(context: Context): UninstallFlow = UninstallFlow(
        isInstalled = { isInstalled(context, it) },
        sizeBytes = { sizeBytes(context, it) },
    )
}
