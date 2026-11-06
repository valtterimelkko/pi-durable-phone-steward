package com.example.phonesteward.net

import kotlinx.serialization.Serializable

/** O6 (A6): the `worker` block of the `phone_diagnostics` result. */
@Serializable
data class DiagnosticsWorker(
    val state: String,
    val lastRunMs: Long = 0,
    val runAttemptCount: Int = 0,
)

/** O6 (A6): the `outbox` block of the `phone_diagnostics` result. */
@Serializable
data class DiagnosticsOutbox(val crashes: Int = 0, val errors: Int = 0)

/**
 * O6 (A6): the `data` of a `phone_diagnostics` result. `log` is the app's
 * in-memory event log, newest last, at most 200 entries with 200-character
 * details and no personal data.
 */
@Serializable
data class PhoneDiagnosticsData(
    val appVersion: String,
    val versionCode: Int,
    val permissions: DevicePermissions,
    val worker: DiagnosticsWorker,
    val outbox: DiagnosticsOutbox,
    val log: List<com.example.phonesteward.logic.obs.EventEntry> = emptyList(),
)
