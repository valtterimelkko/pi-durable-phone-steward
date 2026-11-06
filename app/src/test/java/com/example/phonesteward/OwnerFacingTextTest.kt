package com.example.phonesteward

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Owner rule, 2026-10-08: no developer commands in anything the owner reads.
 * Scans the app's main sources (strings the screens show) for command names.
 */
class OwnerFacingTextTest {

    private val forbidden = listOf(
        Regex("""bin/steward"""),
        Regex("""\badb\b"""),
        Regex("""\bcurl\b"""),
    )

    private fun mainSources(): List<File> {
        val root = listOf(File("src/main"), File("app/src/main")).first { it.isDirectory }
        return root.walkTopDown().filter { it.isFile && (it.extension == "kt" || it.extension == "xml") }.toList()
    }

    @Test
    fun `no developer command appears in the app's main sources`() {
        val hits = mainSources().flatMap { file ->
            file.readLines().mapIndexedNotNull { i, line ->
                if (forbidden.any { it.containsMatchIn(line) }) "${file.name}:${i + 1}: ${line.trim()}" else null
            }
        }
        assertTrue("developer commands in owner-facing text: $hits", hits.isEmpty())
    }
}
