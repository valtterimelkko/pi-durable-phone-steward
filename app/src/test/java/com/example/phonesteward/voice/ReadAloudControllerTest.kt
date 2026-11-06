package com.example.phonesteward.voice

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** A15.6 app half: one round reads at a time, the next sentence is fetched while one plays, Stop stops. */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ReadAloudControllerTest {

    private class FakePlayer : SpeechPlayer {
        val played = mutableListOf<String>()
        var hold: CompletableDeferred<Unit>? = null
        var stopped = 0
        override suspend fun play(audio: ByteArray, contentType: String) {
            played += String(audio)
            hold?.await()
        }
        override fun stop() { stopped++ }
    }

    @Test
    fun `fetches the next sentence while the current one plays`() = runTest(UnconfinedTestDispatcher()) {
        val events = mutableListOf<String>()
        val player = FakePlayer()
        val controller = ReadAloudController(
            speak = { text -> events += "fetch:$text"; SpeechAudio(text.toByteArray(), "audio/wav") },
            player = player,
            scope = backgroundScope,
        )
        controller.toggle("One. Two.")
        advanceUntilIdle()
        assertEquals(listOf("One.", "Two."), player.played)
        assertTrue("the second fetch starts before the first play", events.indexOf("fetch:Two.") < player.played.size)
        assertEquals(ReadAloudState.Idle, controller.state.value)
    }

    @Test
    fun `toggle while playing stops the reading`() = runTest(UnconfinedTestDispatcher()) {
        val player = FakePlayer()
        val gate = CompletableDeferred<Unit>()
        player.hold = gate
        val controller = ReadAloudController(
            speak = { text -> SpeechAudio(text.toByteArray(), "audio/wav") },
            player = player,
            scope = backgroundScope,
        )
        controller.toggle("One. Two.")
        advanceUntilIdle()
        assertEquals(ReadAloudState.Playing, controller.state.value)
        controller.toggle("One. Two.")
        advanceUntilIdle()
        assertEquals(ReadAloudState.Idle, controller.state.value)
        assertEquals(1, player.stopped)
        gate.complete(Unit)
    }

    @Test
    fun `a failed fetch shows one calm line`() = runTest(UnconfinedTestDispatcher()) {
        val controller = ReadAloudController(
            speak = { throw VoiceException("VOICE_FAILED", null) },
            player = FakePlayer(),
            scope = backgroundScope,
        )
        controller.toggle("Hello.")
        advanceUntilIdle()
        val state = controller.state.value
        assertTrue(state is ReadAloudState.Failed)
        assertEquals("Read aloud is not available right now", (state as ReadAloudState.Failed).message)
    }

    @Test
    fun `stops on an empty round`() = runTest(UnconfinedTestDispatcher()) {
        val controller = ReadAloudController(speak = { SpeechAudio(ByteArray(0), "audio/wav") }, player = FakePlayer(), scope = backgroundScope)
        controller.toggle("   ")
        advanceUntilIdle()
        assertEquals(ReadAloudState.Idle, controller.state.value)
    }
}
