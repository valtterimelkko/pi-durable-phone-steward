package com.example.phonesteward.logic.jobs

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import com.example.phonesteward.net.Job
import com.example.phonesteward.net.JobErrorBody
import com.example.phonesteward.net.JobResultRequest
import com.example.phonesteward.net.ProtocolJson

/** What an executor produced for one job (protocol §3.2 statuses ok | error | partial). */
sealed interface JobOutcome {
    data class Ok(val data: JsonObject) : JobOutcome
    data class Partial(val data: JsonObject, val errorCode: String, val message: String) : JobOutcome
    data class Failed(val errorCode: String, val message: String) : JobOutcome
}

fun interface JobExecutor {
    fun execute(job: Job): JobOutcome
}

/** Maps job kinds to executors; an unknown kind answers the protocol's UNKNOWN_KIND error. */
class JobDispatcher(private val executors: Map<String, JobExecutor>) {

    fun dispatch(job: Job): JobOutcome =
        executors[job.kind]?.execute(job)
            ?: JobOutcome.Failed(
                "UNKNOWN_KIND",
                "this app version has no executor for kind ${job.kind}",
            )
}

/** One finished job's stored result, kept locally so a restart re-posts instead of re-running. */
@Serializable
data class StoredResult(
    val requestId: String,
    val status: String,
    val data: JsonObject? = null,
    val errorCode: String? = null,
    val errorMessage: String? = null,
    val deviceTimeMs: Long,
    val appVersion: String,
) {
    fun toRequest(): JobResultRequest = JobResultRequest(
        requestId = requestId,
        status = status,
        data = data,
        error = errorCode?.let { JobErrorBody(it, errorMessage ?: "") },
        deviceTimeMs = deviceTimeMs,
        appVersion = appVersion,
    )

    companion object {
        fun from(request: JobResultRequest): StoredResult = StoredResult(
            requestId = request.requestId,
            status = request.status,
            data = request.data,
            errorCode = request.error?.code,
            errorMessage = request.error?.message,
            deviceTimeMs = request.deviceTimeMs,
            appVersion = request.appVersion,
        )

        fun encode(result: StoredResult): String = ProtocolJson.encodeToString(result)

        fun decode(text: String): StoredResult = ProtocolJson.decodeFromString(text)
    }
}

/** Local persistence for finished results; the android/ adapter keeps them in prefs. */
interface DoneJobStore {
    fun loadAll(): Map<String, StoredResult>
    fun save(jobId: String, result: StoredResult)
    fun remove(jobId: String)
}
