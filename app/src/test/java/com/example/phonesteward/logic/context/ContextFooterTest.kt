package com.example.phonesteward.logic.context

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import com.example.phonesteward.net.AgentSettingsResponse
import com.example.phonesteward.net.ContextInfo
import com.example.phonesteward.net.ProtocolJson

/** A11.4 + A11.1: the footer reads the share of the model's window, and the new `compactAtTokens` field is tolerated. */
class ContextFooterTest {

    @Test
    fun `the footer is the share of the model's window`() {
        val context = ContextInfo(tokens = 500_000, budget = 1_000_000, compactAtTokens = 750_000)
        assertEquals(50, ContextFooter.percent(context))
        assertEquals("Context 50 %", ContextFooter.contextText(context))
    }

    @Test
    fun `a settings answer that carries compactAtTokens and a new field still parses`() {
        val decoded = ProtocolJson.decodeFromString(
            AgentSettingsResponse.serializer(),
            """{"conversationId":"main","model":{"provider":"zai","id":"m"},
               "context":{"tokens":500000,"budget":1000000,"compactAtTokens":750000,"somethingNew":true}}""",
        )
        assertEquals(750_000L, decoded.context!!.compactAtTokens)
        assertEquals("the footer ignores it", 50, ContextFooter.percent(decoded.context))
    }

    @Test
    fun `an old answer without compactAtTokens still parses`() {
        val decoded = ProtocolJson.decodeFromString(
            AgentSettingsResponse.serializer(),
            """{"conversationId":"main","model":{"provider":"zai","id":"m"},"context":{"tokens":10,"budget":1000}}""",
        )
        assertNull(decoded.context!!.compactAtTokens)
        assertEquals(1, ContextFooter.percent(decoded.context))
    }

    @Test
    fun `no context or no budget means no reading`() {
        assertNull(ContextFooter.percent(null))
        assertNull(ContextFooter.percent(ContextInfo(tokens = 10, budget = 0)))
    }

    @Test
    fun `a full window reads one hundred, never more`() {
        assertEquals(100, ContextFooter.percent(ContextInfo(tokens = 1_000_001, budget = 1_000_000)))
    }
}
