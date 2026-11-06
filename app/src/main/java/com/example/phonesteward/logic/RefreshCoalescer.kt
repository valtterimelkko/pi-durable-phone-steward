package com.example.phonesteward.logic

/**
 * N10: coalesces screen refreshes to at most one per [minGapMs]. The first
 * request runs at once; requests inside the gap collapse into ONE trailing
 * run at the end of the gap, so the screen still ends on fresh data. Pure:
 * the clock and the delayed call come in as lambdas.
 */
class RefreshCoalescer(
    private val minGapMs: Long,
    private val nowMs: () -> Long,
    private val scheduleLater: (delayMs: Long, action: () -> Unit) -> Unit,
) {
    private var lastRunAtMs: Long? = null
    private var trailingPending = false

    fun request(run: () -> Unit) {
        val delay: Long
        synchronized(this) {
            val last = lastRunAtMs
            val now = nowMs()
            if (last == null || now - last >= minGapMs) {
                lastRunAtMs = now
                delay = -1
            } else {
                if (trailingPending) return
                trailingPending = true
                delay = minGapMs - (now - last)
            }
        }
        if (delay < 0) {
            run()
        } else {
            scheduleLater(delay) {
                synchronized(this) {
                    trailingPending = false
                    lastRunAtMs = nowMs()
                }
                run()
            }
        }
    }
}
