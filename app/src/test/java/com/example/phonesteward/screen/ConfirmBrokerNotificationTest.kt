package com.example.phonesteward.screen

import android.app.Notification
import android.app.NotificationManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * A closure review's test gap (A15.8): the notification's Allow / Don't allow actions
 * carry the question id, and sending the posted `PendingIntent` settles the waiting `confirm()`.
 * Nothing before this test inspected the posted notification; `answerById` alone was unit-tested.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33], application = android.app.Application::class)
class ConfirmBrokerNotificationTest {

    private val app: android.app.Application get() = ApplicationProvider.getApplicationContext()

    @After
    fun tidy() {
        ConfirmBroker.cancelPending()
    }

    /** The broker's heads-up notification while a question waits; null until it is up. */
    private fun postedNotification(): Notification? {
        shadowOf(app).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        val nm = app.getSystemService(NotificationManager::class.java)
        val until = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < until) {
            shadowOf(android.os.Looper.getMainLooper()).idle()
            shadowOf(nm).activeNotifications.firstOrNull { it.id == ConfirmBroker.NOTIFICATION_ID }?.let { return it.notification }
            Thread.sleep(20)
        }
        return null
    }

    /** The owner's question, asked on its own thread like the session runner does. */
    private fun askOnOwnThread(verdict: AtomicBoolean, asked: CountDownLatch): Thread =
        Thread({
            val answer = ConfirmBroker(app).confirm(ConfirmRequest("tap", "Clear cache", "Clear", "com.example.app"))
            verdict.set(answer)
            asked.countDown()
        }, "test-confirm")

    /** Delivers queued main-loop work (a broadcast's onReceive runs there). */
    private fun deliver() {
        repeat(5) {
            shadowOf(android.os.Looper.getMainLooper()).idle()
            Thread.sleep(20)
        }
    }

    @Test
    fun `the notification's Allow action settles the waiting question as a yes`() {
        val asked = CountDownLatch(1)
        val verdict = AtomicBoolean(false)
        askOnOwnThread(verdict, asked).start()
        val n = postedNotification()
        assertNotNull("the notification to be posted", n)

        n!!.actions.first { it.title == "Allow" }.actionIntent.send()
        deliver()

        assertTrue("confirm() to return after the notification tap", asked.await(10, TimeUnit.SECONDS))
        assertTrue("the answer to be a yes", verdict.get())
    }

    @Test
    fun `the notification's Don't allow action settles the waiting question as a no`() {
        val asked = CountDownLatch(1)
        val verdict = AtomicBoolean(true)
        askOnOwnThread(verdict, asked).start()
        val n = postedNotification()
        assertNotNull("the notification to be posted", n)

        n!!.actions.first { it.title == "Don't allow" }.actionIntent.send()
        deliver()

        assertTrue("confirm() to return after the notification tap", asked.await(10, TimeUnit.SECONDS))
        assertTrue("the answer to be a no", !verdict.get())
    }

    @Test
    fun `a stale notification action (an older question's id) settles nothing`() {
        val asked = CountDownLatch(1)
        val verdict = AtomicBoolean(false)
        askOnOwnThread(verdict, asked).start()
        val first = postedNotification()
        assertNotNull("the first notification to be posted", first)
        val staleId = org.robolectric.Shadows.shadowOf(first!!.actions.first { it.title == "Allow" }.actionIntent)
            .savedIntent.getLongExtra(ConfirmReceiver.EXTRA_QUESTION_ID, -1L)
        deliverBroadcast(staleAllowAction(staleId))
        deliver()
        assertTrue(asked.await(10, TimeUnit.SECONDS))
        assertTrue("the first question answered as a yes", verdict.get())

        // A second question is asked; a broadcast carrying the first question's id (as an old
        // notification action would) must not settle the second question.
        val secondAsked = CountDownLatch(1)
        val secondVerdict = AtomicBoolean(true)
        askOnOwnThread(secondVerdict, secondAsked).start()
        assertNotNull("the second notification to be posted", postedNotification())
        deliverBroadcast(staleAllowAction(staleId))
        deliver()
        assertTrue("the second question to still wait", !secondAsked.await(300, TimeUnit.MILLISECONDS))
        assertNotNull("the second question to be pending", ConfirmBroker.asking.value)
    }

    /** The broadcast one of the notification's actions sends, with any question id. */
    private fun staleAllowAction(questionId: Long): android.content.Intent =
        android.content.Intent(app, ConfirmReceiver::class.java)
            .setAction(ConfirmReceiver.ACTION_ALLOW)
            .setPackage(app.packageName)
            .putExtra(ConfirmReceiver.EXTRA_QUESTION_ID, questionId)

    private fun deliverBroadcast(intent: android.content.Intent) = app.sendBroadcast(intent)
}
