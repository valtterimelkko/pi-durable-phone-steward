package com.example.phonesteward

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.lifecycle.Lifecycle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.concurrent.CompletableFuture
import java.util.concurrent.LinkedBlockingDeque
import com.example.phonesteward.android.Permissions
import com.example.phonesteward.android.PowerSettings
import com.example.phonesteward.android.SelftestDialogs
import com.example.phonesteward.android.StatFsSpaceSource
import com.example.phonesteward.logic.Onboarding
import com.example.phonesteward.logic.SpaceRow
import com.example.phonesteward.ui.AppViewModel
import com.example.phonesteward.ui.StatusActions
import com.example.phonesteward.ui.StewardApp
import com.example.phonesteward.ui.StewardTab
import com.example.phonesteward.ui.theme.PhoneStewardTheme
import com.example.phonesteward.update.UpdateViewModel

class MainActivity : ComponentActivity() {

    private val vm by viewModels<AppViewModel>()

    /** F1 (A7): the one-tap self-update; its own view model, away from the chat and proposals code. */
    private val updateVm by viewModels<UpdateViewModel>()

    /** Futures for in-flight system dialogs, completed in launch order. */
    private val dialogResults = LinkedBlockingDeque<CompletableFuture<Int>>()

    private fun nextFuture(): CompletableFuture<Int> =
        CompletableFuture<Int>().also { dialogResults.addLast(it) }

