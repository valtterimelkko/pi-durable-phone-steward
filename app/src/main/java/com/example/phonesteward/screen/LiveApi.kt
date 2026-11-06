package com.example.phonesteward.screen

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import com.example.phonesteward.net.ApiException
import com.example.phonesteward.net.RequestIds

/**
 * The phone side of the live session channel (protocol A10.3): `POST /v1/live/sessions`,
 * `GET .../next?wait=25`, `POST .../steps/{id}`, `POST .../stop`. Synchronous; the session
 * thread owns the calls. A 404 or 409 on `next` and `answer` means the server has ended the
 * session ([SessionGoneException]), except `409 STEP_NOT_OPEN` on an answer: that step was closed
 * (the answer came too late), the session lives and the runner polls `next` again.
 */
class LiveApi(
    baseUrl: String,
    private val tokenProvider: () -> String?,
    private val http: OkHttpClient = defaultClient(),
    private val log: (String) -> Unit = {},
) : LiveChannel {

    private val base = baseUrl.trimEnd('/')
    private val json = Json { ignoreUnknownKeys = true }
    private val mediaType = "application/json; charset=utf-8".toMediaType()

    private fun request(path: String): Request.Builder =
        Request.Builder().url("$base$path").also { b -> tokenProvider()?.let { b.header("Authorization", "Bearer $it") } }

    private fun execute(builder: Request.Builder): Pair<Int, String> =
        http.newCall(builder.build()).execute().use { it.code to it.body?.string().orEmpty() }

    private fun post(path: String, body: JsonObject): Pair<Int, String> =
        execute(request(path).post(body.toString().toRequestBody(mediaType)))

    private fun failure(status: Int, body: String): ApiException {
        val code = runCatching { json.parseToJsonElement(body).jsonObject["error"]?.jsonObject?.get("code")?.jsonPrimitive?.contentOrNull }.getOrNull()
        return ApiException(status, code ?: "UNKNOWN")
    }

    override fun start(task: String): String {
        val (status, body) = post("/v1/live/sessions", buildJsonObject {
            put("requestId", RequestIds.new("live"))
            put("task", task)
        })
        if (status !in 200..299) throw failure(status, body)
        return json.parseToJsonElement(body).jsonObject["sessionId"]?.jsonPrimitive?.contentOrNull
            ?: throw IOException("the server answered without a sessionId")
    }

    override fun next(sessionId: String, waitSeconds: Int): LiveNext {
        val (status, body) = execute(request("/v1/live/sessions/$sessionId/next?wait=$waitSeconds").get())
        if (status == 404 || status == 409) throw SessionGoneException()
        if (status !in 200..299) throw failure(status, body)
        val obj = json.parseToJsonElement(body).jsonObject
        if (obj["ended"]?.jsonPrimitive?.contentOrNull == "true") {
            return LiveNext.Ended(obj["reason"]?.jsonPrimitive?.contentOrNull, obj["summary"]?.jsonPrimitive?.contentOrNull)
        }
        val step = obj["step"]
        if (step == null || step is JsonNull) return LiveNext.Idle
        val s = step.jsonObject
        val args = (s["args"] as? JsonObject).orEmpty().mapValues { (_, v) -> (v as? JsonPrimitive)?.contentOrNull.orEmpty() }
        return LiveNext.Step(
            stepId = s["stepId"]!!.jsonPrimitive.content,
            tool = s["tool"]!!.jsonPrimitive.content,
            args = args,
        )
    }

    override fun answer(sessionId: String, stepId: String, answer: StepAnswerOut) {
        val (status, body) = post("/v1/live/sessions/$sessionId/steps/$stepId", buildJsonObject {
            put("requestId", "ans-$stepId")
            put("outcome", answer.outcome)
            answer.tree?.let { put("tree", it.toJson()) }
            answer.detail?.let { put("detail", it.take(500)) }
            answer.nodeText?.let { put("nodeText", it.take(200)) }
        })
        if (status == 409 && failure(status, body).code == "STEP_NOT_OPEN") {
            log("answer to step $stepId arrived after it closed (409 STEP_NOT_OPEN); the session goes on")
            return
        }
        if (status == 404 || status == 409) throw SessionGoneException()
        if (status !in 200..299) throw failure(status, body)
    }

    override fun stop(sessionId: String, reason: String) {
        val (status, body) = post("/v1/live/sessions/$sessionId/stop", buildJsonObject {
            put("requestId", RequestIds.new("stop"))
            put("reason", reason)
        })
        if (status !in 200..299 && status != 404) throw failure(status, body)
    }

    companion object {
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS) // covers the 25 s long-poll
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()

        private fun JsonObject?.orEmpty(): JsonObject = this ?: JsonObject(emptyMap())
    }
}

