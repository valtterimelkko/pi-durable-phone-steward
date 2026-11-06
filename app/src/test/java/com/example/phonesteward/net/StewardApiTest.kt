package com.example.phonesteward.net

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * MockWebServer coverage of every /v1 endpoint: happy paths, the bearer
 * header everywhere except pair, every listed error code, Retry-After on
 * 429/503, and the requestId in every mutating body.
 */
class StewardApiTest {

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

    private fun enqueueJson(json: String, status: Int = 200, headers: Map<String, String> = emptyMap()) {
        val response = MockResponse()
            .setResponseCode(status)
            .setBody(json)
        headers.forEach { (k, v) -> response.setHeader(k, v) }
        server.enqueue(response)
    }

    private fun errorJson(code: String, message: String = "human text", retry: Int? = null): String {
        val retryField = retry?.let { ", \"retryAfterSeconds\": $it" } ?: ""
        return """{"error": {"code": "$code", "message": "$message"$retryField}}"""
    }

    @Test
    fun `pair posts code and device name without a bearer header`() {
        enqueueJson("""{"deviceId": "dev_1", "token": "tok"}""")
        val res = StewardApi(server.url("/").toString().trimEnd('/')).pair("k7q4-9mzd", "Example Phone")
        assertEquals("dev_1", res.deviceId)
        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/v1/pair", recorded.path)
        assertNull(recorded.getHeader("Authorization"))
        val body = recorded.body.readUtf8()
        assertTrue(body.contains("\"code\":\"k7q4-9mzd\""))
        assertTrue(body.contains("\"deviceName\":\"Example Phone\""))
    }

    @Test
    fun `pair sends no Authorization header even with a token provider set (fix 01-8)`() {
        enqueueJson("""{"deviceId": "dev_1", "token": "tok"}""")
        val res = api.pair("K7Q4-9MZD", "Example Phone")
        assertEquals("dev_1", res.deviceId)
        val recorded = server.takeRequest()
        assertNull("pairing must never carry a bearer token", recorded.getHeader("Authorization"))
    }

    @Test
    fun `fetchJobs sends bearer and wait parameter`() {
        enqueueJson("""{"jobs": [{"jobId": "pj-1", "kind": "storage_overview", "params": {}, "createdAtMs": 1, "expiresAtMs": 2}]}""")
        val jobs = api.fetchJobs(waitSeconds = 25)
        assertEquals(1, jobs.size)
        assertEquals("storage_overview", jobs.single().kind)
        val recorded = server.takeRequest()
        assertEquals("GET", recorded.method)
        assertEquals("/v1/jobs?wait=25", recorded.path)
        assertEquals("Bearer tok-1", recorded.getHeader("Authorization"))
    }

    @Test
    fun `reportResult posts the full result body`() {
        enqueueJson("""{"accepted": true, "duplicate": false}""")
        val res = api.reportResult(
            "pj-1",
            JobResultRequest(
                requestId = "res-pj-1",
                status = "ok",
                data = buildJsonObject { put("totalBytes", 5) },
                error = null,
                deviceTimeMs = 123L,
                appVersion = "0.2.0",
            ),
        )
        assertTrue(res.accepted)
        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/v1/jobs/pj-1/result", recorded.path)
        val body = recorded.body.readUtf8()
        assertTrue(body.contains("\"requestId\":\"res-pj-1\""))
        assertTrue(body.contains("\"status\":\"ok\""))
        assertTrue(body.contains("\"deviceTimeMs\":123"))
        assertTrue(body.contains("\"appVersion\":\"0.2.0\""))
    }

    @Test
    fun `listProposals supports the status filter`() {
        enqueueJson("""{"proposals": []}""")
        api.listProposals("open")
        assertEquals("/v1/proposals?status=open", server.takeRequest().path)
        enqueueJson("""{"proposals": []}""")
        api.listProposals()
        assertEquals("/v1/proposals", server.takeRequest().path)
    }

