package com.example.phonesteward.net

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * Wire models for every docs/protocol.md §1–§7 shape. Pure JVM: no Android
 * imports. Field names follow the protocol exactly (isFavourite, inKeep,
 * dateTakenMs, …); sizes are bytes, times epoch milliseconds.
 */

/** The one error envelope shape (§1). */
@Serializable
data class ApiErrorBody(
    val code: String,
    val message: String? = null,
    val retryAfterSeconds: Int? = null,
)

@Serializable
data class ErrorEnvelope(val error: ApiErrorBody)

/** §2 pairing. */
@Serializable
data class PairRequest(val code: String, val deviceName: String)

@Serializable
data class PairResponse(val deviceId: String, val token: String)

/** §3.1 one queued job. `params` stays a raw object; kind parsers below. */
@Serializable
data class Job(
    val jobId: String,
    val kind: String,
    val params: JsonObject = JsonObject(emptyMap()),
    val createdAtMs: Long = 0,
    val expiresAtMs: Long? = null,
)

@Serializable
data class JobsResponse(val jobs: List<Job> = emptyList())

/** §3.2 result report. `status` is ok | error | partial. */
@Serializable
data class JobErrorBody(val code: String, val message: String)

@Serializable
data class JobResultRequest(
    val requestId: String,
    val status: String,
    val data: JsonObject? = null,
    val error: JobErrorBody? = null,
    val deviceTimeMs: Long,
    val appVersion: String,
)

@Serializable
data class JobResultResponse(val accepted: Boolean, val duplicate: Boolean = false)

/** §3.3 MediaRef : metadata only, never pixels or hashes. */
@Serializable
data class MediaRef(
    val mediaId: String,
    val relativePath: String,
    val displayName: String,
    val mimeType: String? = null,
    val sizeBytes: Long = 0,
    val dateTakenMs: Long? = null,
    val dateAddedMs: Long? = null,
    val isFavourite: Boolean = false,
    val inKeep: Boolean = false,
)

/** §3.3 storage_overview result data. */
@Serializable
data class StorageOverviewApp(
    val packageName: String,
    val label: String? = null,
    val appBytes: Long = 0,
    val dataBytes: Long = 0,
    val cacheBytes: Long = 0,
    val isSystem: Boolean = false,
)

@Serializable
data class StorageCategories(
    val imagesBytes: Long = 0,
    val videoBytes: Long = 0,
    val audioBytes: Long = 0,
    val documentsBytes: Long = 0,
    val appsBytes: Long = 0,
    val systemBytes: Long = 0,
    val otherBytes: Long = 0,
)

@Serializable
data class StorageOverviewData(
    val totalBytes: Long,
    val freeBytes: Long,
    val apps: List<StorageOverviewApp> = emptyList(),
    val categories: StorageCategories = StorageCategories(),
)

/** §3.3 app_usage result data. `lastUsedMs` is null when Android has no record. */
@Serializable
data class AppUsageEntry(
    val packageName: String,
    val label: String? = null,
    val lastUsedMs: Long? = null,
    val installedMs: Long? = null,
    val isSystem: Boolean = false,
)

@Serializable
data class AppUsageData(val apps: List<AppUsageEntry> = emptyList())

/** One age bucket's count and bytes. */
@Serializable
data class BucketStat(val count: Int = 0, val bytes: Long = 0)

@Serializable
data class AgeBucketsData(
    val under3m: BucketStat? = null,
    @SerialName("m3to4") val m3To4: BucketStat? = null,
    val over4m: BucketStat? = null,
    val unknown: BucketStat? = null,
)

/** §3.3 media_index summary: one folder row. `buckets` is null outside the rule's scope (R1/). */
@Serializable
data class FolderIndex(
    val folderKey: String,
    val bytes: Long = 0,
    val count: Int = 0,
    val buckets: AgeBucketsData? = null,
)

@Serializable
data class TopLevelFile(val name: String, val sizeBytes: Long = 0, val modifiedMs: Long = 0)

@Serializable
data class MediaIndexSummaryData(
    val scope: List<String> = emptyList(),
    val folders: List<FolderIndex> = emptyList(),
    val largest: List<MediaRef> = emptyList(),
    val topLevelFiles: List<TopLevelFile> = emptyList(),
    val oldApks: List<TopLevelFile> = emptyList(),
)

/** §3.3 media_index rule_candidates: every scoped item past the cut-off, protected ones included. */
@Serializable
data class MediaIndexCandidatesData(
    val scope: List<String> = emptyList(),
    val items: List<MediaRef> = emptyList(),
)

/** §3.3 phone_selftest result data. `outcome` is pass | fail | cancelled | skipped. */
@Serializable
data class SelftestCheck(
    val id: String,
    val outcome: String,
    val resultCode: Int? = null,
    val detail: String? = null,
    val atMs: Long = 0,
)

@Serializable
data class SelftestData(val checks: List<SelftestCheck> = emptyList())

