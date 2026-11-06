package com.example.phonesteward.update

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import com.example.phonesteward.android.AppGraph
import com.example.phonesteward.logic.IdentityStore
import com.example.phonesteward.logic.PairedIdentity

/**
 * F1 (A7): the update installs IN PLACE through a PackageInstaller session for this app's own
 * package, and nothing in the flow touches the paired identity (the token and base URL), so the
 * pairing survives. Android keeps the app's data when the same package is updated with the same
 * signature; this test pins the app's half of that contract.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class InPlaceInstallTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private class MemoryIdentityStore(var identity: PairedIdentity?) : IdentityStore {
        var writes = 0
        override fun read() = identity
        override fun write(identity: PairedIdentity) { this.identity = identity; writes += 1 }
        override fun clear() { identity = null; writes += 1 }
    }

    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    @Test
    fun `the whole flow over the paired client ends in one session for this package and leaves the pairing alone`() = runBlocking {
        val apkBytes = ByteArray(4096) { (it % 13).toByte() }
        val latestJson = """{"versionName":"0.2.3","versionCode":5,"sha256":"${sha(apkBytes)}","sizeBytes":${apkBytes.size},"publishedAtMs":1}"""
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.requestUrl?.encodedPath) {
                "/v1/app/latest" -> MockResponse().setBody(latestJson)
                "/v1/app/apk" -> MockResponse().setBody(Buffer().write(apkBytes))
                else -> MockResponse().setResponseCode(404)
            }
        }
        server.start()
        val priorFactory = AppGraph.identityStoreFactory
        try {
            val identity = PairedIdentity(server.url("/").toString().trimEnd('/'), "token-that-must-survive")
            val store = MemoryIdentityStore(identity)
            AppGraph.identityStoreFactory = { store }
            AppGraph.resetForTest()
            val graph = AppGraph.get(context)
            val signer = SignerInfo(setOf("aa11"), setOf("aa11"), false)
            val events = mutableListOf<String>()

            val controller = UpdateController(
                installedVersionCode = 4,
                installedVersionName = "0.2.2",
                server = StewardUpdateServer { graph.paired.client() },
                verifier = UpdateVerifier(context.packageName, 4, signer) { ApkInfo(context.packageName, 5, signer) },
                installer = PackageInstallerGateway(context),
                permission = object : InstallPermission {
                    override fun canInstall() = true
                    override fun openSettings() = Unit
                },
                workDir = File(context.cacheDir, "updates"),
                log = object : UpdateLog {
                    override fun event(level: String, event: String, detail: String) { events += "$event $detail".trim() }
                    override fun error(site: String, error: Throwable) { events += "ERROR $site" }
                },
            )
            controller.check()
            controller.update()

            // One install session, for this app's own package (an in-place update).
            val sessions = context.packageManager.packageInstaller.allSessions
            assertEquals(1, sessions.size)
            assertEquals(context.packageName, sessions[0].appPackageName)
            assertTrue(events.toString(), events.contains("update_install committed"))
            assertTrue(events.none { it.startsWith("ERROR") })
            assertEquals(5L, UpdatePrefs.pendingTarget(context))

            // Both requests carried the paired token, and the pairing was never rewritten or cleared.
            val first = server.takeRequest()
            assertEquals("Bearer token-that-must-survive", first.getHeader("Authorization"))
            assertEquals(identity, store.read())
            assertEquals(0, store.writes)
            assertEquals(identity, graph.identityStore.read())
        } finally {
            AppGraph.identityStoreFactory = priorFactory
            AppGraph.resetForTest()
            server.shutdown()
        }
    }
}
