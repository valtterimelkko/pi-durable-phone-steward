package com.example.phonesteward.e2e

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import com.example.phonesteward.net.ChatEvent
import com.example.phonesteward.net.PhotoWindow
import com.example.phonesteward.net.Proposal
import com.example.phonesteward.net.ProtocolJson
import com.example.phonesteward.net.RulesResponse

/**
 * A fake steward server on the device's loopback (L4, "end to end"): the protocol of
 * docs/protocol.md section 1 to 7 plus A5 to A8, in memory, with every request recorded.
 * The real [com.example.phonesteward.MainActivity] pairs to it through the real pairing
 * screen. Invented data only; never a real server, token or file name.
 *
 * Long polls and the chat stream answer after at most about a second (the app reconnects),
 * which keeps the dispatcher simple and the tests inside a few seconds of latency.
 */
class FakeSteward : Dispatcher() {

    data class Req(val method: String, val path: String, val body: String, val auth: String?) {
        val route: String get() = path.substringBefore('?')
        val json: JsonObject get() = ProtocolJson.parseToJsonElement(body).jsonObject
        fun str(key: String): String? = json[key]?.jsonPrimitive?.contentOrNull
    }

    val server = MockWebServer()
    val baseUrl: String get() = "http://127.0.0.1:${server.port}"

    val requests = CopyOnWriteArrayList<Req>()

    // ---- identity ----
    val token = "e2e-token-" + "0123456789abcdef".repeat(2)

    /** `GET /v1/jobs` answers this status at once when it is not 200 (a proxy whose backend is restarting); every poll is counted. */
    @Volatile var jobsStatus = 200
    val jobPolls = AtomicLong(0)
    val streamPolls = AtomicLong(0)
    /** Unauthorised answers given after [revoked] was set, for failure messages. */
    val revokedAnswers = AtomicLong(0)

    /** After this is set every authenticated route answers 401 TOKEN_REVOKED. */
    @Volatile var revoked = false

    // ---- data ----
    val proposals = CopyOnWriteArrayList<Proposal>()
    private val proposalStatus = java.util.concurrent.ConcurrentHashMap<String, String>()
    @Volatile var rules: RulesResponse = defaultRules()
    @Volatile var rulesConflictOnNextPut = false

    // ---- decisions and execution ----
    data class Decision(val decisionId: String, val proposalId: String, val approvedItemIds: List<String>)
    val decisions = CopyOnWriteArrayList<Decision>()
    private val decisionSeq = AtomicLong(0)

    /** When set, the next decision on any proposal answers 409 PROPOSAL_NOT_OPEN (it was superseded). */
    @Volatile var supersedeNextDecision = false

    // ---- chat ----
    private val chatEvents = CopyOnWriteArrayList<ChatEvent>()
    private val chatSeq = AtomicLong(0)

    /** Called on a background thread for each owner message the phone sends (after the echo). */
    @Volatile var onChat: ((FakeSteward, String) -> Unit)? = null
    @Volatile var echoOwnerMessages = true

    // ---- jobs (A10: files_query, shell_info, ...) ----
    data class QueuedJob(val jobId: String, val kind: String, val params: JsonObject)
    private val queuedJobs = CopyOnWriteArrayList<QueuedJob>()
    private val jobSeq = AtomicLong(0)
    /** jobId -> the result body the phone reported. */
    val jobResults = java.util.concurrent.ConcurrentHashMap<String, JsonObject>()

    /** Queues a phone job; the app's long poll picks it up at once. Returns its id. */
    fun queueJob(kind: String, params: JsonObject): String {
        val id = "pj-e2e-${jobSeq.incrementAndGet()}"
        queuedJobs.add(QueuedJob(id, kind, params))
        return id
    }

