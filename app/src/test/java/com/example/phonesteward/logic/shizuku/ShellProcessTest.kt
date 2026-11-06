package com.example.phonesteward.logic.shizuku

import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The wait on a Shizuku shell process. `rikka.shizuku.ShizukuRemoteProcess` (api 13.1.5) overrides `waitFor()` and
 * `exitValue()` but NOT `waitFor(long, TimeUnit)`, so `java.lang.Process`'s default polls `exitValue()`, which on the
 * remote throws a plain `IllegalArgumentException` while the process runs (Binder turns the server's
 * `IllegalThreadStateException` into it). [ShizukuLikeProcess] reproduces exactly that.
 */
class ShellProcessTest {

    /** Mimics ShizukuRemoteProcess: blocking `waitFor()`, `exitValue()` throws IAE while running, no `waitFor(long, unit)`. */
    private class ShizukuLikeProcess(
        private val stdout: InputStream = ByteArrayInputStream(ByteArray(0)),
        private val stderr: InputStream = ByteArrayInputStream(ByteArray(0)),
        private val code: Int = 0,
        private val waitFails: Throwable? = null,
    ) : Process() {
        private val done = CountDownLatch(1)
        @Volatile private var exit: Int? = null
        @Volatile var destroyed = false

        fun finish() { exit = code; done.countDown() }

        override fun getOutputStream(): OutputStream = OutputStream.nullOutputStream()
        override fun getInputStream(): InputStream = stdout
        override fun getErrorStream(): InputStream = stderr
        override fun waitFor(): Int {
            waitFails?.let { throw it }
            done.await()
            return exit!!
        }
        override fun exitValue(): Int = exit ?: throw IllegalArgumentException()
        override fun destroy() { destroyed = true; exit = 143; done.countDown() }
        // waitFor(long, TimeUnit) deliberately NOT overridden: Process's default is what broke on the phone.
    }

    @Test
    fun `the root cause - the default timed wait on a Shizuku-like process throws IllegalArgumentException`() {
        val p = ShizukuLikeProcess()
        try {
            p.waitFor(100, TimeUnit.MILLISECONDS)
            fail("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertEquals(IllegalArgumentException::class.java, e.javaClass)
        } finally {
            p.finish()
        }
    }

    @Test
    fun `a command that runs a little and exits returns its code and both streams`() {
        val p = ShizukuLikeProcess(
            stdout = ByteArrayInputStream("package:a.b\npackage:c.d\n".toByteArray()),
            stderr = ByteArrayInputStream("warning one\n".toByteArray()),
            code = 0,
        )
        Thread { Thread.sleep(150); p.finish() }.start()
        val r = ShellProcess.run(p, 5_000)
        assertEquals(0, r.exitCode)
        assertFalse(r.timedOut)
        assertTrue(r.ok)
        assertEquals("package:a.b\npackage:c.d\n", r.stdout)
        assertEquals("warning one\n", r.stderr)
    }

    @Test
    fun `a process that has already exited is read straight away`() {
        val p = ShizukuLikeProcess(stdout = ByteArrayInputStream("done\n".toByteArray()), code = 0)
        p.finish()
        val r = ShellProcess.run(p, 5_000)
        assertEquals(0, r.exitCode)
        assertEquals("done\n", r.stdout)
    }

    @Test
    fun `a non-zero exit reports its code`() {
        val p = ShizukuLikeProcess(stderr = ByteArrayInputStream("Error: no such package\n".toByteArray()), code = 1)
        Thread { Thread.sleep(100); p.finish() }.start()
        val r = ShellProcess.run(p, 5_000)
        assertEquals(1, r.exitCode)
        assertFalse(r.ok)
        assertFalse(r.timedOut)
        assertEquals("Error: no such package\n", r.stderr)
    }

    @Test
    fun `a command that never ends is destroyed and reported as timed out`() {
        val p = ShizukuLikeProcess(stdout = ByteArrayInputStream("partial\n".toByteArray()))
        val start = System.nanoTime()
        val r = ShellProcess.run(p, 300)
        val tookMs = (System.nanoTime() - start) / 1_000_000
        assertTrue(r.timedOut)
        assertFalse(r.ok)
        assertEquals(-1, r.exitCode)
        assertTrue("destroy() must be called", p.destroyed)
        assertTrue("returned after $tookMs ms", tookMs in 250..3_000)
    }

    @Test
    fun `output that arrives just after the exit is still read in full`() {
        val pipeIn = PipedInputStream()
        val pipeOut = PipedOutputStream(pipeIn)
        val p = ShizukuLikeProcess(stdout = pipeIn, code = 0)
        Thread {
            Thread.sleep(100)
            p.finish()                      // the process has exited ...
            Thread.sleep(300)
            pipeOut.write("late line\n".toByteArray())   // ... and the last bytes are still on their way
            pipeOut.close()
        }.start()
        val r = ShellProcess.run(p, 5_000)
        assertEquals(0, r.exitCode)
        assertEquals("late line\n", r.stdout)
    }

    @Test
    fun `a failure of the wait itself is passed on, not turned into an exit code`() {
        val p = ShizukuLikeProcess(waitFails = RuntimeException("binder died"))
        try {
            ShellProcess.run(p, 2_000)
            fail("expected RuntimeException")
        } catch (e: RuntimeException) {
            assertEquals("binder died", e.message)
        }
    }

    // ---- describing a failure (the next one on the phone must be diagnosable) ----

    @Test
    fun `describe carries the class name and the first line of the message`() {
        assertEquals("IllegalStateException: bad thing", ShellProcess.describe(IllegalStateException("bad thing\nsecond line")))
    }

    @Test
    fun `describe gives the class name alone when there is no message`() {
        assertEquals("IllegalArgumentException", ShellProcess.describe(IllegalArgumentException()))
        assertEquals("IllegalArgumentException", ShellProcess.describe(IllegalArgumentException("  ")))
    }

    @Test
    fun `describe unwraps a reflection InvocationTargetException to its cause`() {
        val wrapped = InvocationTargetException(SecurityException("not allowed\nat x"))
        assertEquals("SecurityException: not allowed", ShellProcess.describe(wrapped))
    }

    @Test
    fun `describe unwraps nested wrappers and a RuntimeException that only wraps a cause`() {
        val inner = IllegalArgumentException("deep")
        val t = InvocationTargetException(RuntimeException(inner))
        assertEquals("IllegalArgumentException: deep", ShellProcess.describe(t))
        assertNotNull(ShellProcess.describe(InvocationTargetException(null)))
    }

    // ---- the adapter must not bring the bug back ----

    @Test
    fun `AndroidShizuku never calls the timed waitFor on a process`() {
        val file = java.io.File("src/main/java/com/example/phonesteward/android/AndroidShizuku.kt")
        assertTrue("run from the app module: ${file.absolutePath}", file.isFile)
        val code = file.readLines().map { it.trim() }.filterNot { it.startsWith("//") || it.startsWith("*") || it.startsWith("/*") }
        assertFalse(code.joinToString("\n"), code.any { Regex("""\.waitFor\(\s*[^)\s]""").containsMatchIn(it) })
        assertTrue(code.any { it.contains("ShellProcess.run(") })
    }
}
