package com.example.phonesteward.net

import okhttp3.OkHttpClient
import com.example.phonesteward.logic.obs.Diagnostics

/**
 * Releases an [OkHttpClient] without network I/O or blocking work on the calling thread.
 *
 * `dispatcher.cancelAll()` cancels in-flight calls and `connectionPool.evictAll()`
 * closes the pooled connections — and closing a live TLS socket writes the TLS
 * close message, which is network I/O. On the main thread that was the 0.3.6
 * crash (cr-04ba47eb10b1): leaving Chat disposed the voice session, whose
 * `VoiceApi.close()` evicted a pool that held a live TLS connection after a
 * Read aloud. The same release pattern in `StewardApi` and `ChatStreamClient`
 * is reached from `PairedClients` rebinds, which can also run on the main
 * thread.
 *
 * The whole release therefore runs on its own background thread: the caller
 * returns at once, is never blocked and never sees an exception (a failure is
 * reported through [Diagnostics] instead). New calls still fail fast through
 * each client's own `closed` flag, which is set before this helper runs.
 */
object ClientRelease {
    fun release(http: OkHttpClient) {
        val thread = Thread {
            try {
                http.dispatcher.cancelAll()
                http.dispatcher.executorService.shutdown()
                http.connectionPool.evictAll()
            } catch (t: Throwable) {
                Diagnostics.error("client.release", t)
            }
        }
        thread.name = "steward-client-release"
        thread.isDaemon = true
        thread.start()
    }
}
