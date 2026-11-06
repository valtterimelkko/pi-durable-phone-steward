package com.example.phonesteward.android

import android.content.Context
import java.time.ZoneId
import com.example.phonesteward.logic.exec.AppUsageExecutor
import com.example.phonesteward.logic.exec.MediaIndexExecutor
import com.example.phonesteward.logic.exec.RuleBatchExecutor
import com.example.phonesteward.logic.exec.StorageOverviewExecutor
import com.example.phonesteward.logic.jobs.JobExecutor
import com.example.phonesteward.net.RulesResponse

/**
 * The executor registry: every job kind this app version can run. The
 * dispatcher answers UNKNOWN_KIND for anything not registered here. The
 * rules lambda is injected so the rule batch re-authorises through the
 * same client (protocol §3.3) without a graph cycle. The test images are
 * injected too: the registry is built while [AppGraph] is being constructed,
 * so it must never call AppGraph.get (that recursed until the stack
 * overflowed and the app crashed on every launch). One [TestImages] also
 * means the self-test creates items in the same guard it checks.
 */
object Executors {

    fun all(
        context: Context,
        testImages: TestImages,
        rulesApi: () -> RulesResponse,
        workerState: () -> com.example.phonesteward.net.DiagnosticsWorker = {
            com.example.phonesteward.net.DiagnosticsWorker("NONE")
        },
        eventLog: () -> List<com.example.phonesteward.logic.obs.EventEntry> = { emptyList() },
        shizuku: () -> com.example.phonesteward.logic.shizuku.ShizukuAccess,
    ): List<Pair<String, JobExecutor>> {
        val appContext = context.applicationContext
        val zone = ZoneId.systemDefault()
        return listOf(
            "storage_overview" to StorageOverviewExecutor(
                space = { StatFsSpaceSource().load() },
                apps = OverviewAppsAdapter(appContext),
                categories = CategoryBytesAdapter(appContext),
            ),
            "app_usage" to AppUsageExecutor(UsageStatsAdapter(appContext)),
            "media_index" to MediaIndexExecutor(
                media = MediaStoreIndexSource(appContext),
                rootFiles = RootFilesAdapter(),
                nowMillis = System::currentTimeMillis,
                zone = zone,
            ),
            "rule_batch" to RuleBatchExecutor(
                rules = rulesApi,
                lookup = MediaStoreLiveLookup(appContext),
                autoTrash = { items -> AndroidAutoTrash(appContext).trash(items) },
            ),
            // A10.1: list, find and du over the shared storage (all-files access).
            "files_query" to com.example.phonesteward.logic.files.FilesQueryExecutor(
                root = android.os.Environment.getExternalStorageDirectory(),
                accessGranted = { Permissions.allFilesGranted() },
                nowMillis = System::currentTimeMillis,
            ),
            // A10.2: read-only listings and force-stop through Shizuku; both answer SHIZUKU_NOT_READY without it.
            "shell_info" to com.example.phonesteward.logic.shizuku.ShellInfoExecutor(
                state = { shizuku().state() },
                shell = { args, timeoutMs -> shizuku().shell().run(args, timeoutMs) },
            ),
            "force_stop" to com.example.phonesteward.logic.shizuku.ForceStopExecutor(
                state = { shizuku().state() },
                shell = { args, timeoutMs -> shizuku().shell().run(args, timeoutMs) },
                isInstalled = { AndroidUninstallProbe.isInstalled(appContext, it) },
                neverTouch = AndroidNeverTouch.of(appContext),
            ),
            "phone_selftest" to com.example.phonesteward.logic.exec.SelftestJobExecutor(
                com.example.phonesteward.logic.selftest.SelftestRunner(
                    device = AndroidSelftestDevice(appContext, testImages),
                    guard = testImages.guard,
                    appPackageName = appContext.packageName,
                ),
            ),
            "phone_diagnostics" to com.example.phonesteward.logic.obs.PhoneDiagnosticsExecutor(
                appVersion = com.example.phonesteward.BuildConfig.VERSION_NAME,
                versionCode = com.example.phonesteward.BuildConfig.VERSION_CODE,
                permissions = {
                    com.example.phonesteward.net.DevicePermissions(
                        usageAccess = Permissions.usageAccessGranted(appContext),
                        allFiles = Permissions.allFilesGranted(),
                        mediaRead = Permissions.mediaReadGranted(appContext),
                        manageMedia = Permissions.canManageMedia(appContext),
                        batteryUnrestricted = PowerSettings.isIgnoringBatteryOptimizations(appContext),
                        shizuku = try { shizuku().state().wire } catch (_: Exception) { "absent" },
                    )
                },
                worker = workerState,
                outboxCounts = {
                    com.example.phonesteward.logic.obs.OutboxCounts.of(
                        CrashCapture.outboxDir(appContext),
                    )
                },
                log = eventLog,
            ),
        )
    }
}
