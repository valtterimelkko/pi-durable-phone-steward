package com.example.phonesteward.logic.shizuku

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * N32: the phone's never-touch list may protect more than the server's, never less. Reads the server's own source
 * (`server/src/shizuku.ts`, relative to the app module directory Gradle runs unit tests in) so the two cannot drift.
 */
class NeverTouchParityTest {

    private val source: String by lazy {
        val file = listOf("../server/src/shizuku.ts", "server/src/shizuku.ts").map { File(it) }.firstOrNull { it.isFile }
        requireNotNull(file) { "server/src/shizuku.ts not found from ${File(".").absoluteFile}" }.readText()
    }

    /** The text of the array literal that follows [name]: from its opening bracket to the line holding the closing one. */
    private fun block(name: String): String {
        val from = source.indexOf(name)
        require(from >= 0) { "$name not found in server/src/shizuku.ts" }
        val open = Regex("(=|\\()\\s*\\[").find(source, from)
        require(open != null) { "$name has no array literal" }
        val close = source.indexOf("\n]", open.range.last)
        require(close > open.range.last) { "$name block does not close" }
        return source.substring(open.range.last, close)
    }

    private fun quoted(text: String): List<String> =
        Regex("\"([A-Za-z0-9_.]+)\"").findAll(text.lineSequence().filterNot { it.trim().startsWith("//") }.joinToString("\n"))
            .map { it.groupValues[1] }.toList()

    private val neverTouch = NeverTouch(keyboardInUse = { null }, launcher = { null })

    @Test
    fun `every exact name on the server list is protected on the phone`() {
        val names = quoted(block("NEVER_TOUCH_EXACT"))
        assertTrue("parsed too few server names: $names", names.size > 30)
        val missing = names.filterNot { neverTouch.isProtected(it) }
        assertTrue("server exact names the phone does not protect: $missing", missing.isEmpty())
    }

    @Test
    fun `the steward package on the server list is protected on the phone`() {
        val steward = Regex("STEWARD_PACKAGE\\s*=\\s*\"([^\"]+)\"").find(source)?.groupValues?.get(1)
        assertTrue("STEWARD_PACKAGE not found", steward != null)
        assertTrue(neverTouch.isProtected(steward!!))
    }

    @Test
    fun `every prefix on the server list protects the prefix itself and anything under it`() {
        val prefixes = quoted(block("NEVER_TOUCH_PREFIXES"))
        assertTrue("parsed too few server prefixes: $prefixes", prefixes.size >= 10)
        val missing = prefixes.filterNot { neverTouch.isProtected(it) && neverTouch.isProtected("$it.invented.service") }
        assertTrue("server prefixes the phone does not protect: $missing", missing.isEmpty())
    }

    @Test
    fun `the phone keeps its own extra names`() {
        assertTrue(neverTouch.isProtected("com.android.messaging"))
    }
}
