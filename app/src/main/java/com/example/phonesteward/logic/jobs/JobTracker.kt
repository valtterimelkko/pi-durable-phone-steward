package com.example.phonesteward.logic.jobs

import com.example.phonesteward.net.JobResultRequest

/**
 * Delivery is at least once (protocol §3.1): the server keeps a job queued
 * until a result is accepted, so the app sees it again on every fetch.
 * This tracker makes that safe:
 * - a job already executing is never started a second time;
 * - a finished job's stored result is re-posted (idempotent by requestId);
 * - a final rejection (409 RESULT_CONFLICT, 404 NOT_FOUND, 409 JOB_EXPIRED)
 *   drops the stored result for good.
 * The done set is persisted through [DoneJobStore] so a killed process
 * re-posts instead of re-executing. All methods are synchronised: the
 * worker and the foreground poller share one instance.
 */
class JobTracker(private val store: DoneJobStore) {

    private val inFlight = mutableSetOf<String>()
    private val done: MutableMap<String, StoredResult> = store.loadAll().toMutableMap()

    /** Retry queue for transient report failures, with due-at times. */
    data class Retry(val jobId: String, val result: JobResultRequest, val dueAtMs: Long)

    private val retryQueue = linkedMapOf<String, Retry>()

    enum class Start { RUN, SKIP_IN_FLIGHT, REPOST_STORED }

    @Synchronized
    fun onStart(jobId: String): Start = when {
        jobId in inFlight -> Start.SKIP_IN_FLIGHT
        done.containsKey(jobId) -> Start.REPOST_STORED
        else -> {
            inFlight += jobId
            Start.RUN
        }
    }

    /** Stores the result BEFORE it is posted: a crash then re-posts, never re-runs. */
    @Synchronized
    fun onResultReady(jobId: String, request: JobResultRequest) {
        inFlight -= jobId
        val stored = StoredResult.from(request)
        done[jobId] = stored
        store.save(jobId, stored)
    }

    /** Transient failure (network, 429/5xx): queue for a later attempt, honouring Retry-After. */
    @Synchronized
    fun onTransientFailure(
        jobId: String,
        request: JobResultRequest,
        retryAfterSeconds: Int?,
        nowMs: Long = System.currentTimeMillis(),
    ) {
        val delayMs = (retryAfterSeconds ?: DEFAULT_RETRY_SECONDS).coerceAtLeast(1) * 1000L
        retryQueue[jobId] = Retry(jobId, request, nowMs + delayMs)
    }

    /**
     * Final rejection: the server has ruled. RESULT_CONFLICT is final by
     * protocol §3.2; NOT_FOUND and JOB_EXPIRED mean the job is gone. Drop
     * the stored result so it is never re-posted.
     */
    @Synchronized
    fun onFinalRejection(jobId: String) {
        inFlight -= jobId
        done.remove(jobId)
        store.remove(jobId)
        retryQueue.remove(jobId)
    }

    @Synchronized
    fun retriesDue(nowMs: Long): List<Retry> =
        retryQueue.values.filter { it.dueAtMs <= nowMs }

    @Synchronized
    fun allRetries(): List<Retry> = retryQueue.values.toList()

    @Synchronized
    fun isInFlight(jobId: String): Boolean = jobId in inFlight

    @Synchronized
    fun storedResult(jobId: String): StoredResult? = done[jobId]

    @Synchronized
    fun doneCount(): Int = done.size

    private companion object {
        const val DEFAULT_RETRY_SECONDS = 5
    }
}
