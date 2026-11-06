package com.example.phonesteward.android

import android.content.Context
import android.content.SharedPreferences
import androidx.annotation.VisibleForTesting
import androidx.work.WorkManager
import java.io.File
import com.example.phonesteward.BuildConfig
import com.example.phonesteward.logic.IdentityStore
import com.example.phonesteward.logic.PairingFlow
import com.example.phonesteward.logic.jobs.DeviceStatusReporter
import com.example.phonesteward.logic.jobs.JobDispatcher
import com.example.phonesteward.logic.jobs.JobPump
import com.example.phonesteward.logic.jobs.JobTracker
import com.example.phonesteward.net.DevicePermissions
import com.example.phonesteward.net.PairedClients
import com.example.phonesteward.net.StewardApi

/**
 * One app-wide graph. Every client derives from the paired identity
 * (base URL + token as one value, fix 01-1): pairing, revocation or a URL
 * change rebinds the API client and the SSE stream together, so a token can
 * never be sent to a server it was not issued by.
 */
class AppGraph private constructor(context: Context) {

    private val appContext = context.applicationContext
    private val prefs: SharedPreferences =
        appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    val identityStore: IdentityStore = identityStoreFactory(appContext)

    /** Every HTTP client, bound to the paired identity. */
    val paired = PairedClients(
        identityProvider = { identityStore.read() },
        onIdentityChanged = { },
    )

    val testImages: TestImages = TestImages(appContext).also { it.restore() }

    val pairing: PairingFlow = PairingFlow(
        store = identityStore,
        // Pairing targets the NEW server with a one-off, unauthenticated
        // client (fix 01-8): no stored token can attach, whatever it is.
        pairCall = { baseUrl, code, deviceName ->
            StewardApi(baseUrl).use { it.pair(code, deviceName) }
        },
        onIdentityChanged = { paired.rebind() },
    )

    /** The server base URL: the paired identity wins once paired. */
    fun baseUrl(): String =
        identityStore.read()?.baseUrl
            ?: prefs.getString(PREF_BASE_URL, DEFAULT_BASE_URL)
            ?: DEFAULT_BASE_URL

    /** Pre-pairing server choice; once paired, the identity's URL wins. */
    fun setBaseUrl(url: String) {
        prefs.edit().putString(PREF_BASE_URL, url.trim()).apply()
        paired.rebind()
    }

    val jobTracker: JobTracker = JobTracker(PrefsDoneJobStore(appContext))

    /** Registered executors; unknown kinds answer UNKNOWN_KIND. */
    val jobDispatcher: JobDispatcher = JobDispatcher(
        Executors.all(
            appContext,
            testImages,
            rulesApi = { paired.client().getRules() },
            workerState = { WorkState.periodicState(appContext, SyncWorker.WORK_NAME, lastWorkerRunMs()) },
            eventLog = { com.example.phonesteward.logic.obs.Diagnostics.logEntries() },
            shizuku = { shizuku },
        ).toMap(),
    )

    /** Derived per call so it always follows the paired identity. */
    val jobPump: JobPump
        get() = JobPump(
            api = paired.client(),
            tracker = jobTracker,
            dispatcher = jobDispatcher,
            appVersion = BuildConfig.VERSION_NAME,
        )

    private val defaultTrash: com.example.phonesteward.logic.files.StewardTrash by lazy {
        com.example.phonesteward.logic.files.StewardTrash(
            android.os.Environment.getExternalStorageDirectory(),
            java.time.ZoneId.systemDefault(),
            System::currentTimeMillis,
        )
    }

    @Volatile
    private var trashOverride: com.example.phonesteward.logic.files.StewardTrash? = null

    /**
     * A10.1: the Steward trash on the primary shared storage; one instance for the view model, the worker and the device
     * status. Tests point it at a temporary folder.
     */
    var stewardTrash: com.example.phonesteward.logic.files.StewardTrash
        get() = trashOverride ?: defaultTrash
        @VisibleForTesting set(value) { trashOverride = value }

    private val defaultRestoreOutbox: com.example.phonesteward.logic.files.RestoredOutbox by lazy {
        com.example.phonesteward.logic.files.RestoredOutbox(File(appContext.filesDir, "restored-outbox"))
    }

    @Volatile
    private var restoreOutboxOverride: com.example.phonesteward.logic.files.RestoredOutbox? = null

    /** A11.3: restore reports waiting for the next sync. Tests point it at a temporary folder. */
    var restoreOutbox: com.example.phonesteward.logic.files.RestoredOutbox
        get() = restoreOutboxOverride ?: defaultRestoreOutbox
        @VisibleForTesting set(value) { restoreOutboxOverride = value }

