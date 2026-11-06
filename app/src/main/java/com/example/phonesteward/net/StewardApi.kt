package com.example.phonesteward.net

import kotlinx.serialization.encodeToString
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

/** A protocol error answer (§1): one code, the HTTP status, optional Retry-After. */
class ApiException(
    val httpStatus: Int,
    val code: String,
    message: String? = null,
    val retryAfterSeconds: Int? = null,
    /** The raw answer body, when it parsed as JSON: A13.2 puts the current status beside `error`. */
    val bodyJson: JsonObject? = null,
) : RuntimeException("HTTP $httpStatus $code${message?.let { ": $it" } ?: ""}") {

    /** A plain top-level field of the error body (A13.2: `status`), or null. */
    fun field(name: String): String? =
        (bodyJson?.get(name) as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull
}

/** The request never got a protocol answer (DNS, socket, timeout). */
class TransportException(cause: Throwable) : RuntimeException(cause.message, cause)

/**
 * The phone-side client for every /v1 endpoint (docs/protocol.md §1–§7).
 * Pure JVM (OkHttp + kotlinx.serialization), no Android imports.
 * Synchronous on purpose: callers wrap calls in coroutines or workers.
 * Every request except `pair` carries `Authorization: Bearer <token>`.
 */
class StewardApi(
    baseUrl: String,
    private val tokenProvider: () -> String? = { null },
    private val http: OkHttpClient = defaultClient(),
) : AutoCloseable {

    private val base = baseUrl.trimEnd('/')

    /**
     * Set by [close]. A handle kept across a rebind must never touch the
     * network again (fix 02-1): any call throws instead.
     */
    @Volatile
    private var closed = false

    private fun ensureOpen() {
        check(!closed) { "client closed" }
    }

    // ---- transport helpers -------------------------------------------------

    private fun get(path: String): Response = send(Request.Builder().url(url(path)).get())

    private fun delete(path: String): Response = send(Request.Builder().url(url(path)).delete())

    private fun send(builder: Request.Builder): Response {
        ensureOpen()
        return try {
            tokenProvider()?.let { builder.header("Authorization", "Bearer $it") }
            http.newCall(builder.build()).execute()
        } catch (e: Exception) {
            throw TransportException(e)
        }
    }

    private fun body(
        method: String,
        path: String,
        payload: String,
    ): Response = send(
        Request.Builder()
            .url(url(path))
            .method(method, payload.toRequestBody("application/json; charset=utf-8".toMediaType())),
    )

    /** Never carries an Authorization header — used only by `pair` (fix 01-8). */
    private fun bodyUnauthenticated(method: String, path: String, payload: String): Response {
        ensureOpen()
        return try {
            http.newCall(
                Request.Builder()
                    .url(url(path))
                    .method(method, payload.toRequestBody("application/json; charset=utf-8".toMediaType()))
                    .build(),
            ).execute()
        } catch (e: Exception) {
            throw TransportException(e)
        }
    }

    /** One request -> parsed answer or a thrown [ApiException]/[TransportException]. */
    private fun <T> call(request: () -> Response, parse: (String) -> T): T {
        request().use { response ->
            val text = try {
                response.body?.string().orEmpty()
            } catch (e: Exception) {
                throw TransportException(e)
            }
            if (response.isSuccessful) return parse(text)
            throw errorOf(response.code, text, response.header("Retry-After"))
        }
    }

    private fun errorOf(status: Int, body: String, retryHeader: String?): ApiException {
        val envelope = try {
            ProtocolJson.decodeFromString(ErrorEnvelope.serializer(), body)
        } catch (_: Exception) {
            null
        }
        val bodyObject = try {
            ProtocolJson.parseToJsonElement(body) as? JsonObject
        } catch (_: Exception) {
            null
        }
        val headerRetry = retryHeader?.trim()?.toIntOrNull()
        return ApiException(
            httpStatus = status,
            code = envelope?.error?.code ?: "UNKNOWN",
            message = envelope?.error?.message,
            retryAfterSeconds = headerRetry ?: envelope?.error?.retryAfterSeconds,
            bodyJson = bodyObject,
        )
    }

    private inline fun <reified T> post(path: String, payload: String): T =
        call({ body("POST", path, payload) }, ::decodeOrThrow)

    private inline fun <reified T> put(path: String, payload: String): T =
        call({ body("PUT", path, payload) }, ::decodeOrThrow)

    private inline fun <reified T> getJson(path: String): T =
        call({ get(path) }, ::decodeOrThrow)

    // ---- §2 pairing ---------------------------------------------------------

    /** The one unauthenticated request — a stale token can never attach. 400 PAIRING_CODE_INVALID on a used/expired/wrong code. */
    fun pair(code: String, deviceName: String): PairResponse =
        call({ bodyUnauthenticated("POST", "/v1/pair", encode(PairRequest(code, deviceName))) }, ::decodeOrThrow)

    // ---- §3 jobs -------------------------------------------------------------

    /** Long-poll. `wait` 0–30 s; the client read timeout comfortably exceeds 30. */
    fun fetchJobs(waitSeconds: Int = 0): List<Job> {
        val suffix = if (waitSeconds > 0) "?wait=$waitSeconds" else ""
        return getJson<JobsResponse>("/v1/jobs$suffix").jobs
    }

    /** §3.2 one job, one result. 409 RESULT_CONFLICT is final for the caller. */
    fun reportResult(jobId: String, body: JobResultRequest): JobResultResponse =
        post("/v1/jobs/$jobId/result", encode(body))

    // ---- §4 proposals and decisions -------------------------------------------

    fun listProposals(status: String? = null): List<Proposal> {
        val suffix = status?.let { "?status=$it" } ?: ""
        return getJson<ProposalsResponse>("/v1/proposals$suffix").proposals
    }

    fun decide(proposalId: String, body: DecisionRequest): DecisionResponse =
        post("/v1/proposals/$proposalId/decision", encode(body))

    fun checkDecision(decisionId: String): DecisionCheckResponse =
        getJson("/v1/decisions/$decisionId")

    fun reportExecution(proposalId: String, body: ExecutionRequest): ExecutionResponse =
        post("/v1/proposals/$proposalId/execution", encode(body))

    // ---- §5 rules --------------------------------------------------------------

    fun getRules(): RulesResponse = getJson("/v1/rules")

    fun putRules(body: RulesPutRequest): RulesResponse =
        put("/v1/rules", encode(body))

    // ---- §6 chat -----------------------------------------------------------------

    fun sendChat(body: ChatSendRequest): ChatSendResponse =
        post("/v1/chat", encode(body))

    /** History as a JSON array (or an {"events": [...]} envelope). */
    fun chatHistory(limit: Int = 50, before: Long? = null): List<ChatEvent> {
        var path = "/v1/chat/history?limit=$limit"
        if (before != null) path += "&before=$before"
        return call({ get(path) }, { text ->
            val element = ProtocolJson.parseToJsonElement(text)
            val array: JsonArray = when (element) {
                is JsonArray -> element
                is JsonObject -> element["events"] as? JsonArray ?: JsonArray(emptyList())
                else -> JsonArray(emptyList())
            }
            array.map { ProtocolJson.decodeFromJsonElement(ChatEvent.serializer(), it.jsonObjectOrEmpty()) }
        })
    }

    // ---- §7 device status and server status ---------------------------------------

    fun postDeviceStatus(body: DeviceStatusRequest): DeviceStatusResponse =
        post("/v1/device/status", encode(body))

    fun serverStatus(): StatusResponse = getJson("/v1/status")

    fun agentSettings(): AgentSettingsResponse = getJson("/v1/agent/settings")

    // ---- A11.3 Steward trash restore -------------------------------------------------

    /** Tells the server which items went back from the Steward trash; deduped by `requestId`. 400 BAD_PATH on a bad path. */
    fun reportRestored(body: RestoredRequest): RestoredResponse =
        post("/v1/files/restored", encode(body))

    // ---- A10.4 model catalogue and choice, A10.9 context ----

    fun agentModels(): ModelCatalogueResponse = getJson("/v1/agent/models")

    /** 400 METERED_NEEDS_CONFIRM, 404 UNKNOWN_MODEL, 409 AGENT_BUSY / MODEL_UNAVAILABLE arrive as [ApiException]. */
    fun chooseModel(body: AgentModelChoice): AgentModelChoiceResponse = put("/v1/agent/settings", encode(body))

    /** 202; 409 AGENT_BUSY while a run is open. */
    fun compactChat(body: RequestOnly) { post<AcceptedResponse>("/v1/chat/compact", encode(body)) }

    fun freshChat(body: RequestOnly) { post<AcceptedResponse>("/v1/chat/fresh", encode(body)) }

    /** A13.2: the owner's answer to a phone-use request; an Allow returns the started session. */
    fun answerPhoneUse(requestId: String, allow: Boolean): PhoneUseAnswerResponse =
        post("/v1/live/requests/$requestId/answer", encode(PhoneUseAnswerRequest(requestId, allow)))

    /** A13.2: the one request still pending, so a card redraws after a restart. */
    fun pendingPhoneUse(): PhoneUsePendingResponse = getJson("/v1/live/requests/pending")

    // ---- A7 app update ---------------------------------------------------------------

    /** The newest published release; 404 NO_RELEASE (an [ApiException]) when there is none. */
    fun appLatest(): com.example.phonesteward.update.AppLatest = getJson("/v1/app/latest")

    /**
     * Streams the release APK for [versionCode] into [dest], at most [maxBytes]
     * (an [com.example.phonesteward.update.ApkTooLargeException] otherwise,
     * with [dest] removed). [onProgress] gets the bytes written so far. 409
     * RELEASE_CHANGED and 404 NO_RELEASE arrive as [ApiException].
     */
    fun downloadApk(versionCode: Long, dest: java.io.File, maxBytes: Long, onProgress: (Long) -> Unit = {}) {
        send(Request.Builder().url(url("/v1/app/apk?versionCode=$versionCode")).get()).use { response ->
            if (!response.isSuccessful) {
                val text = try {
                    response.body?.string().orEmpty()
                } catch (e: Exception) {
                    throw TransportException(e)
                }
                throw errorOf(response.code, text, response.header("Retry-After"))
            }
            val body = response.body ?: throw TransportException(java.io.IOException("empty body"))
            val announced = body.contentLength()
            if (announced > maxBytes) {
                throw com.example.phonesteward.update.ApkTooLargeException(announced)
            }
            dest.parentFile?.mkdirs()
            var written = 0L
            try {
                body.byteStream().use { input ->
                    dest.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            written += n
                            if (written > maxBytes) {
                                throw com.example.phonesteward.update.ApkTooLargeException(written)
                            }
                            output.write(buffer, 0, n)
                            onProgress(written)
                        }
                    }
                }
            } catch (e: com.example.phonesteward.update.ApkTooLargeException) {
                dest.delete()
                throw e
            } catch (e: java.io.IOException) {
                dest.delete()
                throw TransportException(e)
            }
        }
    }

    // ---- plumbing -------------------------------------------------------------------

    /** Builds an absolute URL for the stream client. Visible for tests. */
    fun url(path: String): String = "$base$path"

    override fun close() {
        closed = true
        // Cancel in-flight and queued calls, then stop the executor —
        // off the calling thread (ClientRelease: closing a live TLS
        // connection is network I/O; the 0.3.6 crash).
        ClientRelease.release(http)
    }

    companion object {
        val JSON_MEDIA_TYPE: okhttp3.MediaType = "application/json; charset=utf-8".toMediaType()

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(90, TimeUnit.SECONDS) // covers the 25 s long-poll
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()

        inline fun <reified T> encode(value: T): String = ProtocolJson.encodeToString<T>(value)

        inline fun <reified T> decodeOrThrow(text: String): T =
            ProtocolJson.decodeFromString(kotlinx.serialization.serializer<T>(), text)
    }
}

private fun kotlinx.serialization.json.JsonElement.jsonObjectOrEmpty(): JsonObject =
    this as? JsonObject ?: JsonObject(emptyMap())
