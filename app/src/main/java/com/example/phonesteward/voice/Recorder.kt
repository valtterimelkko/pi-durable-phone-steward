package com.example.phonesteward.voice

/**
 * A15.5: the microphone, behind an interface so a test or Test Lab (which has no microphone)
 * can substitute a fake. [contentType] is what the recording will be uploaded as.
 */
interface Recorder {
    val contentType: String
    fun start(): Boolean
    /** Stops and returns the recorded bytes (empty when nothing was captured). */
    fun stop(): ByteArray
    fun cancel()
}

/** A15.6: one chunk of speech, behind an interface so a test can hold or observe playback. */
interface SpeechPlayer {
    /** Plays the chunk; the coroutine resumes when it finishes (or is cancelled). */
    suspend fun play(audio: ByteArray, contentType: String)
    fun stop()
}
