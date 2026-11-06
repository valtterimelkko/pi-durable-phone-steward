package com.example.phonesteward.logic.jobs

import com.example.phonesteward.net.ApiException
import com.example.phonesteward.net.Job
import com.example.phonesteward.net.JobResultRequest
import com.example.phonesteward.net.RequestIds
import com.example.phonesteward.net.StewardApi

/**
 * One cycle of phone work (protocol §3): drain retries, long-poll for
 * queued jobs, run each exactly once (or re-post its stored result), and
 * post results. Pure orchestration: Android sits in the executors' adapters.
 */
class JobPump(
    private val api: StewardApi,
    private val tracker: JobTracker,
    private val dispatcher: JobDispatcher,
    private val appVersion: String,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {

    data class CycleSummary(
        val ran: Int = 0,
        val reposted: Int = 0,
        val skippedInFlight: Int = 0,
        val dropped: Int = 0,
        val retriesPosted: Int = 0,
        val retriesWaiting: Int = 0,
        /** The job fetch failed (offline, server error): the caller waits before the next cycle. */
        val fetchFailed: Boolean = false,
    )

    @Synchronized
    fun pollOnce(waitSeconds: Int = 0): CycleSummary {
        var summary = CycleSummary()
        summary = drainRetries(summary)

        val jobs = try {
            api.fetchJobs(waitSeconds)
        } catch (e: Exception) {
            com.example.phonesteward.logic.obs.Diagnostics.error("jobs.fetch", e)
            // A revoked or unknown token is not "offline": let the caller send the owner back to pairing.
            if (e is ApiException && e.httpStatus == 401) throw e
            return summary.copy(fetchFailed = true) // offline: retries stay queued, next cycle tries again
        }

        for (job in jobs) {
            when (tracker.onStart(job.jobId)) {
                JobTracker.Start.SKIP_IN_FLIGHT -> {
                    summary = summary.copy(skippedInFlight = summary.skippedInFlight + 1)
                }
                JobTracker.Start.REPOST_STORED -> {
                    val stored = tracker.storedResult(job.jobId)?.toRequest()
                    if (stored != null) {
                        summary = when (post(job.jobId, stored)) {
                            PostResult.DROPPED -> summary.copy(dropped = summary.dropped + 1)
                            PostResult.RETRIED -> summary.copy(retriesWaiting = summary.retriesWaiting + 1)
                            PostResult.ACCEPTED -> summary.copy(reposted = summary.reposted + 1)
                        }
                    }
                }
                JobTracker.Start.RUN -> {
                    val request = runJob(job)
                    tracker.onResultReady(job.jobId, request)
                    com.example.phonesteward.logic.obs.Diagnostics.event(
                        "info", "job_answered", "${job.kind} status=${request.status}",
                    )
                    summary = when (post(job.jobId, request)) {
                        PostResult.DROPPED -> summary.copy(ran = summary.ran + 1, dropped = summary.dropped + 1)
                        PostResult.RETRIED -> summary.copy(ran = summary.ran + 1, retriesWaiting = summary.retriesWaiting + 1)
                        PostResult.ACCEPTED -> summary.copy(ran = summary.ran + 1)
                    }
                }
            }
        }
        com.example.phonesteward.logic.obs.Diagnostics.event(
            "info", "jobs_polled",
            "ran=${summary.ran} reposted=${summary.reposted} skipped=${summary.skippedInFlight}",
        )
        return summary
    }

    private fun runJob(job: Job): JobResultRequest {
        val outcome = try {
            dispatcher.dispatch(job)
        } catch (e: Exception) {
            JobOutcome.Failed("EXECUTOR_CRASHED", e.message ?: e.javaClass.simpleName)
        }
        return when (outcome) {
            is JobOutcome.Ok -> JobResultRequest(
                requestId = RequestIds.forJobResult(job.jobId),
                status = "ok",
                data = outcome.data,
                error = null,
                deviceTimeMs = nowMillis(),
                appVersion = appVersion,
            )
            is JobOutcome.Partial -> JobResultRequest(
                requestId = RequestIds.forJobResult(job.jobId),
                status = "partial",
                data = outcome.data,
                error = com.example.phonesteward.net.JobErrorBody(outcome.errorCode, outcome.message),
                deviceTimeMs = nowMillis(),
                appVersion = appVersion,
            )
            is JobOutcome.Failed -> JobResultRequest(
                requestId = RequestIds.forJobResult(job.jobId),
                status = "error",
                data = null,
                error = com.example.phonesteward.net.JobErrorBody(outcome.errorCode, outcome.message),
                deviceTimeMs = nowMillis(),
                appVersion = appVersion,
            )
        }
    }

    private enum class PostResult { ACCEPTED, DROPPED, RETRIED }

    /** One post attempt with the protocol's outcome classes. */
    private fun post(jobId: String, request: JobResultRequest): PostResult {
        try {
            api.reportResult(jobId, request)
            return PostResult.ACCEPTED
        } catch (e: ApiException) {
            return when (e.code) {
                "RESULT_CONFLICT", "NOT_FOUND", "JOB_EXPIRED" -> {
                    tracker.onFinalRejection(jobId)
                    PostResult.DROPPED
                }
                else -> {
                    tracker.onTransientFailure(jobId, request, e.retryAfterSeconds, nowMillis())
                    PostResult.RETRIED
                }
            }
        } catch (e: Exception) {
            com.example.phonesteward.logic.obs.Diagnostics.error("jobs.report", e)
            tracker.onTransientFailure(jobId, request, null, nowMillis())
            return PostResult.RETRIED
        }
    }

    private fun drainRetries(summary: CycleSummary): CycleSummary {
        var out = summary
        for (retry in tracker.retriesDue(nowMillis()).toList()) {
            try {
                api.reportResult(retry.jobId, retry.result)
                tracker.onFinalRejection(retry.jobId) // accepted: nothing more to do
                out = out.copy(retriesPosted = out.retriesPosted + 1)
            } catch (e: ApiException) {
                when (e.code) {
                    "RESULT_CONFLICT", "NOT_FOUND", "JOB_EXPIRED" -> {
                        tracker.onFinalRejection(retry.jobId)
                        out = out.copy(dropped = out.dropped + 1)
                    }
                    else -> tracker.onTransientFailure(retry.jobId, retry.result, e.retryAfterSeconds, nowMillis())
                }
            } catch (e: Exception) {
                com.example.phonesteward.logic.obs.Diagnostics.error("jobs.retry_drain", e)
                tracker.onTransientFailure(retry.jobId, retry.result, null, nowMillis())
            }
        }
        out = out.copy(retriesWaiting = tracker.allRetries().size)
        return out
    }
}
