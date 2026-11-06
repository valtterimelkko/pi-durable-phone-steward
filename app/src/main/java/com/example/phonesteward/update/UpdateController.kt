package com.example.phonesteward.update

import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import com.example.phonesteward.net.ApiException
import com.example.phonesteward.net.TransportException

/** The server side of an update: the newest release, and its APK (A7). Blocking calls. */
interface UpdateServer {
    /** The release, or null when the server has none published (404 NO_RELEASE). */
    fun latest(): AppLatest?

    /** Streams the APK for [latest] into [dest]; throws [ApkTooLargeException] past [maxBytes]. */
    fun download(latest: AppLatest, dest: File, maxBytes: Long, onProgress: (Long) -> Unit)
}

/** Starts an in-place install of [file] (a `PackageInstaller` session); Android's answers arrive via [UpdateController.onInstallStatus]. */
interface UpdateInstaller {
    fun install(file: File, versionCode: Long)
}

/** Android's "Install unknown apps" switch for this app. */
interface InstallPermission {
    fun canInstall(): Boolean

    /** Opens the one switch (`ACTION_MANAGE_UNKNOWN_APP_SOURCES` with the package URI). */
    fun openSettings()
}

/** Where the update events (A6 log) and error reports (A5/A6) go. */
interface UpdateLog {
    fun event(level: String, event: String, detail: String)
    fun error(site: String, error: Throwable)
}

/**
 * The one-tap self-update (protocol A7) as a state machine: check, ask for the
 * "Install unknown apps" switch once, download, verify, install in place. Pure JVM; the
 * Android pieces come in through the small interfaces above.
 */
