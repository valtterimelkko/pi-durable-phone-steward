package com.example.phonesteward.net

import java.net.InetAddress
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import javax.net.SocketFactory
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import com.example.phonesteward.voice.VoiceApi
import org.junit.Assert.fail
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The 0.3.6 crash (cr-04ba47eb10b1): leaving Chat disposed the voice session,
 * and `VoiceApi.close()` ran `connectionPool.evictAll()` on the main thread —
 * after a Read aloud the pool held a live TLS connection, and closing it
 * writes the TLS close message, network I/O on the calling thread. The same
 * release pattern lives in `StewardApi` and `ChatStreamClient`, and
 * `PairedClients` calls their `close()` from `client()`/`streamClient()`/
 * `rebind()`, which can also run on the main thread.
 *
 * These tests prove, for each of the three clients, that after a completed
 * request `close()` returns without the pooled socket being closed on the
 * calling thread, and that the socket does get closed shortly afterwards on
 * another thread. A recording [SocketFactory] attributes every socket close
 * to its thread. On the old code the eviction ran on the caller, so the
 * first assertion failed; these tests were seen red before the fix.
 */
class ClientCloseNoMainThreadIOTest {

    private lateinit var server: MockWebServer
    private lateinit var factory: RecordingSocketFactory

    /** The thread each test runs its requests and `close()` on. */
    private val callingThread: String = Thread.currentThread().name

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        factory = RecordingSocketFactory()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun client(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .socketFactory(factory)
        .build()

    /** Fails unless some socket was closed after [mark], on any thread, within ten seconds. */
    private fun awaitCloseAfter(mark: Int) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (System.nanoTime() < deadline) {
            if (factory.closeEventsAfter(mark).isNotEmpty()) return
            Thread.sleep(20)
        }
        fail("the pooled socket was never closed after close(); recorded events: ${factory.events}")
    }

    @Test
    fun `voiceApi close releases the pooled connection off the calling thread`() {
        val api = VoiceApi(
            server.url("/").toString().trimEnd('/'),
            tokenProvider = { "tok" },
            http = client(),
        )
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"text":"hi"}"""))
        api.transcribe(byteArrayOf(1, 2, 3), "audio/ogg")

        val mark = factory.events.size
        api.close()
        assertTrue(
            "close() closed a socket on the calling thread: ${factory.closeEventsAfter(mark, callingThread)} " +
                "(the 0.3.6 crash)",
            factory.closeEventsAfter(mark, callingThread).isEmpty(),
        )
        awaitCloseAfter(mark)
    }

    @Test
    fun `stewardApi close releases the pooled connection off the calling thread`() {
        val api = StewardApi(
            server.url("/").toString().trimEnd('/'),
            tokenProvider = { "tok" },
            http = client(),
        )
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"jobs": []}"""))
        api.fetchJobs()

        val mark = factory.events.size
        api.close()
        assertTrue(
            "close() closed a socket on the calling thread: ${factory.closeEventsAfter(mark, callingThread)}",
            factory.closeEventsAfter(mark, callingThread).isEmpty(),
        )
        awaitCloseAfter(mark)
    }

    @Test
    fun `chatStreamClient close releases the pooled connection off the calling thread`() {
        val stream = ChatStreamClient(
            server.url("/").toString().trimEnd('/'),
            tokenProvider = { "tok" },
            http = client(),
        )
        // A completed request that ends in an error answer: the body is read, the
        // connection stays alive and returns to the pool.
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":{"code":"AUTH"}}"""))
        try {
            stream.stream(after = null) { }
        } catch (_: ApiException) {
            // the error answer stream() surfaces
        }

        val mark = factory.events.size
        stream.close()
        assertTrue(
            "close() closed a socket on the calling thread: ${factory.closeEventsAfter(mark, callingThread)}",
            factory.closeEventsAfter(mark, callingThread).isEmpty(),
        )
        awaitCloseAfter(mark)
    }
}

/** One recorded socket operation: what happened and on which thread. */
private class SocketEvent(val event: String, val thread: String) {
    override fun toString(): String = "$event@$thread"
}

/**
 * Sockets that record their own close. OkHttp's [RealConnection] creates the
 * raw socket for a plain-HTTP route with `socketFactory.createSocket()`, so
 * every pooled connection here carries a [RecordingSocket].
 */
private class RecordingSocketFactory : SocketFactory() {
    val events = CopyOnWriteArrayList<SocketEvent>()

    fun closeEventsAfter(mark: Int): List<SocketEvent> = events.drop(mark).filter { it.event == "close" }

    fun closeEventsAfter(mark: Int, thread: String): List<SocketEvent> =
        closeEventsAfter(mark).filter { it.thread == thread }

    override fun createSocket(): Socket {
        events.add(SocketEvent("create", Thread.currentThread().name))
        return RecordingSocket(events)
    }

    // Unused by the direct plain-HTTP route OkHttp takes in these tests.
    private val fallback = getDefault()

    override fun createSocket(host: String?, port: Int): Socket = fallback.createSocket(host, port)

    override fun createSocket(host: String?, port: Int, localHost: InetAddress?, localPort: Int): Socket =
        fallback.createSocket(host, port, localHost, localPort)

    override fun createSocket(host: InetAddress?, port: Int): Socket = fallback.createSocket(host, port)

    override fun createSocket(address: InetAddress?, port: Int, localAddress: InetAddress?, localPort: Int): Socket =
        fallback.createSocket(address, port, localAddress, localPort)
}

/** A real socket that records its close before closing. */
private class RecordingSocket(private val events: CopyOnWriteArrayList<SocketEvent>) : Socket() {
    override fun close() {
        events.add(SocketEvent("close", Thread.currentThread().name))
        super.close()
    }
}
