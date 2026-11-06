package com.example.phonesteward.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.example.phonesteward.net.ApiException
import com.example.phonesteward.net.PhotoWindow

class RulesFormTest {

    private val folders = listOf("DCIM/Camera/", "Download/")

    @Test
    fun `a well-formed window passes`() {
        val window = PhotoWindow(
            enabled = true, reminderDays = 90, cutoffDays = 120,
            folders = folders, keepMarker = "Keep",
        )
        assertTrue(RulesForm.validate(window).none)
    }

    @Test
    fun `reminder at or after the cut-off fails`() {
        val problems = RulesForm.validate(PhotoWindow(reminderDays = 120, cutoffDays = 120, folders = folders))
        assertTrue(problems.reminderNotBeforeCutoff)
        assertFalse(problems.none)
        assertTrue(RulesForm.validate(PhotoWindow(reminderDays = 121, cutoffDays = 120, folders = folders)).reminderNotBeforeCutoff)
    }

    @Test
    fun `empty folders fail and the keep marker is fixed at Keep (A4)`() {
        val noFolders = RulesForm.validate(PhotoWindow(reminderDays = 90, cutoffDays = 120, folders = emptyList()))
        assertTrue(noFolders.foldersEmpty)
        val noMarker = RulesForm.validate(PhotoWindow(reminderDays = 90, cutoffDays = 120, folders = folders, keepMarker = " "))
        assertTrue(noMarker.keepMarkerWrong)
        val renamed = RulesForm.validate(PhotoWindow(reminderDays = 90, cutoffDays = 120, folders = folders, keepMarker = "Safe"))
        assertTrue(renamed.keepMarkerWrong)
        assertTrue(RulesForm.validate(PhotoWindow(reminderDays = 90, cutoffDays = 120, folders = folders)).none)
    }

    @Test
    fun `auto mode needs at least one one-tap batch`() {
        assertFalse(RulesForm.autoModeAllowed(0))
        assertTrue(RulesForm.autoModeAllowed(1))
    }

    @Test
    fun `put carries the current version and a request id`() {
        val current = com.example.phonesteward.net.RulesResponse(
            3, PhotoWindow(reminderDays = 90, cutoffDays = 120, folders = folders), batchesRunOneTap = 0,
        )
        val put = RulesForm.put(current, PhotoWindow(enabled = true, reminderDays = 60, cutoffDays = 90, folders = folders), "req-1")
        assertEquals(3, put.ruleVersion)
        assertEquals("req-1", put.requestId)
        assertEquals(60, put.photoWindow.reminderDays)
    }

    @Test
    fun `errors classify for the screen`() {
        val refetched = com.example.phonesteward.net.RulesResponse(5, PhotoWindow(reminderDays = 90, cutoffDays = 120, folders = folders))
        val conflict = RulesForm.classifyPutError(ApiException(409, "RULE_VERSION_CONFLICT"), refetched)
        assertTrue(conflict is RulesForm.PutOutcome.VersionConflict)
        assertEquals(5, (conflict as RulesForm.PutOutcome.VersionConflict).current.ruleVersion)

        val auto = RulesForm.classifyPutError(ApiException(409, "AUTO_MODE_NOT_YET"), refetched)
        assertTrue((auto as RulesForm.PutOutcome.Rejected).message.contains("one-tap"))

        val bad = RulesForm.classifyPutError(ApiException(400, "BAD_REQUEST", "folders"), refetched)
        assertTrue((bad as RulesForm.PutOutcome.Rejected).message.contains("folders"))

        val other = RulesForm.classifyPutError(ApiException(500, "UNKNOWN"), null)
        assertTrue((other as RulesForm.PutOutcome.Rejected).message.contains("UNKNOWN"))
    }
}

class OnboardingTest {

    private val done = Onboarding.Snapshot(
        batteryUnrestricted = true, usageAccess = true, allFiles = true,
        mediaRead = true, manageMedia = false, autoModeOn = false,
    )

    @Test
    fun `a full snapshot finishes onboarding with auto mode off`() {
        assertTrue(done.coreDone)
        assertNull(Onboarding.nextStep(done))
        val statuses = Onboarding.steps(done)
        assertEquals(5, statuses.size)
        assertTrue(statuses.all { it.second })
    }

    @Test
    fun `each missing core permission becomes the next step`() {
        val missing = done.copy(batteryUnrestricted = false, allFiles = false)
        assertEquals(Onboarding.Step.BATTERY, Onboarding.nextStep(missing))
        assertEquals(Onboarding.Step.ALL_FILES, Onboarding.nextStep(missing.copy(batteryUnrestricted = true)))
    }

    @Test
    fun `manage media is checked only when auto mode is on`() {
        val auto = done.copy(autoModeOn = true, manageMedia = false)
        assertEquals(Onboarding.Step.MANAGE_MEDIA, Onboarding.nextStep(auto))
        val granted = auto.copy(manageMedia = true)
        assertNull(Onboarding.nextStep(granted))
    }
}
