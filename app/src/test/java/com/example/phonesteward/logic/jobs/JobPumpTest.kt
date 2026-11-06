package com.example.phonesteward.logic.jobs

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import com.example.phonesteward.net.Job
import com.example.phonesteward.net.JobResultRequest
import com.example.phonesteward.net.RequestIds
import com.example.phonesteward.net.StewardApi

/**
 * Pump cycles against MockWebServer: run-and-report, re-post of stored
 * results, in-flight skip, the final 409 rule, and transient retries.
 */
class JobPumpTest {

    private lateinit var server: MockWebServer
    private lateinit var api: StewardApi
    private lateinit var store: InMemoryDoneStore

    private val okData = buildJsonObject { put("totalBytes", 100) }

    private fun executor(counting: MutableList<String>): JobDispatcher = JobDispatcher(
        mapOf(
            "storage_overview" to JobExecutor { j ->
                counting += j.jobId
                JobOutcome.Ok(okData)
            },
        ),
    )

    private val clock = arrayOf(1_000L)

    private fun pump(tracker: JobTracker, counting: MutableList<String>) = JobPump(
        api = api,
        tracker = tracker,
        dispatcher = executor(counting),
        appVersion = "0.2.0",
        nowMillis = { clock[0] },
    )

    private fun jobJson(id: String) =
        """{"jobId": "$id", "kind": "storage_overview", "params": {}, "createdAtMs": 1, "expiresAtMs": 9}"""

