package com.example.phonesteward.logic.shizuku

import java.lang.reflect.InvocationTargetException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Waits for a shell [Process] the way a Shizuku process needs.
 *
 * `rikka.shizuku.ShizukuRemoteProcess` (api 13.1.5) overrides `waitFor()` and `exitValue()` but not
 * `waitFor(long, TimeUnit)`. `java.lang.Process`'s default for that polls `exitValue()` and only tolerates
 * `IllegalThreadStateException`; the remote `exitValue()` throws it in the Shizuku server while the process runs, and
 * Binder delivers it to the app as a plain `IllegalArgumentException`, so every shell command died with
 * "exit -1: IllegalArgumentException" (seen on a real phone). So `waitFor(long, TimeUnit)` is never called
 * here: the blocking `waitFor()` runs on a worker thread and the timeout is kept with a latch.
 *
 * Pure JVM (a `java.lang.Process` in, a [ShellResult] out), so a fake process that behaves like Shizuku's drives the tests.
 */
object ShellProcess {

    /** How long the readers get to finish after the process has exited (the last bytes may still be in the pipe). */
    private const val READER_GRACE_MS = 2_000L

    /** How long the readers get after a timeout and `destroy()`. */
    private const val READER_GRACE_AFTER_KILL_MS = 500L

    /**
     * Runs to the end of [process] or [timeoutMs], whichever is first. On a timeout the process is destroyed and the
     * result is `timedOut` with whatever was printed so far. An exception from the wait itself (a dead binder, say) is
     * thrown to the caller, who reports it with [describe].
     */
    fun run(process: Process, timeoutMs: Long): ShellResult {
        val out = StringBuffer()
        val err = StringBuffer()
        val outReader = reader("shizuku-out") { process.inputStream.bufferedReader().forEachLine { out.append(it).append('\n') } }
        val errReader = reader("shizuku-err") { process.errorStream.bufferedReader().forEachLine { err.append(it).append('\n') } }

        val ended = CountDownLatch(1)
        val code = AtomicInteger(0)
        val failure = AtomicReference<Throwable?>(null)
        val waiter = Thread {
            try {
                code.set(process.waitFor())
            } catch (t: Throwable) {
                failure.set(t)
            } finally {
                ended.countDown()
            }
        }.apply { name = "shizuku-wait"; isDaemon = true; start() }

        if (!ended.await(timeoutMs.coerceAtLeast(0), TimeUnit.MILLISECONDS)) {
            runCatching { process.destroy() }
            waiter.join(READER_GRACE_AFTER_KILL_MS)
            if (waiter.isAlive) waiter.interrupt()
            outReader.join(READER_GRACE_AFTER_KILL_MS)
            errReader.join(READER_GRACE_AFTER_KILL_MS)
            return ShellResult(-1, out.toString(), err.toString(), timedOut = true)
        }
        failure.get()?.let { throw it }
        outReader.join(READER_GRACE_MS)
        errReader.join(READER_GRACE_MS)
        return ShellResult(code.get(), out.toString(), err.toString())
    }

    /**
     * One line for the owner and for diagnostics: the class name and the first line of the message, with reflection and
     * "RuntimeException(cause)" wrappers unwrapped to the real cause, so the next failure on the phone says what it was.
     */
    fun describe(t: Throwable): String {
        var real: Throwable = t
        var hops = 0
        while (hops++ < 8) {
            val cause = real.cause
            val wrapper = real is InvocationTargetException ||
                (real.javaClass == RuntimeException::class.java && cause != null && (real.message == null || real.message == cause.toString()))
            if (wrapper && cause != null) real = cause else break
        }
        val name = real.javaClass.simpleName.ifEmpty { real.javaClass.name }
        val firstLine = real.message?.lineSequence()?.map { it.trim() }?.firstOrNull { it.isNotEmpty() }
        return if (firstLine == null) name else "$name: $firstLine"
    }

    private fun reader(name: String, body: () -> Unit): Thread =
        Thread { runCatching(body) }.apply { this.name = name; isDaemon = true; start() }
}
