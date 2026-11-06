package com.example.phonesteward.ui

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.Shadows.shadowOf
import com.example.phonesteward.net.ChatEvent
import com.example.phonesteward.screen.LiveSessionService

/** A13.6 through the real view model: the request card's answers, the pending redraw and the plain refusals. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class PhoneUseRequestFlowTest : VmFlowBase() {

    private fun requestEvent(requestId: String = "pu-1", task: String = "Clear WhatsApp's cache") = ChatEvent(
        10,
        "phone_use",
        buildJsonObject {
            put("kind", "request"); put("requestId", requestId); put("task", task); put("why", "It holds 1.2 GB"); put("atMs", 1L)
        },
    )

    private fun vm(): AppViewModel {
        answers["GET /v1/live/requests/pending"] = 200 to """{"request":null}"""
        return newViewModel()
    }

    @Test
    fun `an allow answers the route and starts the session on the returned id`() {
        answers["POST /v1/live/requests/pu-1/answer"] = 200 to """{"status":"allowed","sessionId":"ls-77"}"""
        val viewModel = vm()
        viewModel.onChatEvent(requestEvent())
        viewModel.allowPhoneUse("pu-1")
        await { countOf("POST /v1/live/requests/pu-1/answer") == 1 }
        assertEquals("""{"requestId":"pu-1","allow":true}""", bodiesOf("POST /v1/live/requests/pu-1/answer").single())
        val started = shadowOf(context as android.app.Application).nextStartedService
        assertEquals(LiveSessionService::class.java.name, started.component?.className)
        assertEquals("ls-77", started.getStringExtra(LiveSessionService.EXTRA_SESSION_ID))
        assertEquals("Clear WhatsApp's cache", started.getStringExtra(LiveSessionService.EXTRA_TASK))
    }

    @Test
    fun `a decline answers allow false and starts nothing`() {
        answers["POST /v1/live/requests/pu-1/answer"] = 200 to """{"status":"declined"}"""
        val viewModel = vm()
        viewModel.onChatEvent(requestEvent())
        viewModel.declinePhoneUse("pu-1")
        await { countOf("POST /v1/live/requests/pu-1/answer") == 1 }
        assertEquals("""{"requestId":"pu-1","allow":false}""", bodiesOf("POST /v1/live/requests/pu-1/answer").single())
        assertNull(shadowOf(context as android.app.Application).nextStartedService)
    }

    @Test
    fun `a request that is no longer pending says so plainly`() {
        answers["POST /v1/live/requests/pu-1/answer"] = 409 to
            """{"status":"expired","error":{"code":"REQUEST_NOT_PENDING","message":"this request is expired, not pending"}}"""
        val viewModel = vm()
        viewModel.onChatEvent(requestEvent())
        viewModel.allowPhoneUse("pu-1")
        await { viewModel.state.value.chatNotice != null }
        assertTrue(viewModel.state.value.chatNotice!!.contains("no longer pending"))
    }

    @Test
    fun `a stale request closes the card and marks it expired (N40)`() {
        answers["GET /v1/live/requests/pending"] = 200 to
            """{"request":{"id":"pu-1","task":"T","why":"W","status":"pending","createdAtMs":1}}"""
        answers["POST /v1/live/requests/pu-1/answer"] = 409 to
            """{"status":"expired","error":{"code":"REQUEST_NOT_PENDING","message":"this request is expired, not pending"}}"""
        val viewModel = newViewModel()
        viewModel.refreshPhoneUsePending()
        await { viewModel.state.value.phoneUsePendingId == "pu-1" }
        viewModel.onChatEvent(requestEvent())
        viewModel.allowPhoneUse("pu-1")
        await { countOf("POST /v1/live/requests/pu-1/answer") == 1 }
        // The card loses its live buttons at once (the pending id is cleared) and the
        // request entry is marked, so the card reads "This request has expired."
        await { viewModel.state.value.phoneUsePendingId == null }
        assertNull(viewModel.state.value.phoneUsePendingId)
        val request = viewModel.state.value.chat.single { it.kind == "phone_use" }
        assertEquals("expired", request.phone!!.status)
    }

    @Test
    fun `a running-session conflict frees the tap so the owner can answer after stopping (N40)`() {
        answers["POST /v1/live/requests/pu-1/answer"] = 409 to
            """{"error":{"code":"LIVE_SESSION_ACTIVE","message":"a phone session is already running"}}"""
        val viewModel = vm()
        viewModel.onChatEvent(requestEvent())
        viewModel.allowPhoneUse("pu-1")
        await { countOf("POST /v1/live/requests/pu-1/answer") == 1 }
        // The owner stops the running session and taps Allow again: the guard must not hold.
        answers["POST /v1/live/requests/pu-1/answer"] = 200 to """{"status":"allowed","sessionId":"ls-77"}"""
        viewModel.allowPhoneUse("pu-1")
        await { countOf("POST /v1/live/requests/pu-1/answer") == 2 }
        assertEquals(2, countOf("POST /v1/live/requests/pu-1/answer"))
    }

    @Test
    fun `a running session conflict says so plainly`() {
        answers["POST /v1/live/requests/pu-1/answer"] = 409 to
            """{"error":{"code":"LIVE_SESSION_ACTIVE","message":"a phone session is already running"}}"""
        val viewModel = vm()
        viewModel.onChatEvent(requestEvent())
        viewModel.allowPhoneUse("pu-1")
        await { viewModel.state.value.chatNotice != null }
        assertTrue(viewModel.state.value.chatNotice!!.contains("already"))
    }

    @Test
    fun `the pending request is re-read so a card redraws after a restart`() {
        val viewModel = vm()
        viewModel.refreshPhoneUsePending()
        await { countOf("GET /v1/live/requests/pending") == 1 && viewModel.state.value.phoneUsePendingId == null }
        assertNull(viewModel.state.value.phoneUsePendingId)

        answers["GET /v1/live/requests/pending"] = 200 to
            """{"request":{"id":"pu-9","task":"T","why":"W","status":"pending","createdAtMs":1}}"""
        viewModel.refreshPhoneUsePending()
        await { countOf("GET /v1/live/requests/pending") == 2 }
        await { viewModel.state.value.phoneUsePendingId == "pu-9" }
        assertEquals("pu-9", viewModel.state.value.phoneUsePendingId)

        answers["GET /v1/live/requests/pending"] = 200 to """{"request":null}"""
        viewModel.refreshPhoneUsePending()
        await { countOf("GET /v1/live/requests/pending") == 3 }
        await { viewModel.state.value.phoneUsePendingId == null }
        assertNull(viewModel.state.value.phoneUsePendingId)
    }

    @Test
    fun `an answer event clears the pending card`() {
        val viewModel = vm()
        answers["GET /v1/live/requests/pending"] = 200 to
            """{"request":{"id":"pu-9","task":"T","why":"W","status":"pending","createdAtMs":1}}"""
        viewModel.refreshPhoneUsePending()
        await { countOf("GET /v1/live/requests/pending") == 1 }
        await { viewModel.state.value.phoneUsePendingId == "pu-9" }
        viewModel.onChatEvent(
            ChatEvent(
                11,
                "phone_use",
                buildJsonObject { put("kind", "answer"); put("requestId", "pu-9"); put("allowed", true); put("sessionId", "ls-1"); put("atMs", 2L) },
            ),
        )
        await { viewModel.state.value.phoneUsePendingId == null }
        assertNull(viewModel.state.value.phoneUsePendingId)
    }

    @Test
    fun `the phone_use events fold into the chat`() {
        val viewModel = vm()
        viewModel.onChatEvent(requestEvent())
        await { viewModel.state.value.chat.isNotEmpty() }
        val entry = viewModel.state.value.chat.single { it.kind == "phone_use" }
        assertEquals("pu-1", entry.phone!!.requestId)
    }

    @Test
    fun `a repeat tap for the same request sends one answer and raises no notice`() {
        answers["POST /v1/live/requests/pu-1/answer"] = 200 to """{"status":"allowed","sessionId":"ls-77"}"""
        val viewModel = vm()
        viewModel.onChatEvent(requestEvent())
        viewModel.allowPhoneUse("pu-1")
        viewModel.allowPhoneUse("pu-1")
        await { countOf("POST /v1/live/requests/pu-1/answer") == 1 }
        await(1_200) { false }
        assertEquals("one call only", 1, countOf("POST /v1/live/requests/pu-1/answer"))
        assertNull("no spurious notice", viewModel.state.value.chatNotice)
    }

    @Test
    fun `answering invalidates a pending-request read issued before it (H1)`() {
        answers["GET /v1/live/requests/pending"] = 200 to
            """{"request":{"id":"pu-1","task":"T","why":"W","status":"pending","createdAtMs":1}}"""
        answers["POST /v1/live/requests/pu-1/answer"] = 200 to """{"status":"allowed","sessionId":"ls-77"}"""
        val viewModel = vm()
        viewModel.refreshPhoneUsePending()
        await { viewModel.state.value.phoneUsePendingId == "pu-1" }
        val before = viewModel.pendingReadTokenForTest()
        viewModel.allowPhoneUse("pu-1")
        await { viewModel.pendingReadTokenForTest() > before }
        await { countOf("POST /v1/live/requests/pu-1/answer") == 1 }
        // The card stays closed: a read issued before the answer can never re-open it.
        assertNull(viewModel.state.value.phoneUsePendingId)
    }

    @Test
    fun `a transport failure forgets the guard so the owner can retry`() {
        // The request is read and the connection drops: a transport failure, but the attempt is counted.
        socketPolicies["POST /v1/live/requests/pu-1/answer"] = okhttp3.mockwebserver.SocketPolicy.DISCONNECT_AFTER_REQUEST
        val viewModel = vm()
        viewModel.onChatEvent(requestEvent())
        viewModel.allowPhoneUse("pu-1")
        await { viewModel.state.value.chatNotice != null }
        assertTrue("the attempt went out", countOf("POST /v1/live/requests/pu-1/answer") >= 1)
        // The retry (a fresh tap) goes out: the guard is only for settled answers.
        // (OkHttp itself may also retry a dropped connection, so the count is >= and not exact.)
        viewModel.declinePhoneUse("pu-1")
        await { countOf("POST /v1/live/requests/pu-1/answer") >= 2 }
    }

    @Test
    fun `a transport failure says the steward could not be reached`() {
        val viewModel = vm()
        // A dead server: the answer route cannot be reached.
        server?.shutdown()
        viewModel.onChatEvent(requestEvent())
        viewModel.allowPhoneUse("pu-1")
        await { viewModel.state.value.chatNotice != null }
        assertTrue(viewModel.state.value.chatNotice!!.contains("Could not reach"))
    }
}
