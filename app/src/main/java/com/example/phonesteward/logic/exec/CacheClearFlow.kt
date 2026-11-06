package com.example.phonesteward.logic.exec

import java.util.concurrent.CompletableFuture

/**
 * The cache-clear attempt flow (fix 01-6 + fix 02-5), pure JVM with the
 * Android edges injected:
 * - `mainThread` marshals onto the main thread (addObserver and startActivity
 *   are main-thread-only); the default injection is `runOnUiThread`;
 * - the resume observer is registered BEFORE the launch, so the app cannot
 *   miss the resume if Settings is fast;
 * - a launch failure completes the reading as `failed` — the exception is
 *   recorded, never swallowed.
 *
 * Unit-tested with a fake main-thread checker: the real hook lives behind
 * MainActivity's Compose wiring, which Robolectric cannot drive as an
 * activity under this Gradle setup (recorded reason per the correction).
 */
class CacheClearFlow(
    private val readCacheBytes: (String) -> Long?,
    private val mainThread: (Runnable) -> Unit,
    private val launchAppInfo: (String) -> Unit,
    private val observeResumeOnce: (Runnable) -> Unit,
) {

    fun begin(packageName: String): CompletableFuture<CacheClearReading> {
        val future = CompletableFuture<CacheClearReading>()
        mainThread(Runnable {
            val before = readCacheBytes(packageName)
            // Register BEFORE launching: a fast Settings round-trip must not
            // be missed (fix 02-5).
            observeResumeOnce(Runnable {
                future.complete(
                    CacheClearReading(before, readCacheBytes(packageName), launched = true),
                )
            })
            try {
                launchAppInfo(packageName)
            } catch (e: Exception) {
                future.complete(
                    CacheClearReading(before, null, launched = false),
                )
            }
        })
        return future
    }
}
