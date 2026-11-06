package com.example.phonesteward.screen

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A15.2: when the owner's answer should hand the screen back to the app the session was using. */
class BackToUsedAppTest {

    @Test
    fun `a send answers the session when the session is running and its last word was a question`() {
        assertTrue(BackToUsedApp.afterChatSend(lastPhoneUseKind = "ask", sessionActive = true))
    }

    @Test
    fun `a send is not an answer when the session's last word was a step, a start or an end`() {
        assertFalse(BackToUsedApp.afterChatSend(lastPhoneUseKind = "step", sessionActive = true))
        assertFalse(BackToUsedApp.afterChatSend(lastPhoneUseKind = "started", sessionActive = true))
        assertFalse(BackToUsedApp.afterChatSend(lastPhoneUseKind = "ended", sessionActive = true))
    }

    @Test
    fun `a send is not an answer when the session asked nothing or none runs`() {
        assertFalse(BackToUsedApp.afterChatSend(lastPhoneUseKind = null, sessionActive = true))
        assertFalse(BackToUsedApp.afterChatSend(lastPhoneUseKind = "ask", sessionActive = false))
        assertFalse(BackToUsedApp.afterChatSend(lastPhoneUseKind = null, sessionActive = false))
    }

    @Test
    fun `an answered confirm card hands back only while a session runs`() {
        assertTrue(BackToUsedApp.afterConfirm(sessionActive = true))
        assertFalse(BackToUsedApp.afterConfirm(sessionActive = false))
    }
}