    // ---- agent settings, model catalogue, context (A10.4, A10.9) ----
    @Volatile var currentModel: Pair<String, String> = "zai" to "glm-5.3-flash"
    /** provider, id, label, billing, available */
    data class FakeModel(
        val provider: String,
        val id: String,
        val label: String,
        val billing: String,
        val available: Boolean = true,
        /** A15.3: the catalogue's short reason this model currently fails. */
        val problem: String? = null,
        /** A15.10: an id that follows whatever is newest. */
        val alias: Boolean = false,
    )
    @Volatile var models: List<FakeModel> = listOf(
        FakeModel("zai", "glm-5.3-flash", "GLM 5.3 Flash", "subscription"),
        FakeModel("zai", "glm-5.3", "GLM 5.3", "subscription"),
        FakeModel("deepseek", "v4.1-flash", "DeepSeek V4.1 Flash", "metered"),
        FakeModel("openrouter", "mix-large", "Mix Large", "metered"),
        FakeModel("anthropic", "sonnet", "Sonnet", "subscription", available = false),
        FakeModel("custom", "local-1", "Local One", "unknown"),
    )
    @Volatile var contextTokens = 53_760L
    @Volatile var contextBudget = 128_000L
    @Volatile var lastFreshStartMs: Long? = null
    /** While set, model changes, compaction and fresh starts answer 409 AGENT_BUSY. */
    @Volatile var agentBusy = false

    // ---- app update ----
    /** `GET /v1/app/latest`; null answers 404 NO_RELEASE. */
    @Volatile var latest: JsonObject? = null
    /** `GET /v1/app/apk?versionCode=`: the bytes served, or null for 404. */
    @Volatile var apkBytes: ((versionCode: Long) -> ByteArray?)? = null

    // ---- phone use from Chat (A13) ----
    /** The request `GET /v1/live/requests/pending` answers; null means none pending. */
    @Volatile var pendingPhoneUse: JsonObject? = null
    /** The answers the phone gave (requestId to allow). */
    val phoneUseAnswers = CopyOnWriteArrayList<Pair<String, Boolean>>()
    /** The session id an Allow returns. */
    @Volatile var phoneUseSessionId = "ls-e2e-1"
    /** When true, an Allow answers 409 LIVE_SESSION_ACTIVE. */
    @Volatile var liveSessionActive = false
    /** When true, an Allow answers 409 REQUEST_NOT_PENDING with this status. */
    @Volatile var notPendingStatus: String? = null

    /** The screen steps the session's long poll is served, in order (A10.3 shapes). */
    private val liveSteps = java.util.concurrent.ConcurrentLinkedQueue<JsonObject>()
    /** When the queue is empty this ends the session; null keeps polling. */
    @Volatile var liveEnd: JsonObject? = null
    val liveNextPolls = AtomicLong(0)
    /** The step answers the phone reported, with the step id. */
    val liveStepAnswers = CopyOnWriteArrayList<Pair<String, JsonObject>>()
    val liveStops = CopyOnWriteArrayList<String>()

    /** Queues one screen step (tool, args) the session will be given. */
    fun pushStep(tool: String, args: JsonObject = JsonObject(emptyMap())) {
        liveSteps.add(buildJsonObject { put("stepId", "st-e2e-${liveSteps.size + 1}"); put("tool", tool); put("args", args) })
    }

    fun phoneUseEvent(kind: String, data: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit = {}) =
        emit("phone_use", buildJsonObject { put("kind", kind); data() })

    /** The test's request, with its task remembered for the started event. */
    fun ask(task: String, why: String, requestId: String = "pu-e2e-1") {
        rememberTask[requestId] = task
        pendingPhoneUse = buildJsonObject {
            put("id", requestId); put("task", task); put("why", why); put("status", "pending"); put("createdAtMs", 1L)
        }
        phoneUseEvent("request") {
            put("requestId", requestId); put("task", task); put("why", why); put("atMs", System.currentTimeMillis())
        }
    }


    fun start() {
        server.dispatcher = this
        server.start(InetAddressLoopback, 0)
    }

    fun stop() {
        runCatching { server.shutdown() }
    }

    // ---- helpers for tests ----

    fun requestsTo(method: String, route: String): List<Req> =
        requests.filter { it.method == method && it.route == route }

    fun addProposal(p: Proposal) {
        proposals.add(p)
        proposalStatus[p.proposalId] = "open"
    }

    fun statusOf(proposalId: String): String = proposalStatus[proposalId] ?: "unknown"

    /** Marks a proposal superseded by a newer one (A6.2): it leaves the open list, a decision on it gets 409. */
    fun supersede(proposalId: String) {
        proposalStatus[proposalId] = "superseded"
    }

    /** Appends a chat event with the next server-wide id. Returns the id. */
    fun emit(type: String, data: JsonObject): Long {
        val id = chatSeq.incrementAndGet()
        chatEvents.add(ChatEvent(id, type, data))
        return id
    }

    fun events(): List<ChatEvent> = chatEvents.toList()

