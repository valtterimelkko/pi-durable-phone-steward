package com.example.phonesteward.logic

import com.example.phonesteward.net.DevicePermissions

/**
 * N9 (app half): the server keeps the permissions of the last device status
 * it received. After a Settings round trip the app posts again, but only when
 * the permissions differ from what was last posted. Before any post (the
 * start-up post is still to come) a resume does not post.
 */
class PermissionPostGate {
    @Volatile
    private var lastPosted: DevicePermissions? = null

    fun shouldPostOnResume(current: DevicePermissions): Boolean {
        val last = lastPosted ?: return false
        return last != current
    }

    fun markPosted(posted: DevicePermissions) {
        lastPosted = posted
    }

    fun reset() {
        lastPosted = null
    }
}
