package com.example.phonesteward.net

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** The A13.2 routes, as the app's client speaks them: the owner's answer and the pending card. */
class StewardApiPhoneUseTest {

    private lateinit var server: MockWebServer
    private lateinit var api: StewardApi

    @Before
    fun setUp() {
        server = MockWebServer().also { it.start() }
        api = StewardApi(server.url("/").toString().trimEnd('/'), tokenProvider = { "tok" })
    }

    @After
    fun tearDown() {
        api.close()
        server.shutdown()
    }

    @Test
    fun `an allow posts the exact A13_2 body and reads the session`() {
        server.enqueue(MockResponse().setBody("""{"status":"allowed","sessionId":"ls-77"}"""))
        val r = api.answerPhoneUse("pu-1", true)
        val rec = server.takeRequest()
        assertEquals("POST", rec.method)
        assertEquals("/v1/live/requests/pu-1/answer", rec.path)
        assertEquals("""{"requestId":"pu-1","allow":true}""", rec.body.readUtf8())
        assertEquals("allowed", r.status)
        assertEquals("ls-77", r.sessionId)
    }

    @Test
    fun `a decline posts allow false and reads the status`() {
        server.enqueue(MockResponse().setBody("""{"status":"declined"}"""))
        val r = api.answerPhoneUse("pu-2", false)
        assertEquals("declined", r.status)
        assertNull(r.sessionId)
    }

    @Test
    fun `a not-pending answer throws with the code and the current status from the body`() {
        server.enqueue(
            MockResponse().setResponseCode(409)
                .setBody("""{"status":"expired","error":{"code":"REQUEST_NOT_PENDING","message":"this request is expired, not pending"}}"""),
        )
        val e = runCatching { api.answerPhoneUse("pu-3", true) }.exceptionOrNull()
        assertTrue("an ApiException: $e", e is ApiException)
        assertEquals(409, (e as ApiException).httpStatus)
        assertEquals("REQUEST_NOT_PENDING", e.code)
        assertEquals("expired", e.field("status"))
    }

    @Test
    fun `a live-session conflict carries its code`() {
        server.enqueue(
            MockResponse().setResponseCode(409)
                .setBody("""{"error":{"code":"LIVE_SESSION_ACTIVE","message":"a phone session is already running"}}"""),
        )
        val e = runCatching { api.answerPhoneUse("pu-4", true) }.exceptionOrNull() as ApiException
        assertEquals("LIVE_SESSION_ACTIVE", e.code)
        assertNull(e.field("status"))
    }

    @Test
    fun `the pending route answers the one request or null`() {
        server.enqueue(
            MockResponse().setBody(
                """{"request":{"id":"pu-9","task":"Clear a cache","why":"It is large","status":"pending","createdAtMs":123,"sessionId":null}}""",
            ),
        )
        val r = api.pendingPhoneUse()
        assertEquals("/v1/live/requests/pending", server.takeRequest().path)
        assertEquals("pu-9", r.request?.id)
        assertEquals("Clear a cache", r.request?.task)
        assertNull(r.request?.sessionId)

        server.enqueue(MockResponse().setBody("""{"request":null}"""))
        assertNull(api.pendingPhoneUse().request)
    }
}
