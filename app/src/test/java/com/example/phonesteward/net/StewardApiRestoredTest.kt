package com.example.phonesteward.net

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** A11.3: `POST /v1/files/restored` — a fresh requestId, the proposal id and the items that went back. */
class StewardApiRestoredTest {

    private lateinit var server: MockWebServer
    private lateinit var api: StewardApi

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        api = StewardApi(server.url("/").toString().trimEnd('/'), tokenProvider = { "tok-1" })
    }

    @After
    fun tearDown() {
        api.close()
        server.shutdown()
    }

    @Test
    fun `reportRestored posts the requestId, proposal and items with the bearer token`() {
        server.enqueue(
            MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json")
                .setBody("""{"recorded":2}"""),
        )
        val res = api.reportRestored(
            RestoredRequest(
                requestId = "req-1a2b",
                proposalId = "pr-1",
                items = listOf(RestoredItem("OfflineMaps/230606", 1_000_000_000), RestoredItem("Download/old.apk", 300)),
            ),
        )
        assertEquals(2, res.recorded)
        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/v1/files/restored", recorded.path)
        assertEquals("Bearer tok-1", recorded.getHeader("Authorization"))
        val body = recorded.body.readUtf8()
        assertTrue(body, body.contains("\"requestId\":\"req-1a2b\""))
        assertTrue(body, body.contains("\"proposalId\":\"pr-1\""))
        assertTrue(body, body.contains("\"path\":\"OfflineMaps/230606\""))
        assertTrue(body, body.contains("\"bytes\":1000000000"))
        assertTrue(body, body.contains("\"bytes\":300"))
    }

    @Test
    fun `an unknown proposal is still recorded`() {
        server.enqueue(
            MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json")
                .setBody("""{"recorded":1}"""),
        )
        val res = api.reportRestored(RestoredRequest("req-9", "pr-gone", listOf(RestoredItem("Dl/a.bin", 10))))
        assertEquals(1, res.recorded)
    }

    @Test
    fun `a bad path answer arrives as an ApiException`() {
        server.enqueue(
            MockResponse().setResponseCode(400).setHeader("Content-Type", "application/json")
                .setBody("""{"error":{"code":"BAD_PATH","message":"the path is not safe"}}"""),
        )
        val e = runCatching {
            api.reportRestored(RestoredRequest("req-9", "pr-1", listOf(RestoredItem("../etc", 1))))
        }.exceptionOrNull()
        assertTrue(e.toString(), e is ApiException)
        assertEquals("BAD_PATH", (e as ApiException).code)
        assertEquals(400, e.httpStatus)
        assertNull(server.takeRequest().getHeader("X-Other"))
    }
}
