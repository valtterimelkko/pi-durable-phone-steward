package com.example.phonesteward.net

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Network policy proof (brief §8): release builds allow no cleartext; debug
 * builds allow cleartext only to 127.0.0.1, localhost and 10.0.2.2 for
 * disposable validation servers. Reads the real resource files from this
 * checkout, so the manifest's @xml/network_security_config can never drift.
 */
class NetworkSecurityConfigTest {

    private fun moduleDir(): File {
        var dir = File(System.getProperty("user.dir") ?: ".")
        // The working directory may be the module or the repo root.
        return if (File(dir, "src/main/res/xml").isDirectory) dir else File(dir, "app")
    }

    private fun read(vararg segments: String): String =
        File(moduleDir(), segments.joinToString("/")).readText()

    @Test
    fun `release config permits no cleartext at all`() {
        val text = read("src", "main", "res", "xml", "network_security_config.xml")
        assertTrue(text.contains("cleartextTrafficPermitted=\"false\""))
        assertFalse("release must not allow cleartext anywhere", text.contains("cleartextTrafficPermitted=\"true\""))
    }

    @Test
    fun `debug config allows cleartext only to the three loopback hosts`() {
        val text = read("src", "debug", "res", "xml", "network_security_config.xml")
        assertTrue(text.contains("cleartextTrafficPermitted=\"true\""))
        for (host in listOf("127.0.0.1", "localhost", "10.0.2.2")) {
            assertTrue("debug cleartext host $host", text.contains("<domain includeSubdomains=\"false\">$host</domain>"))
        }
        // No other domain line exists.
        val domains = Regex("<domain[^>]*>([^<]+)</domain>").findAll(text).map { it.groupValues[1] }.toList()
        assertTrue("only the three loopback hosts", domains.toSet() == setOf("127.0.0.1", "localhost", "10.0.2.2"))
    }

    @Test
    fun `the manifest declares INTERNET and points at the config`() {
        val manifest = read("src", "main", "AndroidManifest.xml")
        assertTrue(manifest.contains("android.permission.INTERNET"))
        assertTrue(manifest.contains("android:networkSecurityConfig=\"@xml/network_security_config\""))
    }
}
