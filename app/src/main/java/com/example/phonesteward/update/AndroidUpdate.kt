package com.example.phonesteward.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import com.example.phonesteward.logic.obs.Diagnostics
import com.example.phonesteward.net.ApiException
import com.example.phonesteward.net.StewardApi

/** The Android halves of the self-update (A7): archive inspection, the install session, the switch. */

private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }

private fun sha256Hex(bytes: ByteArray) = hex(MessageDigest.getInstance("SHA-256").digest(bytes))

/** [SignerInfo] of a [PackageInfo] read with `GET_SIGNING_CERTIFICATES`; empty when it carries no signature. */
internal fun signerInfoOf(info: PackageInfo?): SignerInfo {
    val signing = info?.signingInfo ?: return SignerInfo(emptySet(), emptySet(), false)
    val current = signing.apkContentsSigners.orEmpty().map { sha256Hex(it.toByteArray()) }.toSet()
    val history = if (signing.hasMultipleSigners()) {
        current
    } else {
        signing.signingCertificateHistory.orEmpty().map { sha256Hex(it.toByteArray()) }.toSet() + current
    }
    return SignerInfo(current = current, history = history, multiple = signing.hasMultipleSigners())
}

/** The running app's own identity, for the verifier. */
internal fun installedSigners(context: Context): SignerInfo {
    val pm = context.packageManager
    val info = pm.getPackageInfo(
        context.packageName,
        PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong()),
    )
    return signerInfoOf(info)
}

/** `getPackageArchiveInfo` with `GET_SIGNING_CERTIFICATES`: what Android itself reads from the file. */
class PackageApkInspector(private val context: Context) : ApkInspector {
    override fun inspect(file: File): ApkInfo? {
        val info = context.packageManager.getPackageArchiveInfo(
            file.absolutePath,
            PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong()),
        ) ?: return null
        return ApkInfo(info.packageName, info.longVersionCode, signerInfoOf(info))
    }
}

/** What the install session reported, from [InstallResultReceiver] to the controller. */
data class InstallResult(val status: Int, val message: String?)

object UpdateInstallBus {
    private val flow = MutableSharedFlow<InstallResult>(extraBufferCapacity = 16)
    val results: SharedFlow<InstallResult> = flow
    fun post(result: InstallResult) {
        flow.tryEmit(result)
    }
}

/**
 * Installs the downloaded APK over this app (same package, same signature) through a
 * `PackageInstaller` session in `MODE_FULL_INSTALL`: Android keeps the app's data, so the
 * pairing, the outbox and the caches survive. Android's answers go to [InstallResultReceiver].
 */
class PackageInstallerGateway(private val context: Context) : UpdateInstaller {
    override fun install(file: File, versionCode: Long) {
        val installer = context.packageManager.packageInstaller
        // A session left by an earlier try would only hold disk and confuse the receiver.
        installer.mySessions.forEach { runCatching { installer.abandonSession(it.sessionId) } }
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            setSize(file.length())
            if (Build.VERSION.SDK_INT >= 31) {
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
        }
        val sessionId = installer.createSession(params)
        try {
            installer.openSession(sessionId).use { session ->
                file.inputStream().use { input ->
                    session.openWrite("base.apk", 0, file.length()).use { out ->
                        input.copyTo(out)
                        session.fsync(out)
                    }
                }
                val intent = Intent(context, InstallResultReceiver::class.java).setAction(ACTION_RESULT)
                // Mutable: the system adds the status extras to it.
                val pending = PendingIntent.getBroadcast(
                    context,
                    sessionId,
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
                )
                UpdatePrefs.setPendingTarget(context, versionCode)
                session.commit(pending.intentSender)
            }
        } catch (e: Exception) {
            runCatching { installer.abandonSession(sessionId) }
            throw e
        }
    }

    companion object {
        const val ACTION_RESULT = "com.example.phonesteward.UPDATE_INSTALL_RESULT"
    }
}

/** Android's answer to the install session. Shows its confirmation dialog when it asks for one. */
class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            // Android's one confirmation : launch the system dialog it handed us.
            val confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
            if (confirm != null) {
                try {
                    context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } catch (e: Exception) {
                    Diagnostics.error("update.install", e)
                }
            }
        }
        UpdateInstallBus.post(InstallResult(status, intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)))
    }
}

/** "Install unknown apps" for this app, with the deep link straight to its own switch. */
class AndroidInstallPermission(private val context: Context) : InstallPermission {
    override fun canInstall(): Boolean = context.packageManager.canRequestPackageInstalls()

    override fun openSettings() {
        // Android kills this app when the switch changes: remember why the owner left (see UpdateViewModel).
        UpdatePrefs.markAwaitingPermission(context)
        context.startActivity(
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}

/** The update events go to the A6 event log, failures to the A5/O5 error reports. */
object DiagnosticsUpdateLog : UpdateLog {
    override fun event(level: String, event: String, detail: String) = Diagnostics.event(level, event, detail)
    override fun error(site: String, error: Throwable) = Diagnostics.error(site, error)
}

/** The paired client as an [UpdateServer]; `clients` follows the paired identity on every call. */
class StewardUpdateServer(private val clients: () -> StewardApi) : UpdateServer {
    override fun latest(): AppLatest? = try {
        clients().appLatest()
    } catch (e: ApiException) {
        if (e.code == "NO_RELEASE") null else throw e
    }

    override fun download(latest: AppLatest, dest: File, maxBytes: Long, onProgress: (Long) -> Unit) {
        clients().downloadApk(latest.versionCode, dest, maxBytes, onProgress)
    }
}

/** The one preference the update keeps: the versionCode an install was started for. */
internal object UpdatePrefs {
    private const val NAME = "phone_steward_update"
    private const val KEY_TARGET = "pending_target_version_code"

    fun setPendingTarget(context: Context, versionCode: Long) {
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE).edit().putLong(KEY_TARGET, versionCode).apply()
    }

    fun pendingTarget(context: Context): Long =
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE).getLong(KEY_TARGET, 0L)

    private const val KEY_AWAITING = "awaiting_permission_since_ms"

    /** Remembers that the owner was sent to the "Install unknown apps" switch (the app may be killed there). */
    fun markAwaitingPermission(context: Context, nowMs: Long = System.currentTimeMillis()) {
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE).edit().putLong(KEY_AWAITING, nowMs).apply()
    }

    /** True when the owner was sent to the switch less than [WITHIN_MS] ago; the mark is cleared either way. */
    fun takeAwaitingPermission(context: Context, nowMs: Long = System.currentTimeMillis()): Boolean {
        val prefs = context.getSharedPreferences(NAME, Context.MODE_PRIVATE)
        val since = prefs.getLong(KEY_AWAITING, 0L)
        prefs.edit().remove(KEY_AWAITING).apply()
        return since > 0 && nowMs - since in 0..WITHIN_MS
    }

    private const val WITHIN_MS = 10L * 60_000

    fun clearPendingTarget(context: Context) {
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE).edit().remove(KEY_TARGET).apply()
    }
}
