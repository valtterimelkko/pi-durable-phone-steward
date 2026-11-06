package com.example.phonesteward.net

import java.util.concurrent.atomic.AtomicReference
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import com.example.phonesteward.logic.IdentityStore
import com.example.phonesteward.logic.PairedIdentity

/**
 * Fix 01-1 (blocker): the base URL and the token are ONE paired identity.
 * After pairing against server B, every client — API and SSE stream —
 * follows B: server A must never see a request, let alone one carrying a
 * token.
 */
class PairedClientsTest {

    private lateinit var serverA: MockWebServer
    private lateinit var serverB: MockWebServer
    private val store = FakeIdentityStore()
    private lateinit var clients: PairedClients

    @Before
    fun setUp() {
        serverA = MockWebServer()
        serverB = MockWebServer()
        serverA.start()
        serverB.start()
        clients = PairedClients(identityProvider = { store.read() })
    }

    @After
    fun tearDown() {
        clients.close()
        serverA.shutdown()
        serverB.shutdown()
    }

    private fun ok(body: String): MockResponse {
        val response = MockResponse().setResponseCode(200).setBody(body)
        // Any long-poll or stream returns immediately in tests.
        return response
    }

    @Test
    fun `after pairing against B, no request reaches A and B carries the bearer`() {
        // The app was "built" pointing at A: an identity exists for A with a token.
        store.write(PairedIdentity(serverA.url("/").toString().trimEnd('/'), "stale-token"))

        // Pre-bind the clients against A (as the app would have at startup).
        serverA.enqueue(ok("""{"jobs": []}"""))
        clients.client().fetchJobs()
        serverA.takeRequest() // the pre-pair poll, carrying the stale token
        assertEquals(1, serverA.requestCount)

        // Pair against B exactly as PairingFlow does: a one-off
        // unauthenticated client at the NEW base URL, then the identity is
        // written and every client rebinds.
        serverB.enqueue(ok("""{"deviceId": "dev_b", "token": "fresh-token"}"""))
        val newRoot = serverB.url("/").toString().trimEnd('/')
        val pairResponse = StewardApi(newRoot).use { it.pair("K7Q4-9MZD", "Example Phone") }
        assertEquals("dev_b", pairResponse.deviceId)
        store.write(PairedIdentity(newRoot, pairResponse.token))
        clients.rebind()

        // Post-pair work: fetch jobs and open a stream.
        serverB.enqueue(ok("""{"jobs": []}"""))
        val jobs = clients.client().fetchJobs(waitSeconds = 0)
        assertTrue(jobs.isEmpty())
        serverB.enqueue(
            ok("""id: 1
event: heartbeat
data: {}

""".trimIndent()),
        )
        clients.streamClient().stream(after = null) { }

        assertEquals("A must see nothing after pairing", 1, serverA.requestCount)
        val pairRequest = serverB.takeRequest() // pairing itself is unauthenticated
        assertNull(pairRequest.getHeader("Authorization"))
        val fetch = serverB.takeRequest()
        assertEquals("Bearer fresh-token", fetch.getHeader("Authorization"))
        assertTrue(fetch.path!!.startsWith("/v1/jobs"))
        val streamRequest = serverB.takeRequest()
        assertEquals("Bearer fresh-token", streamRequest.getHeader("Authorization"))
        assertTrue(streamRequest.path!!.startsWith("/v1/chat/stream"))
    }

    @Test
    fun `a 401 that clears the identity rebinds every client`() {
        store.write(PairedIdentity(serverA.url("/").toString().trimEnd('/'), "tok"))
        serverA.enqueue(ok("""{"jobs": []}"""))
        clients.client().fetchJobs()
        serverA.takeRequest()

        store.clear()
        clients.rebind()

        // No identity: the client is rooted at the placeholder and carries no token.
        serverA.enqueue(ok("""{"jobs": []}"""))
        val dead = clients.client()
        assertThrows(Exception::class.java) { dead.fetchJobs() }
        assertEquals("the cleared identity must not reach the old server", 1, serverA.requestCount)
    }

    @Test
    fun `a handle held across a rebind throws client closed and never reaches the old server (fix 02-1)`() {
        store.write(PairedIdentity(serverA.url("/").toString().trimEnd('/'), "stale-token"))
        val held = clients.client() // generation bound to A

        // Pair against B, exactly as PairingFlow does.
        serverB.enqueue(ok("""{"deviceId": "dev_b", "token": "fresh-token"}"""))
        val newRoot = serverB.url("/").toString().trimEnd('/')
        val pairResponse = StewardApi(newRoot).use { it.pair("K7Q4-9MZD", "Example Phone") }
        store.write(PairedIdentity(newRoot, pairResponse.token))
        clients.rebind()

        // A is ready to answer, in case the held handle wrongly reaches it.
        serverA.enqueue(ok("""{"jobs": []}"""))
        val e = assertThrows(IllegalStateException::class.java) { held.fetchJobs() }
        assertEquals("client closed", e.message)
        assertEquals("the old server must see nothing through a held handle", 0, serverA.requestCount)
    }

    @Test
    fun `the stream client follows the identity rebind, a cached base URL must fail this`() {
        store.write(PairedIdentity(serverA.url("/").toString().trimEnd('/'), "tok-a"))
        val before = clients.streamClient()
        store.write(PairedIdentity(serverB.url("/").toString().trimEnd('/'), "tok-b"))
        clients.rebind()
        val after = clients.streamClient()
        assertTrue("rebind must produce a stream client for the new server", before !== after)

        serverB.enqueue(
            ok("""id: 1
event: message
data: {"role": "assistant", "text": "hi"}

"""),
        )
        var events = 0
        after.stream(after = null) { events++ }
        assertEquals(1, events)
        assertEquals("Bearer tok-b", serverB.takeRequest().getHeader("Authorization"))
        assertEquals("A saw a stream request", 0, serverA.requestCount)
    }
}

private class FakeIdentityStore : IdentityStore {
    private val value = AtomicReference<PairedIdentity?>()
    override fun read(): PairedIdentity? = value.get()
    override fun write(identity: PairedIdentity) {
        value.set(identity)
    }

    override fun clear() {
        value.set(null)
    }
}
