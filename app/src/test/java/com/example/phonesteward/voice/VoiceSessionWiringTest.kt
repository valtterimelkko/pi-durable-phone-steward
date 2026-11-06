package com.example.phonesteward.voice

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import com.example.phonesteward.android.AppGraph
import com.example.phonesteward.logic.IdentityStore
import com.example.phonesteward.logic.PairedIdentity

/**
 * A15.5/A15.6 wiring: the real [VoiceSession] over a real [VoiceApi] and OkHttp, pointed at a
 * MockWebServer through a paired identity. This is the JVM stand-in for the Test Lab flow; it
 * proves the base URL, the bearer token and the route paths, which the Test Lab fakes cannot.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class VoiceSessionWiringTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private lateinit var server: MockWebServer

    private class MemoryIdentityStore(var identity: PairedIdentity?) : IdentityStore {
        override fun read() = identity
        override fun write(identity: PairedIdentity) { this.identity = identity }
        override fun clear() { identity = null }
    }

    private class FakeRecorder : Recorder {
        override val contentType: String = "audio/ogg"
        override fun start(): Boolean = true
        override fun stop(): ByteArray = byteArrayOf(1, 2, 3, 4)
        override fun cancel() {}
    }

    private class FakePlayer : SpeechPlayer {
        val played = mutableListOf<String>()
        override suspend fun play(audio: ByteArray, contentType: String) { played.add(contentType) }
        override fun stop() {}
    }

    private lateinit var player: FakePlayer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.path.orEmpty().startsWith("/v1/voice/transcribe") ->
                    MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json")
                        .setBody("""{"text":"dictated words"}""")
                request.path.orEmpty().startsWith("/v1/voice/speak") ->
                    MockResponse().setResponseCode(200).setHeader("Content-Type", "audio/wav").setBody("RIFF0000")
                else -> MockResponse().setResponseCode(404)
            }
        }
        server.start()
        AppGraph.identityStoreFactory = { MemoryIdentityStore(PairedIdentity(server.url("/").toString().trimEnd('/'), "tok-voice")) }
        AppGraph.resetForTest()
        player = FakePlayer()
        VoiceSession.recorderFactory = { FakeRecorder() }
        VoiceSession.playerFactory = { player }
    }

    @After
    fun tearDown() {
        VoiceSession.recorderFactory = { AndroidRecorder(it) }
        VoiceSession.playerFactory = { AndroidSpeechPlayer(it) }
        server.shutdown()
        AppGraph.resetForTest()
    }

    @Test
    fun dictationUploadsTheRecordingAndReportsTheText() = runBlocking {
        val got = CompletableDeferred<String>()
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val session = VoiceSession.open(context, scope, onDictatedText = { got.complete(it) }, io = Dispatchers.Unconfined)
        session.dictation.start()
        session.dictation.stop()
        assertEquals("dictated words", withTimeout(10_000) { got.await() })
        val recorded = server.takeRequest(10, TimeUnit.SECONDS)!!
        assertEquals("/v1/voice/transcribe", recorded.path)
        assertEquals("Bearer tok-voice", recorded.getHeader("Authorization"))
        assertEquals("audio/ogg", recorded.getHeader("Content-Type"))
        session.close()
    }

    @Test
    fun readAloudFetchesAndPlaysTheRound() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val session = VoiceSession.open(context, scope, onDictatedText = {}, io = Dispatchers.Unconfined)
        session.readAloud.toggle("Here is your answer. It is short.")
        val recorded = server.takeRequest(10, TimeUnit.SECONDS)!!
        assertEquals("/v1/voice/speak", recorded.path)
        assertEquals("Bearer tok-voice", recorded.getHeader("Authorization"))
        assertEquals(listOf("audio/wav", "audio/wav"), player.played)
        session.close()
    }
}
