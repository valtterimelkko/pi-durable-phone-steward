package com.example.phonesteward.logic.exec

import java.time.ZoneId
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.json.JsonPrimitive
import com.example.phonesteward.logic.AppSizeRow
import com.example.phonesteward.logic.MediaRow
import com.example.phonesteward.logic.SpaceRow
import com.example.phonesteward.logic.jobs.JobExecutor
import com.example.phonesteward.logic.jobs.JobOutcome
import com.example.phonesteward.net.AppUsageData
import com.example.phonesteward.net.AppUsageEntry
import com.example.phonesteward.net.Job
import com.example.phonesteward.net.MediaIndexParams
import com.example.phonesteward.net.MediaIndexSummaryData
import com.example.phonesteward.net.MediaRef
import com.example.phonesteward.net.ProtocolJson
import com.example.phonesteward.net.RuleBatchData
import com.example.phonesteward.net.RuleBatchItem
import com.example.phonesteward.net.RuleBatchParams
import com.example.phonesteward.net.RulesResponse
import com.example.phonesteward.net.StorageCategories
import com.example.phonesteward.net.StorageOverviewApp
import com.example.phonesteward.net.StorageOverviewData
import com.example.phonesteward.net.TopLevelFile

/** Encodes a typed result body into the `data` JsonObject jobs carry. */
internal inline fun <reified T> body(value: T): JsonObject =
    ProtocolJson.parseToJsonElement(ProtocolJson.encodeToString(value)).let {
        it as? JsonObject ?: buildJsonObject { }
    }

/** Source interfaces: thin Android adapters implement each; fakes in tests. */
fun interface OverviewAppSource {
    fun load(): List<StorageOverviewApp>
}

fun interface CategorySource {
    fun load(): StorageCategories
}

fun interface UsageSource {
    fun load(): AppUsageData
}

fun interface MediaIndexSource {
    fun load(): List<IndexedMedia>
}

fun interface RootFilesSource {
    fun load(): List<TopLevelFile>
}

/** `storage_overview`: space, per-app sizes, category breakdown. */
class StorageOverviewExecutor(
    private val space: () -> SpaceRow,
    private val apps: OverviewAppSource,
    private val categories: CategorySource,
) : JobExecutor {

    override fun execute(job: Job): JobOutcome {
        val spaceRow = try {
            space()
        } catch (e: Exception) {
            return JobOutcome.Failed("SPACE_UNAVAILABLE", e.message ?: "StatFs failed")
        }
        val appRows = try {
            apps.load()
        } catch (e: Exception) {
            com.example.phonesteward.logic.obs.Diagnostics.error("exec.storage_overview.apps", e)
            emptyList<StorageOverviewApp>()
        }
        val cats = try {
            categories.load()
        } catch (e: Exception) {
            com.example.phonesteward.logic.obs.Diagnostics.error("exec.storage_overview.categories", e)
            StorageCategories()
        }
        val data = StorageOverviewData(
            totalBytes = spaceRow.totalBytes,
            freeBytes = spaceRow.freeBytes,
            apps = appRows,
            categories = cats,
        )
        return JobOutcome.Ok(body(data))
    }
}

/** `app_usage`: per-app last-used and installed times. */
class AppUsageExecutor(private val source: UsageSource) : JobExecutor {

    override fun execute(job: Job): JobOutcome = try {
        JobOutcome.Ok(body(source.load()))
    } catch (e: Exception) {
        JobOutcome.Failed("USAGE_UNAVAILABLE", e.message ?: "usage access missing")
    }
}

/** `media_index` in `summary` or `rule_candidates` mode. */
class MediaIndexExecutor(
    private val media: MediaIndexSource,
    private val rootFiles: RootFilesSource,
    private val nowMillis: () -> Long,
    private val zone: ZoneId,
) : JobExecutor {

    override fun execute(job: Job): JobOutcome {
        val params = try {
            ProtocolJson.decodeFromJsonElement(MediaIndexParams.serializer(), job.params)
        } catch (_: Exception) {
            return JobOutcome.Failed("BAD_PARAMS", "media_index params unreadable")
        }
        val rows = try {
            media.load()
        } catch (e: Exception) {
            return JobOutcome.Failed("MEDIA_UNAVAILABLE", e.message ?: "MediaStore unreadable")
        }
        val data: Any = when (params.mode) {
            "summary" -> MediaIndexBuilder.summary(
                scope = params.folders,
                rows = rows,
                topLevelFiles = try {
                    rootFiles.load()
                } catch (e: Exception) {
                    com.example.phonesteward.logic.obs.Diagnostics.error("media_index.root_files", e)
                    emptyList()
                },
                nowMillis = nowMillis(),
                zone = zone,
            )
            "rule_candidates" -> MediaIndexBuilder.candidates(
                scope = params.folders,
                rows = rows,
                olderThanDays = params.olderThanDays ?: return JobOutcome.Failed(
                    "BAD_PARAMS",
                    "rule_candidates needs olderThanDays",
                ),
                nowMillis = nowMillis(),
                zone = zone,
            )
            else -> return JobOutcome.Failed("BAD_PARAMS", "unknown media_index mode ${params.mode}")
        }
        @Suppress("UNCHECKED_CAST")
        return when (data) {
            is MediaIndexSummaryData -> JobOutcome.Ok(body(data))
            is com.example.phonesteward.net.MediaIndexCandidatesData -> JobOutcome.Ok(body(data))
            else -> JobOutcome.Failed("BAD_PARAMS", "unreachable mode")
        }
    }
}

