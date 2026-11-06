package com.example.phonesteward.logic.jobs

import com.example.phonesteward.logic.StatusPostCoalescer
import com.example.phonesteward.net.DevicePermissions
import com.example.phonesteward.net.DeviceStatusRequest
import com.example.phonesteward.net.RequestIds
import com.example.phonesteward.net.StewardTrashStatus
import com.example.phonesteward.net.StewardApi

/**
 * Builds and posts `POST /v1/device/status` (protocol §7), sent on app open
 * and from the periodic worker. Pure: the permission snapshot arrives as a
 * lambda so tests inject any state.
 */
class DeviceStatusReporter(
    private val api: StewardApi,
    private val appVersion: String,
    private val permissions: () -> DevicePermissions,
    private val lastWorkerRunMs: () -> Long,
    private val requestId: () -> String = { RequestIds.new("status") },
    /** A10.1: the Steward trash the status reports; an empty trash when the app cannot read one. */
    private val stewardTrash: () -> StewardTrashStatus = { StewardTrashStatus() },
    /** N29: holds back an identical status posted a moment ago (pairing, first resume and the worker each post). */
    private val gate: StatusPostCoalescer? = null,
) {
    /** True when the server accepted the status; false when it was held back by [gate]. */
    fun send(): Boolean {
        val perms = permissions()
        val trash = stewardTrash()
        if (gate != null && !gate.allow(perms to trash)) return false
        val body = DeviceStatusRequest(
            requestId = requestId(),
            appVersion = appVersion,
            permissions = perms,
            lastWorkerRunMs = lastWorkerRunMs(),
            stewardTrash = trash,
        )
        return api.postDeviceStatus(body).accepted
    }

    companion object {
        /** Pure mapping used by tests and the UI: which permissions to report. */
        fun snapshot(
            usageAccess: Boolean,
            allFiles: Boolean,
            mediaRead: Boolean,
            manageMedia: Boolean,
            batteryUnrestricted: Boolean,
        ): DevicePermissions = DevicePermissions(
            usageAccess = usageAccess,
            allFiles = allFiles,
            mediaRead = mediaRead,
            manageMedia = manageMedia,
            batteryUnrestricted = batteryUnrestricted,
        )
    }
}
