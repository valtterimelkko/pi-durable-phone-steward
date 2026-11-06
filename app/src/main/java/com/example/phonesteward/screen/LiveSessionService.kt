package com.example.phonesteward.screen

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import com.example.phonesteward.R
import com.example.phonesteward.android.AppGraph

/**
 * The foreground service behind a phone-use session: an ongoing "The steward is using your
 * phone" notification with a Stop button, and the thread that runs [SessionRunner]. Only the owner's tap
 * on Start (or a test) starts it; nothing on the server or in a schedule can.
 */
class LiveSessionService : Service() {

    private val runner = AtomicReference<SessionRunner?>(null)
    private val busy = AtomicBoolean(false)
    private val generation = AtomicInteger(0)

    /** A Stop that arrived before the runner existed. */
    @Volatile private var pendingStop = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                pendingStop = true
                runner.get()?.stop()
                ConfirmBroker.cancelPending()
                if (runner.get() == null && !busy.get()) stopSelf()
            }
            ACTION_START -> start(
                intent.getStringExtra(EXTRA_TASK).orEmpty().trim(),
                intent.getStringExtra(EXTRA_SESSION_ID),
                startId,
            )
        }
        return START_NOT_STICKY
    }

    private fun start(task: String, sessionId: String?, startId: Int) {
        if (!busy.compareAndSet(false, true)) return
        val gen = generation.incrementAndGet()
        startForeground(NOTIFICATION_ID, notification("Starting…"), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        pendingStop = false
        PhoneUseState.starting(task)
        val ctx = applicationContext
        val thread = Thread({
            val end = try {
                // A13.2: an Allow started the session on the server; the app joins it by id.
                if (sessionId != null) runSession(ctx, task, sessionId) else runSession(ctx, task)
            } catch (t: Throwable) {
                Log.w(TAG, "phone session failed: ${t.javaClass.simpleName}")
                PhoneUseState.ended("error")
                SessionEnd("error", null)
            } finally {
                ConfirmBroker.cancelPending()
                runner.set(null)
                busy.set(false)
            }
            Log.i(TAG, "session ended: ${end.reason}")
            notifyEnded(end)
            // A newer session may have started meanwhile: leave its foreground state and service alone.
            if (generation.get() == gen) {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf(startId)
            }
        }, "phone-use-session")
        thread.start()
    }

    private fun runSession(ctx: Context, task: String, sessionId: String? = null): SessionEnd {
        val svc = StewardAccessibilityService.instance
        if (svc == null) {
            PhoneUseState.ended("service_off")
            return SessionEnd("service_off", null)
        }
        val channel = channelOverride?.invoke(ctx) ?: run {
            val identity = AppGraph.get(ctx).identityStore.read()
            if (identity == null) {
                PhoneUseState.ended("not_paired")
                return SessionEnd("not_paired", null)
            }
            LiveApi(identity.baseUrl, { identity.token }, log = { Log.i(TAG, it) })
        }
        val driver = AndroidScreenDriver(svc, ctx, foregroundProvider = { svc.foreground() })
        val policy = GuardPolicy(PrefsGuardListStore(ctx).load(), ctx.packageName)
        val updates = object : SessionListener {
            override fun onAction(text: String) = update(text)
            override fun onWaitingForOwner(label: String) = update("Waiting for you: “${label.take(30)}”")
        }
        val r = SessionRunner(
            channel, driver, policy, ConfirmBroker(ctx),
            listener = PhoneUseState.listenerFor(task, updates),
            // A15.11: the allow-for-this-task question names the app, not its package id.
            appLabel = { pkg -> runCatching { AppGraph.get(ctx).installedApps.label(pkg) }.getOrNull() },
        )
        val watcher = StewardAccessibilityService.ForegroundListener { pkg, activity -> r.onForeground(pkg, activity) }
        StewardAccessibilityService.addListener(watcher)
        runner.set(r)
        if (pendingStop) r.stop()
        return try {
            if (sessionId != null) r.runFromSession(sessionId, task) else r.run(task)
        } finally {
            StewardAccessibilityService.removeListener(watcher)
        }
    }

    private fun update(text: String) {
        getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, notification(text))
    }

    private fun notification(text: String): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.screen_notification_channel), NotificationManager.IMPORTANCE_LOW),
        )
        val stop = PendingIntent.getService(
            this, 0, Intent(this, LiveSessionService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle("The steward is using your phone")
            .setContentText(text)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .build()
    }

    private fun notifyEnded(end: SessionEnd) {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        val body = end.summary?.takeIf { it.isNotBlank() } ?: PhoneUseState.endText(end.reason)
        runCatching {
            nm.notify(
                ENDED_ID,
                Notification.Builder(this, CHANNEL)
                    .setSmallIcon(android.R.drawable.ic_menu_view)
                    .setContentTitle("Phone session ended")
                    .setContentText(body.take(200))
                    .setAutoCancel(true)
                    .build(),
            )
        }
    }

    companion object {
        const val ACTION_START = "com.example.phonesteward.screen.START"
        const val ACTION_STOP = "com.example.phonesteward.screen.STOP"
        const val EXTRA_TASK = "task"
        /** A13.2: set when the owner's Allow already started the session on the server. */
        const val EXTRA_SESSION_ID = "sessionId"
        const val CHANNEL = "phone_use"
        const val NOTIFICATION_ID = 7101
        const val ENDED_ID = 7103
        private const val TAG = "PhoneUse"

        /** Test seam: a channel other than the paired server (the real one comes from the paired identity). */
        @Volatile var channelOverride: ((Context) -> LiveChannel)? = null

        /** Starts a session; [sessionId] joins the session the server already started (A13.2). */
        fun start(context: Context, task: String, sessionId: String? = null) {
            context.startForegroundService(
                Intent(context, LiveSessionService::class.java)
                    .setAction(ACTION_START)
                    .putExtra(EXTRA_TASK, task)
                    .putExtra(EXTRA_SESSION_ID, sessionId),
            )
        }

        /** The same thing the notification's Stop button does. */
        fun stop(context: Context) {
            context.startService(Intent(context, LiveSessionService::class.java).setAction(ACTION_STOP))
        }
    }
}
