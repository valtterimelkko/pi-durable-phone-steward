package com.example.phonesteward.net

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import com.example.phonesteward.logic.CrashDevice
import com.example.phonesteward.logic.CrashReport
import com.example.phonesteward.logic.CrashUpload

/** A5: POST /v1/crash, with the bearer only when paired, and the keep-or-drop mapping. */
class CrashClientTest {

    private val server = MockWebServer().also { it.start() }
    private val base = server.url("/").toString()

    @After
    fun tearDown() = server.shutdown()

    private val report = CrashReport(
        requestId = "crash-1", appVersion = "0.2.1", crashedAtMs = 1L, thread = "main",
        exception = "java.lang.StackOverflowError", stackTrace = "t", device = CrashDevice("IN2023", 33),
    )

    @Test
    fun `posts the report to v1 crash without a token before pairing`() {
        server.enqueue(MockResponse().setBody("""{"accepted":true,"reportId":"cr-1"}"""))
        assertEquals(CrashUpload.SENT, CrashClient(base, token = null).send(report))
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/v1/crash", request.path)
        assertNull(request.getHeader("Authorization"))
        val body = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
        assertEquals("crash-1", body["requestId"]!!.jsonPrimitive.content)
    }

    @Test
    fun `carries the bearer once paired`() {
        server.enqueue(MockResponse().setBody("""{"accepted":true,"reportId":"cr-1"}"""))
        CrashClient(base, token = "tok").send(report)
        assertEquals("Bearer tok", server.takeRequest().getHeader("Authorization"))
    }

    @Test
    fun `maps answers to keep or drop`() {
        val cases = listOf(
            200 to CrashUpload.SENT,
            400 to CrashUpload.REFUSED,
            409 to CrashUpload.REFUSED,
            413 to CrashUpload.REFUSED,
            429 to CrashUpload.RETRY_LATER,
            503 to CrashUpload.RETRY_LATER,
        )
        for ((status, expected) in cases) {
            server.enqueue(MockResponse().setResponseCode(status).setBody("{}"))
            assertEquals("HTTP $status", expected, CrashClient(base, token = null).send(report))
        }
    }

    @Test
    fun `an unreachable server means retry later`() {
        server.shutdown()
        assertEquals(CrashUpload.RETRY_LATER, CrashClient(base, token = null).send(report))
    }
}