/** §3.3 rule_batch result data. `outcome` is trashed | skipped_protected | missing | failed. */
@Serializable
data class RuleBatchItem(val mediaId: String, val outcome: String, val resultCode: Int? = null)

@Serializable
data class RuleBatchData(val batchId: String, val items: List<RuleBatchItem> = emptyList())

/** Typed `params` parsers per job kind (§3.1). */
@Serializable
data class MediaIndexParams(
    val mode: String,
    val folders: List<String> = emptyList(),
    val olderThanDays: Int? = null,
)

@Serializable
data class SelftestParams(val checks: List<String> = emptyList())

@Serializable
data class RuleBatchParams(
    val batchId: String,
    val ruleVersion: Int,
    val items: List<MediaRef> = emptyList(),
)

/** §4 proposals. */
@Serializable
data class ProposalItem(
    val itemId: String,
    val kind: String,
    val target: JsonObject = JsonObject(emptyMap()),
    val bytesEstimate: Long = 0,
    val reason: String? = null,
)

@Serializable
data class Proposal(
    val proposalId: String,
    val createdAtMs: Long = 0,
    val source: String = "agent",
    val title: String,
    val reason: String? = null,
    val bytesEstimate: Long = 0,
    val status: String = "open",
    val items: List<ProposalItem> = emptyList(),
)

@Serializable
data class ProposalsResponse(val proposals: List<Proposal> = emptyList())

/** Item targets (§4). */
@Serializable
data class UninstallTarget(val packageName: String, val label: String? = null)

@Serializable
data class ClearCacheTarget(val packageName: String, val label: String? = null)

/** A6 (N6): one app's cache in a `clear_all_caches` target — shown to the owner for information. */
@Serializable
data class CacheApp(val packageName: String, val label: String? = null, val cacheBytes: Long = 0)

/** A6 (N6): the `clear_all_caches` target: the biggest caches, for information. */
@Serializable
data class ClearAllCachesTarget(val apps: List<CacheApp> = emptyList())

@Serializable
data class AdviceTarget(val text: String)

@Serializable
data class DecisionRequest(
    val requestId: String,
    val decision: String,
    val itemIds: List<String>? = null,
)

@Serializable
data class DecisionResponse(
    val decisionId: String,
    val approvedItemIds: List<String> = emptyList(),
    val expiresAtMs: Long = 0,
)

