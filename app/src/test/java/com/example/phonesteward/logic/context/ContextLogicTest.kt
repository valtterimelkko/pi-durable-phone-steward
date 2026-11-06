package com.example.phonesteward.logic.context

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import com.example.phonesteward.net.ContextInfo

/** A10.9 app half: the "Context NN %" footer and the typed commands. */
class ContextLogicTest {

    @Test
    fun `percent is tokens over the working budget, rounded and capped`() {
        assertEquals(42, ContextFooter.percent(ContextInfo(tokens = 53_760, budget = 128_000)))
        assertEquals(0, ContextFooter.percent(ContextInfo(tokens = 0, budget = 128_000)))
        assertEquals(100, ContextFooter.percent(ContextInfo(tokens = 200_000, budget = 128_000)))
        assertNull(ContextFooter.percent(ContextInfo(tokens = 5, budget = 0)))
        assertNull(ContextFooter.percent(null))
    }

    @Test
    fun `the footer text joins the model and the context`() {
        assertEquals("Context 42 %", ContextFooter.contextText(ContextInfo(tokens = 53_760, budget = 128_000)))
        assertNull(ContextFooter.contextText(null))
    }

    @Test
    fun `typed commands are recognised exactly`() {
        assertEquals(ChatCommand.Compact, ChatCommands.parse("/compact"))
        assertEquals(ChatCommand.Fresh, ChatCommands.parse("/new"))
        assertEquals(ChatCommand.Compact, ChatCommands.parse("  /Compact  "))
        assertNull(ChatCommands.parse("/compact now please"))
        assertNull(ChatCommands.parse("compact"))
        assertNull(ChatCommands.parse("tell me about /new"))
        assertNull(ChatCommands.parse(""))
    }

    @Test
    fun `the outcomes read in plain words`() {
        assertEquals("The steward is working. Try again when it has finished.", ContextFooter.busyText())
        assertEquals("Compacting the steward's memory of this conversation. The chat itself stays as it is.", ContextFooter.compactStarted())
        assertEquals("Starting fresh: the steward is rebuilding its working notes from its records. The chat itself stays as it is.", ContextFooter.freshStarted())
    }
}
