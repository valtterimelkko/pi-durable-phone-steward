package com.example.phonesteward.net

import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import com.example.phonesteward.logic.CrashDevice
import com.example.phonesteward.logic.CrashReport
import com.example.phonesteward.logic.CrashReports
import com.example.phonesteward.logic.CrashUpload

/**
 * Opt-in A6 contract checks against a REAL, disposable steward server, with
 * the same guards as [CrashContractLiveTest] (consent, loopback, never port
 * 7461):
 * - an `error` report (O5) is accepted;
 * - a `phone_diagnostics` (O6) result is accepted for a job the test queues
 *   through the disposable server's admin diagnostics route — the run needs
 *   `STEWARD_DEVICE_TOKEN` (a device bearer of THAT server) and skips
 *   cleanly with a message when the route or the token is not available.
 * ```
 * STEWARD_DISPOSABLE=1 STEWARD_BASE_URL=http://127.0.0.1:<port> \
 *   [STEWARD_DEVICE_TOKEN=<bearer>] [STEWARD_DIAG_JOB_ID=<pj-… from bin/steward diag>] \
 *   ANDROID_HOME=$ANDROID_HOME \
 *   ./gradlew --no-daemon testDebugUnitTest --tests '*DiagnosticsContractLiveTest'
 * ```
 */
class DiagnosticsContractLiveTest {

    private val base = System.getenv("STEWARD_BASE_URL")

    private fun consentAndLoopback() {
        assumeTrue("set STEWARD_DISPOSABLE=1", ContractGuards.consentGiven(System.getenv("STEWARD_DISPOSABLE")))
        assumeTrue("STEWARD_BASE_URL must be a disposable loopback server", ContractGuards.isDisposableHost(base))
    }

    @Test
    fun `a real server accepts the app's error report`() {
        consentAndLoopback()
        val report = CrashReports.errorReport(
            site = "contract.test",
            severity = CrashReports.SEVERITY_ERROR,
            threadName = "main",
            error = StackOverflowError("contract"),
            appVersion = "0.2.2",
            nowMs = System.currentTimeMillis(),
            device = CrashDevice("contract-test", 33),
            requestId = RequestIds.new("err"),
        )
        assertEquals("error", report.severity)
        assertEquals(CrashUpload.SENT, CrashClient(base!!, token = null).send(report))
    }

    @Test
    fun `a phone_diagnostics result is accepted for a job queued through the admin route`() {
        consentAndLoopback()
        val token = System.getenv("STEWARD_DEVICE_TOKEN")
        // Admin routes live only on the server's Unix socket, so the runner queues the
        // job with `bin/steward diag` and passes its id; the TCP attempt stays as a fallback.
        val jobId = System.getenv("STEWARD_DIAG_JOB_ID") ?: queueThroughAdminRoute()
        assumeTrue(
            "skipped: the admin diagnostics route answered nothing queueable " +
                "(is the disposable server the A6 build?) — no job was touched",
            jobId != null,
        )
        assumeTrue(
            "skipped: set STEWARD_DEVICE_TOKEN to the disposable server's device " +
                "bearer to report the result — no job was touched",
            token != null,
        )
        val api = StewardApi(base!!, tokenProvider = { token })
        api.use {
            val job = it.fetchJobs(waitSeconds = 0).firstOrNull { j -> j.jobId == jobId }
            assumeTrue("skipped: the queued diagnostics job was not delivered (already answered?)", job != null)
            assertEquals("phone_diagnostics", job!!.kind)
            val data = buildJsonObject {
                put("appVersion", "0.2.2")
                put("versionCode", 4)
                put(
                    "permissions",
                    buildJsonObject {
                        put("usageAccess", true)
                        put("allFiles", true)
                        put("mediaRead", true)
                        put("manageMedia", false)
                        put("batteryUnrestricted", true)
                    },
                )
                put(
                    "worker",
                    buildJsonObject {
                        put("state", "NONE")
                        put("lastRunMs", 0)
                        put("runAttemptCount", 0)
                    },
                )
                put(
                    "outbox",
                    buildJsonObject {
                        put("crashes", 0)
                        put("errors", 0)
                    },
                )
                put("log", kotlinx.serialization.json.JsonArray(emptyList()))
            }
            val answer = it.reportResult(
                job.jobId,
                JobResultRequest(
                    requestId = RequestIds.forJobResult(job.jobId),
                    status = "ok",
                    data = data,
                    error = null,
                    deviceTimeMs = System.currentTimeMillis(),
                    appVersion = "0.2.2",
                ),
            )
            assertTrue("the diagnostics result must be accepted", answer.accepted)
        }
    }

    /** Returns a queued job id, or null when the route is not usable. */
    private fun queueThroughAdminRoute(): String? = try {
        val request = Request.Builder()
            .url(base!!.trimEnd('/') + "/admin/diagnostics")
            .post(
                """{"requestId":"contract-${System.currentTimeMillis()}"}"""
                    .toRequestBody("application/json; charset=utf-8".toMediaType()),
            )
            .build()
        OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .build()
            .newCall(request)
            .execute()
            .use { response ->
                if (!response.isSuccessful) {
                    null
                } else {
                    val body = response.body?.string().orEmpty()
                    Regex("pj-[A-Za-z0-9]+").find(body)?.value
                }
            }
    } catch (_: Exception) {
        null
    }
}
