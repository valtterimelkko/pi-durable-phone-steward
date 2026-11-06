package com.example.phonesteward.logic.exec

import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fix 02-5: the cache-clear flow marshals onto the main thread, registers
 * the resume observer BEFORE launching, and completes the reading on resume;
 * a launch failure completes as `failed`.
 *
 * Tested with a fake main-thread checker (runs the runnable inline) rather
 * than Robolectric: the real hook lives behind MainActivity's Compose
 * wiring, which cannot be driven as an activity under this Gradle unit-test
 * setup — recorded reason, as the correction allows. The MainActivity side
 * wires runOnUiThread + a real one-shot pause→resume lifecycle observer.
 */
class CacheClearFlowTest {

    @Test
    fun `completes after resume with before and after readings, observer registered before launch`() {
        val cache = mutableMapOf("com.a" to 1_000L)
        var launched = false
        var observerRegisteredBeforeLaunch: Boolean? = null
        var resumeAction: Runnable? = null

        val flow = CacheClearFlow(
            readCacheBytes = { cache[it] },
            mainThread = { it.run() }, // fake main-thread checker: inline
            launchAppInfo = { pkg ->
                observerRegisteredBeforeLaunch = resumeAction != null
                launched = true
                cache[pkg] = 300L // the owner cleared the cache in Settings
            },
            observeResumeOnce = { action ->
                resumeAction = action
            },
        )

        val future = flow.begin("com.a")
        assertTrue(launched)
        assertEquals("the observer must be registered before the launch", true, observerRegisteredBeforeLaunch)
        assertFalse(future.isDone)

        // The owner comes back from Settings: pause then resume.
        resumeAction!!.run()

        val reading = future.get(1, TimeUnit.SECONDS)
        assertTrue(reading.launched)
        assertEquals(1_000L, reading.beforeBytes)
        assertEquals(300L, reading.afterBytes)
        assertEquals(CacheClearOutcome.Verdict("done", 700L), reading.verdict())
    }

    @Test
    fun `a launch failure completes as failed and never swallows silently`() {
        val flow = CacheClearFlow(
            readCacheBytes = { 500L },
            mainThread = { it.run() },
            launchAppInfo = { error("no activity found") },
            observeResumeOnce = { },
        )
        val future = flow.begin("com.a")
        val reading = future.get(1, TimeUnit.SECONDS)
        assertFalse(reading.launched)
        assertEquals(500L, reading.beforeBytes)
        assertEquals(null, reading.afterBytes)
        assertEquals(CacheClearOutcome.Verdict("failed", null), reading.verdict())
    }

    @Test
    fun `an unchanged cache reading completes as cancelled`() {
        val cache = mutableMapOf("com.a" to 500L)
        var resumeAction: Runnable? = null
        val flow = CacheClearFlow(
            readCacheBytes = { cache[it] },
            mainThread = { it.run() },
            launchAppInfo = { },
            observeResumeOnce = { resumeAction = it },
        )
        val future: CompletableFuture<CacheClearReading> = flow.begin("com.a")
        resumeAction!!.run()
        assertEquals(
            CacheClearOutcome.Verdict("cancelled", null),
            future.get(1, TimeUnit.SECONDS).verdict(),
        )
    }
}
