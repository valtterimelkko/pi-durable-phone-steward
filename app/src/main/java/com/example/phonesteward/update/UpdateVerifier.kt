package com.example.phonesteward.update

import java.io.File
import java.security.MessageDigest

/** SHA-256 hex digests of signing certificates. */
data class SignerInfo(
    /** The certificates the APK is signed with right now. */
    val current: Set<String>,
    /** Its signing-certificate lineage (key rotation), including [current]. */
    val history: Set<String>,
    val multiple: Boolean,
)

data class ApkInfo(val packageName: String, val versionCode: Long, val signers: SignerInfo)

/** Reads an APK's identity; null when Android cannot parse it. The real one is `getPackageArchiveInfo`. */
fun interface ApkInspector {
    fun inspect(file: File): ApkInfo?
}

/**
 * The checks before an install (A7), each a refusal: the SHA-256 against `latest`, the
 * archive's package name, its versionCode against `latest` (and newer than the installed
 * app), and its signing certificate against the installed app's. Without a matching
 * certificate Android would refuse the in-place update anyway; checking first gives a
 * clear reason and never lets a foreign APK reach the installer.
 */
class UpdateVerifier(
    private val expectedPackage: String,
    private val installedVersionCode: Long,
    private val installedSigners: SignerInfo,
    private val inspector: ApkInspector,
) {
    sealed interface Verdict {
        data object Ok : Verdict
        data class Refused(val reason: UpdateRefusal) : Verdict
    }

    /** The announced size is checked before downloading anything. */
    fun checkAnnounced(latest: AppLatest): Verdict =
        if (latest.sizeBytes > UpdateController.MAX_APK_BYTES || latest.sizeBytes <= 0) {
            Verdict.Refused(UpdateRefusal.TOO_LARGE)
        } else {
            Verdict.Ok
        }

    fun verify(file: File, latest: AppLatest): Verdict {
        if (!file.isFile) return Verdict.Refused(UpdateRefusal.UNREADABLE)
        if (file.length() > UpdateController.MAX_APK_BYTES) return Verdict.Refused(UpdateRefusal.TOO_LARGE)
        if (file.length() != latest.sizeBytes) return Verdict.Refused(UpdateRefusal.SHA256_MISMATCH)
        if (!sha256Of(file).equals(latest.sha256, ignoreCase = true)) return Verdict.Refused(UpdateRefusal.SHA256_MISMATCH)
        val info = try {
            inspector.inspect(file)
        } catch (_: Exception) {
            null
        } ?: return Verdict.Refused(UpdateRefusal.UNREADABLE)
        if (info.packageName != expectedPackage) return Verdict.Refused(UpdateRefusal.WRONG_PACKAGE)
        if (info.versionCode != latest.versionCode || info.versionCode <= installedVersionCode) {
            return Verdict.Refused(UpdateRefusal.WRONG_VERSION)
        }
        if (!sameSigner(installedSigners, info.signers)) return Verdict.Refused(UpdateRefusal.SIGNER_MISMATCH)
        return Verdict.Ok
    }

    companion object {
        fun sha256Of(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    digest.update(buffer, 0, n)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }

        /** Same certificates, or (one signer) a rotation lineage that includes the installed one. */
        fun sameSigner(installed: SignerInfo, candidate: SignerInfo): Boolean {
            if (installed.current.isEmpty() || candidate.current.isEmpty()) return false
            if (installed.multiple || candidate.multiple) return installed.current == candidate.current
            return installed.current == candidate.current ||
                candidate.history.intersect(installed.current).isNotEmpty()
        }
    }
}
