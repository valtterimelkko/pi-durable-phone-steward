package com.example.phonesteward.screen

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** What the Status card shows about the phone-use session (process-wide; one session at a time). */
sealed interface PhoneUseUi {
    data object Idle : PhoneUseUi
    data class Starting(val task: String) : PhoneUseUi
    data class Running(val task: String, val steps: Int, val waitingFor: String? = null) : PhoneUseUi
    data class Ended(val reason: String, val summary: String?) : PhoneUseUi
}

object PhoneUseState {
    private val _state = MutableStateFlow<PhoneUseUi>(PhoneUseUi.Idle)
    val state: StateFlow<PhoneUseUi> = _state.asStateFlow()

    /** Plain words for why a session ended. */
    fun endText(reason: String): String = when (reason) {
        "stopped" -> "You stopped the session."
        "denied" -> "The session ended because it reached an app the steward may not use."
        "budget" -> "The session ended: it used up its 40 steps or 15 minutes."
        "finished", "server_ended" -> "The session finished."
        "error" -> "The session ended because the connection to the server failed."
        "service_off" -> "The accessibility service is off. Turn it on, then start again."
        "not_paired" -> "This phone is not paired yet."
        else -> "The session ended."
    }

    val isActive: Boolean get() = _state.value.let { it is PhoneUseUi.Starting || it is PhoneUseUi.Running }

    fun starting(task: String) { _state.value = PhoneUseUi.Starting(task) }
    fun running(task: String, steps: Int, waitingFor: String? = null) { _state.value = PhoneUseUi.Running(task, steps, waitingFor) }
    fun ended(reason: String, summary: String? = null) { _state.value = PhoneUseUi.Ended(reason, summary) }
    fun reset() { _state.value = PhoneUseUi.Idle }

    /** A [SessionListener] that mirrors a runner into this state. */
    fun listenerFor(task: String, also: SessionListener = SessionListener.None) = object : SessionListener {
        private var steps = 0
        override fun onStarted(sessionId: String, task: String) { running(task, 0); also.onStarted(sessionId, task) }
        override fun onStep(count: Int, tool: String) { steps = count; running(task, count); also.onStep(count, tool) }
        override fun onAction(text: String) { also.onAction(text) }
        override fun onWaitingForOwner(label: String) { running(task, steps, label); also.onWaitingForOwner(label) }
        override fun onOwnerAnswered(allowed: Boolean) { running(task, steps); also.onOwnerAnswered(allowed) }
        override fun onEnded(end: SessionEnd) { ended(end.reason, end.summary); also.onEnded(end) }
    }
}
