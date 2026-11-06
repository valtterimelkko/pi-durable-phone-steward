package com.example.phonesteward.screen

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest

/**
 * A fake steward for the phone-use device proof: only the live channel under /v1/live/ (protocol A10.3),
 * scripted by the test. Everything else answers 404, which the app tolerates. Invented data only.
 */
class FakeLive : Dispatcher() {
    private val json = Json { ignoreUnknownKeys = true }
    val server = MockWebServer()
    val baseUrl: String get() = "http://127.0.0.1:${server.port}"

    private val queue = LinkedBlockingQueue<String>() // pre-rendered `next` bodies
    private val seq = AtomicInteger(0)
    private val answers = ConcurrentHashMap<String, JsonObject>()
    private val latches = ConcurrentHashMap<String, CountDownLatch>()
    val requests = CopyOnWriteArrayList<String>()
    val stops = CopyOnWriteArrayList<String>()
    @Volatile var startedTask: String? = null
    @Volatile var ended = false

    fun start() {
        server.dispatcher = this
        server.start(java.net.InetAddress.getByName("127.0.0.1"), 0)
    }

    fun stop() {
        runCatching { server.shutdown() }
    }

    /** Queue a step for the phone; returns its id. */
    fun push(tool: String, args: Map<String, String> = emptyMap()): String {
        val id = "st-${seq.incrementAndGet()}"
        latches[id] = CountDownLatch(1)
        queue.add(
            buildJsonObject {
                put("step", buildJsonObject {
                    put("stepId", id)
                    put("tool", tool)
                    put("args", buildJsonObject { args.forEach { (k, v) -> put(k, v) } })
                })
            }.toString(),
        )
        return id
    }

    /** The phone's answer to a step (outcome, tree, detail, nodeText), or fails the test after [timeoutMs]. */
    fun awaitAnswer(stepId: String, timeoutMs: Long = 30_000): JsonObject {
        check(latches[stepId]!!.await(timeoutMs, TimeUnit.MILLISECONDS)) { "no answer to $stepId within ${timeoutMs / 1000} s; requests so far: $requests" }
        return answers[stepId]!!
    }

    fun awaitStop(timeoutMs: Long = 15_000): String {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            stops.firstOrNull()?.let { return it }
            Thread.sleep(100)
        }
        error("the phone never posted stop; requests: $requests")
    }

    override fun dispatch(request: RecordedRequest): MockResponse {
        val path = request.path.orEmpty()
        requests += "${request.method} ${path.substringBefore('?')}"
        val route = path.substringBefore('?')
        fun ok(body: String) = MockResponse().setResponseCode(200).setHeader("content-type", "application/json").setBody(body)
        return when {
            request.method == "POST" && route == "/v1/live/sessions" -> {
                startedTask = json.parseToJsonElement(request.body.readUtf8()).jsonObject["task"]?.jsonPrimitive?.contentOrNull
                ended = false
                ok("""{"sessionId":"ls-proof"}""")
            }
            request.method == "GET" && route == "/v1/live/sessions/ls-proof/next" -> {
                if (ended) return ok("""{"ended":true,"reason":"stopped"}""")
                val next = queue.poll(1500, TimeUnit.MILLISECONDS)
                ok(next ?: """{"step":null}""")
            }
            request.method == "POST" && route.startsWith("/v1/live/sessions/ls-proof/steps/") -> {
                val id = route.substringAfterLast('/')
                answers[id] = json.parseToJsonElement(request.body.readUtf8()).jsonObject
                latches[id]?.countDown()
                ok("""{"accepted":true}""")
            }
            request.method == "POST" && route == "/v1/live/sessions/ls-proof/stop" -> {
                val reason = json.parseToJsonElement(request.body.readUtf8()).jsonObject["reason"]?.jsonPrimitive?.contentOrNull ?: "stopped"
                stops += reason
                ended = true
                ok("""{"stopped":true,"alreadyEnded":false}""")
            }
            else -> MockResponse().setResponseCode(404).setHeader("content-type", "application/json")
                .setBody("""{"error":{"code":"NOT_FOUND","message":"fake"}}""")
        }
    }

    companion object {
        fun JsonObject.outcome(): String = this["outcome"]!!.jsonPrimitive.content
        fun JsonObject.detail(): String? = this["detail"]?.jsonPrimitive?.contentOrNull
        fun JsonObject.nodeTexts(): List<String> =
            (this["tree"]?.jsonObject?.get("nodes") as? kotlinx.serialization.json.JsonArray)
                ?.mapNotNull { it.jsonObject["text"]?.jsonPrimitive?.contentOrNull } ?: emptyList()
        fun JsonObject.treePackage(): String? = this["tree"]?.jsonObject?.get("package")?.jsonPrimitive?.contentOrNull
        fun JsonObject.nodeIdWithText(text: String): String? =
            (this["tree"]?.jsonObject?.get("nodes") as? kotlinx.serialization.json.JsonArray)
                ?.firstOrNull { it.jsonObject["text"]?.jsonPrimitive?.contentOrNull == text }?.jsonObject?.get("id")?.jsonPrimitive?.contentOrNull
    }
}