/**
 * Maps an auto-trash attempt to its protocol outcome (fix 01-3). Pure:
 * Android's OK trashes; anything else (no MANAGE_MEDIA, an unmappable id, a
 * failed update) is `failed` with its result code; no answer at all is
 * `failed` too.
 */
object AutoTrashOutcome {
    const val OK = -1

    fun outcomeFor(result: AutoTrashResult?): Pair<String, Int?> = when {
        result == null -> "failed" to null
        result.resultCode == OK -> "trashed" to result.resultCode
        else -> "failed" to result.resultCode
    }
}

/** Live-row lookup the executors re-check before acting (protocol §3.3). */
fun interface MediaLookup {
    fun byId(mediaId: String): LiveMediaRow?
}

/** One trash attempt's per-item Android result code (null when dialog-based). */
data class AutoTrashResult(val resultCode: Int?, val detail: String)

/**
 * `rule_batch` (auto mode only): re-authorises against GET /v1/rules, then
 * trashes each unprotected item. Any mismatch answers RULE_CHANGED and acts
 * on nothing (protocol §3.3).
 */
class RuleBatchExecutor(
    private val rules: () -> RulesResponse,
    private val lookup: MediaLookup,
    private val autoTrash: (items: List<MediaRef>) -> Map<String, AutoTrashResult>,
) : JobExecutor {

    override fun execute(job: Job): JobOutcome {
        val params = try {
            ProtocolJson.decodeFromJsonElement(RuleBatchParams.serializer(), job.params)
        } catch (_: Exception) {
            return JobOutcome.Failed("BAD_PARAMS", "rule_batch params unreadable")
        }
        val current = try {
            rules()
        } catch (e: Exception) {
            return JobOutcome.Failed("RULE_CHECK_FAILED", e.message ?: "GET /v1/rules failed")
        }
        if (!AuthorityGate.ruleAuthorises(current, params.ruleVersion)) {
            return JobOutcome.Failed(
                "RULE_CHANGED",
                "batch was built for ruleVersion ${params.ruleVersion}, " +
                    "but the rule is version ${current.ruleVersion} " +
                    "(enabled=${current.photoWindow.enabled}, autoMode=${current.photoWindow.autoMode})",
            )
        }
        val items = params.items.map { ref ->
            val live = lookup.byId(ref.mediaId)
            when {
                live == null -> RuleBatchItem(ref.mediaId, "missing", null)
                live.isProtected -> RuleBatchItem(ref.mediaId, "skipped_protected", null)
                // Fix 02-2: the same identity check as proposals — a recycled
                // id must not trash a different item. Detail is logged; the
                // wire shape for a rule-batch item has no detail field.
                !liveMatches(ref.relativePath, ref.displayName, ref.sizeBytes, live) -> {
                    println("[phone-steward] rule batch ${params.batchId}: " +
                        "${ref.mediaId} failed: live row no longer matches")
                    RuleBatchItem(ref.mediaId, "failed", null)
                }
                else -> {
                    // Fix 01-3: report what Android actually returned, never an
                    // assumed "trashed".
                    val result = autoTrash(listOf(ref))[ref.mediaId]
                    val (outcome, resultCode) = AutoTrashOutcome.outcomeFor(result)
                    RuleBatchItem(ref.mediaId, outcome, resultCode)
                }
            }
        }
        return JobOutcome.Ok(body(RuleBatchData(params.batchId, items)))
    }
}

/** `phone_selftest` arrives in the self-test milestone; kinds reserved here stay UNKNOWN_KIND. */
object ReservedKinds {
    const val SELFTEST = "phone_selftest"
}
