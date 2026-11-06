package com.example.phonesteward.logic.jobs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Test
import com.example.phonesteward.net.Job
import com.example.phonesteward.net.StewardApi

internal class InMemoryDoneStore : DoneJobStore {
    val map = linkedMapOf<String, StoredResult>()
    override fun loadAll(): Map<String, StoredResult> = map.toMap()
    override fun save(jobId: String, result: StoredResult) { map[jobId] = result }
    override fun remove(jobId: String) { map.remove(jobId) }
}

private fun job(id: String, kind: String = "storage_overview") = Job(
    jobId = id, kind = kind,
    params = buildJsonObject { },
    createdAtMs = 1, expiresAtMs = 2,
)

class JobDispatcherTest {

    @Test
    fun `a known kind goes to its executor`() {
        var seen: Job? = null
        val dispatcher = JobDispatcher(
            mapOf("storage_overview" to JobExecutor { j -> seen = j; JobOutcome.Ok(buildJsonObject { put("x", 1) }) }),
        )
        val outcome = dispatcher.dispatch(job("pj-1"))
        assertTrue(outcome is JobOutcome.Ok)
        assertEquals("pj-1", seen!!.jobId)
    }

    @Test
    fun `an unknown kind answers UNKNOWN_KIND without running anything`() {
        val dispatcher = JobDispatcher(mapOf("storage_overview" to JobExecutor { JobOutcome.Ok(buildJsonObject { put("x", 1) }) }))
        val outcome = dispatcher.dispatch(job("pj-2", kind = "live_tap"))
        assertTrue(outcome is JobOutcome.Failed)
        assertEquals("UNKNOWN_KIND", (outcome as JobOutcome.Failed).errorCode)
    }
}

class JobTrackerTest {

    @Test
    fun `run once, skip while in flight, repost when done`() {
        val store = InMemoryDoneStore()
        val tracker = JobTracker(store)
        assertEquals(JobTracker.Start.RUN, tracker.onStart("pj-1"))
        assertEquals(JobTracker.Start.SKIP_IN_FLIGHT, tracker.onStart("pj-1"))
        val request = StoredResult(
            requestId = "res-pj-1", status = "ok",
            data = buildJsonObject { put("totalBytes", 5) },
            deviceTimeMs = 9, appVersion = "0.2.0",
        ).toRequest()
        tracker.onResultReady("pj-1", request)
        assertEquals(JobTracker.Start.REPOST_STORED, tracker.onStart("pj-1"))
        assertEquals("res-pj-1", tracker.storedResult("pj-1")!!.requestId)
    }

    @Test
    fun `done results survive a restart via the store`() {
        val store = InMemoryDoneStore()
        val first = JobTracker(store)
        first.onStart("pj-9")
        first.onResultReady(
            "pj-9",
            StoredResult("res-pj-9", "ok", null, null, null, 1, "0.2.0").toRequest(),
        )
        val restarted = JobTracker(store)
        assertEquals(JobTracker.Start.REPOST_STORED, restarted.onStart("pj-9"))
    }

    @Test
    fun `final rejection drops the stored result for good`() {
        val store = InMemoryDoneStore()
        val tracker = JobTracker(store)
        tracker.onStart("pj-3")
        tracker.onResultReady("pj-3", StoredResult("res-pj-3", "ok", null, null, null, 1, "0.2.0").toRequest())
        tracker.onFinalRejection("pj-3")
        assertNull(tracker.storedResult("pj-3"))
        assertFalse(store.map.containsKey("pj-3"))
    }

    @Test
    fun `transient failure queues a retry honouring Retry-After`() {
        val tracker = JobTracker(InMemoryDoneStore())
        val request = StoredResult("res-pj-4", "ok", null, null, null, 1, "0.2.0").toRequest()
        tracker.onStart("pj-4")
        tracker.onTransientFailure("pj-4", request, retryAfterSeconds = 30)
        assertTrue(tracker.retriesDue(System.currentTimeMillis() + 31_000).isNotEmpty())
        assertTrue(tracker.retriesDue(System.currentTimeMillis()).isEmpty())
    }
}
