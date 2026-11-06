package com.example.phonesteward.voice

import android.content.Context
import androidx.annotation.VisibleForTesting
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.example.phonesteward.android.AppGraph

/**
 * A15.5/A15.6: the voice pieces of the Chat screen, bound to the paired identity. The recorder
 * and the player sit behind interfaces ([Recorder], [SpeechPlayer]) so a test or Test Lab can
 * substitute fakes; the controllers are pure Kotlin.
 */
class VoiceSession(
    private val api: VoiceApi?,
    val dictation: DictationController,
    val readAloud: ReadAloudController,
) : AutoCloseable {

    override fun close() {
        dictation.cancel()
        readAloud.stop()
        api?.close()
    }

    companion object {
        /** Test seam: Test Lab has no microphone, so a flow swaps in a fake recorder. */
        @VisibleForTesting
        var recorderFactory: (Context) -> Recorder = { AndroidRecorder(it) }

        /** Test seam: a flow swaps in a fake player it can hold and observe. */
        @VisibleForTesting
        var playerFactory: (Context) -> SpeechPlayer = { AndroidSpeechPlayer(it) }

        fun open(
            context: Context,
            scope: CoroutineScope,
            onDictatedText: (String) -> Unit,
            io: kotlin.coroutines.CoroutineContext = Dispatchers.IO,
        ): VoiceSession {
            val identity = AppGraph.get(context).identityStore.read()
            android.util.Log.i("StewardVoice", "voice session open: paired=${identity != null}")
            val api = identity?.let { VoiceApi(it.baseUrl, tokenProvider = { it.token }) }
            val unavailable: () -> Nothing = { throw VoiceException("VOICE_UNAVAILABLE", null) }
            val dictation = DictationController(
                recorder = recorderFactory(context),
                transcribe = { bytes, contentType ->
                    try {
                        withContext(io) { api?.transcribe(bytes, contentType) ?: unavailable() }
                    } catch (e: Exception) {
                        android.util.Log.w("StewardVoice", "dictation failed", e)
                        throw e
                    }
                },
                scope = scope,
                onText = onDictatedText,
            )
            val readAloud = ReadAloudController(
                speak = { text ->
                    try {
                        withContext(io) { api?.speak(text) ?: unavailable() }
                    } catch (e: Exception) {
                        android.util.Log.w("StewardVoice", "read aloud failed", e)
                        throw e
                    }
                },
                player = playerFactory(context),
                scope = scope,
            )
            return VoiceSession(api, dictation, readAloud)
        }
    }
}

/** The Chat screen's voice session, closed when it leaves the composition. */
@Composable
fun rememberVoiceSession(onDictatedText: (String) -> Unit): VoiceSession {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val session = remember { VoiceSession.open(context, scope, onDictatedText) }
    DisposableEffect(session) { onDispose { session.close() } }
    return session
}
