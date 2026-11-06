package com.example.phonesteward.update

import java.io.File
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import com.example.phonesteward.net.ApiException
import com.example.phonesteward.net.StewardApi

/** A7 over the wire: the latest-release read and the streamed APK download. */
class StewardApiUpdateTest {

    private lateinit var server: MockWebServer
    private lateinit var api: StewardApi
    private lateinit var dir: File

    @Before fun setUp() {
        server = MockWebServer().also { it.start() }
        api = StewardApi(server.url("/").toString().trimEnd('/'), tokenProvider = { "tok-1" })
        dir = java.nio.file.Files.createTempDirectory("apiupdate").toFile()
    }

    @After fun tearDown() {
        api.close()
        server.shutdown()
        dir.deleteRecursively()
    }

    private fun error(code: String) = """{"error": {"code": "$code", "message": "text"}}"""

    @Test fun `appLatest reads the release with the bearer token`() {
        server.enqueue(
            MockResponse().setBody(
                """{"versionName":"0.2.3","versionCode":5,"sha256":"${"a".repeat(64)}","sizeBytes":123,"publishedAtMs":99}""",
            ),
        )
        val latest = api.appLatest()
        assertEquals(AppLatest("0.2.3", 5, "a".repeat(64), 123, 99), latest)
        val request = server.takeRequest()
        assertEquals("/v1/app/latest", request.path)
        assertEquals("Bearer tok-1", request.getHeader("Authorization"))
    }

    @Test fun `NO_RELEASE and RELEASE_CORRUPT are protocol errors`() {
        server.enqueue(MockResponse().setResponseCode(404).setBody(error("NO_RELEASE")))
        assertEquals("NO_RELEASE", assertThrows(ApiException::class.java) { api.appLatest() }.code)
        server.enqueue(MockResponse().setResponseCode(500).setBody(error("RELEASE_CORRUPT")))
        assertEquals("RELEASE_CORRUPT", assertThrows(ApiException::class.java) { api.appLatest() }.code)
    }

    @Test fun `downloadApk streams the file for the asked versionCode and reports progress`() {
        val bytes = ByteArray(300_000) { (it % 251).toByte() }
        server.enqueue(MockResponse().setHeader("Content-Type", "application/vnd.android.package-archive").setBody(Buffer().write(bytes)))
        val dest = File(dir, "updates/update-5.apk")
        val seen = mutableListOf<Long>()
        api.downloadApk(5, dest, 1_000_000) { seen += it }
        assertTrue(bytes.contentEquals(dest.readBytes()))
        assertEquals(bytes.size.toLong(), seen.last())
        assertTrue(seen.zipWithNext().all { (a, b) -> b >= a })
        val request = server.takeRequest()
        assertEquals("/v1/app/apk?versionCode=5", request.path)
        assertEquals("Bearer tok-1", request.getHeader("Authorization"))
    }

    @Test fun `a response over the limit is refused by its announced length, before writing`() {
        server.enqueue(MockResponse().setBody(Buffer().write(ByteArray(2_000))))
        val dest = File(dir, "big.apk")
        assertThrows(ApkTooLargeException::class.java) { api.downloadApk(5, dest, 1_000) }
        assertFalse(dest.exists())
    }

    @Test fun `a chunked response that grows past the limit is cut off and removed`() {
        server.enqueue(MockResponse().setChunkedBody(Buffer().write(ByteArray(5_000)), 512))
        val dest = File(dir, "grow.apk")
        assertThrows(ApkTooLargeException::class.java) { api.downloadApk(5, dest, 1_000) }
        assertFalse(dest.exists())
    }

    @Test fun `RELEASE_CHANGED arrives as a protocol error and writes nothing`() {
        server.enqueue(MockResponse().setResponseCode(409).setBody(error("RELEASE_CHANGED")))
        val dest = File(dir, "x.apk")
        assertEquals("RELEASE_CHANGED", assertThrows(ApiException::class.java) { api.downloadApk(4, dest, 1_000) }.code)
        assertFalse(dest.exists())
    }
}
