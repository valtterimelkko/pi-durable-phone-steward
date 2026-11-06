package com.example.phonesteward.logic.exec

import kotlinx.serialization.json.JsonObject
import com.example.phonesteward.logic.jobs.JobExecutor
import com.example.phonesteward.logic.jobs.JobOutcome
import com.example.phonesteward.logic.selftest.SelftestRunner
import com.example.phonesteward.net.Job
import com.example.phonesteward.net.ProtocolJson
import com.example.phonesteward.net.SelftestParams

/**
 * `phone_selftest` as a job (protocol §3.1): runs the requested checks —
 * all five when `checks` is empty — on the app's own test images only.
 */
class SelftestJobExecutor(private val runner: SelftestRunner) : JobExecutor {

    override fun execute(job: Job): JobOutcome {
        val params: SelftestParams? = try {
            if (job.params is JsonObject && job.params.isNotEmpty()) {
                ProtocolJson.decodeFromJsonElement(SelftestParams.serializer(), job.params)
            } else {
                SelftestParams(emptyList())
            }
        } catch (_: Exception) {
            null
        }
        if (params == null) {
            return JobOutcome.Failed("BAD_PARAMS", "phone_selftest params unreadable")
        }
        return try {
            JobOutcome.Ok(body(runner.run(params.checks)))
        } catch (e: Exception) {
            JobOutcome.Failed("SELFTEST_CRASHED", e.message ?: e.javaClass.simpleName)
        }
    }
}
