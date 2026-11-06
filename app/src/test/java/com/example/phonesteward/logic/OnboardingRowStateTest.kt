package com.example.phonesteward.logic

import org.junit.Assert.assertEquals
import org.junit.Test
import com.example.phonesteward.logic.Onboarding.RowState
import com.example.phonesteward.logic.Onboarding.Step

/**
 * N3: "Manage media" must show a NEUTRAL "not needed while auto mode is
 * off" state — visibly distinct from granted (green) and from action
 * needed. The other four steps stay granted/action-needed.
 */
class OnboardingRowStateTest {

    private val grantedCore = Onboarding.Snapshot(
        batteryUnrestricted = true,
        usageAccess = true,
        allFiles = true,
        mediaRead = true,
        manageMedia = false,
        autoModeOn = false,
    )

    @Test
    fun `manage media with auto mode off is NOT_NEEDED and not granted`() {
        val states = Onboarding.rowStates(grantedCore).toMap()
        assertEquals(RowState.NOT_NEEDED, states[Step.MANAGE_MEDIA])
        assertEquals(RowState.GRANTED, states[Step.BATTERY])
        assertEquals(RowState.GRANTED, states[Step.USAGE_ACCESS])
        assertEquals(RowState.GRANTED, states[Step.ALL_FILES])
        assertEquals(RowState.GRANTED, states[Step.MEDIA_READ])
    }

    @Test
    fun `manage media with auto mode on but not granted is ACTION_NEEDED`() {
        val states = Onboarding.rowStates(grantedCore.copy(autoModeOn = true)).toMap()
        assertEquals(RowState.ACTION_NEEDED, states[Step.MANAGE_MEDIA])
    }

    @Test
    fun `manage media granted is GRANTED whatever auto mode says`() {
        val states = Onboarding.rowStates(grantedCore.copy(manageMedia = true)).toMap()
        assertEquals(RowState.GRANTED, states[Step.MANAGE_MEDIA])
    }

    @Test
    fun `an ungranted core step is ACTION_NEEDED`() {
        val states = Onboarding.rowStates(
            grantedCore.copy(batteryUnrestricted = false, manageMedia = true),
        ).toMap()
        assertEquals(RowState.ACTION_NEEDED, states[Step.BATTERY])
        assertEquals(RowState.GRANTED, states[Step.MANAGE_MEDIA])
    }

    @Test
    fun `steps keeps its onboarding meaning — manage media counts done while auto mode is off`() {
        // nextStep must not send the owner to manage media while auto mode is off.
        assertEquals(null, Onboarding.nextStep(grantedCore))
        assertEquals(
            Step.MANAGE_MEDIA,
            Onboarding.nextStep(grantedCore.copy(autoModeOn = true)),
        )
    }
}
