package com.example.phonesteward.screen

import android.app.Notification
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * A15.11: the allow-for-this-task question answers ALLOWED / DENIED / and, when nobody answers
 * inside the request's own two minutes, NO_ANSWER — so the step's detail can say which happened.
 * The notification asks about the app and offers "Allow for this task" / "Not now".
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33], application = android.app.Application::class)
class ConfirmBrokerOutcomeTest {

    private val app: android.app.Application get() = ApplicationProvider.getApplicationContext()

    @After
    fun tidy() {
        ConfirmBroker.cancelPending()
    }

    private fun ask(request: ConfirmRequest, outcome: AtomicReference<ConfirmOutcome>, asked: CountDownLatch): Thread =
        Thread({
            outcome.set(ConfirmBroker(app).confirmOutcome(request))
            asked.countDown()
        }, "test-confirm").also { it.start() }

    private fun allowForTask(timeoutMs: Long) = ConfirmRequest(
        action = "allow_app",
        label = "Google Play services",
        word = "com.google.android.gms",
        pkg = "com.google.android.gms",
        timeoutMs = timeoutMs,
    )

    private fun postedNotification(): Notification? {
        shadowOf(app).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        val nm = app.getSystemService(android.app.NotificationManager::class.java)
        val until = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < until) {
            shadowOf(android.os.Looper.getMainLooper()).idle()
            shadowOf(nm).activeNotifications.firstOrNull { it.id == ConfirmBroker.NOTIFICATION_ID }?.let { return it.notification }
            Thread.sleep(20)
        }
        return null
    }

    private fun deliver() {
        repeat(5) {
            shadowOf(android.os.Looper.getMainLooper()).idle()
            Thread.sleep(20)
        }
    }

    @Test
    fun `an unanswered question inside the request's two minutes reports no answer`() {
        val outcome = AtomicReference(ConfirmOutcome.ALLOWED)
        val asked = CountDownLatch(1)
        ask(allowForTask(timeoutMs = 400L), outcome, asked)
        assertTrue(asked.await(10, TimeUnit.SECONDS))
        assertEquals(ConfirmOutcome.NO_ANSWER, outcome.get())
    }

    @Test
    fun `the notification asks about the app and offers Allow for this task and Not now`() {
        val outcome = AtomicReference(ConfirmOutcome.NO_ANSWER)
        val asked = CountDownLatch(1)
        ask(allowForTask(timeoutMs = 30_000L), outcome, asked)
        val n = postedNotification()
        assertNotNull("the notification to be posted", n)
        assertEquals("Allow Google Play services for this task?", n!!.extras.getString(Notification.EXTRA_TITLE))
        assertEquals(listOf("Allow for this task", "Not now"), n.actions.map { it.title.toString() })

        n.actions.first { it.title == "Allow for this task" }.actionIntent.send()
        deliver()
        assertTrue(asked.await(10, TimeUnit.SECONDS))
        assertEquals(ConfirmOutcome.ALLOWED, outcome.get())
    }

    @Test
    fun `Not now on the notification reports a denial`() {
        val outcome = AtomicReference(ConfirmOutcome.NO_ANSWER)
        val asked = CountDownLatch(1)
        ask(allowForTask(timeoutMs = 30_000L), outcome, asked)
        val n = postedNotification()
        assertNotNull(n)
        n!!.actions.first { it.title == "Not now" }.actionIntent.send()
        deliver()
        assertTrue(asked.await(10, TimeUnit.SECONDS))
        assertEquals(ConfirmOutcome.DENIED, outcome.get())
    }

    @Test
    fun `an ordinary tap question keeps the plain confirm wording`() {
        val outcome = AtomicReference(ConfirmOutcome.NO_ANSWER)
        val asked = CountDownLatch(1)
        ask(ConfirmRequest("tap", "Clear cache", "Clear", "com.android.settings"), outcome, asked)
        val n = postedNotification()
        assertNotNull(n)
        assertEquals(listOf("Allow", "Don't allow"), n!!.actions.map { it.title.toString() })
        n.actions.first { it.title == "Allow" }.actionIntent.send()
        deliver()
        assertTrue(asked.await(10, TimeUnit.SECONDS))
        assertEquals(ConfirmOutcome.ALLOWED, outcome.get())
    }
}
