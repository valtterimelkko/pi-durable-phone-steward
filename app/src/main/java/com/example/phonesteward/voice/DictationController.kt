package com.example.phonesteward.voice

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** A15.5: what the microphone button is doing. */
sealed interface DictationState {
    data object Idle : DictationState
    data class Recording(val elapsedMs: Long) : DictationState
    data object Transcribing : DictationState
    data class Failed(val message: String) : DictationState
}

/**
 * A15.5: tap to record, tap to stop, upload, then hand the text back for the owner to edit and
 * send (never sent automatically). A five-minute cap stops the recording on its own; cancel
 * discards it. The upload runs on [scope]; [onText] is called on the main dispatcher.
 */
class DictationController(
    private val recorder: Recorder,
    private val transcribe: suspend (ByteArray, String) -> String,
    private val scope: CoroutineScope,
    private val onText: (String) -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    val maxDurationMs: Long = 5 * 60_000L

    private val _state = MutableStateFlow<DictationState>(DictationState.Idle)
    val state: StateFlow<DictationState> = _state

    private var ticker: Job? = null
    private var startedAt = 0L

    /** Starts a recording; false when one is already running or the microphone refused. */
    fun start(): Boolean {
        if (_state.value !is DictationState.Idle) return false
        if (!recorder.start()) {
            _state.value = DictationState.Failed("The microphone is not available")
            return false
        }
        startedAt = clock()
        _state.value = DictationState.Recording(0)
        ticker = scope.launch {
            while (isActive) {
                delay(200)
                val elapsed = clock() - startedAt
                _state.value = DictationState.Recording(elapsed)
                if (elapsed >= maxDurationMs) {
                    finish()
                    return@launch
                }
            }
        }
        return true
    }

    /** Stops the recording and transcribes it (no-op unless a recording is running). */
    fun stop() {
        if (_state.value is DictationState.Recording) finish()
    }

    /** Discards the recording without transcribing it. */
    fun cancel() {
        ticker?.cancel()
        ticker = null
        recorder.cancel()
        _state.value = DictationState.Idle
    }

    private fun finish() {
        ticker?.cancel()
        ticker = null
        _state.value = DictationState.Transcribing
        val bytes = recorder.stop()
        val contentType = recorder.contentType
        scope.launch {
            val text = try {
                transcribe(bytes, contentType)
            } catch (_: Exception) {
                _state.value = DictationState.Failed("Dictation is not available right now")
                return@launch
            }
            if (text.isNotBlank()) onText(text)
            _state.value = DictationState.Idle
        }
    }
}
