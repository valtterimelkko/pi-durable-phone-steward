package com.example.phonesteward.net

import kotlinx.serialization.json.jsonObject
import java.net.URI
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Opt-in contract test against a REAL steward server — but ONLY a disposable
 * one (fix 01-4). It refuses to run unless `STEWARD_BASE_URL`'s host is
 * `127.0.0.1` or `localhost`, so production (`steward.example.com`) can
 * never be driven by mistake.
 *
 * It also acts only on items it can recognise as its own:
 * - **Jobs:** reports a result only for a job whose `kind` is
 *   `phone_selftest` — queue one with `bin/steward selftest`
 *   before the run. Any other job is left untouched.
 * - **Proposals:** decides (rejects) only a proposal whose `title` starts
 *   with `[contract-test]`. If none exists, that step is skipped with a
 *   clear message — the test never decides a real proposal.
 *
 * Run (AR2, disposable P2 instance on a temporary port):
 * ```
 * STEWARD_BASE_URL=http://127.0.0.1:<port> STEWARD_PAIR_CODE=<fresh code> \
 *   ANDROID_HOME=$ANDROID_HOME ./gradlew --no-daemon testDebugUnitTest \
 *   --tests 'com.example.phonesteward.net.ContractLiveTest'
 * ```
 */
/**
 * The production-avoidance guards of [ContractLiveTest], pure and tested
 * (fix 02-3): disposable consent, loopback host, and never port 7461.
 */
object ContractGuards {
    const val PRODUCTION_PORT = 7461

    fun consentGiven(disposableEnv: String?): Boolean = disposableEnv == "1"

    fun isDisposableHost(url: String?): Boolean = try {
        val uri = URI(requireNotNull(url).trim())
        val host = uri.host ?: return false
        val port = if (uri.port > 0) uri.port else if (uri.scheme == "https") 443 else 80
        (host == "127.0.0.1" || host == "localhost") && port != PRODUCTION_PORT
    } catch (_: Exception) {
        false
    }
}

class ContractGuardsTest {

    @Test
    fun `loopback hosts pass, anything else fails`() {
        assertTrue(ContractGuards.isDisposableHost("http://127.0.0.1:8899"))
        assertTrue(ContractGuards.isDisposableHost("http://localhost:8899"))
        assertFalse(ContractGuards.isDisposableHost("https://steward.example.com"))
        assertFalse(ContractGuards.isDisposableHost("http://example.com"))
        assertFalse(ContractGuards.isDisposableHost("not a url"))
        assertFalse(ContractGuards.isDisposableHost(null))
    }

    @Test
    fun `production port 7461 is refused even on loopback`() {
        assertFalse(ContractGuards.isDisposableHost("http://127.0.0.1:7461"))
        assertFalse(ContractGuards.isDisposableHost("http://localhost:7461"))
        assertTrue("any other loopback port is fine", ContractGuards.isDisposableHost("http://127.0.0.1:7462"))
    }

    @Test
    fun `the disposable consent env var is required`() {
        assertTrue(ContractGuards.consentGiven("1"))
        assertFalse(ContractGuards.consentGiven(null))
        assertFalse(ContractGuards.consentGiven("0"))
        assertFalse(ContractGuards.consentGiven("yes"))
    }
}

class ContractLiveTest {

    private val base = System.getenv("STEWARD_BASE_URL")
    private val code = System.getenv("STEWARD_PAIR_CODE")

    private fun isDisposableHost(url: String?): Boolean = ContractGuards.isDisposableHost(url)

    @Test
    fun `full phone contract against a live disposable server`() {
        assumeTrue("STEWARD_BASE_URL not set", !base.isNullOrBlank())
        assumeTrue("STEWARD_PAIR_CODE not set", !code.isNullOrBlank())
        assumeTrue(
            "STEWARD_DISPOSABLE=1 must be set: this test drives a real server on purpose",
            ContractGuards.consentGiven(System.getenv("STEWARD_DISPOSABLE")),
        )
        assumeTrue(
            "STEWARD_BASE_URL must be a loopback host on a non-production port (disposable servers only), got: $base",
            ContractGuards.isDisposableHost(base),
        )

        // 1. Pair once (unauthenticated by protocol; fix 01-8). A used/expired
        // code surfaces as PAIRING_CODE_INVALID; the token is captured directly.
        val identity = base.trimEnd('/')
        val pairResponse = try {
            StewardApi(identity).use { it.pair(code.trim(), "contract-live-test") }
        } catch (e: ApiException) {
            if (e.code == "PAIRING_CODE_INVALID") {
                throw AssertionError("pairing code rejected (used or expired): ask for a fresh one", e)
            }
            throw e
        }
        assertTrue(pairResponse.token.length >= 32)

        StewardApi(identity, tokenProvider = { pairResponse.token }).use { api ->
            // 2. Fetch jobs (no long-poll in a test).
            val jobs = api.fetchJobs(waitSeconds = 0)

            // 3. Report a result ONLY for our own selftest job.
            val selftestJob = jobs.firstOrNull { it.kind == "phone_selftest" }
            if (selftestJob == null) {
                println("[contract-test] no phone_selftest job queued — run `bin/steward selftest` first; report step skipped")
            } else {
                val answer = api.reportResult(
                    selftestJob.jobId,
                    JobResultRequest(
                        requestId = RequestIds.forJobResult(selftestJob.jobId),
                        status = "ok",
                        // A protocol-valid phone_selftest result: skipped, no
                        // device work done by the contract test itself (fix 02-3).
                        data = ProtocolJson.encodeToJsonElement(
                            SelftestData.serializer(),
                            SelftestData(
                                checks = listOf(
                                    SelftestCheck(
                                        id = "trash_dialog",
                                        outcome = "skipped",
                                        resultCode = null,
                                        detail = "contract test",
                                        atMs = System.currentTimeMillis(),
                                    ),
                                ),
                            ),
                        ).jsonObject,
                        error = null,
                        deviceTimeMs = System.currentTimeMillis(),
                        appVersion = "0.2.0-p3",
                    ),
                )
                assertTrue(answer.accepted)
            }

            // 4. List proposals.
            val proposals = api.listProposals("open")

            // 5. Decide ONLY a proposal marked for this test.
            val ours = proposals.firstOrNull { it.title.startsWith("[contract-test]") }
            if (ours == null) {
                println("[contract-test] no proposal titled [contract-test]… — decision step skipped")
            } else {
                val decision = api.decide(
                    ours.proposalId,
                    DecisionRequest(RequestIds.new("contract"), "reject", emptyList()),
                )
                assertTrue(decision.approvedItemIds.isEmpty())
                val check = api.checkDecision(decision.decisionId)
                assertTrue("a rejected decision must report valid", check.valid)
            }
        }
    }
}
