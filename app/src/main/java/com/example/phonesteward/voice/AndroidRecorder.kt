package com.example.phonesteward.voice

import android.content.Context
import android.media.MediaRecorder
import java.io.File

/**
 * A15.5: the real microphone. minSdk is 33, so Opus in an OGG container is always available
 * (the API 29+ path); the recording is a temporary file in the app's cache, read back on stop
 * and deleted.
 */
class AndroidRecorder(private val context: Context) : Recorder {

    override val contentType: String = "audio/ogg"

    private var recorder: MediaRecorder? = null
    private var file: File? = null

    override fun start(): Boolean {
        return try {
            val output = File.createTempFile("steward-dictation", ".ogg", context.cacheDir)
            val media = MediaRecorder(context)
            media.setAudioSource(MediaRecorder.AudioSource.MIC)
            media.setOutputFormat(MediaRecorder.OutputFormat.OGG)
            media.setAudioEncoder(MediaRecorder.AudioEncoder.OPUS)
            media.setAudioSamplingRate(16_000)
            media.setOutputFile(output.absolutePath)
            media.prepare()
            media.start()
            recorder = media
            file = output
            true
        } catch (_: Exception) {
            cancel()
            false
        }
    }

    override fun stop(): ByteArray {
        val media = recorder ?: return ByteArray(0)
        return try {
            media.stop()
            file?.readBytes() ?: ByteArray(0)
        } catch (_: Exception) {
            ByteArray(0)
        } finally {
            runCatching { media.release() }
            recorder = null
            file?.delete()
            file = null
        }
    }

    override fun cancel() {
        runCatching { recorder?.reset() }
        runCatching { recorder?.release() }
        recorder = null
        file?.delete()
        file = null
    }
}
