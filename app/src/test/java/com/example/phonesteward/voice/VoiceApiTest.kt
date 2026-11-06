package com.example.phonesteward.voice

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** A15.5/A15.6 app half: the transcribe and speak client against a MockWebServer. */
class VoiceApiTest {

    private lateinit var server: MockWebServer
    private lateinit var api: VoiceApi

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        api = VoiceApi(server.url("/").toString().trimEnd('/'), tokenProvider = { "tok-voice" })
    }

    @After
    fun tearDown() {
        api.close()
        server.shutdown()
    }

    @Test
    fun `transcribe posts the audio with its content type and bearer, and returns the text`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"text":"hello there"}"""))
        val text = api.transcribe(byteArrayOf(1, 2, 3), "audio/ogg")
        assertEquals("hello there", text)
        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/v1/voice/transcribe", recorded.path)
        assertEquals("Bearer tok-voice", recorded.getHeader("Authorization"))
        assertEquals("audio/ogg", recorded.getHeader("Content-Type"))
        assertArrayEquals(byteArrayOf(1, 2, 3), recorded.body.readByteArray())
    }

    @Test
    fun `speak posts the text as JSON and returns the audio and its content type`() {
        server.enqueue(MockResponse().setResponseCode(200).setHeader("Content-Type", "audio/wav").setBody("RIFFdata"))
        val audio = api.speak("Hello there.")
        assertEquals("audio/wav", audio.contentType)
        assertEquals("RIFFdata", String(audio.bytes))
        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/v1/voice/speak", recorded.path)
        assertEquals("Bearer tok-voice", recorded.getHeader("Authorization"))
        assertTrue(recorded.body.readUtf8().contains("\"text\":\"Hello there.\""))
    }

    @Test
    fun `maps a 503 to VOICE_UNAVAILABLE and a 502 to VOICE_FAILED`() {
        server.enqueue(MockResponse().setResponseCode(503).setBody("""{"error":{"code":"VOICE_UNAVAILABLE"}}"""))
        val unavailable = assertThrows(VoiceException::class.java) { api.transcribe(byteArrayOf(1), "audio/ogg") }
        assertEquals("VOICE_UNAVAILABLE", unavailable.code)

        server.enqueue(MockResponse().setResponseCode(502).setBody("""{"error":{"code":"VOICE_FAILED"}}"""))
        val failed = assertThrows(VoiceException::class.java) { api.speak("Hi.") }
        assertEquals("VOICE_FAILED", failed.code)
    }

    @Test
    fun `a transport failure is a VoiceException with code TRANSPORT`() {
        server.shutdown()
        val error = assertThrows(VoiceException::class.java) { api.transcribe(byteArrayOf(1), "audio/ogg") }
        assertEquals("TRANSPORT", error.code)
    }
}
