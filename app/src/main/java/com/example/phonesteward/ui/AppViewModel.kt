package com.example.phonesteward.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import java.util.concurrent.CompletableFuture
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.example.phonesteward.android.AppGraph
import com.example.phonesteward.android.SyncWorker
import com.example.phonesteward.logic.ActionResult
import com.example.phonesteward.logic.Onboarding
import com.example.phonesteward.logic.PermissionPostGate
import com.example.phonesteward.logic.PairingFlow
import com.example.phonesteward.logic.RefreshCoalescer
import com.example.phonesteward.logic.RulesForm
import com.example.phonesteward.logic.exec.ActivityResults
import com.example.phonesteward.logic.exec.ProposalExecutionPlanner
import com.example.phonesteward.logic.exec.ProposalExecutionPlanner.Companion.mediaIdOf
import com.example.phonesteward.logic.exec.RemovedApps
import com.example.phonesteward.net.ApiException
import com.example.phonesteward.logic.chat.ChatLog
import com.example.phonesteward.net.ChatEvent
import com.example.phonesteward.net.DecisionRequest
import com.example.phonesteward.net.DevicePermissions
import com.example.phonesteward.net.ExecutionRequest
import com.example.phonesteward.net.PhotoWindow
import com.example.phonesteward.net.Proposal
import com.example.phonesteward.net.ProtocolJson
import com.example.phonesteward.net.RequestIds
import com.example.phonesteward.net.RulesResponse
import com.example.phonesteward.net.StewardApi
import com.example.phonesteward.net.TransportException

/**
 * One app-wide view model: pairing state, chat, proposals, rules, status;
 * the foreground long-poll and the SSE stream while the app is open.
 * All server calls run on Dispatchers.IO; any 401 returns the app to pairing.
 */
class AppViewModel(app: Application) : AndroidViewModel(app) {

    private val graph = AppGraph.get(app)
    private val api: StewardApi get() = graph.paired.client()

    /** F2: the saved Chat verbosity (the phone's own preferences). */
    internal var chatSettings: com.example.phonesteward.logic.chat.ChatSettings =
        com.example.phonesteward.android.PrefsChatSettings(app)

    /**
     * One row of conversation history, from a chat event (protocol §6, A8) or typed here.
     * `seq` is the server's event id (0 for a message not yet echoed back); tool and run
     * events fill the optional fields (`name`, `status`, summaries, `durationMs`).
     */
    data class ChatEntry(
        val role: String,
        val text: String,
        val atMs: Long = 0,
        val kind: String = "message", // message | tool | proposal | run | phone_use
        val seq: Long = 0,
        /** The highest event id folded into this entry (a tool call is two events). */
        val lastSeq: Long = 0,
        val name: String = "",
        val status: String = "",
        val reason: String? = null,
        val callId: String? = null,
        val argsSummary: String? = null,
        val resultSummary: String? = null,
        val durationMs: Long? = null,
        val proposalId: String? = null,
        /** A13.6: the phone-use payload of a `phone_use` event; null for every other kind. */
        val phone: PhoneUseEntry? = null,
        /** A15.10: the model that answered this message (additive on the event); null when it says none. */
        val answering: com.example.phonesteward.net.AnsweringModel? = null,
    )

    /** One `phone_use` chat event (protocol A13.3). Unknown kinds never reach an entry. */
    data class PhoneUseEntry(
        val kind: String, // request | answer | started | step | say | ask | ended
        val requestId: String? = null,
        val sessionId: String? = null,
        val askId: String? = null,
        val task: String? = null,
        val why: String? = null,
        val question: String? = null,
        val summary: String? = null,
        val tool: String? = null,
        val outcome: String? = null,
        val allowed: Boolean? = null,
        val reason: String? = null,
        val steps: Int? = null,
        /** For a request: pending (default) or the folded answer's outcome. */
        val status: String = "pending",
        /** A15.10: the session's model, as its `started` event names it (additive); null when absent. */
        val model: com.example.phonesteward.net.AnsweringModel? = null,
    )

    data class AppState(
        val paired: Boolean = false,
        val baseUrl: String = "",
        val chat: List<ChatEntry> = emptyList(),
        /** F2: how much of the agent's work Chat shows; saved on the phone. */
        val chatVerbosity: com.example.phonesteward.logic.chat.ChatVerbosity =
            com.example.phonesteward.logic.chat.ChatVerbosity.Default,
        val proposals: List<Proposal> = emptyList(),
        val rules: RulesResponse? = null,
        val busy: Boolean = false,
        val notice: String? = null,
        /** shown in Chat when a message could not be sent; cleared by the owner or by the next send. */
        val chatNotice: String? = null,
        /** N16: the outcome of the owner's last approve/reject, shown on Proposals where it happened. */
        val result: ActionResult? = null,
        /** N2: the live permission snapshot, re-read on resume and refresh. */
        val permissions: Onboarding.Snapshot? = null,
        /** N31: the `uninstall_app` items whose app is no longer installed ("Already removed"). */
        val removedItemIds: Set<String> = emptySet(),
        /** A10.1: what the Steward trash holds now. */
        val stewardTrash: com.example.phonesteward.net.StewardTrashStatus = com.example.phonesteward.net.StewardTrashStatus(),
        /** A10.1: the outcome of the owner's last Empty Steward trash, shown on Status. */
        val trashResult: ActionResult? = null,
        /** A10.2: Shizuku's four-way state, for the Status card. */
        val shizuku: com.example.phonesteward.logic.shizuku.ShizukuState = com.example.phonesteward.logic.shizuku.ShizukuState.ABSENT,
        /** A10.4 / A10.9: the steward's model and context, read from `GET /v1/agent/settings`. */
        val agent: com.example.phonesteward.net.AgentSettingsResponse? = null,
        /** The model catalogue, loaded when the picker opens. */
        val models: com.example.phonesteward.net.ModelCatalogueResponse? = null,
        /** A calm line above the message box (Compact now started, ...); the error form is [chatNotice]. */
        val chatInfo: String? = null,
        /** A13.6: the request the server still has pending (its Chat card shows live buttons). */
        val phoneUsePendingId: String? = null,
        /** The one in-app confirmation waiting for the owner (files to the Steward trash, Empty trash, Start fresh, ...). */
        val confirm: PendingConfirm? = null,
        /** A11.3: the Steward trash screen is open (Status tab). */
        val trashOpen: Boolean = false,
        /** A11.3: the trash as the restore screen lists it; null until it has been read. */
        val trashBatches: List<com.example.phonesteward.logic.files.TrashBatch>? = null,
        /** A11.3: the outcome of the owner's last restore, on the trash screen. */
        val trashScreenResult: ActionResult? = null,
        /** a restore is running, so a second tap cannot race it (the Restore buttons are held). */
        val trashRestoring: Boolean = false,
    )

    /** An in-app "are you sure?" (Android has no dialog for these). Answer it with [answerConfirm]. */
    data class PendingConfirm(
        val title: String,
        val body: String,
        val confirmLabel: String,
        /** Draws the confirm button as a warning (emptying the trash is permanent). */
        val destructive: Boolean = false,
    )

