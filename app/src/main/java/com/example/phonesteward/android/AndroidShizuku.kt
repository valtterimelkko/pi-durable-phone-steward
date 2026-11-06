package com.example.phonesteward.android

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import rikka.shizuku.Shizuku
import com.example.phonesteward.logic.shizuku.ShellProcess
import com.example.phonesteward.logic.shizuku.ShellResult
import com.example.phonesteward.logic.shizuku.ShellRunner
import com.example.phonesteward.logic.shizuku.ShizukuAccess
import com.example.phonesteward.logic.shizuku.ShizukuState

/**
 * The real Shizuku (A10.2): `dev.rikka.shizuku:api` 13.1.5, MIT licence (about 31 KB with the provider). The owner starts
 * Shizuku through wireless debugging once after each restart of the phone and allows the steward in Shizuku's own dialog.
 *
 * It cannot run on Firebase Test Lab (no wireless debugging), so everything that decides anything is in pure classes tested
 * on the JVM; this adapter is thin and is checked on a real phone. On a phone without Shizuku every call answers
 * [ShizukuState.ABSENT] or [ShizukuState.NOT_RUNNING] and no shell is ever started.
 */
class AndroidShizuku(context: Context) : ShizukuAccess {

    private val appContext = context.applicationContext

    private fun installed(): Boolean = try {
        appContext.packageManager.getPackageInfo(PACKAGE, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    private fun binderAlive(): Boolean = try {
        Shizuku.pingBinder()
    } catch (_: Throwable) {
        false
    }

    private fun granted(): Boolean = try {
        !Shizuku.isPreV11() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (_: Throwable) {
        false
    }

    override fun state(): ShizukuState {
        val alive = binderAlive()
        return ShizukuState.of(installed = installed(), binderAlive = alive, permissionGranted = alive && granted())
    }

    override fun requestPermission() {
        try {
            if (binderAlive() && !Shizuku.isPreV11()) Shizuku.requestPermission(REQUEST_CODE)
        } catch (e: Throwable) {
            com.example.phonesteward.logic.obs.Diagnostics.error("shizuku.request_permission", RuntimeException(e))
        }
    }

    override fun addChangeListener(onChange: () -> Unit): AutoCloseable {
        val received = Shizuku.OnBinderReceivedListener { onChange() }
        val dead = Shizuku.OnBinderDeadListener { onChange() }
        val permission = Shizuku.OnRequestPermissionResultListener { _, _ -> onChange() }
        return try {
            Shizuku.addBinderReceivedListener(received)
            Shizuku.addBinderDeadListener(dead)
            Shizuku.addRequestPermissionResultListener(permission)
            AutoCloseable {
                Shizuku.removeBinderReceivedListener(received)
                Shizuku.removeBinderDeadListener(dead)
                Shizuku.removeRequestPermissionResultListener(permission)
            }
        } catch (_: Throwable) {
            AutoCloseable { }
        }
    }

    override fun shell(): ShellRunner = ShellRunner { args, timeoutMs -> runShell(args, timeoutMs) }

    /**
     * `Shizuku.newProcess` has been private since API 13.1, so it is called by reflection (the app is not minified).
     * The command is an argument array: no shell line, nothing to inject into.
     */
    private fun runShell(args: List<String>, timeoutMs: Long): ShellResult {
        if (state() != ShizukuState.READY) return ShellResult(-1, "", "Shizuku is not ready")
        return try {
            val method = Shizuku::class.java.getDeclaredMethod(
                "newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java,
            ).apply { isAccessible = true }
            val process = method.invoke(null, args.toTypedArray(), null, null) as Process
            // Never waitFor(long, TimeUnit) on a Shizuku process: see ShellProcess.
            ShellProcess.run(process, timeoutMs)
        } catch (e: Throwable) {
            com.example.phonesteward.logic.obs.Diagnostics.error("shizuku.shell", RuntimeException(e))
            ShellResult(-1, "", ShellProcess.describe(e))
        }
    }

    /** The intent that opens the Shizuku app, or null when it is not installed. */
    fun openIntent(): Intent? = appContext.packageManager.getLaunchIntentForPackage(PACKAGE)

    /** Where to get Shizuku when it is not installed (its Google Play page). */
    fun getIntent(): Intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$PACKAGE"))

    companion object {
        const val PACKAGE = "moe.shizuku.privileged.api"
        private const val REQUEST_CODE = 7001
    }
}
