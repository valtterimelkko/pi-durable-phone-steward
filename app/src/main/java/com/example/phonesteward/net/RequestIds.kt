package com.example.phonesteward.net

import java.security.MessageDigest

/**
 * Client `requestId` generation (§1): 1–128 characters, stable per logical
 * action so a retried POST can never double-apply. Job results use the
 * protocol's `res-<jobId>` form.
 */
object RequestIds {

    private const val MAX = 128

    /** Random id for one logical mutation, e.g. `req-<32 hex>`. */
    fun new(prefix: String = "req"): String = "$prefix-${randomHex(16)}"

    /** Stable result id for a job: `res-<jobId>`; hashed when the form would exceed 128 chars. */
    fun forJobResult(jobId: String): String = clamped("res-$jobId")

    fun clamped(candidate: String): String =
        if (candidate.length <= MAX) candidate
        else "h-" + sha256Hex(candidate).take(MAX - 2)

    fun isValid(candidate: String): Boolean = candidate.isNotEmpty() && candidate.length <= MAX

    private fun randomHex(bytes: Int): String =
        ByteArray(bytes).also { java.security.SecureRandom().nextBytes(it) }
            .joinToString("") { "%02x".format(it) }

    fun sha256Hex(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}