    /** A11.3: sends the waiting restore reports through the paired client; the worker and a foreground refresh flush it. */
    fun restoreReporter(): com.example.phonesteward.logic.files.RestoreReporter =
        com.example.phonesteward.logic.files.RestoreReporter(
            restoreOutbox,
            com.example.phonesteward.logic.files.ApiRestoreSender { paired.client() },
        )

    private val defaultShizuku: AndroidShizuku by lazy { AndroidShizuku(appContext) }

    @Volatile
    private var shizukuOverride: com.example.phonesteward.logic.shizuku.ShizukuAccess? = null

    /** A10.2: the app's Shizuku. Tests replace it with a fake. */
    var shizuku: com.example.phonesteward.logic.shizuku.ShizukuAccess
        get() = shizukuOverride ?: defaultShizuku
        @VisibleForTesting set(value) { shizukuOverride = value }

    /** The real one, for the intents that open or get the Shizuku app. */
    val androidShizuku: AndroidShizuku get() = defaultShizuku

    private val defaultInstalledApps: com.example.phonesteward.screen.AndroidInstalledApps by lazy {
        com.example.phonesteward.screen.AndroidInstalledApps(appContext)
    }

    @Volatile
    private var installedAppsOverride: com.example.phonesteward.screen.InstalledApps? = null

    /** A14.2: the phone's launchable apps for the Rules picker. Tests replace it with a fake. */
    var installedApps: com.example.phonesteward.screen.InstalledApps
        get() = installedAppsOverride ?: defaultInstalledApps
        @VisibleForTesting set(value) { installedAppsOverride = value }

    private val defaultClipboard: com.example.phonesteward.ui.ClipboardPort by lazy {
        com.example.phonesteward.ui.AndroidClipboard(appContext)
    }

    @Volatile
    private var clipboardOverride: com.example.phonesteward.ui.ClipboardPort? = null

    /**
     * A15.7: the phone's clipboard behind the Copy buttons. An end-to-end test replaces it with a
     * recorder: since API 29 only the focused app may read the clipboard, and that is not the test.
     */
    var clipboard: com.example.phonesteward.ui.ClipboardPort
        get() = clipboardOverride ?: defaultClipboard
        @VisibleForTesting set(value) { clipboardOverride = value }

    /** N29: one gate for every device-status post (pairing, resume, the worker), so a burst posts once. */
    val statusPostGate = com.example.phonesteward.logic.StatusPostCoalescer()

    val deviceStatusReporter: DeviceStatusReporter
        get() = DeviceStatusReporter(
            gate = statusPostGate,
            stewardTrash = {
                try {
                    stewardTrash.status()
                } catch (_: Exception) {
                    com.example.phonesteward.net.StewardTrashStatus()
                }
            },
            api = paired.client(),
            appVersion = BuildConfig.VERSION_NAME,
            permissions = {
                DevicePermissions(
                    usageAccess = Permissions.usageAccessGranted(appContext),
                    allFiles = Permissions.allFilesGranted(),
                    mediaRead = Permissions.mediaReadGranted(appContext),
                    manageMedia = Permissions.canManageMedia(appContext),
                    batteryUnrestricted = PowerSettings.isIgnoringBatteryOptimizations(appContext),
                    shizuku = try { shizuku.state().wire } catch (_: Exception) { "absent" },
                )
            },
            lastWorkerRunMs = { lastWorkerRunMs() },
        )

    fun lastWorkerRunMs(): Long = prefs.getLong(PREF_LAST_WORKER_RUN, 0L)

    fun recordWorkerRun() {
        prefs.edit().putLong(PREF_LAST_WORKER_RUN, System.currentTimeMillis()).apply()
    }

    /** Called once per app open: schedule the periodic worker. */
    fun ensureWorkerScheduled() = SyncWorker.schedule(appContext)

    fun cancelWorker() = WorkManager.getInstance(appContext).cancelUniqueWork("phone-steward-sync")

    companion object {
        const val DEFAULT_BASE_URL = "https://steward.example.com"
        internal const val PREFS_NAME = "phone_steward_app"
        internal const val PREF_BASE_URL = "base_url"
        private const val PREF_LAST_WORKER_RUN = "last_worker_run_ms"

        @Volatile
        private var instance: AppGraph? = null

        /**
         * Test seam: AndroidKeyStore is unavailable under Robolectric, so the
         * launch tests swap in an in-memory store to start the app paired.
         */
        @VisibleForTesting
        internal var identityStoreFactory: (Context) -> IdentityStore = { KeystoreIdentityStore(it) }

        /** Test seam: drops the process-wide graph so each test starts cold. */
        @VisibleForTesting
        internal fun resetForTest() {
            synchronized(this) { instance = null }
        }

        fun get(context: Context): AppGraph =
            instance ?: synchronized(this) {
                instance ?: AppGraph(context.applicationContext).also { instance = it }
            }
    }
}
