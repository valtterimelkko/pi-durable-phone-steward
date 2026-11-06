package com.example.phonesteward.e2e

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.hasSetTextAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CompletableDeferred
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import com.example.phonesteward.voice.AndroidRecorder
import com.example.phonesteward.voice.AndroidSpeechPlayer
import com.example.phonesteward.voice.Recorder
import com.example.phonesteward.voice.SpeechPlayer
import com.example.phonesteward.voice.VoiceSession

/**
 * Flow V1, voice (A15.5, A15.6): dictation inserts the transcribed text into the input, and
 * Read aloud turns into Stop and back. Test Lab has no microphone, so the recorder is a fake;
 * the player is a fake the test holds, so it can look at the Stop state. The server is the fake
 * steward on loopback.
 */
@RunWith(AndroidJUnit4::class)
class FlowV1VoiceTest : E2eBase() {
    override val flow = "voice"

    private class FakeRecorder : Recorder {
        override val contentType: String = "audio/ogg"
        override fun start(): Boolean = true
        override fun stop(): ByteArray = byteArrayOf(1, 2, 3, 4)
        override fun cancel() {}
    }

    /** Plays nothing and holds the coroutine until [stop], so the Stop state is observable. */
    private class FakePlayer : SpeechPlayer {
        @Volatile
        var hold = true
        val played = CopyOnWriteArrayList<String>()
        private val gate = CompletableDeferred<Unit>()
        override suspend fun play(audio: ByteArray, contentType: String) {
            played.add(contentType)
            if (hold) gate.await()
        }
        override fun stop() {
            hold = false
            gate.complete(Unit)
        }
    }

    private lateinit var player: FakePlayer

    @Before
    fun voiceSetUp() {
        player = FakePlayer()
        VoiceSession.recorderFactory = { FakeRecorder() }
        VoiceSession.playerFactory = { player }
    }

    @After
    fun voiceTearDown() {
        VoiceSession.recorderFactory = { AndroidRecorder(it) }
        VoiceSession.playerFactory = { AndroidSpeechPlayer(it) }
    }

    private fun editableText(): String =
        compose.onNode(hasSetTextAction()).fetchSemanticsNode()
            .config[SemanticsProperties.EditableText].text

    @Test
    fun dictationUploadsTheRecordingAndInsertsTheText() {
        launchApp()
        shell("pm grant ${context.packageName} android.permission.RECORD_AUDIO")
        pairAndLand()
        waitForTag("chat-dictate")
        clickTag("chat-dictate")
        waitForTag("chat-dictate-elapsed")
        shot("recording")
        clickTag("chat-dictate")
        eventually("the transcribe request") { fake.requestsTo("POST", "/v1/voice/transcribe").isNotEmpty() }
        eventually("the dictated text in the input") { editableText().contains("dictated words") }
        shot("dictated")
        assertEquals(1, fake.requestsTo("POST", "/v1/voice/transcribe").size)
    }

    @Test
    fun readAloudTurnsIntoStopAndBack() {
        fake.say("user", "Tell me about storage")
        fake.say("assistant", "Here is your answer. It is short.")
        launchApp()
        pairAndLand()
        waitForTag("read-aloud")
        clickTag("read-aloud")
        eventually("the speak request") { fake.requestsTo("POST", "/v1/voice/speak").isNotEmpty() }
        eventually("the button becomes Stop") { hasText("Stop") }
        shot("reading")
        assertTrue("speak was asked for audio", fake.requestsTo("POST", "/v1/voice/speak").isNotEmpty())
        clickTag("read-aloud")
        eventually("the button becomes Read aloud again") { hasText("Read aloud") }
        shot("stopped")
    }

    /**
     * P14: the 0.3.6 crash (cr-04ba47eb10b1) happened here — leaving Chat while Read aloud was
     * active disposed the voice session and its close() did TLS network I/O on the main thread.
     * Test Lab's fake is plain HTTP, so the TLS write itself cannot be reproduced here (the JVM
     * regression test ClientCloseNoMainThreadIOTest covers that); this guards the navigation path:
     * leave Chat mid-read, land on Rules, come back, and the app must be alive with a fresh session.
     */
    @Test
    fun leavingChatDuringReadAloudAndComingBackDoesNotCrash() {
        fake.say("user", "Tell me about storage")
        fake.say("assistant", "Here is your answer. It is short.")
        launchApp()
        pairAndLand()
        waitForTag("read-aloud")
        clickTag("read-aloud")
        eventually("the speak request") { fake.requestsTo("POST", "/v1/voice/speak").isNotEmpty() }
        eventually("the button becomes Stop") { hasText("Stop") }
        // Leave Chat mid-read: the dispose runs the session's close().
        openTab("Rules")
        waitForText("Photo window")
        shot("rules-after-leaving-chat")
        openTab("Chat")
        eventually("back on Chat with a fresh, idle Read aloud") { hasText("Read aloud") }
        shot("chat-again")
    }
}
