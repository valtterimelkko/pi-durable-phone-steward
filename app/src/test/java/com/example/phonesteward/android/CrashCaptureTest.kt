package com.example.phonesteward.android

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import com.example.phonesteward.PhoneStewardApplication
import com.example.phonesteward.logic.CrashOutbox

/**
 * A5 on Android: the app is our Application class, the handler stores the crash and then
 * hands it to Android's own handler (the "keeps stopping" dialog stays), and the next
 * start uploads what was stored.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class CrashCaptureTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun `the manifest names the crash-capturing Application`() {
        assertTrue(context.applicationContext is PhoneStewardApplication)
    }

    @Test
    fun `an uncaught exception is stored, then passed on to the previous handler`() {
        var passedOn: Throwable? = null
        val previous = Thread.UncaughtExceptionHandler { _, e -> passedOn = e }
        val handler = CrashCapture.handler(context, previous)
        val boom = StackOverflowError("deep")
        handler.uncaughtException(Thread.currentThread(), boom)
        assertSame(boom, passedOn)
        val stored = CrashOutbox(CrashCapture.outboxDir(context)).pending()
        assertEquals(1, stored.size)
        assertEquals("java.lang.StackOverflowError", stored[0].report.exception)
    }

    @Test
    fun `the next start uploads stored crashes to the server and empties the outbox`() {
        val server = MockWebServer().also { it.start() }
        try {
            server.enqueue(MockResponse().setBody("""{"accepted":true,"reportId":"cr-1"}"""))
            CrashCapture.handler(context, null).uncaughtException(Thread.currentThread(), IllegalStateException("x"))
            CrashCapture.uploadPending(context, baseUrl = server.url("/").toString(), token = null)
            assertEquals("/v1/crash", server.takeRequest().path)
            assertTrue(CrashOutbox(CrashCapture.outboxDir(context)).pending().isEmpty())
        } finally {
            server.shutdown()
        }
    }
}
