package com.example.phonesteward.voice

import android.content.Context
import android.media.MediaPlayer
import java.io.File
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * A15.6: the real player. The chunk (WAV from the primary, MP3 from the fallback) is written to
 * a temporary cache file and played by [MediaPlayer]; the coroutine resumes when it finishes.
 */
class AndroidSpeechPlayer(private val context: Context) : SpeechPlayer {

    private var current: MediaPlayer? = null

    override suspend fun play(audio: ByteArray, contentType: String) {
        val extension = if (contentType.contains("mpeg")) ".mp3" else ".wav"
        val file = File.createTempFile("steward-speech", extension, context.cacheDir)
        file.writeBytes(audio)
        suspendCancellableCoroutine { continuation ->
            val player = MediaPlayer()
            current = player
            val done = {
                runCatching { player.release() }
                if (current === player) current = null
                file.delete()
                if (continuation.isActive) continuation.resume(Unit)
            }
            try {
                player.setDataSource(file.absolutePath)
                player.setOnCompletionListener { done() }
                player.setOnErrorListener { _, _, _ -> done(); true }
                player.prepare()
                player.start()
            } catch (_: Exception) {
                done()
                return@suspendCancellableCoroutine
            }
            continuation.invokeOnCancellation {
                runCatching { player.stop() }
                runCatching { player.release() }
                if (current === player) current = null
                file.delete()
            }
        }
    }

    override fun stop() {
        val player = current
        current = null
        runCatching { player?.stop() }
        runCatching { player?.release() }
    }
}
