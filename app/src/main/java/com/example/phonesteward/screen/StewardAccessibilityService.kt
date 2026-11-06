package com.example.phonesteward.screen

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import java.util.concurrent.ConcurrentHashMap

/**
 * The screen service (protocol A10.3). It does nothing by itself: it only keeps track of
 * the foreground window and hands the active window to [AndroidScreenDriver] while an owner-started
 * session runs. Every guard is in [SessionRunner], not here.
 *
 * Android blocks accessibility services of apps installed outside Google Play ("restricted settings")
 * until the owner allows it once: App info, the three-dot menu, "Allow restricted settings".
 */
class StewardAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        val cls = event.className?.toString()
        if (cls != null) lastActivity[pkg] = cls
        // The event's own package first (the active window may not have switched yet; a denied app that shows
        // a window at all ends the session, which is the safe direction), then the active window a moment later.
        listeners.forEach { it.onForeground(pkg, cls ?: lastActivity[pkg]) }
        handler.postDelayed({
            foreground()?.let { fg -> listeners.forEach { it.onForeground(fg.pkg, fg.activity) } }
        }, RECHECK_MS)
    }

    private val handler = android.os.Handler(android.os.Looper.getMainLooper())

    override fun onInterrupt() {}

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        if (instance === this) instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    fun foreground(): Foreground? {
        val pkg = rootInActiveWindow?.packageName?.toString() ?: return null
        return Foreground(pkg, lastActivity[pkg])
    }

    fun interface ForegroundListener {
        fun onForeground(pkg: String, activity: String?)
    }

    companion object {
        @Volatile var instance: StewardAccessibilityService? = null
            private set

        private const val RECHECK_MS = 400L
        private val lastActivity = ConcurrentHashMap<String, String>()
        private val listeners = java.util.concurrent.CopyOnWriteArrayList<ForegroundListener>()

        fun addListener(l: ForegroundListener) { listeners += l }
        fun removeListener(l: ForegroundListener) { listeners -= l }

        /** True once Android has bound the service. */
        fun isConnected(): Boolean = instance != null

        /** True when the owner has switched the service on in Accessibility settings (it may not be bound yet). */
        fun isEnabled(context: Context): Boolean {
            val flat = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
            val me = ComponentName(context, StewardAccessibilityService::class.java)
            return flat.split(':').any { entry ->
                ComponentName.unflattenFromString(entry)?.let { it == me } ?: false
            }
        }
    }
}
