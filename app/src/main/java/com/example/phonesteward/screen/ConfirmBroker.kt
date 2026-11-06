package com.example.phonesteward.screen

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import com.example.phonesteward.R

/**
 * The confirm gate's owner prompt: a heads-up notification with Allow and Don't allow,
 * and the same question as a dialog on the Status card when the app is in front. Anything but a
 * clear yes inside [timeoutMs], or a Stop, is a no.
 */
class ConfirmBroker(
    private val appContext: Context,
    private val timeoutMs: Long = 50_000,
) : OwnerConfirmer {

    private class Pending(val request: ConfirmRequest, val id: Long) {
        val latch = CountDownLatch(1)
        @Volatile var allowed = false
    }

    override fun confirm(request: ConfirmRequest): Boolean =
        ask(request) { it == ConfirmOutcome.ALLOWED }

    /** A15.11: the answer as allowed / denied / no answer, so a step's detail can say which happened. */
    override fun confirmOutcome(request: ConfirmRequest): ConfirmOutcome = ask(request) { it }

    private fun <T> ask(request: ConfirmRequest, map: (ConfirmOutcome) -> T): T {
        val pending = Pending(request, nextId.incrementAndGet())
        if (!current.compareAndSet(null, pending)) return map(ConfirmOutcome.DENIED)
        _asking.value = request
        postNotification(request, pending.id)
        return try {
            // A15.11: the request may set its own window (two minutes for allow-for-this-task).
            val answered = pending.latch.await(request.timeoutMs ?: timeoutMs, TimeUnit.MILLISECONDS)
            map(
                when {
                    !answered -> ConfirmOutcome.NO_ANSWER
                    pending.allowed -> ConfirmOutcome.ALLOWED
                    else -> ConfirmOutcome.DENIED
                },
            )
        } finally {
            current.set(null)
            _asking.value = null
            appContext.getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID)
        }
    }

    private fun postNotification(request: ConfirmRequest, questionId: Long) {
        val nm = appContext.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, appContext.getString(R.string.screen_confirm_channel), NotificationManager.IMPORTANCE_HIGH),
        )
        fun action(name: String, code: Int) = PendingIntent.getBroadcast(
            appContext, code,
            Intent(appContext, ConfirmReceiver::class.java).setAction(name).setPackage(appContext.packageName)
                .putExtra(ConfirmReceiver.EXTRA_QUESTION_ID, questionId),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        // A15.11: the allow-for-this-task question asks about the app, in its own words.
        val allowForTask = request.action == "allow_app"
        val title = if (allowForTask) {
            "Allow ${request.label.take(40)} for this task?"
        } else {
            "Allow the steward to tap “${request.label.take(40)}”?"
        }
        val text = if (allowForTask) {
            "It is not on your allow list. Only this task may use it."
        } else {
            "It matched “${request.word}”. Nothing happens until you answer."
        }
        val (allowLabel, denyLabel) = if (allowForTask) "Allow for this task" to "Not now" else "Allow" to "Don't allow"
        val n = Notification.Builder(appContext, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(title)
            .setContentText(text)
            .setCategory(Notification.CATEGORY_CALL)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, allowLabel, action(ConfirmReceiver.ACTION_ALLOW, 1)).build())
            .addAction(Notification.Action.Builder(null, denyLabel, action(ConfirmReceiver.ACTION_DENY, 2)).build())
            .build()
        runCatching { nm.notify(NOTIFICATION_ID, n) }
    }

    companion object {
        const val CHANNEL = "phone_use_confirm"
        const val NOTIFICATION_ID = 7102
        private val current = AtomicReference<Pending?>(null)
        private val _asking = MutableStateFlow<ConfirmRequest?>(null)
        private val nextId = java.util.concurrent.atomic.AtomicLong(0)

        /** The question on screen right now, if any (the Status card shows it as a dialog). */
        val asking: StateFlow<ConfirmRequest?> = _asking.asStateFlow()

        /** The id of the question on screen right now, if any (the notification's answers carry it). */
        val askingId: Long? get() = current.get()?.id

        /** The owner's answer for the question it was drawn for (the dialog passes the question it
         *  shows). An answer whose question is no longer the pending one is ignored (H2: a stale
         *  card or a late tap must not answer a newer question). Identity, not content. */
        fun answer(allowed: Boolean, question: ConfirmRequest) {
            val p = current.get() ?: return
            if (p.request !== question) return
            settle(p, allowed)
            // A15.2: the owner answered the gate's question; the used app comes back to the front.
            if (BackToUsedApp.afterConfirm(PhoneUseState.isActive)) BackToUsedApp.request()
        }

        /** The notification's answer: its intent carries the id of the question it was posted for. */
        fun answerById(allowed: Boolean, questionId: Long) {
            val p = current.get() ?: return
            if (p.id != questionId) return
            settle(p, allowed)
            // A15.2: as above — an answered gate while the session runs hands the screen back.
            if (BackToUsedApp.afterConfirm(PhoneUseState.isActive)) BackToUsedApp.request()
        }

        /** A Stop or a session end releases a waiting question as a no (whatever it is). */
        fun cancelPending() {
            val p = current.get() ?: return
            settle(p, false)
        }

        private fun settle(p: Pending, allowed: Boolean) {
            p.allowed = allowed
            p.latch.countDown()
        }
    }
}

class ConfirmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val questionId = intent.getLongExtra(EXTRA_QUESTION_ID, -1L)
        when (intent.action) {
            ACTION_ALLOW -> ConfirmBroker.answerById(true, questionId)
            ACTION_DENY -> ConfirmBroker.answerById(false, questionId)
        }
    }

    companion object {
        const val ACTION_ALLOW = "com.example.phonesteward.screen.ALLOW"
        const val ACTION_DENY = "com.example.phonesteward.screen.DENY"
        const val EXTRA_QUESTION_ID = "com.example.phonesteward.screen.QUESTION_ID"
    }
}
