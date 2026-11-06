package com.example.phonesteward.screen

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/** Which refusals of the live routes end a session (protocol A10.3). */
class LiveApiTest {

    private lateinit var server: MockWebServer
    private lateinit var api: LiveApi
    private val logged = mutableListOf<String>()

    @Before
    fun setUp() {
        server = MockWebServer().also { it.start() }
        api = LiveApi(server.url("/").toString().trimEnd('/'), tokenProvider = { "tok" }, log = { logged += it })
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun error(status: Int, code: String) =
        MockResponse().setResponseCode(status).setBody("""{"error":{"code":"$code","message":"x"}}""")

    private val answer = StepAnswerOut("ok")

    @Test
    fun `a late answer to a closed step keeps the session`() {
        server.enqueue(error(409, "STEP_NOT_OPEN"))
        api.answer("ls-1", "s1", answer) // no exception: the session goes on
        assertTrue(logged.single().contains("STEP_NOT_OPEN"))
    }

    @Test
    fun `an accepted late answer is just a success`() {
        server.enqueue(MockResponse().setBody("""{"accepted":true,"late":true}"""))
        api.answer("ls-1", "s1", answer)
    }

    @Test
    fun `409 SESSION_ENDED on an answer ends the session`() {
        server.enqueue(error(409, "SESSION_ENDED"))
        try { api.answer("ls-1", "s1", answer); fail("expected SessionGoneException") } catch (_: SessionGoneException) {}
    }

    @Test
    fun `404 and any other 409 on an answer end the session`() {
        server.enqueue(error(404, "NOT_FOUND"))
        try { api.answer("ls-1", "s1", answer); fail() } catch (_: SessionGoneException) {}
        server.enqueue(error(409, "SOMETHING_ELSE"))
        try { api.answer("ls-1", "s1", answer); fail() } catch (_: SessionGoneException) {}
        server.enqueue(MockResponse().setResponseCode(409).setBody("not json"))
        try { api.answer("ls-1", "s1", answer); fail() } catch (_: SessionGoneException) {}
    }

    @Test
    fun `404 and 409 on next still end the session`() {
        server.enqueue(error(404, "NOT_FOUND"))
        try { api.next("ls-1", 1); fail() } catch (_: SessionGoneException) {}
        server.enqueue(error(409, "SESSION_ENDED"))
        try { api.next("ls-1", 1); fail() } catch (_: SessionGoneException) {}
    }

    @Test
    fun `the runner keeps polling after a late answer`() {
        // answer() returns normally, so the runner's post() reports success and the loop goes on to the next poll.
        server.enqueue(error(409, "STEP_NOT_OPEN"))
        api.answer("ls-1", "s1", answer)
        server.enqueue(MockResponse().setBody("""{"step":null}"""))
        assertEquals(LiveNext.Idle, api.next("ls-1", 1))
    }
}