/** §4 authority check answer for GET /v1/decisions/{id}. */
@Serializable
data class DecisionCheckResponse(
    val valid: Boolean,
    val proposalId: String = "",
    val approvedItemIds: List<String> = emptyList(),
    val expiresAtMs: Long = 0,
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class ExecutionItemRequest(
    val itemId: String,
    val outcome: String,
    val resultCode: Int? = null,
    val bytesFreed: Long? = null,
    /** A10.1: a `delete_files` item moved to the Steward trash reports its size here (bytesFreed stays 0). Omitted for every other kind. */
    @EncodeDefault(EncodeDefault.Mode.NEVER) val trashedBytes: Long? = null,
)

@Serializable
data class ExecutionRequest(
    val requestId: String,
    val decisionId: String,
    val items: List<ExecutionItemRequest> = emptyList(),
)

@Serializable
data class ExecutionResponse(val accepted: Boolean = false)

/** §5 rules. Only the app can change them. */
@Serializable
data class PhotoWindow(
    val enabled: Boolean = false,
    val reminderDays: Int = 90,
    val cutoffDays: Int = 120,
    val autoMode: Boolean = false,
    val folders: List<String> = emptyList(),
    val keepMarker: String = "Keep",
)

@Serializable
data class RulesResponse(
    val ruleVersion: Int,
    val photoWindow: PhotoWindow,
    val batchesRunOneTap: Int = 0,
)

@Serializable
data class RulesPutRequest(val requestId: String, val ruleVersion: Int, val photoWindow: PhotoWindow)

/** §6 chat. */
@Serializable
data class ChatSendRequest(val requestId: String, val text: String)

@Serializable
data class ChatSendResponse(val accepted: Boolean, val conversationId: String)

@Serializable
data class ChatMessageData(val role: String, val text: String, val atMs: Long = 0)

@Serializable
data class ChatToolData(
    val name: String,
    val status: String,
    /** A8 (all optional): the call id, safe one-line summaries (at most 300 characters), timing. */
    val callId: String? = null,
    val argsSummary: String? = null,
    val resultSummary: String? = null,
    val durationMs: Long? = null,
    val atMs: Long? = null,
)

@Serializable
data class ChatProposalData(val proposalId: String, val title: String, val atMs: Long? = null)

@Serializable
data class ChatRunData(val status: String, val reason: String? = null, val atMs: Long? = null, val durationMs: Long? = null)

/**
 * One chat-stream event, shape-agnostic: `type` is message | tool | proposal |
 * run | heartbeat, `data` the raw object. History returns the same events.
 */
@Serializable
data class ChatEvent(
    val id: Long,
    val type: String,
    val data: JsonObject = JsonObject(emptyMap()),
)

/** A15.10 (additive): the model that actually produced one steward message, as its event names it. */
@Serializable
data class AnsweringModel(val provider: String, val id: String, val label: String = "")

/** A13.1: one phone-use request, as `GET /v1/live/requests/pending` answers it. */
@Serializable
data class PhoneUseRequestRecord(
    val id: String,
    val task: String,
    val why: String,
    /** pending | allowed | declined | expired | superseded. */
    val status: String,
    val createdAtMs: Long = 0,
    val answeredAtMs: Long? = null,
    /** Set once an Allow started the session. */
    val sessionId: String? = null,
)

@Serializable
data class PhoneUsePendingResponse(val request: PhoneUseRequestRecord? = null)

@Serializable
data class PhoneUseAnswerRequest(val requestId: String, val allow: Boolean)

/** 200 `{"status": "allowed", "sessionId": …}` or `{"status": "declined"}`. */
@Serializable
data class PhoneUseAnswerResponse(val status: String, val sessionId: String? = null)

/** §7 device status. */
@Serializable
data class DevicePermissions(
    val usageAccess: Boolean = false,
    val allFiles: Boolean = false,
    val mediaRead: Boolean = false,
    val manageMedia: Boolean = false,
    val batteryUnrestricted: Boolean = false,
    /** A10.2: `absent` | `not_running` | `no_permission` | `ready`. */
    val shizuku: String = "absent",
)

/** A10.1: what the Steward trash holds; `oldestMs` is null when it is empty. */
@Serializable
data class StewardTrashStatus(val bytes: Long = 0, val oldestMs: Long? = null)

@Serializable
data class DeviceStatusRequest(
    val requestId: String,
    val appVersion: String,
    val permissions: DevicePermissions,
    val lastWorkerRunMs: Long = 0,
    val stewardTrash: StewardTrashStatus = StewardTrashStatus(),
)

@Serializable
data class DeviceStatusResponse(val accepted: Boolean = false)

@Serializable
data class StatusDevice(
    val deviceId: String,
    val lastSeenMs: Long = 0,
    val permissions: DevicePermissions = DevicePermissions(),
)

@Serializable
data class AgentModel(val provider: String, val id: String)

@Serializable
data class AgentInfo(val model: AgentModel)

@Serializable
data class StatusResponse(
    val serverTimeMs: Long = 0,
    val device: StatusDevice? = null,
    val pendingJobs: Int = 0,
    val openProposals: Int = 0,
    val lastSnapshotMs: Long = 0,
    val agent: AgentInfo? = null,
)

@Serializable
data class AllowlistEntry(val provider: String, val id: String, val metered: Boolean = false)

@Serializable
data class UsageInfo(val inputTokens: Long = 0, val outputTokens: Long = 0)

/** A10.9/A11.1: how full the steward's working context is, against the model's window (A11.1: `budget` is the window). */
@Serializable
data class ContextInfo(
    val tokens: Long = 0,
    val budget: Long = 0,
    /** A11.1: the background-compaction threshold (75 % of the window by default); the app only shows the share. */
    val compactAtTokens: Long? = null,
    val lastCompactionMs: Long? = null,
    val lastFreshStartMs: Long? = null,
)

/** §7 agent settings (A10.4: PUT chooses the model; A10.9: the `context` block). */
@Serializable
data class AgentSettingsResponse(
    val conversationId: String,
    val model: AgentModel,
    val allowlist: List<AllowlistEntry> = emptyList(),
    val usage: UsageInfo = UsageInfo(),
    val context: ContextInfo? = null,
)

/** A10.4: one model of the Pi coding agent's catalogue. `billing` is subscription | metered | unknown. */
@Serializable
data class ModelEntry(
    val provider: String,
    val id: String,
    val label: String = "",
    val billing: String = "unknown",
    val available: Boolean = true,
    val reasoning: Boolean = false,
    val contextWindow: Long = 0,
    /** A15.3 (additive): a short, plain reason this model currently fails (for example an expired sign-in); the model stays choosable. */
    val problem: String? = null,
    /** A15.10 (additive): an id that follows whatever is newest (the server's `alias`); the label says what it follows. */
    val alias: Boolean = false,
)

/** A10.4: `GET /v1/agent/models`. */
@Serializable
data class ModelCatalogueResponse(val current: AgentModel, val models: List<ModelEntry> = emptyList())

/** A10.4: `PUT /v1/agent/settings`. */
@Serializable
data class AgentModelChoice(val requestId: String, val model: AgentModel, val confirmMetered: Boolean = false)

@Serializable
data class AgentModelChoiceResponse(val model: AgentModel)

/** A10.9: the body of `POST /v1/chat/compact` and `POST /v1/chat/fresh`. */
@Serializable
data class RequestOnly(val requestId: String)

/** Shared codec: unknown fields tolerated, defaults encoded, nulls explicit. */
val ProtocolJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = true
    classDiscriminator = "type"
}

@Serializable
data class AcceptedResponse(val accepted: Boolean = false)