    @Test
    fun `decision execution rules and chat endpoints hit their paths`() {
        enqueueJson("""{"decisionId": "dc-1", "approvedItemIds": ["it-1"], "expiresAtMs": 9}""")
        assertEquals("dc-1", api.decide("pr-1", DecisionRequest("req-1", "approve", listOf("it-1"))).decisionId)
        assertEquals("/v1/proposals/pr-1/decision", server.takeRequest().path)

        enqueueJson("""{"valid": true, "proposalId": "pr-1", "approvedItemIds": ["it-1"], "expiresAtMs": 9}""")
        assertTrue(api.checkDecision("dc-1").valid)
        assertEquals("/v1/decisions/dc-1", server.takeRequest().path)

        enqueueJson("""{"accepted": true}""")
        assertTrue(
            api.reportExecution("pr-1", ExecutionRequest("req-2", "dc-1", emptyList())).accepted,
        )
        assertEquals("/v1/proposals/pr-1/execution", server.takeRequest().path)

        enqueueJson("""{"ruleVersion": 3, "photoWindow": {"enabled": false, "reminderDays": 90, "cutoffDays": 120, "autoMode": false, "folders": ["DCIM/Camera/"], "keepMarker": "Keep"}, "batchesRunOneTap": 0}""")
        assertEquals(3, api.getRules().ruleVersion)
        assertEquals("/v1/rules", server.takeRequest().path)

        enqueueJson("""{"ruleVersion": 4, "photoWindow": {"enabled": true, "reminderDays": 90, "cutoffDays": 120, "autoMode": false, "folders": ["DCIM/Camera/"], "keepMarker": "Keep"}, "batchesRunOneTap": 0}""")
        val put = RulesPutRequest(
            "req-3", 3,
            PhotoWindow(enabled = true, folders = listOf("DCIM/Camera/")),
        )
        assertEquals(4, api.putRules(put).ruleVersion)
        val putRecorded = server.takeRequest()
        assertEquals("PUT", putRecorded.method)
        assertEquals("/v1/rules", putRecorded.path)
        assertTrue(putRecorded.body.readUtf8().contains("\"requestId\":\"req-3\""))

        enqueueJson("""{"accepted": true, "conversationId": "main"}""")
        assertTrue(api.sendChat(ChatSendRequest("req-4", "hello")).accepted)
        assertEquals("/v1/chat", server.takeRequest().path)

        enqueueJson("""[{"id": 3, "type": "heartbeat", "data": {}}]""")
        assertEquals(3L, api.chatHistory().single().id)
        assertEquals("/v1/chat/history?limit=50", server.takeRequest().path)

        enqueueJson("""[{"id": 5, "type": "message", "data": {}}]""")
        api.chatHistory(limit = 10, before = 6)
        assertEquals("/v1/chat/history?limit=10&before=6", server.takeRequest().path)

        enqueueJson("""{"accepted": true}""")
        assertTrue(
            api.postDeviceStatus(
                DeviceStatusRequest("req-5", "0.2.0", DevicePermissions(), 0),
            ).accepted,
        )
        assertEquals("/v1/device/status", server.takeRequest().path)

        enqueueJson("""{"serverTimeMs": 1, "pendingJobs": 0, "openProposals": 0, "lastSnapshotMs": 0}""")
        assertEquals(1, api.serverStatus().serverTimeMs)
        assertEquals("/v1/status", server.takeRequest().path)

        enqueueJson("""{"conversationId": "main", "model": {"provider": "zai", "id": "glm-5.3-flash"}, "allowlist": [], "usage": {"inputTokens": 0, "outputTokens": 0}}""")
        assertEquals("zai", api.agentSettings().model.provider)
        assertEquals("/v1/agent/settings", server.takeRequest().path)
    }

    @Test
    fun `chat history accepts the object envelope too`() {
        enqueueJson("""{"events": [{"id": 2, "type": "run", "data": {"status": "ended"}}]}""")
        val events = api.chatHistory()
        assertEquals("run", events.single().type)
    }

    @Test
    fun `every listed error code maps to ApiException with its code and status`() {
        data class Case(val status: Int, val code: String)
        val cases = listOf(
            Case(400, "BAD_REQUEST"),
            Case(401, "UNAUTHORIZED"),
            Case(401, "TOKEN_REVOKED"),
            Case(404, "NOT_FOUND"),
            Case(429, "RATE_LIMITED"),
            Case(501, "NOT_IMPLEMENTED"),
            Case(413, "BODY_TOO_LARGE"),
            Case(409, "RESULT_CONFLICT"),
            Case(409, "JOB_EXPIRED"),
            Case(409, "PROPOSAL_NOT_OPEN"),
            Case(409, "RULE_VERSION_CONFLICT"),
            Case(409, "AUTO_MODE_NOT_YET"),
            Case(409, "REQUEST_ID_CONFLICT"),
            Case(400, "PAIRING_CODE_INVALID"),
        )
        for (case in cases) {
            enqueueJson(errorJson(case.code), status = case.status)
            val e = assertThrows(ApiException::class.java) { api.fetchJobs() }
            assertEquals("status ${case.status}", case.status, e.httpStatus)
            assertEquals("code ${case.code}", case.code, e.code)
            assertNull("no retry unless 429/503", e.retryAfterSeconds)
        }
    }

    @Test
    fun `429 and 503 surface Retry-After from header and body`() {
        enqueueJson(errorJson("RATE_LIMITED", retry = 42), status = 429, headers = mapOf("Retry-After" to "30"))
        val e1 = assertThrows(ApiException::class.java) { api.fetchJobs() }
        assertEquals(30, e1.retryAfterSeconds) // header wins
        enqueueJson(errorJson("UNAVAILABLE", retry = 42), status = 503)
        val e2 = assertThrows(ApiException::class.java) { api.fetchJobs() }
        assertEquals(42, e2.retryAfterSeconds) // body fallback
    }

    @Test
    fun `a non-JSON gateway error becomes an UNKNOWN protocol error with its status`() {
        server.enqueue(MockResponse().setResponseCode(502).setBody("<html>bad gateway</html>"))
        val e = assertThrows(ApiException::class.java) { api.fetchJobs() }
        assertEquals(502, e.httpStatus)
        assertEquals("UNKNOWN", e.code)
    }

    @Test
    fun `a connection failure raises TransportException`() {
        api.close()
        server.shutdown()
        val dead = StewardApi("http://127.0.0.1:1", tokenProvider = { "t" })
        try {
            assertThrows(TransportException::class.java) { dead.fetchJobs() }
        } finally {
            dead.close()
        }
    }

    @Test
    fun `request ids in bodies stay within 1-128 characters`() {
        enqueueJson("""{"accepted": true, "duplicate": false}""")
        api.reportResult(
            "pj-x",
            JobResultRequest(
                requestId = RequestIds.forJobResult("pj-x"),
                status = "ok",
                data = null,
                error = null,
                deviceTimeMs = 1,
                appVersion = "0.2.0",
            ),
        )
        server.takeRequest()
        val id = RequestIds.forJobResult("pj-" + "z".repeat(150))
        assertTrue(RequestIds.isValid(id))
    }
}
