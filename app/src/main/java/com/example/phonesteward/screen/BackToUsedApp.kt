package com.example.phonesteward.screen

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * A15.2: after the owner answers a running session's question, the app the session was using
 * must come back to the front, so Phone Steward moves its own task to the back. The decision
 * is pure and tested in [BackToUsedAppTest]; the activity consumes [token] and calls
 * `moveTaskToBack(true)`. Nothing is asked when no session runs.
 */
object BackToUsedApp {

    private val _token = MutableStateFlow<Long?>(null)

    /** A one-shot hand-back request; the activity moves back and consumes it. */
    val token: StateFlow<Long?> = _token.asStateFlow()

    /**
     * True when a chat message the owner is about to send answers the session's open question:
     * the session is running and its last phone-use word was a question (`ask`). A step, a
     * start or an end is not a question, and with no session running there is nothing to hand
     * back to.
     */
    fun afterChatSend(lastPhoneUseKind: String?, sessionActive: Boolean): Boolean =
        sessionActive && lastPhoneUseKind == "ask"

    /** True when the owner answers the confirm gate's question while a session runs. */
    fun afterConfirm(sessionActive: Boolean): Boolean = sessionActive

    /** Asks the activity to move its task to the back (the used app returns to the front). */
    fun request(nowMs: Long = System.currentTimeMillis()) {
        _token.value = nowMs
    }

    /** The activity consumes the token once it has moved back. */
    fun consume() {
        _token.value = null
    }

    /** Tests clear the signal between cases. */
    fun reset() {
        _token.value = null
    }
}
