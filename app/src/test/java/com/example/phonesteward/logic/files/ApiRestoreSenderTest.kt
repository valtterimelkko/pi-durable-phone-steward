package com.example.phonesteward.logic.files

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import com.example.phonesteward.net.RestoredItem
import com.example.phonesteward.net.StewardApi

/** A11.3: what one restore report's POST answer means. */
class ApiRestoreSenderTest {

    private lateinit var server: MockWebServer
    private lateinit var api: StewardApi
    private lateinit var sender: ApiRestoreSender

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        api = StewardApi(server.url("/").toString().trimEnd('/'), tokenProvider = { "tok" })
        sender = ApiRestoreSender { api }
    }

    @After
    fun tearDown() {
        api.close()
        server.shutdown()
    }

    private fun report() = RestoredReport("req-1", "pr-1", listOf(RestoredItem("Dl/a.bin", 100)), 5L)

    private fun answer(status: Int, body: String) {
        server.enqueue(MockResponse().setResponseCode(status).setHeader("Content-Type", "application/json").setBody(body))
    }

    @Test
    fun `an accepted report is sent and drops from the outbox`() {
        answer(200, """{"recorded":1}""")
        assertEquals(RestoreUpload.SENT, sender.invoke(report()))
        assertEquals("/v1/files/restored", server.takeRequest().path)
    }

    @Test
    fun `a body the server refuses for good is dropped`() {
        answer(400, """{"error":{"code":"BAD_PATH","message":"the path is not safe"}}""")
        assertEquals(RestoreUpload.REFUSED, sender.invoke(report()))
    }

    @Test
    fun `a server error waits for the next sync`() {
        answer(503, """{"error":{"code":"UNAVAILABLE","message":"try later"}}""")
        assertEquals(RestoreUpload.RETRY_LATER, sender.invoke(report()))
    }

    @Test
    fun `an unreachable server waits for the next sync`() {
        server.shutdown()
        assertEquals(RestoreUpload.RETRY_LATER, sender.invoke(report()))
    }
}
