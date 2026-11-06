package com.example.phonesteward.android

import android.content.Context
import android.os.Build
import java.io.File
import com.example.phonesteward.BuildConfig
import com.example.phonesteward.logic.CrashDevice
import com.example.phonesteward.logic.CrashOutbox
import com.example.phonesteward.logic.CrashReports
import com.example.phonesteward.logic.CrashUploader
import com.example.phonesteward.net.CrashClient
import com.example.phonesteward.net.RequestIds

/**
 * Crash self-reporting (A5). Installed first thing in the Application, so
 * it also sees a crash in start-up code: the handler stores the report, then passes the
 * crash on to Android's own handler, so the "keeps stopping" dialog and the process exit
 * stay exactly as before. The next start uploads what was stored.
 */
object CrashCapture {

    fun outboxDir(context: Context): File = File(context.filesDir, "crash-outbox")

    /** The capturing handler; [previous] receives every crash after it is stored. */
    fun handler(context: Context, previous: Thread.UncaughtExceptionHandler?): Thread.UncaughtExceptionHandler =
        Handler(context.applicationContext ?: context, previous)

    private class Handler(
        private val context: Context,
        val previous: Thread.UncaughtExceptionHandler?,
    ) : Thread.UncaughtExceptionHandler {
        override fun uncaughtException(thread: Thread, error: Throwable) {
            try {
                CrashOutbox(outboxDir(context)).save(
                    CrashReports.from(
                        threadName = thread.name,
                        error = error,
                        appVersion = BuildConfig.VERSION_NAME,
                        nowMs = System.currentTimeMillis(),
                        device = CrashDevice(Build.MODEL ?: "unknown", Build.VERSION.SDK_INT),
                        requestId = RequestIds.new("crash"),
                    ),
                )
            } catch (_: Throwable) {
                // Storing is best effort; the crash itself must still reach Android.
            }
            previous?.uncaughtException(thread, error)
        }
    }

    /** Called from Application.attachBaseContext; replaces an earlier capture, never nests. */
    fun install(context: Context) {
        val current = Thread.getDefaultUncaughtExceptionHandler()
        val previous = (current as? Handler)?.previous ?: current
        Thread.setDefaultUncaughtExceptionHandler(handler(context, previous))
    }

    /** Synchronous upload of everything stored; makes no call when nothing is stored. */
    fun uploadPending(context: Context, baseUrl: String, token: String?) {
        val outbox = CrashOutbox(outboxDir(context))
        if (outbox.pending().isEmpty()) return
        val client = CrashClient(baseUrl, token)
        CrashUploader(outbox) { client.send(it) }.uploadAll()
    }

    /**
     * Called from Application.onCreate: uploads on a background thread. It reads the server
     * address the way [AppGraph] does but never builds the graph, which may be what crashed.
     */
    fun uploadOnStart(context: Context) {
        val appContext = context.applicationContext ?: context
        if (CrashOutbox(outboxDir(appContext)).pending().isEmpty()) return
        Thread({
            try {
                val identity = try {
                    KeystoreIdentityStore(appContext).read()
                } catch (_: Throwable) {
                    null
                }
                val baseUrl = identity?.baseUrl
                    ?: appContext.getSharedPreferences(AppGraph.PREFS_NAME, Context.MODE_PRIVATE)
                        .getString(AppGraph.PREF_BASE_URL, null)
                    ?: AppGraph.DEFAULT_BASE_URL
                uploadPending(appContext, baseUrl, identity?.token)
            } catch (_: Throwable) {
                // The next start tries again.
            }
        }, "crash-upload").apply { isDaemon = true }.start()
    }
}
