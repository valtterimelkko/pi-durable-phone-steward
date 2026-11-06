package com.example.phonesteward.voice

import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** A15.5 app half: record, stop, upload, insert; cancel discards; the 5-minute cap stops on its own. */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class DictationControllerTest {

    private class FakeRecorder(override val contentType: String = "audio/ogg") : Recorder {
        var started = 0
        var stopped = 0
        var cancelled = 0
        override fun start(): Boolean { started++; return true }
        override fun stop(): ByteArray { stopped++; return byteArrayOf(4, 5, 6) }
        override fun cancel() { cancelled++ }
    }

    @Test
    fun `stop transcribes the recording and reports the text`() = runTest(UnconfinedTestDispatcher()) {
        val recorder = FakeRecorder()
        var asked: Pair<ByteArray, String>? = null
        var got: String? = null
        val controller = DictationController(
            recorder = recorder,
            transcribe = { bytes, contentType -> asked = bytes to contentType; "hello there" },
            scope = backgroundScope,
            onText = { got = it },
        )
        controller.start()
        controller.stop()
        advanceUntilIdle()
        assertEquals(1, recorder.stopped)
        assertEquals(byteArrayOf(4, 5, 6).toList(), asked!!.first.toList())
        assertEquals("audio/ogg", asked!!.second)
        assertEquals("hello there", got)
        assertEquals(DictationState.Idle, controller.state.value)
    }

    @Test
    fun `cancel discards the recording without transcribing`() = runTest(UnconfinedTestDispatcher()) {
        val recorder = FakeRecorder()
        var transcribed = 0
        val controller = DictationController(
            recorder = recorder,
            transcribe = { _, _ -> transcribed++; "x" },
            scope = backgroundScope,
            onText = {},
        )
        controller.start()
        controller.cancel()
        advanceUntilIdle()
        assertEquals(1, recorder.cancelled)
        assertEquals(0, transcribed)
        assertEquals(DictationState.Idle, controller.state.value)
    }

    @Test
    fun `the five-minute cap stops and transcribes on its own`() = runTest(UnconfinedTestDispatcher()) {
        val recorder = FakeRecorder()
        var got: String? = null
        val controller = DictationController(
            recorder = recorder,
            transcribe = { _, _ -> "capped text" },
            scope = backgroundScope,
            onText = { got = it },
            clock = { testScheduler.currentTime },
        )
        controller.start()
        advanceTimeBy(5 * 60_000L + 500)
        advanceUntilIdle()
        assertEquals(1, recorder.stopped)
        assertEquals("capped text", got)
        assertEquals(DictationState.Idle, controller.state.value)
    }

    @Test
    fun `a failed upload shows one calm line`() = runTest(UnconfinedTestDispatcher()) {
        val controller = DictationController(
            recorder = FakeRecorder(),
            transcribe = { _, _ -> throw VoiceException("VOICE_UNAVAILABLE", null) },
            scope = backgroundScope,
            onText = {},
        )
        controller.start()
        controller.stop()
        advanceUntilIdle()
        val state = controller.state.value
        assertTrue(state is DictationState.Failed)
        assertEquals("Dictation is not available right now", (state as DictationState.Failed).message)
    }
}
