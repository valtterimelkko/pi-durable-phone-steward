package com.example.phonesteward.android

import android.Manifest
import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Environment
import android.os.Process
import android.provider.MediaStore
import android.provider.Settings
import android.os.storage.StorageManager

/**
 * Permission state checks and the Settings intents, kept in one thin place.
 * No INTERNET anywhere; every route goes through Android's own screens.
 */
object Permissions {

    fun usageAccessGranted(context: Context): Boolean {
        val appOps = context.getSystemService(AppOpsManager::class.java) ?: return false
        val mode = appOps.unsafeCheckOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            context.packageName,
        )
        return mode == AppOpsManager.MODE_ALLOWED
    }

    /**
     * A status probe must never take the app down: if Android cannot answer
     * (no primary volume yet, as under Robolectric), report not granted.
     */
    fun allFilesGranted(): Boolean = try {
        Environment.isExternalStorageManager()
    } catch (_: RuntimeException) {
        false
    }

    fun mediaReadGranted(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED &&
            context.checkSelfPermission(Manifest.permission.READ_MEDIA_VIDEO) == PackageManager.PERMISSION_GRANTED

    fun canManageMedia(context: Context): Boolean = MediaStore.canManageMedia(context)

    fun mediaPermissions() = arrayOf(
        Manifest.permission.READ_MEDIA_IMAGES,
        Manifest.permission.READ_MEDIA_VIDEO,
    )

    fun usageAccessIntent(): Intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)

    fun allFilesIntent(packageName: String): Intent = Intent(
        Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
        Uri.parse("package:$packageName"),
    )

    /**
     * Settings page to grant media management. The action string is the
     * documented `android.provider.action.REQUEST_MANAGE_MEDIA` (API 30+).
     */
    fun manageMediaIntent(packageName: String): Intent = Intent(
        "android.provider.action.REQUEST_MANAGE_MEDIA",
        Uri.parse("package:$packageName"),
    )

    fun appInfoIntent(packageName: String): Intent = Intent(
        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.parse("package:$packageName"),
    )

    /** Deprecated but working self-uninstall prompt; the owner presses Cancel. */
    @Suppress("DEPRECATION")
    fun uninstallSelfIntent(packageName: String): Intent = Intent(
        Intent.ACTION_UNINSTALL_PACKAGE,
        Uri.parse("package:$packageName"),
    )

    /**
     * The uninstall dialog for an approved `uninstall_app` item. ACTION_DELETE returns no result, so
     * ACTION_UNINSTALL_PACKAGE with EXTRA_RETURN_RESULT is used: PackageInstaller answers RESULT_OK
     * after the removal finished, RESULT_CANCELED on Cancel and RESULT_FIRST_USER on failure
     * (deprecated at API 29, still honoured on 33 and 35; needs REQUEST_DELETE_PACKAGES, which the
     * manifest declares).
     */
    @Suppress("DEPRECATION")
    fun uninstallIntent(packageName: String): Intent = Intent(
        Intent.ACTION_UNINSTALL_PACKAGE,
        Uri.parse("package:$packageName"),
    ).putExtra(Intent.EXTRA_RETURN_RESULT, true)

    /** Some OEM builds (e.g. OxygenOS) may behave differently here; unverified. */
    fun clearAppCacheIntent(): Intent = Intent(StorageManager.ACTION_CLEAR_APP_CACHE)

    fun shareSummaryIntent(text: String): Intent = Intent.createChooser(
        Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, text),
        "Share storage summary",
    )
}
