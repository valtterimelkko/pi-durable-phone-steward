package com.example.phonesteward.update

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import com.example.phonesteward.BuildConfig
import com.example.phonesteward.android.AppGraph

/**
 * Holds the self-update (A7) for the Status screen. Separate from the app view model so the
 * update has its own lifecycle and the Chat/Proposals code is untouched.
 */
class UpdateViewModel(app: Application) : AndroidViewModel(app) {

    private val graph = AppGraph.get(app)

    val controller = UpdateController(
        installedVersionCode = BuildConfig.VERSION_CODE.toLong(),
        installedVersionName = BuildConfig.VERSION_NAME,
        server = StewardUpdateServer { graph.paired.client() },
        verifier = UpdateVerifier(
            expectedPackage = app.packageName,
            installedVersionCode = BuildConfig.VERSION_CODE.toLong(),
            installedSigners = runCatching { installedSigners(app) }.getOrDefault(SignerInfo(emptySet(), emptySet(), false)),
            inspector = PackageApkInspector(app),
        ),
        installer = PackageInstallerGateway(app),
        permission = AndroidInstallPermission(app),
        workDir = java.io.File(app.cacheDir, "updates"),
        log = DiagnosticsUpdateLog,
    )

    val state: StateFlow<UpdateUiState> = controller.state

    /**
     * True when this start follows the owner's trip to "Install unknown apps" (Android killed the app
     * there): the first resume carries the update on, and the activity shows Status where it runs.
     */
    val resumedAfterSwitch: Boolean = UpdatePrefs.takeAwaitingPermission(app)
    private var carryOnPending = resumedAfterSwitch

    init {
        controller.noteInstallOutcomeAtStart(UpdatePrefs.pendingTarget(app))
        UpdatePrefs.clearPendingTarget(app)
        viewModelScope.launch {
            UpdateInstallBus.results.collect { controller.onInstallStatus(it.status) }
        }
    }

    fun check() {
        viewModelScope.launch(Dispatchers.IO) { controller.check() }
    }

    fun update() {
        viewModelScope.launch(Dispatchers.IO) { controller.update() }
    }

    /** On return from Android's settings (ON_RESUME). */
    fun onAppResume() {
        if (carryOnPending) {
            carryOnPending = false
            viewModelScope.launch(Dispatchers.IO) { controller.continueAfterPermissionRestart() }
            return
        }
        viewModelScope.launch(Dispatchers.IO) { controller.resumeIfPermitted() }
    }
}