    private fun completeNext(code: Int) {
        dialogResults.pollFirst()?.complete(code)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // N2: every return to the app (from Settings, from another app) re-reads
        // the permission snapshot and the server data, so no row or list waits
        // for a restart.
        lifecycle.addObserver(
            androidx.lifecycle.LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) {
                    vm.onAppResume()
                    // F1: back from Android's "Install unknown apps" switch, the update carries on.
                    updateVm.onAppResume()
                    // A11.4 (correction 1): returning to the app on Chat re-reads the footer too — the coalesced
                    // refresh cannot cover it, because a failing proposals call stops it before its readAgent.
                    if (bottomTab == StewardTab.Chat.ordinal) vm.onChatForeground()
                }
            },
        )
        // After the "Install unknown apps" switch Android restarted the app: show where the update runs.
        if (savedInstanceState == null && updateVm.resumedAfterSwitch) bottomTab = StewardTab.Status.ordinal
        enableEdgeToEdge()
        setContent {
            PhoneStewardTheme {
                PhoneStewardApp(vm)
            }
        }
    }

    @Composable
    private fun PhoneStewardApp(viewModel: AppViewModel) {
        val state by viewModel.state.collectAsState()

        // A15.2: when the owner answers a running session's question, the app the session was
        // using comes back to the front — this activity moves its own task to the back.
        LaunchedEffect(Unit) {
            com.example.phonesteward.screen.BackToUsedApp.token.collect { token ->
                if (token != null) {
                    com.example.phonesteward.screen.BackToUsedApp.consume()
                    moveTaskToBack(true)
                }
            }
        }

        // --- system-dialog launchers (trash batch, uninstall, self-test) ---
        // Each launcher completes the futures queue in launch order; the view
        // model awaits each dialog before starting the next.
        val trashLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.StartIntentSenderForResult(),
        ) { result -> completeNext(result.resultCode) }
        val selftestLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.StartIntentSenderForResult(),
        ) { result -> completeNext(result.resultCode) }
        val uninstallLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.StartActivityForResult(),
        ) { result -> completeNext(result.resultCode) }
        val allCachesLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.StartActivityForResult(),
        ) { result -> completeNext(result.resultCode) }
        val mediaReadLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions(),
        ) { }

        fun launchIntentSender(launcher: androidx.activity.result.ActivityResultLauncher<IntentSenderRequest>, sender: android.content.IntentSender) {
            launcher.launch(IntentSenderRequest.Builder(sender).build())
        }

        // Execution hooks: the trash batch goes through ONE createTrashRequest dialog.
        LaunchedEffect(Unit) {
            val resolver = contentResolver
            viewModel.executionHooks = object : AppViewModel.ExecutionHooks {
                override fun launchTrash(uris: List<Uri>): CompletableFuture<Int> {
                    val future = nextFuture()
                    try {
                        val request = android.provider.MediaStore.createTrashRequest(resolver, uris, true)
                        runOnUiThread { launchIntentSender(trashLauncher, request.intentSender) }
                    } catch (e: Exception) {
                        com.example.phonesteward.logic.obs.Diagnostics.error("exec.launch_trash", e)
                        future.complete(Activity.RESULT_CANCELED)
                    }
                    return future
                }

                override fun launchUninstall(packageName: String): CompletableFuture<Int> {
                    val future = nextFuture()
                    val intent = Permissions.uninstallIntent(packageName)
                    runOnUiThread {
                        try {
                            uninstallLauncher.launch(intent)
                        } catch (e: Exception) {
                            com.example.phonesteward.logic.obs.Diagnostics.error("exec.launch_uninstall", e)
                            future.complete(Activity.RESULT_CANCELED)
                        }
                    }
                    return future
                }

                // Fix 01-6 + fix 02-5: marshalled onto the main thread by
                // CacheClearFlow; the resume observer registers before the
                // launch, and a launch failure completes as `failed`.
                override fun beginCacheClear(packageName: String): CompletableFuture<com.example.phonesteward.logic.exec.CacheClearReading> =
                    cacheClearFlow.begin(packageName)

                // A6 (N6): ONE clear-temporary-files request — one Android
                // confirmation that clears EVERY app's cache. The before
                // readings are taken before the dialog; the after readings
                // when it answers.
                override fun beginAllCaches(
                    item: com.example.phonesteward.logic.exec.ProposalExecutionPlanner.AllCachesPlan,
                ): CompletableFuture<com.example.phonesteward.logic.exec.AllCachesOutcome.Verdict> {
                    val future = nextFuture()
                    val beforeBox = arrayOfNulls<Long?>(1)
                    // the before/after readings cover ALL
                    // installed packages, so they run off the main thread.
                    Thread {
                        beforeBox[0] = readAllCacheBytes()
                        runOnUiThread {
                            try {
                                allCachesLauncher.launch(
                                    Intent(android.os.storage.StorageManager.ACTION_CLEAR_APP_CACHE),
                                )
                            } catch (e: Exception) {
                                com.example.phonesteward.logic.obs.Diagnostics.error("exec.launch_all_caches", e)
                                future.complete(android.app.Activity.RESULT_FIRST_USER)
                            }
                        }
                    }.start()
                    return future.thenApplyAsync { code ->
                        val after = readAllCacheBytes()
                        com.example.phonesteward.logic.exec.AllCachesOutcome.decide(
                            code,
                            com.example.phonesteward.logic.exec.AllCachesBytes.freed(beforeBox[0], after),
                        )
                    }
                }
            }

            // Self-test dialogs: the same mechanism, its own launcher.
            SelftestDialogs.register(object : SelftestDialogs.Launcher {
                override fun launch(intentSender: android.content.IntentSender): CompletableFuture<Int> {
                    val future = nextFuture()
                    runOnUiThread { launchIntentSender(selftestLauncher, intentSender) }
                    return future
                }
            })
        }

        // The scaffold launchers complete the futures queue in launch order.
        // (Wired here because rememberLauncherForActivityResult must live in composition.)

        // --- lifecycle -----------------------------------------------------
        LaunchedEffect(state.paired) {
            if (state.paired) viewModel.start()
        }

        val updateState by updateVm.state.collectAsState()
        // F1: look for a newer release whenever the owner opens Status.
        LaunchedEffect(state.paired, bottomTab) {
            if (state.paired && bottomTab == StewardTab.Status.ordinal) updateVm.check()
            // A11.4: Chat coming (back) to the foreground re-reads the model and its context.
            if (state.paired && bottomTab == StewardTab.Chat.ordinal) viewModel.onChatForeground()
        }

        StewardApp(
            state = state,
            update = updateState,
            tab = StewardTab.entries[bottomTab],
            onTab = {
                // Leaving Status closes the trash screen, so coming back lands on Status.
                if (it != StewardTab.Status) viewModel.closeTrash()
                bottomTab = it.ordinal
            },
            space = if (state.paired && bottomTab == StewardTab.Status.ordinal) readSpace() else SpaceRow(0, 0),
            lastWorkerRunMs = viewModel.lastWorkerRunMs,
            // Read only while Status is showing, as before the tabs were extracted.
            onboarding = if (state.paired && bottomTab == StewardTab.Status.ordinal) {
                state.permissions ?: permissionSnapshot(state.rules?.photoWindow?.autoMode == true)
            } else {
                Onboarding.Snapshot(false, false, false, false, false, false)
            },
            statusActions = StatusActions(
                onOpenBattery = { runOnUiThread {
                    PowerSettings.openBatterySettings(
                        this@MainActivity,
                        launcher = { startActivity(it) },
                        onFailed = { e ->
                            com.example.phonesteward.logic.obs.Diagnostics.error("status.battery_button", e)
                        },
                    )
                } },
                onOpenUsageAccess = { runOnUiThread {
                    try {
                        startActivity(Permissions.usageAccessIntent())
                    } catch (e: Exception) {
                        com.example.phonesteward.logic.obs.Diagnostics.error("status.usage_access_button", e)
                    }
                } },
                onOpenAllFiles = { runOnUiThread {
                    try {
                        startActivity(Permissions.allFilesIntent(packageName))
                    } catch (e: Exception) {
                        com.example.phonesteward.logic.obs.Diagnostics.error("status.all_files_button", e)
                    }
                } },
                onRequestMediaRead = { runOnUiThread {
                    mediaReadLauncher.launch(Permissions.mediaPermissions())
                } },
                onOpenManageMedia = { runOnUiThread {
                    try {
                        startActivity(Permissions.manageMediaIntent(packageName))
                    } catch (e: Exception) {
                        com.example.phonesteward.logic.obs.Diagnostics.error("status.manage_media_button", e)
                    }
                } },
                onUnpair = { viewModel.unpairLocal() },
                onRefresh = { viewModel.refresh() },
                onUpdate = { updateVm.update() },
                onCheckUpdate = { updateVm.check() },
                onEmptyTrash = { viewModel.emptyTrash() },
                onDismissTrashResult = { viewModel.dismissTrashResult() },
                onOpenTrash = { viewModel.openTrash() },
                onCloseTrash = { viewModel.closeTrash() },
                onRestoreItem = { batch, item -> viewModel.restoreItem(batch, item) },
                onRestoreBatch = { batch -> viewModel.restoreBatch(batch) },
                onDismissTrashScreenResult = { viewModel.dismissTrashScreenResult() },
                shizuku = com.example.phonesteward.ui.ShizukuActions(
                    onGet = { runOnUiThread { launchOrNote(com.example.phonesteward.android.AppGraph.get(this@MainActivity).androidShizuku.getIntent(), "status.shizuku_get") } },
                    onOpen = { runOnUiThread {
                        com.example.phonesteward.android.AppGraph.get(this@MainActivity).androidShizuku.openIntent()
                            ?.let { launchOrNote(it, "status.shizuku_open") }
                    } },
                    onAllow = { viewModel.requestShizukuPermission() },
                    onCheckAgain = { viewModel.refreshShizuku() },
                ),
            ),
            onConfirm = { viewModel.answerConfirm(it) },
            agentActions = com.example.phonesteward.ui.ChatAgentActions(
                onLoadModels = { viewModel.loadModels() },
                onChooseModel = { viewModel.chooseModel(it) },
                onDismissInfo = { viewModel.dismissChatInfo() },
            ),
            onCompactNow = { viewModel.compactNow() },
            onStartFresh = { viewModel.startFresh() },
            phoneUse = com.example.phonesteward.ui.ChatPhoneUseActions(
                onAllow = { viewModel.allowPhoneUse(it) },
                onDecline = { viewModel.declinePhoneUse(it) },
                onOpenStatus = { bottomTab = StewardTab.Status.ordinal },
                onStopSession = { com.example.phonesteward.screen.LiveSessionService.stop(applicationContext) },
            ),
            onPair = { code, deviceName, baseUrl -> viewModel.pair(baseUrl, code, deviceName) },
            onSend = { viewModel.send(it) },
            onChatVerbosity = { viewModel.setChatVerbosity(it) },
            onDismissChatNotice = { viewModel.dismissChatNotice() },
            onDecide = { proposal, approve, ids -> viewModel.decide(proposal, approve, ids) },
            onDismissResult = { viewModel.clearResult() },
            onSaveRules = { viewModel.saveRules(it) },
        )
    }

    private fun launchOrNote(intent: Intent, site: String) {
        try {
            startActivity(intent)
        } catch (e: Exception) {
            com.example.phonesteward.logic.obs.Diagnostics.error(site, e)
        }
    }

    private fun readSpace(): SpaceRow = try {
        StatFsSpaceSource().load()
    } catch (e: Exception) {
        com.example.phonesteward.logic.obs.Diagnostics.error("status.read_space", e)
        SpaceRow(0, 0)
    }

    /**
     * The cache-clear flow (fix 02-5): everything inside begin() runs on the
     * main thread; the resume observer completes once after a pause→resume.
     */
    private val cacheClearFlow by lazy {
        com.example.phonesteward.logic.exec.CacheClearFlow(
            readCacheBytes = { readCacheBytes(it) },
            mainThread = { runOnUiThread(it) },
            launchAppInfo = { packageName ->
                startActivity(
                    Intent(
                        android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:$packageName"),
                    ),
                )
            },
            observeResumeOnce = { action ->
                var cameBack = false
                lateinit var observer: androidx.lifecycle.LifecycleEventObserver
                observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
                    when (event) {
                        Lifecycle.Event.ON_PAUSE -> cameBack = true
                        Lifecycle.Event.ON_RESUME -> if (cameBack) {
                            lifecycle.removeObserver(observer)
                            action.run()
                        }
                        else -> Unit
                    }
                }
                lifecycle.addObserver(observer)
            },
        )
    }

    /** The app's cacheBytes via StorageStatsManager; null when unreadable (fix 01-6). */
    /**
     * The summed cacheBytes of EVERY installed package (correction 01-3),
     * ALL-OR-NULL (correction 02-1): any package read that fails makes the
     * pass unreadable — one O5 report records the failed reads (stable site
     * `exec.clear_all_caches.measure`) and the pass answers null, so the
     * app shows "unknown" rather than a partial total.
     */
    private fun readAllCacheBytes(): Long? {
        return try {
            val stats = getSystemService(android.app.usage.StorageStatsManager::class.java) ?: return null
        var failed = 0
        val readings = packageManager.getInstalledPackages(0).map { pkg ->
            try {
                stats.queryStatsForPackage(
                    android.os.storage.StorageManager.UUID_DEFAULT,
                    pkg.packageName,
                    android.os.Process.myUserHandle(),
                ).cacheBytes
            } catch (_: Exception) {
                failed += 1
                null // this package's read failed: the whole pass is unreadable
            }
        }
        if (failed > 0) {
            com.example.phonesteward.logic.obs.Diagnostics.error(
                "exec.clear_all_caches.measure",
                IllegalStateException("$failed of ${readings.size} package readings failed"),
            )
            return null
        }
        com.example.phonesteward.logic.exec.AllCachesBytes.total(readings)
        } catch (e: Exception) {
            com.example.phonesteward.logic.obs.Diagnostics.error("exec.clear_all_caches.measure", e)
            null
        }
    }

    private fun readCacheBytes(packageName: String): Long? = try {
        val stats = getSystemService(android.app.usage.StorageStatsManager::class.java) ?: return null
        stats.queryStatsForPackage(
            android.os.storage.StorageManager.UUID_DEFAULT,
            packageName,
            android.os.Process.myUserHandle(),
        ).cacheBytes
    } catch (e: Exception) {
        com.example.phonesteward.logic.obs.Diagnostics.error("status.cache_bytes_read", e)
        null
    }

    private fun permissionSnapshot(autoModeOn: Boolean): Onboarding.Snapshot = Onboarding.Snapshot(
        batteryUnrestricted = PowerSettings.isIgnoringBatteryOptimizations(this),
        usageAccess = Permissions.usageAccessGranted(this),
        allFiles = Permissions.allFilesGranted(),
        mediaRead = Permissions.mediaReadGranted(this),
        manageMedia = Permissions.canManageMedia(this),
        autoModeOn = autoModeOn,
    )

    // Test seam (correction 01-9): the resume test puts the Status tab up
    // directly, because Robolectric cannot dispatch Compose clicks here.
    internal var bottomTab by mutableIntStateOf(0)
}
