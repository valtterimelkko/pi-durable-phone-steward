package com.example.phonesteward.voice

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope

/** A15.6: what the Read aloud button is doing. */
sealed interface ReadAloudState {
    data object Idle : ReadAloudState
    data object Loading : ReadAloudState
    data object Playing : ReadAloudState
    data class Failed(val message: String) : ReadAloudState
}

/**
 * A15.6: read one round aloud. The text is split into sentences of at most 280 characters; the
 * next sentence is fetched while the current one plays; only one round reads at a time. Toggling
 * again (or another round) stops the first. [speak] is a suspending fetch of one chunk.
 */
class ReadAloudController(
    private val speak: suspend (String) -> SpeechAudio,
    private val player: SpeechPlayer,
    private val scope: CoroutineScope,
) {

    private val _state = MutableStateFlow<ReadAloudState>(ReadAloudState.Idle)
    val state: StateFlow<ReadAloudState> = _state

    private var job: Job? = null

    /** Starts reading [text], or stops when something is already reading. */
    fun toggle(text: String) {
        if (_state.value is ReadAloudState.Playing || _state.value is ReadAloudState.Loading) stop() else start(text)
    }

    /** Stops the reading now; safe to call when idle. */
    fun stop() {
        job?.cancel()
        job = null
        player.stop()
        _state.value = ReadAloudState.Idle
    }

    private fun start(text: String) {
        val chunks = SpeechChunker.chunkIntoSentences(text)
        if (chunks.isEmpty()) {
            _state.value = ReadAloudState.Idle
            return
        }
        _state.value = ReadAloudState.Loading
        job = scope.launch {
            try {
                supervisorScope {
                    var next = async { speak(chunks[0]) }
                    for (index in chunks.indices) {
                        val audio = next.await()
                        if (index + 1 < chunks.size) next = async { speak(chunks[index + 1]) }
                        _state.value = ReadAloudState.Playing
                        player.play(audio.bytes, audio.contentType)
                    }
                }
                _state.value = ReadAloudState.Idle
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _state.value = ReadAloudState.Failed("Read aloud is not available right now")
            } finally {
                job = null
            }
        }
    }
}