    /** A15.10: a message may name the model that produced it; null leaves the field out. */
    fun say(
        role: String,
        text: String,
        atMs: Long = System.currentTimeMillis(),
        model: Triple<String, String, String>? = null,
    ) = emit("message", buildJsonObject {
        put("role", role); put("text", text); put("atMs", atMs)
        model?.let { (provider, id, label) ->
            put("model", buildJsonObject { put("provider", provider); put("id", id); put("label", label) })
        }
    })

    fun runEvent(status: String, reason: String = "owner", durationMs: Long? = null) =
        emit("run", buildJsonObject {
            put("status", status); put("reason", reason); put("atMs", System.currentTimeMillis())
            if (durationMs != null) put("durationMs", durationMs)
        })

    fun toolEvent(name: String, status: String, callId: String, args: String, result: String? = null, durationMs: Long? = null) =
        emit("tool", buildJsonObject {
            put("name", name); put("status", status); put("callId", callId); put("argsSummary", args)
            put("atMs", System.currentTimeMillis())
            if (result != null) put("resultSummary", result)
            if (durationMs != null) put("durationMs", durationMs)
        })

    fun proposalEvent(proposalId: String, title: String) =
        emit("proposal", buildJsonObject { put("proposalId", proposalId); put("title", title); put("atMs", System.currentTimeMillis()) })

    // ---- the protocol ----