    private val _state = kotlinx.coroutines.flow.MutableStateFlow(
        // the server address field starts with the saved or default address, never empty.
        AppState(paired = graph.pairing.isPaired(), baseUrl = graph.baseUrl(), chatVerbosity = chatSettings.read()),
    )
    val state: kotlinx.coroutines.flow.StateFlow<AppState> = _state

    /** UI hooks for the Android-side launches (trash dialog, uninstall, cache clear). */
    interface ExecutionHooks {
        fun launchTrash(uris: List<Uri>): CompletableFuture<Int>
        fun launchUninstall(packageName: String): CompletableFuture<Int>

        /**
         * Opens App info for the app and completes when the user comes back:
         * the reading carries the app's cacheBytes before the launch and
         * after the resume, plus whether the launch succeeded (fix 01-6).
         */
        fun beginCacheClear(packageName: String): CompletableFuture<com.example.phonesteward.logic.exec.CacheClearReading>

        /**
         * A6 (N6): ONE clear-temporary-files request for every app's cache;
         * the future completes with the mapped verdict once Android answers.
         */
        fun beginAllCaches(item: ProposalExecutionPlanner.AllCachesPlan): CompletableFuture<com.example.phonesteward.logic.exec.AllCachesOutcome.Verdict>
    }

    var executionHooks: ExecutionHooks? = null

    /**
     * 0.2.4: "is it installed?" and the app's size, read from the phone around each uninstall dialog.
     * Tests replace it; the default asks the package manager and storage stats.
     */
    internal var uninstallFlow: com.example.phonesteward.logic.exec.UninstallFlow =
        com.example.phonesteward.android.AndroidUninstallProbe.flow(app)

    /** N31: "is this package installed?"; tests replace it. */
    internal var installedProbe: (String) -> Boolean =
        { com.example.phonesteward.android.AndroidUninstallProbe.isInstalled(app, it) }

    internal fun graphForTest() = graph

    /** Tests replace the never-touch list; the real one adds the keyboard in use and the home app. */
    internal var neverTouchForTest: com.example.phonesteward.logic.shizuku.NeverTouch? = null

    /** The summed cache of every app (all-or-null), read before and after a `trim_caches`. */
    internal var cacheMeter: () -> Long? = { com.example.phonesteward.android.AndroidCacheMeter.total(app) }

    /** Whether an app is enabled now, read after a `disable_app` / `enable_app`; null when unreadable. */
    internal var appEnabledProbe: (String) -> Boolean? = { com.example.phonesteward.android.AndroidCacheMeter.isEnabled(app, it) }

    /** A10.2: the owner taps Allow Shizuku; Shizuku shows its own dialog. */
    fun requestShizukuPermission() {
        viewModelScope.launch(Dispatchers.IO) {
            graph.shizuku.requestPermission()
            readShizuku()
        }
    }

    fun refreshShizuku() {
        viewModelScope.launch(Dispatchers.IO) { readShizuku() }
    }

    private fun readShizuku() {
        val now = try {
            graph.shizuku.state()
        } catch (e: Exception) {
            com.example.phonesteward.logic.obs.Diagnostics.error("shizuku.state", e)
            com.example.phonesteward.logic.shizuku.ShizukuState.ABSENT
        }
        val before = _state.value.shizuku
        _state.update { if (it.shizuku == now) it else it.copy(shizuku = now) }
        // The server prefers trim_caches once Shizuku is ready, so a change is told to it at once.
        if (before != now && _state.value.paired) viewModelScope.launch(Dispatchers.IO) { postDeviceStatus() }
    }

    private var shizukuListener: AutoCloseable? = null

    // ---- model picker (A10.4) and context (A10.9) ------------------------------------

