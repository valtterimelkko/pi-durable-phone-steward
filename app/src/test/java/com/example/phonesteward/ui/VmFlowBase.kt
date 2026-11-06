package com.example.phonesteward.ui

import android.content.Context
import android.os.Looper
import androidx.startup.InitializationProvider
import androidx.test.core.app.ApplicationProvider
import java.util.Collections
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Before
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import com.example.phonesteward.android.AppGraph
import com.example.phonesteward.android.KeystoreIdentityStore
import com.example.phonesteward.logic.IdentityStore
import com.example.phonesteward.logic.PairedIdentity

/**
 * A paired view model against a mock steward, for the P7 flow tests. Every request is recorded as `METHOD path` with its
 * body; a test sets [answers] to override a route's reply (status, body) and [approvedIds] for the decision routes.
 */
abstract class VmFlowBase {

    protected val context: Context get() = ApplicationProvider.getApplicationContext()
    protected var server: MockWebServer? = null

    /** `METHOD /path` -> request bodies, in arrival order. */
    protected val seen: MutableList<Pair<String, String>> = Collections.synchronizedList(mutableListOf())

    /** `METHOD /path` -> (HTTP status, body) overrides. */
    protected val answers: MutableMap<String, Pair<Int, String>> = java.util.concurrent.ConcurrentHashMap()

    /** `METHOD /path` -> a socket policy (a dropped connection, for transport-failure tests). */
    protected val socketPolicies: MutableMap<String, okhttp3.mockwebserver.SocketPolicy> = java.util.concurrent.ConcurrentHashMap()

    @Volatile protected var proposalsJson = """{"proposals":[]}"""
    @Volatile protected var approvedIds = listOf<String>()
    @Volatile protected var decidedProposal = "pr-1"

    protected fun bodiesOf(key: String): List<String> = synchronized(seen) { seen.filter { it.first == key }.map { it.second } }
    protected fun countOf(key: String): Int = bodiesOf(key).size

    private class MemoryIdentityStore(var identity: PairedIdentity?) : IdentityStore {
        override fun read() = identity
        override fun write(identity: PairedIdentity) { this.identity = identity }
        override fun clear() { identity = null }
    }

    private fun approvedJson() = approvedIds.joinToString(",", "[", "]") { "\"$it\"" }

    @Before
    fun baseSetUp() {
        Robolectric.setupContentProvider(InitializationProvider::class.java, "${context.packageName}.androidx-startup")
        AppGraph.resetForTest()
        val mock = MockWebServer()
        mock.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty().substringBefore('?')
                val key = "${request.method} $path"
                seen += key to request.body.readUtf8()
                answers[key]?.let { (code, body) ->
                    return MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)
                }
                socketPolicies[key]?.let { return MockResponse().setSocketPolicy(it) }
                val body = when {
                    path == "/v1/proposals" -> proposalsJson
                    path == "/v1/rules" -> """{"ruleVersion":3,"photoWindow":{"enabled":false,"reminderDays":90,"cutoffDays":120,"autoMode":false,"folders":["DCIM/Camera/"],"keepMarker":"Keep"},"batchesRunOneTap":0}"""
                    path == "/v1/chat/history" -> """{"events":[]}"""
                    path.endsWith("/decision") -> """{"decisionId":"dc-1","approvedItemIds":${approvedJson()},"expiresAtMs":9999999999999}"""
                    path.contains("/decisions/") -> """{"valid":true,"proposalId":"$decidedProposal","approvedItemIds":${approvedJson()},"expiresAtMs":9999999999999}"""
                    else -> """{"accepted":true}"""
                }
                return MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json").setBody(body)
            }
        }
        mock.start()
        server = mock
        AppGraph.identityStoreFactory = { MemoryIdentityStore(PairedIdentity(mock.url("/").toString().trimEnd('/'), "t")) }
    }

    @After
    fun baseTearDown() {
        server?.shutdown()
        AppGraph.identityStoreFactory = { KeystoreIdentityStore(it) }
        AppGraph.resetForTest()
    }

    /** Drains the main looper until [condition] holds or the timeout passes. */
    protected fun await(timeoutMs: Long = 5_000, condition: () -> Boolean) {
        val until = System.currentTimeMillis() + timeoutMs
        while (!condition() && System.currentTimeMillis() < until) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(50)
        }
        shadowOf(Looper.getMainLooper()).idle()
    }

    protected fun newViewModel() = AppViewModel(ApplicationProvider.getApplicationContext())
}
