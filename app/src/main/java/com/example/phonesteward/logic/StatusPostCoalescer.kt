package com.example.phonesteward.logic

/**
 * N29: pairing, the first resume and the periodic worker each post the device status, so a fresh
 * pairing produced three identical posts within two seconds. [allow] answers true for the first post
 * of a given [key] (the permissions and the trash the status carries) and false for an identical one
 * inside [windowMs]; a different key, or an identical one after the window, goes out. A held-back
 * post does not extend the window. Thread-safe.
 */
class StatusPostCoalescer(
    private val windowMs: Long = 10_000,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    private var lastKey: Any? = null
    private var lastAtMs = 0L

    @Synchronized
    fun allow(key: Any): Boolean {
        val now = nowMs()
        if (key == lastKey && now - lastAtMs in 0..windowMs) return false
        lastKey = key
        lastAtMs = now
        return true
    }

    @Synchronized
    fun reset() {
        lastKey = null
        lastAtMs = 0
    }
}
