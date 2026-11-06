package com.example.phonesteward.logic.shizuku

/** What a shell command printed and how it ended. */
data class ShellResult(val exitCode: Int, val stdout: String, val stderr: String, val timedOut: Boolean = false) {
    val ok: Boolean get() = exitCode == 0 && !timedOut
}

/**
 * Runs one fixed command with shell rights (Shizuku). The real one is `AndroidShizuku`; tests use fakes, because
 * Shizuku needs a phone with wireless debugging. Arguments are an array, never a shell line.
 */
fun interface ShellRunner {
    fun run(args: List<String>, timeoutMs: Long): ShellResult

    fun run(args: List<String>): ShellResult = run(args, 20_000)
}

/** Android package names as the server validates them (A10.2): dotted, each part starting with a letter. */
object PackageNames {
    private val PATTERN = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")

    fun valid(name: String): Boolean = name.length <= 255 && PATTERN.matches(name)
}

/**
 * The only commands the app ever runs with shell rights (A10.2). The model never sends shell text: each command is a
 * fixed template, executed as an argument array (no shell line to inject into), and every package argument is checked
 * with [PackageNames.valid] first. Absolute paths, so no PATH is trusted.
 */
object ShellCommands {
    private const val BIN = "/system/bin"

    private fun checked(pkg: String): String {
        require(PackageNames.valid(pkg)) { "not a package name" }
        return pkg
    }

    fun forceStop(pkg: String) = listOf("$BIN/am", "force-stop", checked(pkg))
    fun disable(pkg: String) = listOf("$BIN/pm", "disable-user", "--user", "0", checked(pkg))
    fun enable(pkg: String) = listOf("$BIN/pm", "enable", "--user", "0", checked(pkg))

    /** One request that clears every app's cache, with no Android dialog. */
    fun trimCaches() = listOf("$BIN/pm", "trim-caches", "999G")

    fun listInstallers() = listOf("$BIN/pm", "list", "packages", "-i")
    fun listDisabled() = listOf("$BIN/pm", "list", "packages", "-d")
    fun listSystem() = listOf("$BIN/pm", "list", "packages", "-s")
    fun diskstats() = listOf("$BIN/dumpsys", "diskstats")

    /** A12: allow restricted settings for the steward's own package (the caller passes its own `packageName`). */
    fun allowRestrictedSettings(ownPackage: String) =
        listOf("$BIN/appops", "set", checked(ownPackage), "ACCESS_RESTRICTED_SETTINGS", "allow")

    /** A12: the read-back of the same setting. */
    fun readRestrictedSettings(ownPackage: String) =
        listOf("$BIN/appops", "get", checked(ownPackage), "ACCESS_RESTRICTED_SETTINGS")
}

/**
 * Packages the steward never stops or disables: the system's, the owner's essentials, the steward itself. The same list
 * as the server's (`server/src/shizuku.ts`), plus what only the phone knows: the keyboard in use and the launcher.
 * Compared in lower case.
 */
class NeverTouch(
    private val keyboardInUse: () -> String?,
    private val launcher: () -> String?,
) {
    fun isProtected(pkg: String): Boolean {
        val p = pkg.lowercase()
        if (p in EXACT || PREFIXES.any { p.startsWith(it) }) return true
        return listOf(keyboardInUse(), launcher()).any { it != null && it.lowercase() == p }
    }

    companion object {
        private val EXACT = setOf(
            "com.example.phonesteward", "android", "moe.shizuku.privileged.api", "com.android.vending",
            "com.google.android.gsf", "com.android.shell", "com.android.phone", "com.android.settings",
            "com.android.packageinstaller", "com.google.android.packageinstaller", "com.android.permissioncontroller",
            "com.google.android.permissioncontroller", "com.android.bluetooth", "com.android.nfc", "com.android.keychain",
            "com.android.documentsui", "com.google.android.documentsui", "com.android.externalstorage",
            "com.android.webview", "com.google.android.webview", "com.android.inputdevices", "com.android.location.fused",
            "com.android.contacts", "com.google.android.contacts", "com.android.dialer", "com.google.android.dialer",
            "com.android.messaging", "com.android.mms", "com.google.android.apps.messaging", "com.android.launcher3",
            "com.google.android.apps.nexuslauncher", "net.oneplus.launcher", "com.oneplus.launcher",
            "com.touchtype.swiftkey",
        )
        private val PREFIXES = listOf(
            "com.android.systemui", "com.android.providers", "com.android.server", "com.android.inputmethod",
            "com.google.android.gms", "com.google.android.inputmethod",
            // Vendor quirk: OxygenOS is built on ColorOS, and the modem and data services are vendor packages; they stay on the never-touch list.
            "com.oplus", "com.coloros", "com.qualcomm", "com.qti",
        )
    }
}

data class PackageRow(val packageName: String, val enabled: Boolean, val system: Boolean, val installer: String?)
data class AppSizeRow(val packageName: String, val appBytes: Long, val dataBytes: Long, val cacheBytes: Long)

/** Parsers for the read-only `shell_info` listings. Pure. */
object ShellInfo {

    private val INSTALLER_LINE = Regex("^package:(\\S+)(?:\\s+installer=(\\S+))?\\s*$")
    private val PACKAGE_LINE = Regex("^package:(\\S+)\\s*$")

    private fun names(listing: String): Set<String> =
        listing.lineSequence().mapNotNull { PACKAGE_LINE.matchEntire(it.trim())?.groupValues?.get(1) }.toSet()

    fun parsePackages(installers: String, disabled: String, system: String): List<PackageRow> {
        val off = names(disabled)
        val sys = names(system)
        return installers.lineSequence().mapNotNull { line ->
            val m = INSTALLER_LINE.matchEntire(line.trim()) ?: return@mapNotNull null
            val pkg = m.groupValues[1]
            val installer = m.groupValues[2].takeIf { it.isNotEmpty() && it != "null" }
            PackageRow(pkg, enabled = pkg !in off, system = pkg in sys, installer = installer)
        }.toList()
    }

    private fun array(output: String, label: String): List<String>? {
        val line = output.lineSequence().map { it.trim() }.firstOrNull { it.startsWith("$label:") } ?: return null
        val inner = line.substringAfter('[', "").substringBeforeLast(']', "")
        if (!line.contains('[') || !line.contains(']')) return null
        return if (inner.isBlank()) emptyList() else inner.split(',').map { it.trim().trim('"') }
    }

    /** `dumpsys diskstats`: parallel arrays of package names and sizes; null when they are missing or disagree. */
    fun parseDiskstats(output: String): List<AppSizeRow>? {
        val names = array(output, "Package Names") ?: return null
        val code = array(output, "App Sizes")?.map { it.toLongOrNull() ?: return null } ?: return null
        val data = array(output, "App Data Sizes")?.map { it.toLongOrNull() ?: return null } ?: return null
        val cache = array(output, "Cache Sizes")?.map { it.toLongOrNull() ?: return null } ?: return null
        if (code.size != names.size || data.size != names.size || cache.size != names.size) return null
        return names.indices.map { AppSizeRow(names[it], code[it], data[it], cache[it]) }
    }
}
