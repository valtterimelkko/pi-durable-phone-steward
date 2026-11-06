package com.example.phonesteward.logic

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import com.example.phonesteward.net.DevicePermissions

/** N9 (app half): post the device status on resume only when the permissions changed since the last post. */
class PermissionPostGateTest {

    private val none = DevicePermissions()
    private val granted = DevicePermissions(usageAccess = true, allFiles = true, mediaRead = true, batteryUnrestricted = true)

    @Test
    fun `before any post the resume does not post, the start-up post will`() {
        assertFalse(PermissionPostGate().shouldPostOnResume(granted))
    }

    @Test
    fun `unchanged permissions do not post again`() {
        val gate = PermissionPostGate()
        gate.markPosted(none)
        assertFalse(gate.shouldPostOnResume(none))
    }

    @Test
    fun `permissions granted in Settings post on the next resume, once`() {
        val gate = PermissionPostGate()
        gate.markPosted(none)
        assertTrue(gate.shouldPostOnResume(granted))
        gate.markPosted(granted)
        assertFalse(gate.shouldPostOnResume(granted))
    }

    @Test
    fun `permissions revoked also post`() {
        val gate = PermissionPostGate()
        gate.markPosted(granted)
        assertTrue(gate.shouldPostOnResume(none))
    }

    @Test
    fun `forgetting the pairing starts over`() {
        val gate = PermissionPostGate()
        gate.markPosted(none)
        gate.reset()
        assertFalse(gate.shouldPostOnResume(granted))
    }
}
