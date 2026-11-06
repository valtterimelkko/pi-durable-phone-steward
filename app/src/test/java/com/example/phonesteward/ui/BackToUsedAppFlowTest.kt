package com.example.phonesteward.ui

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import com.example.phonesteward.net.ChatEvent
import com.example.phonesteward.screen.BackToUsedApp
import com.example.phonesteward.screen.ConfirmBroker
import com.example.phonesteward.screen.ConfirmRequest
import com.example.phonesteward.screen.PhoneUseState

/**
 * A15.2 through the real view model: sending the answer to a session's open question (or
 * answering the confirm gate) asks the activity to move its task to the back, so the app the
 * session was using comes back to the front. Nothing is asked when no session runs.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class BackToUsedAppFlowTest : VmFlowBase() {

    @Before
    fun chatRoute() {
        // A15.2's hand-back now waits for a *successful* send, so the route must answer in its real
        // shape (the view model parses it; the harness default lacks conversationId).
        answers["POST /v1/chat"] = 200 to """{"accepted":true,"conversationId":"main"}"""
    }

    @After
    fun tidy() {
        PhoneUseState.reset()
        BackToUsedApp.reset()
        ConfirmBroker.cancelPending()
    }

    private fun phoneUseEvent(seq: Long, kind: String) = ChatEvent(
        seq,
        "phone_use",
        buildJsonObject {
            put("kind", kind)
            if (kind == "ask") put("question", "Which label should I open?")
            put("sessionId", "ls-1"); put("atMs", seq)
        },
    )

    @Test
    fun `sending the answer to a session's question asks to hand the screen back`() {
        PhoneUseState.starting("Find the labelled photo")
        val viewModel = newViewModel()
        viewModel.onChatEvent(phoneUseEvent(20, "ask"))
        await { viewModel.state.value.chat.any { it.kind == "phone_use" } }
        viewModel.send("The one called Holiday")
        await { BackToUsedApp.token.value != null }
        assertEquals(true, BackToUsedApp.token.value != null)
    }

    @Test
    fun `a message when no session runs hands nothing back`() {
        PhoneUseState.reset()
        val viewModel = newViewModel()
        viewModel.onChatEvent(phoneUseEvent(20, "ask"))
        await { viewModel.state.value.chat.any { it.kind == "phone_use" } }
        viewModel.send("Hello")
        await { countOf("POST /v1/chat") == 1 }
        assertNull(BackToUsedApp.token.value)
    }

    @Test
    fun `a message while the session is working, not asking, hands nothing back`() {
        PhoneUseState.starting("Find the labelled photo")
        val viewModel = newViewModel()
        viewModel.onChatEvent(phoneUseEvent(20, "step"))
        await { viewModel.state.value.chat.any { it.kind == "phone_use" } }
        viewModel.send("Hello")
        await { countOf("POST /v1/chat") == 1 }
        assertNull(BackToUsedApp.token.value)
    }

    @Test
    fun `a send the steward refuses keeps the owner in Chat, so the notice is seen`() {
        PhoneUseState.starting("Find the labelled photo")
        answers["POST /v1/chat"] = 500 to """{"error":"INTERNAL","message":"the steward is unwell"}"""
        val viewModel = newViewModel()
        viewModel.onChatEvent(phoneUseEvent(20, "ask"))
        await { viewModel.state.value.chat.any { it.kind == "phone_use" } }
        viewModel.send("The one called Holiday")
        await { viewModel.state.value.chatNotice != null }
        assertNull("the failed send must be readable, so no hand-back", BackToUsedApp.token.value)
    }

    @Test
    fun `answering the confirm gate while a session runs asks to hand the screen back`() {
        PhoneUseState.starting("Find the labelled photo")
        val question = ConfirmRequest("tap", "Clear cache", "Clear", "com.example.app")
        val answered = java.util.concurrent.CountDownLatch(1)
        val verdict = java.util.concurrent.atomic.AtomicBoolean(false)
        Thread({
            verdict.set(ConfirmBroker(context).confirm(question))
            answered.countDown()
        }, "test-confirm").start()
        val until = System.currentTimeMillis() + 10_000
        while (ConfirmBroker.asking.value == null && System.currentTimeMillis() < until) Thread.sleep(20)
        BackToUsedApp.reset()
        ConfirmBroker.answer(true, question)
        assertTrue(answered.await(10, java.util.concurrent.TimeUnit.SECONDS))
        assertEquals(true, BackToUsedApp.token.value != null)
    }

    @Test
    fun `answering the confirm gate with no session runs hands nothing back`() {
        PhoneUseState.reset()
        val question = ConfirmRequest("tap", "Clear cache", "Clear", "com.example.app")
        val answered = java.util.concurrent.CountDownLatch(1)
        val verdict = java.util.concurrent.atomic.AtomicBoolean(false)
        Thread({
            verdict.set(ConfirmBroker(context).confirm(question))
            answered.countDown()
        }, "test-confirm").start()
        val until = System.currentTimeMillis() + 10_000
        while (ConfirmBroker.asking.value == null && System.currentTimeMillis() < until) Thread.sleep(20)
        BackToUsedApp.reset()
        ConfirmBroker.answer(true, question)
        assertTrue(answered.await(10, java.util.concurrent.TimeUnit.SECONDS))
        assertNull(BackToUsedApp.token.value)
    }
}
