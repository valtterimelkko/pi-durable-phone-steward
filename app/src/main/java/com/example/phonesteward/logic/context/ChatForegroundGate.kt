package com.example.phonesteward.logic.context

/**
 * Correction 1 (review finding 2): A11.4's Chat reload has two triggers — the Chat tab becoming visible and the app
 * returning to the foreground while Chat is showing. At launch they can fire in the same instant, and a reload per
 * trigger would double the settings reads (and move state under a tap). This gate lets one through per [minGapMs].
 * Pure: the clock comes in as a lambda.
 */
class ChatForegroundGate(
    private val nowMs: () -> Long,
    private val minGapMs: Long = 1_000,
) {
    private var lastReloadAtMs: Long? = null

    @Synchronized
    fun shouldReload(): Boolean {
        val now = nowMs()
        val last = lastReloadAtMs
        if (last != null && now - last < minGapMs) return false
        lastReloadAtMs = now
        return true
    }
}
