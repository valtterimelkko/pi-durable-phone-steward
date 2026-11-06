package com.example.phonesteward.logic.jobs

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import com.example.phonesteward.net.DevicePermissions
import com.example.phonesteward.net.StewardApi

class DeviceStatusReporterTest {

    private lateinit var server: MockWebServer
    private lateinit var api: StewardApi

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        api = StewardApi(server.url("/").toString().trimEnd('/'), tokenProvider = { "tok" })
    }

    @After
    fun tearDown() {
        api.close()
        server.shutdown()
    }

    @Test
    fun `posts the permission snapshot, app version and last worker run`() {
        server.enqueue(MockResponse().setBody("""{"accepted": true}"""))
        val reporter = DeviceStatusReporter(
            api = api,
            appVersion = "0.2.0",
            permissions = {
                DeviceStatusReporter.snapshot(
                    usageAccess = true, allFiles = true, mediaRead = true,
                    manageMedia = false, batteryUnrestricted = true,
                )
            },
            lastWorkerRunMs = { 42L },
            requestId = { "status-fixed" },
        )
        assertTrue(reporter.send())
        val recorded = server.takeRequest()
        assertEquals("/v1/device/status", recorded.path)
        val body = recorded.body.readUtf8()
        assertTrue(body.contains("\"requestId\":\"status-fixed\""))
        assertTrue(body.contains("\"appVersion\":\"0.2.0\""))
        assertTrue(body.contains("\"usageAccess\":true"))
        assertTrue(body.contains("\"manageMedia\":false"))
        assertTrue(body.contains("\"batteryUnrestricted\":true"))
        assertTrue(body.contains("\"lastWorkerRunMs\":42"))
    }

    @Test
    fun `N29 an identical status inside the coalescing window is posted once`() {
        server.enqueue(MockResponse().setBody("""{"accepted": true}"""))
        server.enqueue(MockResponse().setBody("""{"accepted": true}"""))
        val gate = com.example.phonesteward.logic.StatusPostCoalescer(10_000) { 1_000L }
        val reporter = DeviceStatusReporter(
            api = api,
            appVersion = "0.3.0",
            permissions = { DevicePermissions(usageAccess = true) },
            lastWorkerRunMs = { 0L },
            gate = gate,
        )
        assertTrue(reporter.send())
        assertTrue("held back, not an error", !reporter.send())
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `A10 the status carries the Shizuku state and the Steward trash`() {
        server.enqueue(MockResponse().setBody("""{"accepted": true}"""))
        val reporter = DeviceStatusReporter(
            api = api,
            appVersion = "0.3.0",
            permissions = { DevicePermissions(shizuku = "not_running") },
            lastWorkerRunMs = { 0L },
            stewardTrash = { com.example.phonesteward.net.StewardTrashStatus(bytes = 2_600_000_000L, oldestMs = 1_791_000_000_000L) },
        )
        assertTrue(reporter.send())
        val body = server.takeRequest().body.readUtf8()
        assertTrue(body, body.contains("\"shizuku\":\"not_running\""))
        assertTrue(body, body.contains("\"stewardTrash\":{\"bytes\":2600000000,\"oldestMs\":1791000000000}"))
    }

    @Test
    fun `A10 without trash the status still says so with an empty object`() {
        server.enqueue(MockResponse().setBody("""{"accepted": true}"""))
        val reporter = DeviceStatusReporter(
            api = api, appVersion = "0.3.0", permissions = { DevicePermissions() }, lastWorkerRunMs = { 0L },
        )
        reporter.send()
        val body = server.takeRequest().body.readUtf8()
        assertTrue(body, body.contains("\"stewardTrash\":{\"bytes\":0,\"oldestMs\":null}"))
        assertTrue(body, body.contains("\"shizuku\":\"absent\""))
    }
}