    override fun dispatch(request: RecordedRequest): MockResponse {
        val path = request.path.orEmpty()
        val method = request.method.orEmpty()
        val body = request.body.readUtf8()
        val auth = request.getHeader("Authorization")
        val req = Req(method, path, body, auth)
        // The long polls are not interesting; every other request is recorded.
        if (!(method == "GET" && (req.route == "/v1/jobs" || req.route == "/v1/chat/stream"))) requests.add(req)
        return try {
            route(req, request)
        } catch (e: Exception) {
            error(500, "INTERNAL", "fake steward failed: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun route(req: Req, raw: RecordedRequest): MockResponse {
        val r = req.route
        if (req.method == "POST" && r == "/v1/pair") return pair(req)
        if (req.method == "POST" && r == "/v1/crash") return ok("""{"accepted":true,"reportId":"cr-e2e"}""")
        // Everything else needs the bearer token (section 1).
        if (revoked) { revokedAnswers.incrementAndGet(); return error(401, "TOKEN_REVOKED", "This device was revoked.") }
        if (req.auth != "Bearer $token") return error(401, "UNAUTHORIZED", "Missing or wrong token.")
        return when {
            req.method == "GET" && r == "/v1/jobs" -> jobs()
            req.method == "POST" && r == "/v1/device/status" -> ok("""{"accepted":true}""")
            req.method == "GET" && r == "/v1/proposals" -> ok(proposalsJson())
            req.method == "POST" && r.startsWith("/v1/proposals/") && r.endsWith("/decision") -> decision(req)
            req.method == "GET" && r.startsWith("/v1/decisions/") -> decisionCheck(r.removePrefix("/v1/decisions/"))
            req.method == "POST" && r.startsWith("/v1/proposals/") && r.endsWith("/execution") -> execution(req)
            req.method == "GET" && r == "/v1/rules" -> ok(ProtocolJson.encodeToString(RulesResponse.serializer(), rules))
            req.method == "PUT" && r == "/v1/rules" -> putRules(req)
            req.method == "GET" && r == "/v1/chat/history" -> ok(historyJson())
            req.method == "GET" && r == "/v1/chat/stream" -> stream(req, raw)
            req.method == "POST" && r == "/v1/chat" -> chat(req)
            req.method == "POST" && r.startsWith("/v1/jobs/") && r.endsWith("/result") -> jobResult(req)
            req.method == "GET" && r == "/v1/agent/settings" -> ok(agentSettingsJson())
            req.method == "GET" && r == "/v1/agent/models" -> ok(modelsJson())
            req.method == "PUT" && r == "/v1/agent/settings" -> chooseModel(req)
            req.method == "POST" && r == "/v1/files/restored" -> restored(req)
            req.method == "POST" && r == "/v1/chat/compact" -> contextOp(fresh = false)
            req.method == "POST" && r == "/v1/chat/fresh" -> contextOp(fresh = true)
            req.method == "POST" && r.startsWith("/v1/live/requests/") && r.endsWith("/answer") -> phoneUseAnswer(req)
            req.method == "GET" && r == "/v1/live/requests/pending" -> pendingPhoneUseJson()
            req.method == "GET" && r.startsWith("/v1/live/sessions/") && r.endsWith("/next") -> liveNext()
            req.method == "POST" && r.contains("/steps/") -> liveStepAnswer(req)
            req.method == "POST" && r.startsWith("/v1/live/sessions/") && r.endsWith("/stop") -> liveStop(req)
            req.method == "POST" && r == "/v1/live/sessions" -> error(409, "LIVE_SESSION_ACTIVE", "the old start route is not used once A13 ships")
            req.method == "POST" && r == "/v1/voice/transcribe" -> ok(buildJsonObject { put("text", fakeTranscript) }.toString())
            req.method == "POST" && r == "/v1/voice/speak" -> voiceSpeak()
            req.method == "GET" && r == "/v1/app/latest" -> latest()
            req.method == "GET" && r == "/v1/app/apk" -> apk(req)
            else -> error(404, "NOT_FOUND", "no such route: ${req.method} $r")
        }
    }

    // ---- voice (A15.5, A15.6) ----
    /** The text `/v1/voice/transcribe` answers. */
    @Volatile var fakeTranscript: String = "dictated words"

    private fun voiceSpeak(): MockResponse {
        // A tiny 44-byte WAV header plus four PCM bytes; the fake player ignores the content.
        val body = Buffer().write(ByteArray(44)).write(byteArrayOf(0, 0, 0, 0))
        return MockResponse().setResponseCode(200).setHeader("Content-Type", "audio/wav").setBody(body)
    }

    private fun pair(req: Req): MockResponse {
        val code = req.json["code"]?.jsonPrimitive?.contentOrNull.orEmpty().uppercase().replace("-", "")
        return if (code == PAIRING_CODE.replace("-", "")) {
            ok("""{"deviceId":"dev_e2e","token":"$token"}""")
        } else {
            error(400, "PAIRING_CODE_INVALID", "That code did not work.")
        }
    }

    private fun jobs(): MockResponse {
        jobPolls.incrementAndGet()
        if (jobsStatus != 200) return error(jobsStatus, "UNAVAILABLE", "backend restarting")
        if (queuedJobs.isNotEmpty() && !revoked) {
            return ok(buildJsonObject {
                put("jobs", JsonArray(queuedJobs.map {
                    buildJsonObject {
                        put("jobId", it.jobId); put("kind", it.kind); put("params", it.params)
                        put("createdAtMs", System.currentTimeMillis()); put("expiresAtMs", System.currentTimeMillis() + 7 * 86_400_000L)
                    }
                }))
            }.toString())
        }
        Thread.sleep(900) // a long poll that finds nothing queued
        return if (revoked) error(401, "TOKEN_REVOKED", "This device was revoked.") else ok("""{"jobs":[]}""")
    }

    private fun jobResult(req: Req): MockResponse {
        val id = req.route.removePrefix("/v1/jobs/").removeSuffix("/result")
        jobResults[id] = req.json
        queuedJobs.removeAll { it.jobId == id }
        return ok("""{"accepted":true,"duplicate":false}""")
    }

    private fun agentSettingsJson(): String = buildJsonObject {
        put("conversationId", "main")
        put("model", buildJsonObject { put("provider", currentModel.first); put("id", currentModel.second) })
        put("allowlist", JsonArray(emptyList()))
        put("usage", buildJsonObject { put("inputTokens", 1000); put("outputTokens", 200) })
        put("usageByModel", JsonArray(listOf(buildJsonObject {
            put("provider", currentModel.first); put("id", currentModel.second); put("inputTokens", 1000); put("outputTokens", 200)
        })))
        put("context", buildJsonObject {
            put("tokens", contextTokens); put("budget", contextBudget)
            put("lastCompactionMs", JsonNull)
            put("lastFreshStartMs", lastFreshStartMs?.let { JsonPrimitive(it) } ?: JsonNull)
        })
    }.toString()

    private fun modelsJson(): String = buildJsonObject {
        put("current", buildJsonObject { put("provider", currentModel.first); put("id", currentModel.second) })
        put("models", JsonArray(models.map {
            buildJsonObject {
                put("provider", it.provider); put("id", it.id); put("label", it.label); put("billing", it.billing)
                put("available", it.available); put("reasoning", false); put("contextWindow", 128_000)
                it.problem?.let { problem -> put("problem", problem) }
                put("alias", it.alias)
            }
        }))
    }.toString()

    /** A10.4: refusals in the server's order: unknown (404), busy (409), unavailable (409), unconfirmed pay-as-you-go (400). */
    private fun chooseModel(req: Req): MockResponse {
        val m = req.json["model"]?.jsonObject
        val provider = m?.get("provider")?.jsonPrimitive?.contentOrNull
        val id = m?.get("id")?.jsonPrimitive?.contentOrNull
        val entry = models.firstOrNull { it.provider == provider && it.id == id }
            ?: return error(404, "UNKNOWN_MODEL", "no such model")
        if (agentBusy) return error(409, "AGENT_BUSY", "the steward is working")
        if (!entry.available) return error(409, "MODEL_UNAVAILABLE", "no credentials for that provider")
        val confirmed = req.json["confirmMetered"]?.jsonPrimitive?.contentOrNull == "true"
        if (entry.billing != "subscription" && !confirmed) return error(400, "METERED_NEEDS_CONFIRM", "This model is pay-as-you-go.")
        currentModel = entry.provider to entry.id
        return ok(buildJsonObject { put("model", buildJsonObject { put("provider", entry.provider); put("id", entry.id) }) }.toString())
    }

    /** A11.3: one decisions-ledger entry per item that was new; deduped by requestId. */
    private fun restored(req: Req): MockResponse {
        val requestId = req.str("requestId").orEmpty()
        if (requestId.isEmpty()) return error(400, "BAD_REQUEST", "requestId is required")
        val items = (req.json["items"] as? JsonArray).orEmpty()
        return ok(buildJsonObject { put("recorded", items.size) }.toString())
    }

    private fun contextOp(fresh: Boolean): MockResponse {
        if (agentBusy) return error(409, "AGENT_BUSY", "the steward is working")
        if (fresh) {
            val now = System.currentTimeMillis()
            lastFreshStartMs = now
            contextTokens = 2_000
            emit("context", buildJsonObject {
                put("kind", "fresh_start")
                put("text", "Fresh start: the steward now works from its records, not this conversation.")
                put("atMs", now)
            })
        } else {
            contextTokens = (contextTokens / 3)
        }
        return MockResponse().setResponseCode(202).setHeader("Content-Type", "application/json").setBody("""{"accepted":true}""")
    }

    private fun proposalsJson(): String {
        val open = proposals.filter { proposalStatus[it.proposalId] == "open" }
        return buildJsonObject {
            put("proposals", JsonArray(open.map { ProtocolJson.encodeToJsonElement(Proposal.serializer(), it) }))
        }.toString()
    }

    private fun decision(req: Req): MockResponse {
        val id = req.route.removePrefix("/v1/proposals/").removeSuffix("/decision")
        val proposal = proposals.firstOrNull { it.proposalId == id } ?: return error(404, "NOT_FOUND", "unknown proposal")
        if (supersedeNextDecision) {
            supersedeNextDecision = false
            proposalStatus[id] = "superseded"
        }
        if (proposalStatus[id] != "open") {
            return error(409, "PROPOSAL_NOT_OPEN", "This proposal is ${proposalStatus[id]}.")
        }
        val body = req.json
        val approve = body["decision"]?.jsonPrimitive?.content == "approve"
        val itemIds = (body["itemIds"] as? JsonArray)?.map { it.jsonPrimitive.content } ?: proposal.items.map { it.itemId }
        val decisionId = "dc-e2e-${decisionSeq.incrementAndGet()}"
        return if (approve) {
            proposalStatus[id] = "approved"
            decisions.add(Decision(decisionId, id, itemIds))
            ok(buildJsonObject {
                put("decisionId", decisionId)
                put("approvedItemIds", JsonArray(itemIds.map { JsonPrimitive(it) }))
                put("expiresAtMs", System.currentTimeMillis() + 7 * 86_400_000L)
            }.toString())
        } else {
            proposalStatus[id] = "rejected"
            ok(buildJsonObject { put("decisionId", decisionId); put("approvedItemIds", JsonArray(emptyList())) }.toString())
        }
    }

    private fun decisionCheck(decisionId: String): MockResponse {
        val d = decisions.firstOrNull { it.decisionId == decisionId } ?: return error(404, "NOT_FOUND", "unknown decision")
        return ok(buildJsonObject {
            put("valid", true)
            put("proposalId", d.proposalId)
            put("approvedItemIds", JsonArray(d.approvedItemIds.map { JsonPrimitive(it) }))
            put("expiresAtMs", System.currentTimeMillis() + 86_400_000L)
        }.toString())
    }

    private fun execution(req: Req): MockResponse {
        val id = req.route.removePrefix("/v1/proposals/").removeSuffix("/execution")
        val items = (req.json["items"] as? JsonArray).orEmpty()
        val anyDone = items.any { it.jsonObject["outcome"]?.jsonPrimitive?.content == "done" }
        // A6.1: a report in which nothing is done returns the proposal to open.
        proposalStatus[id] = if (anyDone) "executed" else "open"
        return ok("""{"accepted":true}""")
    }

    private fun putRules(req: Req): MockResponse {
        val body = req.json
        val version = body["ruleVersion"]!!.jsonPrimitive.content.toInt()
        if (rulesConflictOnNextPut || version != rules.ruleVersion) {
            rulesConflictOnNextPut = false
            return error(409, "RULE_VERSION_CONFLICT", "The rule changed.")
        }
        val window = ProtocolJson.decodeFromJsonElement(PhotoWindow.serializer(), body["photoWindow"]!!)
        if (window.reminderDays >= window.cutoffDays) return error(400, "BAD_REQUEST", "reminderDays must be below cutoffDays")
        rules = rules.copy(ruleVersion = rules.ruleVersion + 1, photoWindow = window)
        return ok(ProtocolJson.encodeToString(RulesResponse.serializer(), rules))
    }

    private fun eventJson(e: ChatEvent): JsonObject = buildJsonObject {
        put("id", e.id); put("type", e.type); put("data", e.data)
    }

    private fun historyJson(): String =
        buildJsonObject { put("events", JsonArray(chatEvents.map(::eventJson))) }.toString()

    private fun stream(req: Req, raw: RecordedRequest): MockResponse {
        streamPolls.incrementAndGet()
        val after = req.path.substringAfter("after=", "").substringBefore('&').toLongOrNull()
            ?: raw.getHeader("Last-Event-ID")?.toLongOrNull() ?: 0L
        val deadline = System.currentTimeMillis() + 1_000
        while (System.currentTimeMillis() < deadline && !revoked && chatEvents.none { it.id > after }) Thread.sleep(50)
        if (revoked) return error(401, "TOKEN_REVOKED", "This device was revoked.")
        val fresh = chatEvents.filter { it.id > after }
        val text = if (fresh.isEmpty()) ": heartbeat\n\n" else fresh.joinToString("") {
            "id: ${it.id}\nevent: ${it.type}\ndata: ${it.data}\n\n"
        }
        return MockResponse().setResponseCode(200).setHeader("Content-Type", "text/event-stream").setBody(text)
    }

    private fun chat(req: Req): MockResponse {
        val text = req.json["text"]?.jsonPrimitive?.content.orEmpty()
        if (echoOwnerMessages) say("user", text)
        onChat?.let { hook -> Thread { runCatching { hook(this, text) } }.start() }
        return MockResponse().setResponseCode(202).setHeader("Content-Type", "application/json")
            .setBody("""{"accepted":true,"conversationId":"main"}""")
    }

    private fun latest(): MockResponse {
        val l = latest ?: return error(404, "NO_RELEASE", "No release is published.")
        return ok(l.toString())
    }

    private fun apk(req: Req): MockResponse {
        val code = req.path.substringAfter("versionCode=", "").substringBefore('&').toLongOrNull()
            ?: return error(400, "BAD_REQUEST", "versionCode is required")
        val bytes = apkBytes?.invoke(code) ?: return error(404, "NO_RELEASE", "No release is published.")
        return MockResponse().setResponseCode(200)
            .setHeader("Content-Type", "application/vnd.android.package-archive")
            .setBody(Buffer().write(bytes))
    }

    private fun ok(json: String) =
        MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json").setBody(json)

    // ---- A13 route bodies ----

    private fun pendingPhoneUseJson(): MockResponse =
        ok(buildJsonObject { put("request", pendingPhoneUse ?: JsonNull) }.toString())

    private fun phoneUseAnswer(req: Req): MockResponse {
        val id = req.route.removePrefix("/v1/live/requests/").removeSuffix("/answer")
        val allow = req.json["allow"]?.jsonPrimitive?.contentOrNull == "true"
        phoneUseAnswers.add(id to allow)
        notPendingStatus?.let {
            // The real server has moved it out of pending, so it is not offered again (N40).
            pendingPhoneUse = null
            return error(409, "REQUEST_NOT_PENDING", "this request is $it, not pending", it)
        }
        if (liveSessionActive) return error(409, "LIVE_SESSION_ACTIVE", "a phone session is already running")
        if (!allow) {
            phoneUseEvent("answer") { put("requestId", id); put("allowed", false); put("atMs", System.currentTimeMillis()) }
            pendingPhoneUse = null
            return ok("""{"status":"declined"}""")
        }
        pendingPhoneUse = null
        phoneUseEvent("answer") {
            put("requestId", id); put("allowed", true); put("sessionId", phoneUseSessionId); put("atMs", System.currentTimeMillis())
        }
        // A13.3: the session's own event follows the answer (startLiveSession's).
        phoneUseEvent("started") {
            put("sessionId", phoneUseSessionId)
            put("task", pendingTaskOf(id))
            put("atMs", System.currentTimeMillis())
        }
        return ok(buildJsonObject { put("status", "allowed"); put("sessionId", phoneUseSessionId) }.toString())
    }

    private fun pendingTaskOf(id: String): String =
        rememberTask.getOrPut(id) { "" }

    /** The task of a request, remembered when the test asks for one (the started event carries it). */
    val rememberTask = java.util.concurrent.ConcurrentHashMap<String, String>()

    /** The session's long poll: one scripted step, or the end. */
    private fun liveNext(): MockResponse {
        liveNextPolls.incrementAndGet()
        val deadline = System.currentTimeMillis() + 3_000
        while (System.currentTimeMillis() < deadline) {
            liveSteps.poll()?.let { return ok(buildJsonObject { put("step", it) }.toString()) }
            liveEnd?.let { return ok(it.toString()) }
            Thread.sleep(50)
        }
        return ok("""{"step":null}""")
    }

    private fun liveStepAnswer(req: Req): MockResponse {
        val stepId = req.route.substringAfter("/steps/")
        val outcome = req.json["outcome"]?.jsonPrimitive?.contentOrNull.orEmpty()
        liveStepAnswers.add(stepId to req.json)
        // A13.3: the owner sees the step in Chat as soon as it is answered.
        phoneUseEvent("step") {
            put("sessionId", phoneUseSessionId)
            put("tool", req.json["tool"]?.let { (it as? JsonPrimitive)?.content } ?: "screen_observe")
            put("summary", "observe the screen ($stepId)")
            put("outcome", outcome)
            put("atMs", System.currentTimeMillis())
        }
        return ok("""{"accepted":true}""")
    }

    private fun liveStop(req: Req): MockResponse {
        val reason = req.json["reason"]?.let { (it as? JsonPrimitive)?.content } ?: "stopped"
        liveStops.add(reason)
        phoneUseEvent("ended") {
            put("sessionId", phoneUseSessionId); put("reason", reason); put("steps", liveStepAnswers.size); put("atMs", System.currentTimeMillis())
        }
        liveEnd = null
        return ok("""{"changed":true}""")
    }

    private fun error(status: Int, code: String, message: String, statusField: String? = null): MockResponse {
        val obj = buildJsonObject {
            if (statusField != null) put("status", statusField)
            put("error", buildJsonObject { put("code", code); put("message", message) })
        }
        return MockResponse().setResponseCode(status).setHeader("Content-Type", "application/json").setBody(obj.toString())
    }

    private fun error(status: Int, code: String, message: String) =
        MockResponse().setResponseCode(status).setHeader("Content-Type", "application/json")
            .setBody(buildJsonObject { put("error", buildJsonObject { put("code", code); put("message", message) }) }.toString())

    companion object {
        const val PAIRING_CODE = "K7Q4-9MZD"
        private val InetAddressLoopback: java.net.InetAddress = java.net.InetAddress.getByName("127.0.0.1")

        fun defaultRules() = RulesResponse(
            ruleVersion = 3,
            photoWindow = PhotoWindow(
                enabled = false, reminderDays = 90, cutoffDays = 120, autoMode = false,
                folders = listOf("DCIM/Camera/", "Pictures/Screenshots/"), keepMarker = "Keep",
            ),
            batchesRunOneTap = 0,
        )
    }
}
