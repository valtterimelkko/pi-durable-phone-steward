package com.example.phonesteward.update

import kotlinx.serialization.Serializable

/** `GET /v1/app/latest` (protocol A7). */
@Serializable
data class AppLatest(
    val versionName: String,
    val versionCode: Long,
    val sha256: String,
    val sizeBytes: Long,
    val publishedAtMs: Long = 0,
)

/** Version comparison: Android's versionCode is a number, never text order. */
object AppVersion {
    fun isNewer(latestCode: Long, installedCode: Long): Boolean = latestCode > installedCode
}

/**
 * Why a download was refused (A7). [code] goes to the event log and the error
 * report; [userText] is what the owner reads. Neither carries a path or a file name.
 */
enum class UpdateRefusal(val code: String, val userText: String) {
    SHA256_MISMATCH("sha256_mismatch", "The download did not match its checksum."),
    WRONG_PACKAGE("wrong_package", "The download is not Phone Steward."),
    WRONG_VERSION("wrong_version", "The download is not the version the server announced."),
    SIGNER_MISMATCH("signer_mismatch", "The download is signed by someone else, so Android would not keep your pairing."),
    UNREADABLE("unreadable", "The download could not be read as an app."),
    TOO_LARGE("too_large", "The download is larger than the 100 MB limit."),
}

/** What the Status screen shows about updates. [installed] is the running versionName. */
sealed interface UpdateUiState {
    val installed: String

    /** Nothing checked yet. */
    data class Unknown(override val installed: String) : UpdateUiState
    data class Checking(override val installed: String) : UpdateUiState
    data class UpToDate(override val installed: String) : UpdateUiState
    data class CheckFailed(override val installed: String) : UpdateUiState
    data class Available(override val installed: String, val latest: AppLatest) : UpdateUiState

    /** Waiting for the owner to flip "Install unknown apps" for this app. */
    data class NeedsPermission(override val installed: String, val latest: AppLatest) : UpdateUiState
    data class Downloading(override val installed: String, val latest: AppLatest, val bytes: Long, val total: Long) : UpdateUiState
    data class Verifying(override val installed: String, val latest: AppLatest) : UpdateUiState

    /** Committed to Android; waiting for its confirmation (or for the process to be replaced). */
    data class Installing(override val installed: String, val latest: AppLatest) : UpdateUiState
    data class Refused(override val installed: String, val latest: AppLatest, val reason: UpdateRefusal) : UpdateUiState
    data class Failed(override val installed: String, val latest: AppLatest, val message: String) : UpdateUiState
}

/** An install that is too large for the app's own limit (A7: at most 100 MiB). */
class ApkTooLargeException(val bytes: Long) : RuntimeException("download is larger than the limit")

/** `PackageInstaller.STATUS_*` values and names (kept here so the logic stays pure JVM). */
object InstallStatus {
    const val PENDING_USER_ACTION = -1
    const val SUCCESS = 0
    const val FAILURE = 1
    const val FAILURE_BLOCKED = 2
    const val FAILURE_ABORTED = 3
    const val FAILURE_INVALID = 4
    const val FAILURE_CONFLICT = 5
    const val FAILURE_STORAGE = 6
    const val FAILURE_INCOMPATIBLE = 7
    const val FAILURE_TIMEOUT = 8

    fun name(status: Int): String = when (status) {
        PENDING_USER_ACTION -> "STATUS_PENDING_USER_ACTION"
        SUCCESS -> "STATUS_SUCCESS"
        FAILURE -> "STATUS_FAILURE"
        FAILURE_BLOCKED -> "STATUS_FAILURE_BLOCKED"
        FAILURE_ABORTED -> "STATUS_FAILURE_ABORTED"
        FAILURE_INVALID -> "STATUS_FAILURE_INVALID"
        FAILURE_CONFLICT -> "STATUS_FAILURE_CONFLICT"
        FAILURE_STORAGE -> "STATUS_FAILURE_STORAGE"
        FAILURE_INCOMPATIBLE -> "STATUS_FAILURE_INCOMPATIBLE"
        FAILURE_TIMEOUT -> "STATUS_FAILURE_TIMEOUT"
        else -> "STATUS_$status"
    }
}
