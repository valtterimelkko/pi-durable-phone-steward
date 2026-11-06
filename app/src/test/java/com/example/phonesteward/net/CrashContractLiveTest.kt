package com.example.phonesteward.net

import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import com.example.phonesteward.logic.CrashDevice
import com.example.phonesteward.logic.CrashReport
import com.example.phonesteward.logic.CrashUpload

/**
 * Opt-in A5 contract check: the app's own [CrashClient] against a REAL, disposable
 * steward server, with the same guards as [ContractLiveTest] (consent, loopback, never
 * port 7461). No pairing needed: a crash report takes no token.
 * ```
 * STEWARD_DISPOSABLE=1 STEWARD_BASE_URL=http://127.0.0.1:<port> ANDROID_HOME=$ANDROID_HOME \
 *   ./gradlew --no-daemon testDebugUnitTest --tests '*CrashContractLiveTest'
 * ```
 */
class CrashContractLiveTest {

    private val base = System.getenv("STEWARD_BASE_URL")

    @Test
    fun `a real server accepts the app's crash report, and a resend is a duplicate`() {
        assumeTrue("set STEWARD_DISPOSABLE=1", ContractGuards.consentGiven(System.getenv("STEWARD_DISPOSABLE")))
        assumeTrue("STEWARD_BASE_URL must be a disposable loopback server", ContractGuards.isDisposableHost(base))
        val report = CrashReport(
            requestId = RequestIds.new("crash"),
            appVersion = "0.2.1-w3",
            crashedAtMs = System.currentTimeMillis(),
            thread = "main",
            exception = "java.lang.StackOverflowError",
            stackTrace = StackOverflowError("contract").stackTraceToString(),
            device = CrashDevice("contract-test", 33),
        )
        val client = CrashClient(base!!, token = null)
        assertEquals(CrashUpload.SENT, client.send(report))
        assertEquals(CrashUpload.SENT, client.send(report))
    }
}