    private fun queuedJob(vararg ids: String): String =
        """{"jobs": [${ids.joinToString(",") { jobJson(it) }}]}"""

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        api = StewardApi(server.url("/").toString().trimEnd('/'), tokenProvider = { "tok" })
        store = InMemoryDoneStore()
    }

    @After
    fun tearDown() {
        api.close()
        server.shutdown()
    }

    private fun okAccepted(duplicate: Boolean = false) =
        MockResponse().setBody("""{"accepted": true, "duplicate": $duplicate}""")

    private fun conflict() =
        MockResponse().setResponseCode(409).setBody("""{"error": {"code": "RESULT_CONFLICT", "message": "one job, one result"}}""")

    private fun notFound() =
        MockResponse().setResponseCode(404).setBody("""{"error": {"code": "NOT_FOUND", "message": "gone"}}""")

    @Test
    fun `run executes each job once and posts res- requestId bodies`() {
        val ran = mutableListOf<String>()
        // One fetch delivers every queued job, oldest first (protocol §3.1).
        server.enqueue(MockResponse().setBody(queuedJob("pj-1", "pj-2")))
        server.enqueue(okAccepted())
        server.enqueue(okAccepted())

        val summary = pump(JobTracker(store), ran).pollOnce()

        assertEquals(2, summary.ran)
        assertEquals(listOf("pj-1", "pj-2"), ran)
        val first = server.takeRequest()
        assertEquals("/v1/jobs", first.path)
        val report = server.takeRequest()
        assertEquals("/v1/jobs/pj-1/result", report.path)
        val reportBody = report.body.readUtf8()
        assertTrue(reportBody.contains("\"requestId\":\"res-pj-1\""))
        assertTrue(reportBody.contains("\"status\":\"ok\""))
        server.takeRequest() // second report
    }

    @Test
    fun `a done job re-posts its stored result and never re-runs`() {
        val ran = mutableListOf<String>()
        val tracker = JobTracker(store)
        tracker.onStart("pj-1")
        tracker.onResultReady(
            "pj-1",
            JobResultRequest("res-pj-1", "ok", okData, null, deviceTimeMs = 5, appVersion = "0.2.0"),
        )
        server.enqueue(MockResponse().setBody(queuedJob("pj-1")))
        server.enqueue(okAccepted(duplicate = true))

        val summary = pump(tracker, ran).pollOnce()

        assertEquals(0, summary.ran)
        assertEquals(1, summary.reposted)
        assertTrue(ran.isEmpty())
        server.takeRequest()
        val body = server.takeRequest().body.readUtf8()
        assertTrue(body.contains("\"requestId\":\"res-pj-1\""))
    }

    @Test
    fun `a 409 RESULT_CONFLICT is final and never re-posted`() {
        val ran = mutableListOf<String>()
        val tracker = JobTracker(store)
        server.enqueue(MockResponse().setBody(queuedJob("pj-1")))
        server.enqueue(conflict())

        var summary = pump(tracker, ran).pollOnce()
        assertEquals(1, summary.dropped)
        assertEquals(2, server.requestCount)
        assertNull(tracker.storedResult("pj-1"))

        // The next fetch delivers nothing new and nothing is re-posted.
        val before = server.requestCount
        server.enqueue(MockResponse().setBody("""{"jobs": []}"""))
        summary = pump(tracker, ran).pollOnce()
        assertEquals(0, summary.reposted)
        assertEquals(before + 1, server.requestCount) // one fetch, no result post
    }

    @Test
    fun `404 NOT_FOUND and 409 JOB_EXPIRED are final too`() {
        val ran = mutableListOf<String>()
        server.enqueue(MockResponse().setBody(queuedJob("pj-x")))
        server.enqueue(notFound())
        pump(JobTracker(store), ran).pollOnce()
        server.takeRequest(); server.takeRequest()
        assertNull(store.map["pj-x"])

        server.enqueue(MockResponse().setBody(queuedJob("pj-y")))
        server.enqueue(
            MockResponse().setResponseCode(409)
                .setBody("""{"error": {"code": "JOB_EXPIRED", "message": "past expiresAtMs"}}"""),
        )
        pump(JobTracker(store), ran).pollOnce()
        assertNull(store.map["pj-y"])
    }

    @Test
    fun `a transient failure queues the result and the next cycle re-posts it`() {
        val ran = mutableListOf<String>()
        val tracker = JobTracker(store)
        server.enqueue(MockResponse().setBody(queuedJob("pj-5")))
        server.enqueue(MockResponse().setResponseCode(500).setBody("""{"error": {"code": "UNKNOWN", "message": "boom"}}"""))

        var summary = pump(tracker, ran).pollOnce()
        assertEquals(1, summary.retriesWaiting)
        assertEquals(2, server.requestCount)
        server.takeRequest(); server.takeRequest()

        clock[0] += 30_000 // the retry delay has passed
        server.enqueue(okAccepted()) // the retry post
        server.enqueue(MockResponse().setBody("""{"jobs": []}""")) // then the fetch
        summary = pump(tracker, ran).pollOnce()
        assertEquals(1, summary.retriesPosted)
        assertEquals(0, summary.retriesWaiting)
        assertEquals("/v1/jobs/pj-5/result", server.takeRequest().path)
    }

    @Test
    fun `a 429 queues the result with the Retry-After delay`() {
        val ran = mutableListOf<String>()
        val tracker = JobTracker(store)
        server.enqueue(MockResponse().setBody(queuedJob("pj-6")))
        server.enqueue(
            MockResponse().setResponseCode(429)
                .setHeader("Retry-After", "45")
                .setBody("""{"error": {"code": "RATE_LIMITED", "message": "later", "retryAfterSeconds": 45}}"""),
        )
        pump(tracker, ran).pollOnce()
        server.takeRequest(); server.takeRequest()
        val retry = tracker.allRetries().single()
        assertTrue(retry.dueAtMs >= 1_000L + 45_000) // pump clock + the Retry-After delay
    }

    @Test
    fun `an offline fetch leaves retries queued and runs nothing`() {
        val ran = mutableListOf<String>()
        api.close()
        server.shutdown()
        val dead = StewardApi("http://127.0.0.1:1", tokenProvider = { "t" })
        try {
            val summary = JobPump(dead, JobTracker(store), executor(ran), "0.2.0").pollOnce()
            assertEquals(0, summary.ran)
            assertTrue(ran.isEmpty())
        } finally {
            dead.close()
        }
    }

    @Test
    fun `executor crash reports an error result instead of losing the job`() {
        val crashing = JobDispatcher(
            mapOf(
                "storage_overview" to JobExecutor { error("disk on fire") },
            ),
        )
        server.enqueue(MockResponse().setBody(queuedJob("pj-7")))
        server.enqueue(okAccepted())
        val summary = JobPump(api, JobTracker(store), crashing, "0.2.0", nowMillis = { 1L }).pollOnce()
        assertEquals(1, summary.ran)
        server.takeRequest()
        val body = server.takeRequest().body.readUtf8()
        assertTrue(body.contains("\"status\":\"error\""))
        assertTrue(body.contains("EXECUTOR_CRASHED"))
    }

    @Test
    fun `stored request ids stay valid when a job id is very long`() {
        val longId = "pj-" + "z".repeat(200)
        assertTrue(RequestIds.isValid(RequestIds.forJobResult(longId)))
    }
}
