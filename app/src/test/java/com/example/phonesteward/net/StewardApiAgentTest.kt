package com.example.phonesteward.net

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** The A10.4 and A10.9 routes, as the app's client speaks them. */
class StewardApiAgentTest {

    private lateinit var server: MockWebServer
    private lateinit var api: StewardApi

    @Before
    fun setUp() {
        server = MockWebServer().also { it.start() }
        api = StewardApi(server.url("/").toString().trimEnd('/'), tokenProvider = { "tok" })
    }

    @After
    fun tearDown() {
        api.close()
        server.shutdown()
    }

    @Test
    fun `the catalogue is read from the models route`() {
        server.enqueue(MockResponse().setBody("""{"current":{"provider":"zai","id":"g"},"models":[
          {"provider":"zai","id":"g","label":"G","billing":"subscription","available":true,"reasoning":false,"contextWindow":1000},
          {"provider":"x","id":"y","label":"Y","billing":"metered","available":false,"reasoning":true,"contextWindow":5,"extra":1}]}"""))
        val c = api.agentModels()
        assertEquals("/v1/agent/models", server.takeRequest().path)
        assertEquals(2, c.models.size)
        assertEquals("metered", c.models[1].billing)
        assertEquals(false, c.models[1].available)
    }

    @Test
    fun `choosing a model puts the exact A10_4 body`() {
        server.enqueue(MockResponse().setBody("""{"model":{"provider":"zai","id":"g"}}"""))
        val r = api.chooseModel(AgentModelChoice("req-1", AgentModel("zai", "g"), confirmMetered = true))
        val rec = server.takeRequest()
        assertEquals("PUT", rec.method)
        assertEquals("/v1/agent/settings", rec.path)
        assertEquals("""{"requestId":"req-1","model":{"provider":"zai","id":"g"},"confirmMetered":true}""", rec.body.readUtf8())
        assertEquals("g", r.model.id)
    }

    @Test
    fun `compact and fresh post a request id`() {
        server.enqueue(MockResponse().setResponseCode(202).setBody("""{"accepted":true}"""))
        server.enqueue(MockResponse().setResponseCode(202).setBody("""{"accepted":true}"""))
        api.compactChat(RequestOnly("r1"))
        api.freshChat(RequestOnly("r2"))
        val a = server.takeRequest()
        val b = server.takeRequest()
        assertEquals("/v1/chat/compact", a.path)
        assertEquals("""{"requestId":"r1"}""", a.body.readUtf8())
        assertEquals("/v1/chat/fresh", b.path)
    }

    @Test
    fun `settings carry the context block when the server sends one`() {
        server.enqueue(MockResponse().setBody("""{"conversationId":"main","model":{"provider":"zai","id":"g"},
          "context":{"tokens":10,"budget":100,"lastCompactionMs":5,"lastFreshStartMs":null}}"""))
        val s = api.agentSettings()
        assertEquals(10L, s.context!!.tokens)
        assertEquals(5L, s.context!!.lastCompactionMs)
        assertNull(s.context!!.lastFreshStartMs)
    }

    @Test
    fun `a busy refusal arrives as an ApiException with its code`() {
        server.enqueue(MockResponse().setResponseCode(409).setBody("""{"error":{"code":"AGENT_BUSY","message":"working"}}"""))
        try {
            api.compactChat(RequestOnly("r"))
            error("expected a refusal")
        } catch (e: ApiException) {
            assertEquals("AGENT_BUSY", e.code)
            assertEquals(409, e.httpStatus)
            assertTrue(e.message!!.contains("409"))
        }
    }
}
