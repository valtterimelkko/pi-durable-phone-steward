package com.example.phonesteward.e2e

import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import com.example.phonesteward.net.Proposal
import com.example.phonesteward.net.ProposalItem

/**
 * Flow Shizuku (A10.2) where it is NOT set up (Test Lab has no wireless debugging): the Status card walks the owner
 * through it in plain words, the device status says `absent`, the shell jobs answer SHIZUKU_NOT_READY, and a trim_caches
 * or disable_app proposal fails with the reason shown on the card. The client itself is tested on the JVM against a fake
 * binder and checked on a real phone.
 */
@RunWith(AndroidJUnit4::class)
class FlowD1ShizukuTest : E2eBase() {
    override val flow = "shizuku"

    private fun land() {
        launchApp()
        pairAndLand()
    }

    private fun job(kind: String, params: kotlinx.serialization.json.JsonObject): kotlinx.serialization.json.JsonObject {
        val id = fake.queueJob(kind, params)
        eventually("the phone answered job $id") { fake.jobResults.containsKey(id) }
        return fake.jobResults.getValue(id)
    }

    @Test
    fun theStatusCardWalksThroughTheSetupAndTheDeviceStatusSaysAbsent() {
        land()
        eventually("the status post says shizuku is absent") {
            fake.requestsTo("POST", "/v1/device/status").any { it.body.contains("\"shizuku\":\"absent\"") }
        }
        openTab("Status")
        waitForTag("shizuku-card")
        waitForText("Shizuku (optional)")
        waitForText("Not set up.", substring = true)
        waitForText("Get Shizuku")
        waitForText("Check again")
        assertTrue(hasText("Install the Shizuku app."))
        assertFalse("no developer command anywhere", hasText("adb", substring = true))
        runCatching { tag("shizuku-card").performScrollTo() }
        shot("card-absent")
        clickText("Check again")
        Thread.sleep(500)
        assertTrue("still not set up", hasText("Get Shizuku"))
    }

    @Test
    fun shellJobsAnswerShizukuNotReady() {
        land()
        val info = job("shell_info", buildJsonObject { put("op", "packages") })
        assertEquals("error", info["status"]!!.jsonPrimitive.content)
        assertEquals("SHIZUKU_NOT_READY", info["error"]!!.jsonObject["code"]!!.jsonPrimitive.content)
        val stop = job("force_stop", buildJsonObject { put("packageName", "com.example.puzzle") })
        assertEquals("SHIZUKU_NOT_READY", stop["error"]!!.jsonObject["code"]!!.jsonPrimitive.content)
        val bad = job("force_stop", buildJsonObject { put("packageName", "com.example; reboot") })
        assertEquals("BAD_PACKAGE", bad["error"]!!.jsonObject["code"]!!.jsonPrimitive.content)
    }

    @Test
    fun aTrimCachesProposalFailsPlainlyWithoutShizuku() {
        land()
        fake.addProposal(
            Proposal(
                "pr-trim", System.currentTimeMillis() - 3_600_000L, "agent", "Clear every app's cache", "Invented.", 400_000_000L, "open",
                listOf(ProposalItem("it-t", "trim_caches", buildJsonObject {
                    put("apps", buildJsonArray {
                        add(buildJsonObject { put("packageName", "com.example.puzzle"); put("label", "Puzzle"); put("cacheBytes", 400_000_000L) })
                    })
                }, 400_000_000L)),
            ),
        )
        fake.proposalEvent("pr-trim", "Clear every app's cache")
        openTab("Proposals")
        waitForText("Clear every app's cache")
        click(tag("approve-button"))
        waitForText("Shizuku is not set up, so nothing was changed.", substring = true)
        shot("trim-refused")
        eventually("reported") { fake.requestsTo("POST", "/v1/proposals/pr-trim/execution").isNotEmpty() }
        val items = fake.requestsTo("POST", "/v1/proposals/pr-trim/execution").last().json["items"]!!.jsonArray
        assertEquals("failed", items.single().jsonObject["outcome"]!!.jsonPrimitive.content)
        assertFalse("no Android dialog opened", foregroundPackage() != context.packageName)
    }

    @Test
    fun aDisableProposalFailsPlainlyWithoutShizuku() {
        land()
        fake.addProposal(
            Proposal(
                "pr-off", System.currentTimeMillis() - 3_600_000L, "agent", "Disable an app", "Invented.", 0L, "open",
                listOf(ProposalItem("it-d", "disable_app", buildJsonObject { put("packageName", context.packageName); put("label", "Self") })),
            ),
        )
        fake.proposalEvent("pr-off", "Disable an app")
        openTab("Proposals")
        waitForText("Disable an app")
        click(tag("approve-button"))
        // Without Shizuku the refusal names Shizuku; the steward's own package is protected either way.
        waitForText("so nothing was changed", substring = true)
        eventually("reported") { fake.requestsTo("POST", "/v1/proposals/pr-off/execution").isNotEmpty() }
        assertEquals("failed", fake.requestsTo("POST", "/v1/proposals/pr-off/execution").last().json["items"]!!.jsonArray.single().jsonObject["outcome"]!!.jsonPrimitive.content)
    }
}
