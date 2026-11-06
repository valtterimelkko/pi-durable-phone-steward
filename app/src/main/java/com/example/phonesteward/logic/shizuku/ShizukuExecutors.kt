package com.example.phonesteward.logic.shizuku

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import com.example.phonesteward.logic.exec.body
import com.example.phonesteward.logic.jobs.JobExecutor
import com.example.phonesteward.logic.jobs.JobOutcome
import com.example.phonesteward.net.Job

@Serializable
data class ShellPackagesData(val packages: List<PackageEntry>) {
    @Serializable
    data class PackageEntry(val packageName: String, val enabled: Boolean, val system: Boolean, val installer: String? = null)
}

@Serializable
data class ShellSizesData(val apps: List<AppSizeEntry>) {
    @Serializable
    data class AppSizeEntry(val packageName: String, val appBytes: Long, val dataBytes: Long, val cacheBytes: Long)
}

@Serializable
data class ForceStopData(val packageName: String, val stopped: Boolean)

private const val NOT_READY_MESSAGE =
    "Shizuku is not set up on the phone, so shell rights are not available. The owner can set it up on the Status tab."

private fun JsonObject.text(key: String): String? = (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString }?.content

/** `shell_info` (A10.2): read-only listings through Shizuku. `packages` or `app_sizes`. */
class ShellInfoExecutor(
    private val state: () -> ShizukuState,
    private val shell: ShellRunner,
) : JobExecutor {

    override fun execute(job: Job): JobOutcome {
        val op = job.params.text("op")
        if (op != "packages" && op != "app_sizes") return JobOutcome.Failed("BAD_PARAMS", "shell_info op must be packages or app_sizes")
        if (state() != ShizukuState.READY) return JobOutcome.Failed("SHIZUKU_NOT_READY", NOT_READY_MESSAGE)
        return when (op) {
            "packages" -> packages()
            else -> sizes()
        }
    }

    private fun packages(): JobOutcome {
        val installers = shell.run(ShellCommands.listInstallers())
        val disabled = shell.run(ShellCommands.listDisabled())
        val system = shell.run(ShellCommands.listSystem())
        listOf(installers, disabled, system).firstOrNull { !it.ok }?.let {
            return JobOutcome.Failed("SHELL_FAILED", failureText(it))
        }
        val rows = ShellInfo.parsePackages(installers.stdout, disabled.stdout, system.stdout)
        return JobOutcome.Ok(body(ShellPackagesData(rows.map { ShellPackagesData.PackageEntry(it.packageName, it.enabled, it.system, it.installer) })))
    }

    private fun sizes(): JobOutcome {
        val out = shell.run(ShellCommands.diskstats(), 30_000)
        if (!out.ok) return JobOutcome.Failed("SHELL_FAILED", failureText(out))
        val rows = ShellInfo.parseDiskstats(out.stdout)
            ?: return JobOutcome.Failed("SHELL_PARSE", "the phone's size listing could not be read")
        return JobOutcome.Ok(body(ShellSizesData(rows.map { ShellSizesData.AppSizeEntry(it.packageName, it.appBytes, it.dataBytes, it.cacheBytes) })))
    }
}

internal fun failureText(r: ShellResult): String =
    if (r.timedOut) "the command took too long" else "the command ended with code ${r.exitCode}"

/**
 * `force_stop` (A10.2): `am force-stop <package>`. Not destructive, so it runs without a proposal ; refused for
 * the steward itself and the never-touch list.
 */
class ForceStopExecutor(
    private val state: () -> ShizukuState,
    private val shell: ShellRunner,
    private val isInstalled: (String) -> Boolean,
    private val neverTouch: NeverTouch,
) : JobExecutor {

    override fun execute(job: Job): JobOutcome {
        val pkg = job.params.text("packageName")?.takeIf { PackageNames.valid(it) }
            ?: return JobOutcome.Failed("BAD_PACKAGE", "force_stop needs a valid packageName")
        if (state() != ShizukuState.READY) return JobOutcome.Failed("SHIZUKU_NOT_READY", NOT_READY_MESSAGE)
        if (neverTouch.isProtected(pkg)) return JobOutcome.Failed("PROTECTED_PACKAGE", "that app is never stopped by the steward")
        if (!isInstalled(pkg)) return JobOutcome.Failed("NOT_INSTALLED", "that app is not installed")
        val r = shell.run(ShellCommands.forceStop(pkg))
        return if (r.ok) JobOutcome.Ok(body(ForceStopData(pkg, true))) else JobOutcome.Failed("SHELL_FAILED", failureText(r))
    }
}