    /** Loads the model catalogue (all of Pi's models) when the picker opens. */
    fun loadModels() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val catalogue = api.agentModels()
                _state.update { it.copy(models = catalogue) }
            } catch (e: ApiException) {
                if (!handleIfAuth(e)) com.example.phonesteward.logic.obs.Diagnostics.error("models.load", e)
            } catch (e: Exception) {
                com.example.phonesteward.logic.obs.Diagnostics.error("models.load", e)
            }
        }
    }

    /**
     * The owner picked [entry]. A pay-as-you-go (or unclassified) model asks one question first, which is the owner's
     * authorisation ; the choice then goes to the server with `confirmMetered`.
     */
    fun chooseModel(entry: com.example.phonesteward.net.ModelEntry) {
        if (!entry.available) return
        if (com.example.phonesteward.logic.models.ModelPicker.needsWarning(entry)) {
            viewModelScope.launch {
                // The question is asked on the main thread, in the same frame as the tap (see startFresh).
                val (title, body) = com.example.phonesteward.logic.models.ModelPicker.warning(entry)
                if (!askConfirm(PendingConfirm(title, body, "Use it"))) return@launch
                viewModelScope.launch(Dispatchers.IO) { sendModelChoice(entry, confirmed = true) }
            }
        } else {
            viewModelScope.launch(Dispatchers.IO) { sendModelChoice(entry, confirmed = false) }
        }
    }

    /**
     * Sends the choice. A refusal means the catalogue on screen was stale: the list is reloaded (N37), and a model the
     * server now says is pay-as-you-go gets the metered question instead of a bare code, then one retry with the yes.
     */
    private suspend fun sendModelChoice(entry: com.example.phonesteward.net.ModelEntry, confirmed: Boolean) {
        try {
            val chosen = com.example.phonesteward.net.AgentModel(entry.provider, entry.id)
            val response = api.chooseModel(
                com.example.phonesteward.net.AgentModelChoice(RequestIds.new("model"), chosen, confirmMetered = confirmed),
            )
            _state.update {
                it.copy(
                    agent = it.agent?.copy(model = response.model)
                        ?: com.example.phonesteward.net.AgentSettingsResponse("main", response.model),
                    models = it.models?.copy(current = response.model),
                    chatNotice = null,
                )
            }
        } catch (e: ApiException) {
            if (handleIfAuth(e)) return
            if (e.code == "UNKNOWN_MODEL" || e.code == "MODEL_UNAVAILABLE" || e.code == "METERED_NEEDS_CONFIRM") loadModels()
            if (e.code == "METERED_NEEDS_CONFIRM" && !confirmed) {
                val metered = entry.copy(billing = "metered")
                val (title, body) = com.example.phonesteward.logic.models.ModelPicker.warning(metered)
                // The question is asked on the main thread, in the same frame as the tap (see startFresh).
                val yes = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main.immediate) {
                    askConfirm(PendingConfirm(title, body, "Use it"))
                }
                if (yes) sendModelChoice(metered, confirmed = true)
                return
            }
            _state.update { it.copy(chatNotice = com.example.phonesteward.logic.models.ModelPicker.errorText(e.code)) }
        } catch (e: TransportException) {
            _state.update { it.copy(chatNotice = "Could not reach the steward. The model was not changed.") }
        }
    }

    private fun refreshAgent() {
        viewModelScope.launch(Dispatchers.IO) { readAgent() }
    }

    /**
     * (review finding 2): the A11.4 reload has two triggers; at launch they can fire in the same instant,
     * and a reload per trigger would double the reads. One reload per foreground event.
     */
    internal var chatForegroundGate = com.example.phonesteward.logic.context.ChatForegroundGate(
        nowMs = System::currentTimeMillis,
    )

    /**
     * A11.4: Chat coming (back) to the foreground re-reads the model and its context. Two triggers call this — the Chat
     * tab becoming visible and the app resuming on Chat — so [chatForegroundGate] lets one reload through per event.
     */
    fun onChatForeground() {
        if (!_state.value.paired) return
        if (!chatForegroundGate.shouldReload()) return
        refreshAgent()
        // A13.6: a pending request card redraws after a restart or a return.
        refreshPhoneUsePending()
    }

    private fun readAgent() {
        try {
            val settings = api.agentSettings()
            _state.update { it.copy(agent = settings) }
        } catch (e: ApiException) {
            if (e.httpStatus != 401) com.example.phonesteward.logic.obs.Diagnostics.error("agent.settings", e)
        } catch (e: Exception) {
            com.example.phonesteward.logic.obs.Diagnostics.error("agent.settings", e)
        }
        // The footer names the model by its label, which only the catalogue knows: load it once.
        if (_state.value.models == null) {
            try {
                val catalogue = api.agentModels()
                _state.update { it.copy(models = catalogue) }
            } catch (e: ApiException) {
                if (e.httpStatus != 401) com.example.phonesteward.logic.obs.Diagnostics.error("models.load", e)
            } catch (e: Exception) {
                com.example.phonesteward.logic.obs.Diagnostics.error("models.load", e)
            }
        }
    }

    /** Chat menu, Compact now (or `/compact`): the steward summarises its working context; the chat is untouched. */
    fun compactNow() {
        viewModelScope.launch(Dispatchers.IO) {
            contextOperation { api.compactChat(com.example.phonesteward.net.RequestOnly(RequestIds.new("compact"))) }
                ?.let { _state.update { s -> s.copy(chatInfo = com.example.phonesteward.logic.context.ContextFooter.compactStarted(), chatNotice = null) } }
        }
    }

    /** Chat menu, Start fresh (or `/new`): one confirmation, then the steward restarts from its records. */
    fun startFresh() {
        viewModelScope.launch {
            // The question is asked on the main thread, so the owner's tap and the question land in the
            // same frame — never an IO hop between them (a menu-to-dialog flake).
            val yes = askConfirm(
                PendingConfirm(
                    title = "Start fresh?",
                    body = "The steward stops using this conversation as its memory and starts again from its own records: " +
                        "open proposals, your rules and its notes. The chat stays here, with a line marking the new start.",
                    confirmLabel = "Start fresh",
                ),
            )
            if (!yes) return@launch
            viewModelScope.launch(Dispatchers.IO) {
                contextOperation { api.freshChat(com.example.phonesteward.net.RequestOnly(RequestIds.new("fresh"))) }
                    ?.let { _state.update { s -> s.copy(chatInfo = com.example.phonesteward.logic.context.ContextFooter.freshStarted(), chatNotice = null) } }
            }
        }
    }

    /** Runs a compact/fresh call; null (after setting the notice) when it was refused or failed. */
    private suspend fun contextOperation(call: () -> Unit): Unit? = try {
        call()
        refreshAgent()
        Unit
    } catch (e: ApiException) {
        if (!handleIfAuth(e)) {
            _state.update {
                it.copy(
                    chatNotice = if (e.code == "AGENT_BUSY") {
                        com.example.phonesteward.logic.context.ContextFooter.busyText()
                    } else {
                        "The steward did not accept that (${e.code}). Try again in a moment."
                    },
                )
            }
        }
        null
    } catch (e: TransportException) {
        _state.update { it.copy(chatNotice = "Could not reach the steward. Try again.") }
        null
    }

    fun dismissChatInfo() = _state.update { it.copy(chatInfo = null) }

    /** A10.1: the Steward trash and the file checks; tests point it at a temporary folder. */
    internal var stewardTrash: com.example.phonesteward.logic.files.StewardTrash
        get() = graph.stewardTrash
        set(value) { graph.stewardTrash = value }

    /** A11.3: the restore reports waiting for the next sync; tests point it at a temporary folder. */
    internal var restoreOutbox: com.example.phonesteward.logic.files.RestoredOutbox
        get() = graph.restoreOutbox
        set(value) { graph.restoreOutbox = value }

    private val planner: ProposalExecutionPlanner
        get() = ProposalExecutionPlanner(
            lookup = com.example.phonesteward.android.MediaStoreLiveLookup(getApplication()),
            nowMillis = System::currentTimeMillis,
            // the runtime gate is the REAL all-files check —
            // the default ({ true }) would let clear_all_caches launch with the
            // permission denied.
            allFilesGranted = allFilesProbe,
            files = stewardTrash,
            shizukuReady = { graph.shizuku.state() == com.example.phonesteward.logic.shizuku.ShizukuState.READY },
            isInstalled = installedProbe,
            neverTouch = neverTouchForTest ?: com.example.phonesteward.android.AndroidNeverTouch.of(getApplication()),
        )

    /** Tests replace it: Robolectric has no all-files access. */
    internal var allFilesProbe: () -> Boolean = { com.example.phonesteward.android.Permissions.allFilesGranted() }

    /** N9: remembers the permissions last posted, so a resume posts only on a change. */
    internal val postGate = PermissionPostGate()

    /** N10: at most one screen refresh per 500 ms; a burst ends in one trailing refresh. */
    private val refreshCoalescer = RefreshCoalescer(
        minGapMs = 500,
        nowMs = System::currentTimeMillis,
        scheduleLater = { delayMs, action ->
            viewModelScope.launch {
                delay(delayMs)
                action()
            }
        },
    )

    private companion object {
        /** Wait after a failed job poll before asking again. */
        const val POLL_RETRY_MS = 5_000L
    }

    private var pollerJob: Job? = null
    private var streamJob: Job? = null

    /** SSE resume cursor, owned here so a rebind to a new server restarts cleanly. */
    @Volatile
    private var chatCursor: Long? = null

    // ---- lifecycle ---------------------------------------------------------

    fun start() {
        if (shizukuListener == null) shizukuListener = graph.shizuku.addChangeListener { refreshShizuku() }
        graph.ensureWorkerScheduled()
        startPoller()
        startStream()
        viewModelScope.launch(Dispatchers.IO) {
            postDeviceStatus()
            refresh()
        }
    }

    fun stop() {
        pollerJob?.cancel()
        streamJob?.cancel()
        pollerJob = null
        streamJob = null
    }

    private fun startPoller() {
        if (pollerJob?.isActive == true) return
        pollerJob = viewModelScope.launch(Dispatchers.IO) {
            while (isActive && _state.value.paired) {
                try {
                    val summary = graph.jobPump.pollOnce(waitSeconds = 25)
                    // a failing server (a proxy answering 502 while the backend restarts) was asked again
                    // at once, dozens of times a second. Wait before the next try.
                    if (summary.fetchFailed) delay(POLL_RETRY_MS)
                } catch (e: ApiException) {
                    // A revoked token ends the session here too, not only on the next tap.
                    if (!handleIfAuth(e)) {
                        com.example.phonesteward.logic.obs.Diagnostics.error("poll.loop", e)
                        delay(POLL_RETRY_MS)
                    }
                } catch (e: Exception) {
                    com.example.phonesteward.logic.obs.Diagnostics.error("poll.loop", e)
                    delay(POLL_RETRY_MS)
                }
            }
        }
    }

    private fun startStream() {
        if (streamJob?.isActive == true) return
        streamJob = viewModelScope.launch(Dispatchers.IO) {
            while (isActive && _state.value.paired) {
                try {
                    // The stream client follows the paired identity; the
                    // cursor belongs to this conversation, not the client.
                    graph.paired.streamClient().stream(chatCursor) { event ->
                        chatCursor = event.id
                        onChatEvent(event)
                    }
                } catch (e: ApiException) {
                    // a revoked token returns the app to pairing now; anything else reconnects.
                    if (!handleIfAuth(e)) {
                        com.example.phonesteward.logic.obs.Diagnostics.error("stream.reconnect", e)
                    }
                } catch (e: Exception) {
                    // A transport drop used to end the loop for good; now it
                    // is recorded and the loop keeps reconnecting (O5).
                    com.example.phonesteward.logic.obs.Diagnostics.error("stream.loop", e)
                }
                if (!isActive) return@launch
                delay(2_000) // reconnect with Last-Event-ID resume
            }
        }
    }

    internal fun onChatEvent(event: ChatEvent) {
        when (event.type) {
            "message", "tool", "run", "proposal", "context", "phone_use" ->
                _state.update { it.copy(chat = ChatLog.apply(it.chat, event)) }
            // heartbeat: nothing shown
        }
        // A13.6: a request card is live while the server still has it pending; an answer closes it.
        when (event.type) {
            "phone_use" -> {
                val d = event.data
                val kind = (d["kind"] as? kotlinx.serialization.json.JsonPrimitive)?.content
                val requestId = (d["requestId"] as? kotlinx.serialization.json.JsonPrimitive)?.content
                when (kind) {
                    // The card opens when the route confirms the request is the pending one: one writer (the read).
                    "request" -> refreshPhoneUsePending()
                    "answer" -> _state.update { if (it.phoneUsePendingId == requestId) it.copy(phoneUsePendingId = null) else it }
                }
            }
        }
        // N7: a new proposal or a finished run means the server data the other
        // screens show has moved — reload it without a restart.
        if (event.type == "proposal" || event.type == "run") refresh()
        // A11.4: the ended run event is the one that can follow a compaction, so the footer re-reads the context
        // itself instead of relying on the wider refresh (which a failing proposals call could cut short).
        if (event.type == "run" && (event.data["status"] as? kotlinx.serialization.json.JsonPrimitive)?.content == "ended") {
            refreshAgent()
        }
    }

    /** F2: the Chat verbosity, saved on the phone. */
    fun setChatVerbosity(level: com.example.phonesteward.logic.chat.ChatVerbosity) {
        chatSettings.write(level)
        _state.update { it.copy(chatVerbosity = level) }
    }

    // ---- phone use from Chat (A13.6) ------------------------------------------------

    /** Request ids already answered or in flight: a repeat tap does nothing (correction 1, finding 3). */
    private val phoneUseAnswered: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    /**
     * The owner's Allow on a request card: the answer route, then the foreground service joins
     * the session the server started on the returned id (the tap is the owner's start).
     */
    fun allowPhoneUse(requestId: String) = answerPhoneUse(requestId, allow = true)

    /** The owner's Not now: the answer route with `allow: false`; no session starts. */
    fun declinePhoneUse(requestId: String) = answerPhoneUse(requestId, allow = false)

    /** For tests: how many pending-request reads have been issued (token ordering, H1). */
    internal fun pendingReadTokenForTest(): Long = pendingUseRead.get()

    private fun answerPhoneUse(requestId: String, allow: Boolean) {
        // One answer per request: a fast double tap must not send a second call (the server would
        // refuse it and the owner would see a red notice right after a successful Allow).
        if (!phoneUseAnswered.add(requestId)) return
        // Any pending-request read issued before this answer is stale: a late response must not
        // redraw live buttons on an answered card (correction 1, H1).
        pendingUseRead.incrementAndGet()
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val response = api.answerPhoneUse(requestId, allow)
                _state.update { if (it.phoneUsePendingId == requestId) it.copy(phoneUsePendingId = null) else it }
                if (allow && response.status == "allowed") {
                    response.sessionId?.let { sessionId ->
                        val task = _state.value.chat.lastOrNull {
                            it.kind == "phone_use" && it.phone?.requestId == requestId
                        }?.phone?.task.orEmpty()
                        com.example.phonesteward.screen.LiveSessionService.start(getApplication(), task, sessionId)
                    }
                }
            } catch (e: ApiException) {
                if (handleIfAuth(e)) {
                    phoneUseAnswered.remove(requestId)
                    return@launch
                }
                when (e.code) {
                    // Terminal answers stay remembered: the request is settled either way.
                    "REQUEST_NOT_PENDING" -> {
                        // N40: the card must not keep dead buttons — the pending id is cleared (the card
                        // closes) and the request entry is marked, so the card reads "This request has
                        // expired." The server puts its current status beside `error` (A13.2).
                        val status = e.field("status")?.takeIf { it.isNotBlank() } ?: "expired"
                        _state.update { if (it.phoneUsePendingId == requestId) it.copy(phoneUsePendingId = null) else it }
                        markRequestStatus(requestId, status)
                        _state.update {
                            it.copy(chatNotice = "That request is no longer pending" +
                                (e.field("status")?.let { s -> " ($s)" } ?: "") + ".")
                        }
                    }
                    // N40: the request itself is still pending — the owner was told to Stop the running
                    // session first, so the tap must not stay forgotten in the guard.
                    "LIVE_SESSION_ACTIVE" -> {
                        phoneUseAnswered.remove(requestId)
                        _state.update {
                            it.copy(chatNotice = "A phone session is already running. Stop it first.")
                        }
                    }
                    else -> {
                        phoneUseAnswered.remove(requestId) // retryable
                        _state.update { it.copy(chatNotice = "The steward did not accept that (${e.code}). Try again in a moment.") }
                    }
                }
            } catch (e: TransportException) {
                phoneUseAnswered.remove(requestId) // retryable
                _state.update { it.copy(chatNotice = "Could not reach the steward. Try again.") }
            }
        }
    }

    /** A13.6: the pending card is the route's answer only; each read is token-ordered, a stale one never applies. */
    private val pendingUseRead = java.util.concurrent.atomic.AtomicLong(0)

    /** N40: marks one request card with its settled status (the card's own outcome words). */
    private fun markRequestStatus(requestId: String, status: String) {
        _state.update { s ->
            val idx = s.chat.indexOfFirst { it.kind == "phone_use" && it.phone?.kind == "request" && it.phone?.requestId == requestId }
            if (idx < 0) s else s.copy(
                chat = s.chat.toMutableList().also {
                    it[idx] = it[idx].copy(phone = it[idx].phone!!.copy(status = status))
                },
            )
        }
    }

    /** A13.2: the one pending request, re-read on Chat foreground so a card redraws after a restart. */
    fun refreshPhoneUsePending() {
        val token = pendingUseRead.incrementAndGet()
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val pending = api.pendingPhoneUse().request
                if (pendingUseRead.get() == token) {
                    _state.update { it.copy(phoneUsePendingId = pending?.id) }
                }
            } catch (e: ApiException) {
                if (e.httpStatus != 401) com.example.phonesteward.logic.obs.Diagnostics.error("phoneuse.pending", e)
            } catch (e: Exception) {
                com.example.phonesteward.logic.obs.Diagnostics.error("phoneuse.pending", e)
            }
        }
    }

    // N39: atomic (compare-and-set); the old read-then-write lost a tap that overlapped a background refresh.
    private fun <T> kotlinx.coroutines.flow.MutableStateFlow<T>.update(block: (T) -> T) = updateState(block)

    // ---- pairing ------------------------------------------------------------

    fun pair(baseUrl: String, code: String, deviceName: String) {
        setBaseUrl(baseUrl)
        setBusy(true)
        viewModelScope.launch(Dispatchers.IO) {
            try {
                graph.pairing.pair(baseUrl, code, deviceName)
                chatCursor = null
                com.example.phonesteward.logic.obs.Diagnostics.event("info", "pair_ok", "")
                _state.update { it.copy(paired = true, notice = null, chat = emptyList()) }
                SyncWorker.schedule(getApplication())
                postDeviceStatus()
                refresh()
                startPoller()
                startStream()
            } catch (e: ApiException) {
                com.example.phonesteward.logic.obs.Diagnostics.event("warn", "pair_failed", e.code)
                _state.update {
                    it.copy(
                        notice = when (e.code) {
                            "PAIRING_CODE_INVALID" -> "That code did not work. Ask for a new one."
                            "RATE_LIMITED" -> "Too many tries. Wait a few minutes, then ask for a new code."
                            else -> "The server did not accept the pairing (${e.code}). Try again."
                        },
                    )
                }
            } catch (e: TransportException) {
                com.example.phonesteward.logic.obs.Diagnostics.event("warn", "pair_failed", "unreachable")
                _state.update { it.copy(notice = "Could not reach the server. Check the address and your connection, then try again.") }
            } catch (e: Exception) {
                com.example.phonesteward.logic.obs.Diagnostics.event("warn", "pair_failed", e.javaClass.simpleName)
                _state.update { it.copy(notice = "Pairing did not work. Check the address and the code, then try again.") }
            } finally {
                setBusy(false)
            }
        }
    }

    fun setBaseUrl(url: String) {
        graph.setBaseUrl(url.trim())
        _state.update { it.copy(baseUrl = graph.baseUrl()) }
    }

    // ---- refresh --------------------------------------------------------------

    /**
     * N2: re-reads the live permission snapshot and the server data. Called
     * on ON_RESUME, on Refresh, and from the SSE proposal/run handlers, so
     * nothing needs a full app restart to catch up.
     */
    fun onAppResume(live: Onboarding.Snapshot? = null) {
        val snapshot = live ?: currentPermissionSnapshot()
        _state.update { it.copy(permissions = snapshot) }
        refresh()
        // N9: the owner may have granted something in Settings; tell the server
        // so the steward does not keep saying the access is missing.
        if (_state.value.paired && postGate.shouldPostOnResume(snapshot.toDevicePermissions())) {
            viewModelScope.launch(Dispatchers.IO) { postDeviceStatus(snapshot.toDevicePermissions()) }
        }
    }

    private fun Onboarding.Snapshot.toDevicePermissions() = DevicePermissions(
        usageAccess = usageAccess,
        allFiles = allFiles,
        mediaRead = mediaRead,
        manageMedia = manageMedia,
        batteryUnrestricted = batteryUnrestricted,
        shizuku = try {
            graph.shizuku.state().wire
        } catch (_: Exception) {
            "absent"
        },
    )

    private fun currentPermissionSnapshot(): Onboarding.Snapshot {
        val app = getApplication<Application>()
        return Onboarding.Snapshot(
            batteryUnrestricted = com.example.phonesteward.android.PowerSettings.isIgnoringBatteryOptimizations(app),
            usageAccess = com.example.phonesteward.android.Permissions.usageAccessGranted(app),
            allFiles = com.example.phonesteward.android.Permissions.allFilesGranted(),
            mediaRead = com.example.phonesteward.android.Permissions.mediaReadGranted(app),
            manageMedia = com.example.phonesteward.android.Permissions.canManageMedia(app),
            autoModeOn = _state.value.rules?.photoWindow?.autoMode == true,
        )
    }

    fun refresh() {
        if (!_state.value.paired) return
        refreshCoalescer.request { doRefresh() }
    }

    private fun doRefresh() {
        if (!_state.value.paired) return
        com.example.phonesteward.logic.obs.Diagnostics.event("info", "screen_refresh", "proposals rules")
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val proposals = api.listProposals("open")
                val rules = try {
                    api.getRules()
                } catch (_: ApiException) {
                    _state.value.rules
                }
                val removed = proposals.flatMap { RemovedApps.removedItemIds(it, installedProbe) }.toSet()
                _state.update {
                    it.copy(
                        proposals = proposals,
                        rules = rules,
                        permissions = currentPermissionSnapshot(),
                        removedItemIds = removed,
                    )
                }
                settleGoneProposals(proposals)
                readTrash()
                readShizuku()
                readAgent()
                flushRestoreReports()
                if (_state.value.chat.isEmpty()) loadHistory()
            } catch (e: ApiException) {
                handleAuth(e)
            } catch (e: Exception) {
                // offline: keep what we have
                com.example.phonesteward.logic.obs.Diagnostics.error("refresh.offline", e)
            }
        }
    }

    /** N31: proposals already settled (or being settled) by this process, so a refresh never repeats it. */
    private val settledProposals = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    /**
     * N31 (A10.8): an open uninstall proposal whose every app is already gone has nothing left for the owner
     * to decide. It is approved for all its items and executed in QUIET mode: no hooks (so no Android dialog can
     * open, even if an app came back in between), no result card, one execution report. The planner and
     * [UninstallFlow] still judge each app by what is on the phone.
     */
    private suspend fun settleGoneProposals(proposals: List<Proposal>) {
        for (proposal in proposals) {
            if (!RemovedApps.settleable(proposal, installedProbe)) continue
            if (!settledProposals.add(proposal.proposalId)) continue
            try {
                val decision = api.decide(
                    proposal.proposalId,
                    DecisionRequest(RequestIds.new("decide"), "approve", proposal.items.map { it.itemId }),
                )
                execute(proposal, decision.decisionId, quiet = true)
                com.example.phonesteward.logic.obs.Diagnostics.event("info", "proposal_settled_gone", "items=${proposal.items.size}")
            } catch (e: ApiException) {
                if (!handleIfAuth(e)) com.example.phonesteward.logic.obs.Diagnostics.error("proposal.settle_gone", e)
            } catch (e: Exception) {
                com.example.phonesteward.logic.obs.Diagnostics.error("proposal.settle_gone", e)
            }
        }
    }

    private suspend fun loadHistory() {
        try {
            val events = api.chatHistory(limit = 200)
            // Merged by event id, so the stream's replay and this history never double up.
            _state.update { it.copy(chat = ChatLog.applyAll(it.chat, events)) }
        } catch (e: Exception) {
            com.example.phonesteward.logic.obs.Diagnostics.error("chat.history", e)
        }
    }

    private suspend fun postDeviceStatus(
        permissions: DevicePermissions = currentPermissionSnapshot().toDevicePermissions(),
    ) {
        try {
            if (graph.deviceStatusReporter.send()) postGate.markPosted(permissions)
        } catch (e: ApiException) {
            // A revoked token ends the session here too ; other server errors are only recorded.
            if (!handleIfAuth(e)) com.example.phonesteward.logic.obs.Diagnostics.error("status.post", e)
        } catch (e: Exception) {
            com.example.phonesteward.logic.obs.Diagnostics.error("status.post", e)
        }
    }

    private suspend fun handleAuth(e: ApiException) {
        if (graph.pairing.onApiError(e)) {
            _state.update {
                it.copy(paired = false, notice = "The server no longer accepts this device. Pair again.", chat = emptyList(), result = null)
            }
            postGate.reset()
            graph.statusPostGate.reset()
            stop()
        }
    }

    // ---- chat -------------------------------------------------------------------

    fun send(text: String) {
        val body = text.trim()
        if (body.isEmpty()) return
        // A10.9: `/compact` and `/new` do what the Chat menu does and are not sent as messages.
        when (com.example.phonesteward.logic.context.ChatCommands.parse(body)) {
            com.example.phonesteward.logic.context.ChatCommand.Compact -> { compactNow(); return }
            com.example.phonesteward.logic.context.ChatCommand.Fresh -> { startFresh(); return }
            null -> Unit
        }
        _state.update { it.copy(chat = ChatLog.withLocalOwner(it.chat, body, System.currentTimeMillis()), chatNotice = null) }
        // A15.2: a message that answers the running session's open question hands the screen back to
        // the app the session was using — but only once the steward has it, so a refusal's notice
        // stays readable in Chat.
        val lastPhoneUseKind = _state.value.chat.lastOrNull { it.kind == "phone_use" }?.phone?.kind
        val handBack =
            com.example.phonesteward.screen.BackToUsedApp.afterChatSend(
                lastPhoneUseKind,
                com.example.phonesteward.screen.PhoneUseState.isActive,
            )
        viewModelScope.launch(Dispatchers.IO) {
            try {
                api.sendChat(com.example.phonesteward.net.ChatSendRequest(RequestIds.new("chat"), body))
                if (handBack) com.example.phonesteward.screen.BackToUsedApp.request()
            } catch (e: ApiException) {
                if (!handleIfAuth(e)) {
                    _state.update { it.copy(chatNotice = "The steward did not accept your message (${e.code}). Try again in a moment.") }
                }
            } catch (e: TransportException) {
                _state.update { it.copy(chatNotice = "Could not reach the steward. Your message was not sent; check your connection and try again.") }
            } catch (e: Exception) {
                com.example.phonesteward.logic.obs.Diagnostics.error("chat.send", e)
                _state.update { it.copy(chatNotice = "Your message was not sent. Try again.") }
            }
        }
    }

    // ---- proposals ------------------------------------------------------------

    fun decide(proposal: Proposal, approve: Boolean, tickedItemIds: List<String>) {
        setBusy(true)
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val decision = api.decide(
                    proposal.proposalId,
                    DecisionRequest(RequestIds.new("decide"), if (approve) "approve" else "reject", tickedItemIds),
                )
                if (approve) {
                    execute(proposal, decision.decisionId)
                } else {
                    setResult(ActionResult("Proposal rejected.", ActionResult.Kind.INFO))
                }
                refresh()
            } catch (e: ApiException) {
                if (!handleIfAuth(e)) {
                    setResult(
                        ActionResult(
                            if (e.code == "PROPOSAL_NOT_OPEN") "This proposal is no longer open." else (e.message ?: "The decision was not accepted."),
                            ActionResult.Kind.ERROR,
                        ),
                    )
                    // a superseded proposal must not stay on screen to be approved again.
                    if (e.code == "PROPOSAL_NOT_OPEN") refresh()
                }
            } catch (e: Exception) {
                setResult(ActionResult("Decision not sent: ${e.message}", ActionResult.Kind.ERROR))
            } finally {
                setBusy(false)
            }
        }
    }

    private suspend fun execute(proposal: Proposal, decisionId: String, quiet: Boolean = false) {
        val check = api.checkDecision(decisionId)
        val plan = planner.plan(proposal, decisionId, check)
        if (!plan.allowed) {
            if (!quiet) setResult(ActionResult("Refused: ${plan.refusalReason}", ActionResult.Kind.ERROR))
            return
        }
        // N31: a quiet settlement has no hooks, so no Android dialog can open.
        val hooks = if (quiet) null else executionHooks
        var trashCode: Int? = ActivityResults.CANCELLED
        if (plan.trashBatch.isNotEmpty()) {
            val uris = plan.trashBatch.mapNotNull { uriForMediaId(it.mediaId) }
            trashCode = if (hooks != null && uris.isNotEmpty()) {
                withContext(Dispatchers.IO) { hooks.launchTrash(uris).get() }
            } else {
                ActivityResults.CANCELLED
            }
        }
        // 0.2.4: ACTION_DELETE gives no result code, so each app's outcome is what is on the phone afterwards.
        val uninstallVerdicts = plan.uninstalls.associate { item ->
            val verdict = withContext(Dispatchers.IO) {
                uninstallFlow.run(item.packageName) {
                    hooks?.launchUninstall(item.packageName)?.get()
                }
            }
            item.itemId to verdict
        }
        val cacheResults = plan.clearCaches.associate { item ->
            val verdict = if (hooks != null) {
                // Fix 02-5: the hook registers observers and starts activities,
                // so it must run on the main thread; the await stays off it.
                val future = withContext(Dispatchers.Main.immediate) {
                    hooks.beginCacheClear(item.packageName)
                }
                withContext(Dispatchers.IO) { future.get().verdict() }
            } else {
                com.example.phonesteward.logic.exec.CacheClearOutcome.Verdict("cancelled", null)
            }
            item.itemId to verdict
        }
        // A6 (N6): one request, one Android confirmation, one report item. A10.2: a `trim_caches` item is one shell command
        // through Shizuku instead, and needs no hooks (so it also works in quiet mode, which never has any).
        val allCachesVerdict: com.example.phonesteward.logic.exec.AllCachesOutcome.Verdict? =
            plan.allCaches.firstOrNull()?.let { item ->
                if (item.viaShizuku) {
                    withContext(Dispatchers.IO) { trimCachesViaShizuku() }
                } else if (hooks == null) {
                    com.example.phonesteward.logic.exec.AllCachesOutcome.Verdict("failed", null, null)
                } else {
                    val future = withContext(Dispatchers.Main.immediate) {
                        hooks.beginAllCaches(item)
                    }
                    withContext(Dispatchers.IO) { future.get() }
                }
            }
        // A10.2: disable_app / enable_app: the command, then what the phone shows decides.
        val appStateResults = withContext(Dispatchers.IO) {
            plan.appStates.associate { it.itemId to changeAppState(it) }
        }
        // A10.1: ONE in-app confirmation for the whole approval, then each item is re-checked and moved (a rename).
        var deleteConfirmed = true
        var deleteResults: Map<String, com.example.phonesteward.logic.files.MoveResult> = emptyMap()
        if (plan.deleteFiles.isNotEmpty()) {
            val confirm = com.example.phonesteward.logic.exec.DeleteFilesConfirm.of(plan.deleteFiles)
            deleteConfirmed = !quiet && askConfirm(PendingConfirm(confirm.title, confirm.body, "Move to trash"))
            if (deleteConfirmed) {
                deleteResults = withContext(Dispatchers.IO) {
                    plan.deleteFiles.associate { it.itemId to stewardTrash.moveIfUnchanged(proposal.proposalId, it.entry) }
                }
            }
        }
        val items = planner.outcomes(
            plan, trashCode, uninstallVerdicts, cacheResults, allCachesVerdict,
            deleteResults = deleteResults, deleteConfirmed = deleteConfirmed, appStateResults = appStateResults,
        )
        api.reportExecution(
            proposal.proposalId,
            ExecutionRequest(RequestIds.new("exec"), decisionId, items),
        )
        com.example.phonesteward.logic.obs.Diagnostics.event(
            "info", "proposal_executed",
            "done=${items.count { it.outcome == "done" }} cancelled=${items.count { it.outcome == "cancelled" }} " +
                "failed=${items.count { it.outcome == "failed" }} skipped=${items.count { it.outcome == "skipped_protected" }}",
        )
        if (plan.deleteFiles.isNotEmpty()) {
            refreshTrash()
            postDeviceStatus()
        }
        if (quiet) return
        // N16 + N11: the outcome lands on Proposals, where the owner tapped Approve.
        setResult(
            com.example.phonesteward.logic.exec.ExecutionNotice.result(
                proposal = proposal,
                plan = plan,
                items = items,
                allCachesVerdict = allCachesVerdict,
            ),
        )
    }

    private fun uriForMediaId(mediaId: String): Uri? {
        val parts = mediaId.split(":")
        if (parts.size != 3) return null
        val id = parts[2].toLongOrNull() ?: return null
        val collection = when (parts[1]) {
            "images" -> android.provider.MediaStore.Images.Media.getContentUri(android.provider.MediaStore.VOLUME_EXTERNAL_PRIMARY)
            "video" -> android.provider.MediaStore.Video.Media.getContentUri(android.provider.MediaStore.VOLUME_EXTERNAL_PRIMARY)
            "files" -> android.provider.MediaStore.Files.getContentUri(android.provider.MediaStore.VOLUME_EXTERNAL_PRIMARY)
            else -> return null
        }
        return android.content.ContentUris.withAppendedId(collection, id)
    }

    // ---- Shizuku actions (A10.2) ----------------------------------------------------------

    /** `pm trim-caches 999G`, measured as the A6 clear: the all-apps cache total before minus after. */
    private fun trimCachesViaShizuku(): com.example.phonesteward.logic.exec.AllCachesOutcome.Verdict {
        val before = cacheMeter()
        val result = graph.shizuku.shell().run(com.example.phonesteward.logic.shizuku.ShellCommands.trimCaches(), 60_000)
        if (!result.ok) {
            com.example.phonesteward.logic.obs.Diagnostics.event("warn", "trim_caches_failed", "exit=${result.exitCode} timeout=${result.timedOut}")
            return com.example.phonesteward.logic.exec.AllCachesOutcome.Verdict("failed", null, null)
        }
        val after = cacheMeter()
        return com.example.phonesteward.logic.exec.AllCachesOutcome.Verdict(
            "done", null, com.example.phonesteward.logic.exec.AllCachesBytes.freed(before, after),
        )
    }

    /** Runs the fixed disable/enable command; true only when the app really is in the wanted state afterwards. */
    private fun changeAppState(item: ProposalExecutionPlanner.AppStatePlan): Boolean {
        val command = if (item.enable) {
            com.example.phonesteward.logic.shizuku.ShellCommands.enable(item.packageName)
        } else {
            com.example.phonesteward.logic.shizuku.ShellCommands.disable(item.packageName)
        }
        val result = graph.shizuku.shell().run(command, 20_000)
        if (!result.ok) return false
        val enabled = appEnabledProbe(item.packageName) ?: return false
        return enabled == item.enable
    }

    // ---- confirmations ---------------------------------------------------------

    private var confirmAnswer: kotlinx.coroutines.CompletableDeferred<Boolean>? = null

    /** Shows [confirm] over the app and suspends until the owner answers; the dialog leaves the screen either way. */
    internal suspend fun askConfirm(confirm: PendingConfirm): Boolean {
        confirmAnswer?.complete(false) // only one question at a time
        val answer = kotlinx.coroutines.CompletableDeferred<Boolean>()
        confirmAnswer = answer
        _state.update { it.copy(confirm = confirm) }
        return try {
            answer.await()
        } finally {
            if (confirmAnswer === answer) confirmAnswer = null
            _state.update { if (it.confirm == confirm) it.copy(confirm = null) else it }
        }
    }

    fun answerConfirm(yes: Boolean) {
        confirmAnswer?.complete(yes)
    }

    // ---- Steward trash (A10.1) ------------------------------------------------------

    /** Re-reads what the Steward trash holds (a walk of its folders, off the main thread). */
    internal fun refreshTrash() {
        viewModelScope.launch(Dispatchers.IO) { readTrash() }
    }

    private fun readTrash() {
        try {
            val status = stewardTrash.status()
            _state.update { if (it.stewardTrash == status) it else it.copy(stewardTrash = status) }
        } catch (e: Exception) {
            com.example.phonesteward.logic.obs.Diagnostics.error("trash.status", e)
        }
    }

    /** Empty Steward trash on Status: one confirmation, then everything in the trash is deleted for good. */
    fun emptyTrash() {
        viewModelScope.launch(Dispatchers.IO) {
            val held = try {
                stewardTrash.status()
            } catch (e: Exception) {
                com.example.phonesteward.logic.obs.Diagnostics.error("trash.status", e)
                return@launch
            }
            if (held.bytes <= 0L) {
                _state.update { it.copy(trashResult = ActionResult("The Steward trash is already empty.", ActionResult.Kind.INFO)) }
                return@launch
            }
            val size = com.example.phonesteward.logic.ByteFormat.format(held.bytes)
            val yes = askConfirm(
                PendingConfirm(
                    title = "Empty the Steward trash?",
                    body = "This permanently deletes $size that the steward moved there. It cannot be undone.",
                    confirmLabel = "Empty trash",
                    destructive = true,
                ),
            )
            if (!yes) return@launch
            val freed = try {
                stewardTrash.empty()
            } catch (e: Exception) {
                com.example.phonesteward.logic.obs.Diagnostics.error("trash.empty", e)
                _state.update { it.copy(trashResult = ActionResult("The trash could not be emptied. Try again.", ActionResult.Kind.ERROR)) }
                return@launch
            }
            com.example.phonesteward.logic.obs.Diagnostics.event("info", "steward_trash_emptied", "bytes=$freed")
            readTrash()
            _state.update {
                it.copy(trashResult = ActionResult("Steward trash emptied: ${com.example.phonesteward.logic.ByteFormat.format(freed)} freed.", ActionResult.Kind.SUCCESS))
            }
            postDeviceStatus()
        }
    }

    fun dismissTrashResult() = _state.update { it.copy(trashResult = null) }

    // ---- A11.3 Steward trash restore -------------------------------------------------

    /** Status → Open Steward trash: shows the screen and reads what is in the trash. */
    fun openTrash() {
        _state.update { it.copy(trashOpen = true, trashScreenResult = null) }
        loadTrashBatches()
    }

    /** The back arrow on the trash screen returns to Status. */
    fun closeTrash() = _state.update { it.copy(trashOpen = false) }

    fun dismissTrashScreenResult() = _state.update { it.copy(trashScreenResult = null) }

    internal fun loadTrashBatches() {
        viewModelScope.launch(Dispatchers.IO) { readTrashBatches() }
    }

    private fun readTrashBatches() {
        try {
            val batches = stewardTrash.listTrash()
            _state.update { if (it.trashBatches == batches) it else it.copy(trashBatches = batches) }
        } catch (e: Exception) {
            com.example.phonesteward.logic.obs.Diagnostics.error("trash.list", e)
        }
    }

    /** Restore all of one batch (no confirmation: it puts things back, nothing is lost). */
    fun restoreBatch(batch: com.example.phonesteward.logic.files.TrashBatch) = restore(batch.proposalId) {
        stewardTrash.restoreBatch(batch)
    }

    /** Restore one item of a batch. */
    fun restoreItem(
        batch: com.example.phonesteward.logic.files.TrashBatch,
        item: com.example.phonesteward.logic.files.TrashItem,
    ) = restore(batch.proposalId) { stewardTrash.restoreItem(batch, item) }

    private fun restore(proposalId: String, block: () -> com.example.phonesteward.logic.files.RestoreResult) {
        // (review finding 3): a double tap would run the same entries twice, and the loser would report a
        // healthy item as "it could not be put back". The guard is set here, on the caller's thread, before any work.
        if (_state.value.trashRestoring) return
        _state.update { it.copy(trashRestoring = true, trashScreenResult = null) }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val result = try {
                    block()
                } catch (e: Exception) {
                    com.example.phonesteward.logic.obs.Diagnostics.error("trash.restore", e)
                    _state.update {
                        it.copy(trashScreenResult = ActionResult("The items could not be put back. Try again.", ActionResult.Kind.ERROR))
                    }
                    return@launch
                }
                if (result.restored.isNotEmpty()) {
                    com.example.phonesteward.logic.obs.Diagnostics.event(
                        "info", "steward_trash_restored", "items=${result.restored.size} bytes=${result.bytesRestored}",
                    )
                    queueRestoreReport(proposalId, result)
                }
                readTrash()
                readTrashBatches()
                postDeviceStatus()
                _state.update {
                    it.copy(
                        trashScreenResult = ActionResult(
                            com.example.phonesteward.logic.files.RestoreText.result(result),
                            if (result.blocked.isEmpty()) ActionResult.Kind.SUCCESS else ActionResult.Kind.INFO,
                        ),
                    )
                }
            } finally {
                _state.update { it.copy(trashRestoring = false) }
            }
        }
    }

    /**
     * A11.3: stores every report first (so a failed post is never lost) and then tries to send them. The requestIds are
     * fixed here, so the next sync's retry is the same logical report and can never double-record. more
     * than [com.example.phonesteward.logic.files.RestoreReports.MAX_ITEMS] restored items go up as several
     * reports, all saved before the first attempt, so one refused chunk cannot lose the rest.
     */
    private fun queueRestoreReport(proposalId: String, result: com.example.phonesteward.logic.files.RestoreResult) {
        val reports = com.example.phonesteward.logic.files.RestoreReports.chunks(
            proposalId = proposalId,
            items = result.restored.map { com.example.phonesteward.net.RestoredItem(it.path, it.sizeBytes) },
            atMs = System.currentTimeMillis(),
            newRequestId = { RequestIds.new("restore") },
        )
        try {
            reports.forEach { restoreOutbox.save(it) }
        } catch (e: Exception) {
            com.example.phonesteward.logic.obs.Diagnostics.error("trash.restore.queue", e)
            return
        }
        flushRestoreReports()
    }

    /** Sends the waiting restore reports; called here, by the periodic worker and by a foreground refresh. */
    internal fun flushRestoreReports() {
        try {
            graph.restoreReporter().flush()
        } catch (e: Exception) {
            com.example.phonesteward.logic.obs.Diagnostics.error("trash.restore.report", e)
        }
    }

    // ---- rules -----------------------------------------------------------------

    fun saveRules(window: PhotoWindow) {
        val current = _state.value.rules ?: return
        // A4: the marker is fixed at Keep; always send exactly that.
        val fixed = window.copy(keepMarker = com.example.phonesteward.logic.RulesForm.FIXED_KEEP_MARKER)
        val problems = RulesForm.validate(fixed)
        if (!problems.none) {
            _state.update { it.copy(notice = problems.messages().joinToString(" ")) }
            return
        }
        setBusy(true)
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val saved = api.putRules(RulesForm.put(current, fixed, RequestIds.new("rules")))
                _state.update { it.copy(rules = saved, notice = "Rules saved.") }
            } catch (e: ApiException) {
                if (!handleIfAuth(e)) {
                    val refetched = try {
                        api.getRules()
                    } catch (e: Exception) {
                        com.example.phonesteward.logic.obs.Diagnostics.error("rules.refetch", e)
                        null
                    }
                    when (val outcome = RulesForm.classifyPutError(e, refetched)) {
                        is RulesForm.PutOutcome.VersionConflict -> _state.update {
                            it.copy(rules = outcome.current, notice = "The rule changed elsewhere. Review and try again.")
                        }
                        is RulesForm.PutOutcome.Rejected -> _state.update { it.copy(notice = outcome.message) }
                        is RulesForm.PutOutcome.Saved -> _state.update { it.copy(rules = outcome.updated) }
                    }
                }
            } catch (e: Exception) {
                _state.update { it.copy(notice = "Could not save: ${e.message}") }
            } finally {
                setBusy(false)
            }
        }
    }

    // ---- helpers -----------------------------------------------------------------

    private suspend fun handleIfAuth(e: ApiException): Boolean {
        if (e.httpStatus == 401) {
            handleAuth(e)
            return true
        }
        return false
    }

    private fun setBusy(busy: Boolean) = _state.update { it.copy(busy = busy) }

    fun clearNotice() = _state.update { it.copy(notice = null) }

    fun dismissChatNotice() = _state.update { it.copy(chatNotice = null) }

    private fun setResult(result: ActionResult) = _state.update { it.copy(result = result, notice = null) }

    fun clearResult() = _state.update { it.copy(result = null) }

    /** Owner-initiated: forget the identity on this phone (the server side keeps authority). */
    fun unpairLocal() {
        answerConfirm(false)
        stop()
        chatCursor = null
        graph.pairing.forget()
        postGate.reset()
        graph.statusPostGate.reset()
        _state.update { it.copy(paired = false, chat = emptyList(), proposals = emptyList(), rules = null, result = null) }
    }

    val lastWorkerRunMs: Long get() = graph.lastWorkerRunMs()

    override fun onCleared() {
        runCatching { shizukuListener?.close() }
        shizukuListener = null
        stop()
    }
}
