package com.example.phonesteward

import android.app.Application
import android.content.Context
import com.example.phonesteward.android.CrashCapture
import com.example.phonesteward.logic.obs.AndroidDiagnostics
import com.example.phonesteward.logic.obs.Diagnostics

/**
 * The app process. Its only job is crash self-reporting (A5): capture is installed before
 * the manifest's start-up providers run, and stored crashes are uploaded on every start.
 */
class PhoneStewardApplication : Application() {

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        CrashCapture.install(this)
    }

    override fun onCreate() {
        super.onCreate()
        // O5/O6: the observability sink is live before anything else can hide
        // an exception. Errors share the A5 crash outbox; events feed the
        // phone_diagnostics job.
        Diagnostics.install(
            AndroidDiagnostics(
                filesDir = getFilesDir(),
                outboxDir = CrashCapture.outboxDir(this),
                appVersion = BuildConfig.VERSION_NAME,
                deviceModel = android.os.Build.MODEL ?: "unknown",
                sdkLevel = android.os.Build.VERSION.SDK_INT,
            ),
        )
        Diagnostics.event("info", "app_start", "v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        CrashCapture.uploadOnStart(this)
    }
}