class UpdateController(
    private val installedVersionCode: Long,
    private val installedVersionName: String,
    private val server: UpdateServer,
    private val verifier: UpdateVerifier,
    private val installer: UpdateInstaller,
    private val permission: InstallPermission,
    private val workDir: File,
    private val log: UpdateLog,
    private val maxBytes: Long = MAX_APK_BYTES,
) {
    private val _state = MutableStateFlow<UpdateUiState>(UpdateUiState.Unknown(installedVersionName))
    val state: StateFlow<UpdateUiState> = _state

    private val busy = AtomicBoolean(false)

    /** Asks the server for the newest release. Safe to call often (Status open, resume). */
    suspend fun check() {
        val current = _state.value
        if (current is UpdateUiState.Downloading || current is UpdateUiState.Verifying ||
            current is UpdateUiState.Installing || current is UpdateUiState.NeedsPermission
        ) {
            return
        }
        if (!busy.compareAndSet(false, true)) return
        try {
            doCheck()
        } finally {
            busy.set(false)
        }
    }

    private fun doCheck() {
        set(UpdateUiState.Checking(installedVersionName))
        val latest = try {
            server.latest()
        } catch (e: TransportException) {
            log.event("warn", "update_check", "unreachable")
            set(UpdateUiState.CheckFailed(installedVersionName))
            return
        } catch (e: ApiException) {
            log.event("warn", "update_check", "failed ${e.code}")
            log.error("update.check", IllegalStateException("update check failed: ${e.code}"))
            set(UpdateUiState.CheckFailed(installedVersionName))
            return
        } catch (e: Exception) {
            log.event("warn", "update_check", "failed ${e.javaClass.simpleName}")
            log.error("update.check", e)
            set(UpdateUiState.CheckFailed(installedVersionName))
            return
        }
        if (latest == null) {
            log.event("info", "update_check", "no_release installed=$installedVersionCode")
            set(UpdateUiState.UpToDate(installedVersionName))
        } else if (AppVersion.isNewer(latest.versionCode, installedVersionCode)) {
            log.event("info", "update_check", "available latest=${latest.versionCode} installed=$installedVersionCode")
            set(UpdateUiState.Available(installedVersionName, latest))
        } else {
            log.event("info", "update_check", "up_to_date latest=${latest.versionCode} installed=$installedVersionCode")
            set(UpdateUiState.UpToDate(installedVersionName))
        }
    }

    /** The Update button: permission switch if needed, then download, verify, install. */
    suspend fun update() {
        val target = targetOf(_state.value) ?: return
        if (!busy.compareAndSet(false, true)) return
        try {
            perform(target)
        } finally {
            busy.set(false)
        }
    }

    /** On return from Android's settings: carry on if the switch is now on (no second tap). */
    suspend fun resumeIfPermitted() {
        val current = _state.value
        if (current is UpdateUiState.NeedsPermission && permission.canInstall()) update()
    }

    /**
     * Android kills this app when the owner flips "Install unknown apps" (seen on Test Lab: "Killing ...
     * REQUEST_INSTALL_PACKAGES changed"), so the state in memory is gone when the owner comes back. The
     * app remembers that it sent the owner there; on the next start it checks again and, now permitted,
     * carries on without a second tap . Still not permitted: it only shows the Update button again.
     */
    suspend fun continueAfterPermissionRestart() {
        check()
        if (_state.value is UpdateUiState.Available && permission.canInstall()) update()
    }

    /** Only a state that holds a release (and is not mid-flight) can start an update. */
    private fun targetOf(state: UpdateUiState): AppLatest? = when (state) {
        is UpdateUiState.Available -> state.latest
        is UpdateUiState.NeedsPermission -> state.latest
        is UpdateUiState.Refused -> state.latest
        is UpdateUiState.Failed -> state.latest
        else -> null
    }

    private fun perform(latest: AppLatest) {
        if (!permission.canInstall()) {
            set(UpdateUiState.NeedsPermission(installedVersionName, latest))
            log.event("info", "update_install", "needs_unknown_sources")
            permission.openSettings()
            return
        }
        if (verifier.checkAnnounced(latest) is UpdateVerifier.Verdict.Refused) {
            refuse(latest, UpdateRefusal.TOO_LARGE, null)
            return
        }
        workDir.mkdirs()
        clean()
        val file = File(workDir, "update-${latest.versionCode}.apk")

        set(UpdateUiState.Downloading(installedVersionName, latest, 0, latest.sizeBytes))
        log.event("info", "update_download", "start code=${latest.versionCode} bytes=${latest.sizeBytes}")
        var lastShown = 0L
        try {
            server.download(latest, file, maxBytes) { done ->
                if (done - lastShown >= PROGRESS_STEP_BYTES || done >= latest.sizeBytes) {
                    lastShown = done
                    set(UpdateUiState.Downloading(installedVersionName, latest, done, latest.sizeBytes))
                }
            }
        } catch (e: ApkTooLargeException) {
            file.delete()
            refuse(latest, UpdateRefusal.TOO_LARGE, null)
            return
        } catch (e: ApiException) {
            file.delete()
            if (e.code == "RELEASE_CHANGED" || e.code == "NO_RELEASE") {
                // A newer release shipped (or was withdrawn) while we were asking: look again.
                log.event("warn", "update_download", "release_changed")
                doCheck()
            } else {
                log.event("warn", "update_download", "failed ${e.code}")
                log.error("update.download", IllegalStateException("download failed: ${e.code}"))
                set(UpdateUiState.Failed(installedVersionName, latest, "The download failed. Try again."))
            }
            return
        } catch (e: TransportException) {
            file.delete()
            log.event("warn", "update_download", "interrupted")
            set(UpdateUiState.Failed(installedVersionName, latest, "The download was interrupted. Try again."))
            return
        } catch (e: Exception) {
            file.delete()
            log.event("warn", "update_download", "failed ${e.javaClass.simpleName}")
            log.error("update.download", e)
            set(UpdateUiState.Failed(installedVersionName, latest, "The download failed. Try again."))
            return
        }
        log.event("info", "update_download", "done bytes=${file.length()}")

        set(UpdateUiState.Verifying(installedVersionName, latest))
        when (val verdict = verifier.verify(file, latest)) {
            is UpdateVerifier.Verdict.Refused -> {
                file.delete()
                refuse(latest, verdict.reason, null)
                return
            }
            UpdateVerifier.Verdict.Ok -> log.event("info", "update_verified", "ok code=${latest.versionCode}")
        }

        set(UpdateUiState.Installing(installedVersionName, latest))
        try {
            installer.install(file, latest.versionCode)
            log.event("info", "update_install", "committed")
        } catch (e: Exception) {
            log.event("warn", "update_install", "commit_failed ${e.javaClass.simpleName}")
            log.error("update.install", e)
            set(UpdateUiState.Failed(installedVersionName, latest, "Android could not start the install. Try again."))
        }
    }

    private fun refuse(latest: AppLatest, reason: UpdateRefusal, detail: String?) {
        log.event("warn", "update_refused", reason.code)
        // The exception message is the reason code only: no path, no file name.
        log.error("update.verify", IllegalStateException("update refused: ${reason.code}${detail?.let { " $it" } ?: ""}"))
        set(UpdateUiState.Refused(installedVersionName, latest, reason))
    }

    /** Android's answer to the install session (from the result receiver). */
    fun onInstallStatus(status: Int) {
        val name = InstallStatus.name(status)
        val latest = (_state.value as? UpdateUiState.Installing)?.latest
        when (status) {
            InstallStatus.PENDING_USER_ACTION -> log.event("info", "update_install", name)
            InstallStatus.SUCCESS -> {
                log.event("info", "update_install", name)
                set(UpdateUiState.UpToDate(installedVersionName))
            }
            InstallStatus.FAILURE_ABORTED -> {
                log.event("info", "update_install", name)
                if (latest != null) set(UpdateUiState.Failed(installedVersionName, latest, "Cancelled. Nothing was changed."))
            }
            else -> {
                log.event("warn", "update_install", name)
                log.error("update.install", IllegalStateException("install failed: $name"))
                if (latest != null) set(UpdateUiState.Failed(installedVersionName, latest, "Android did not install the update ($name). Nothing was changed."))
            }
        }
    }

    /** At start: a recorded install target that is now the installed version means the update landed. */
    fun noteInstallOutcomeAtStart(pendingTargetVersionCode: Long) {
        if (pendingTargetVersionCode > 0 && installedVersionCode >= pendingTargetVersionCode) {
            log.event("info", "update_install", "success")
        }
    }

    private fun clean() {
        workDir.listFiles()?.forEach { it.delete() }
    }

    private fun set(state: UpdateUiState) {
        _state.value = state
    }

    companion object {
        /** A7: at most 100 MiB. */
        const val MAX_APK_BYTES: Long = 100L * 1024 * 1024
        private const val PROGRESS_STEP_BYTES = 256L * 1024
    }
}
