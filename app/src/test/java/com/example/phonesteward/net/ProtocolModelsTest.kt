package com.example.phonesteward.net

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Round trips of every example shape in docs/protocol.md §1–§7 (fix 01-9):
 * each example is DECODED into its typed Kotlin model, the model is
 * RE-ENCODED, and the re-encoded JSON is compared with the example. The
 * comparison runs through the model — a parse-only check would not catch a
 * missing or renamed property.
 */
class ProtocolModelsTest {

    private inline fun <reified T> roundTrips(json: String, ignoreKeys: Set<String> = emptySet()): T {
        val decoded = ProtocolJson.decodeFromString<T>(json)
        val reEncoded = ProtocolJson.encodeToString(decoded)
        // Canonical tree comparison through the typed model. Absent and null
        // are treated as equal (the examples omit optional fields); a renamed,
        // missing or wrongly-defaulted property still fails the key/value walk.
        // ignoreKeys scopes out protocol-optional fields whose examples omit
        // them entirely (never whole required sections).
        assertEquals(
            "re-encoded JSON must match the example",
            canonical(ProtocolJson.parseToJsonElement(json.trim()), ignoreKeys),
            canonical(ProtocolJson.parseToJsonElement(reEncoded), ignoreKeys),
        )
        return decoded
    }

    private fun canonical(
        element: kotlinx.serialization.json.JsonElement,
        ignoreKeys: Set<String> = emptySet(),
    ): Any? = when (element) {
        is kotlinx.serialization.json.JsonObject ->
            element.entries
                .mapNotNull { (k, v) ->
                    if (v is kotlinx.serialization.json.JsonNull || k in ignoreKeys) null // absent == null == ignored
                    else k to canonical(v, ignoreKeys)
                }
                .toMap()
        is kotlinx.serialization.json.JsonArray -> element.map { canonical(it) }
        // Keep the primitive's wire kind (fix 02-6): "1", 1 and true are
        // different values and must not compare equal.
        is kotlinx.serialization.json.JsonPrimitive ->
            when {
                element.isString -> listOf("string", element.content)
                element.content == "true" || element.content == "false" -> listOf("boolean", element.content)
                else -> listOf("number", element.content)
            }
    }

    @Test
    fun `§1 error envelope round trip`() {
        val json = """{"error": {"code": "RATE_LIMITED", "message": "slow down", "retryAfterSeconds": 30}}"""
        val env = roundTrips<ErrorEnvelope>(json)
        assertEquals("RATE_LIMITED", env.error.code)
        assertEquals(30, env.error.retryAfterSeconds)
    }

    @Test
    fun `§1 error envelope without optional fields`() {
        val env = roundTrips<ErrorEnvelope>("""{"error": {"code": "UPPER_SNAKE", "message": "human text"}}""")
        assertNull(env.error.retryAfterSeconds)
    }

    @Test
    fun `§2 pair request and response round trip`() {
        val req = roundTrips<PairRequest>("""{"code": "K7Q4-9MZD", "deviceName": "Example Phone"}""")
        assertEquals("K7Q4-9MZD", req.code)
        val res = roundTrips<PairResponse>("""{"deviceId": "dev_abc", "token": "43charsbase64url"}""")
        assertEquals("dev_abc", res.deviceId)
    }

    @Test
    fun `§3 job shape with params round trip`() {
        val json = """
        {"jobId": "pj-1", "kind": "media_index",
         "params": {"mode": "rule_candidates", "folders": ["DCIM/Camera/"], "olderThanDays": 90},
         "createdAtMs": 1791300000000, "expiresAtMs": 1791904800000}
        """.trimIndent()
        val job = roundTrips<Job>(json)
        assertEquals("media_index", job.kind)
        val params = ProtocolJson.decodeFromJsonElement(MediaIndexParams.serializer(), job.params)
        assertEquals("rule_candidates", params.mode)
        assertEquals(90, params.olderThanDays)
    }

    @Test
    fun `§3 jobs response with empty list`() {
        val res = roundTrips<JobsResponse>("""{"jobs": []}""")
        assertTrue(res.jobs.isEmpty())
    }

    @Test
    fun `§3 result report and answer round trip`() {
        val body = JobResultRequest(
            requestId = "res-pj-1",
            status = "ok",
            data = buildJsonObject { put("totalBytes", 0) },
            error = null,
            deviceTimeMs = 1791300100000,
            appVersion = "0.2.0",
        )
        val encoded = ProtocolJson.encodeToString(JobResultRequest.serializer(), body)
        assertTrue(encoded.contains("\"error\":null"))
        assertTrue(encoded.contains("\"requestId\":\"res-pj-1\""))
        val answer = roundTrips<JobResultResponse>("""{"accepted": true, "duplicate": false}""")
        assertTrue(answer.accepted)
        assertFalse(answer.duplicate)
        roundTrips<JobResultResponse>("""{"accepted": true, "duplicate": true}""")
    }

    @Test
    fun `§3 MediaRef round trip with British field names`() {
        val json = """
        {"mediaId": "external_primary:images:12345", "relativePath": "DCIM/Camera/", "displayName": "IMG_20260601_101010.jpg",
         "mimeType": "image/jpeg", "sizeBytes": 0, "dateTakenMs": 0, "dateAddedMs": 0,
         "isFavourite": false, "inKeep": false}
        """.trimIndent()
        val ref = roundTrips<MediaRef>(json)
        assertFalse(ref.isFavourite)
        assertFalse(ref.inKeep)
    }

    @Test
    fun `§3 storage_overview data round trip`() {
        val json = """
        {"totalBytes": 0, "freeBytes": 0,
         "apps": [{"packageName": "com.whatsapp", "label": "WhatsApp", "appBytes": 0, "dataBytes": 0, "cacheBytes": 0, "isSystem": false}],
         "categories": {"imagesBytes": 0, "videoBytes": 0, "audioBytes": 0, "documentsBytes": 0, "appsBytes": 0, "systemBytes": 0, "otherBytes": 0}}
        """.trimIndent()
        val data = roundTrips<StorageOverviewData>(json)
        assertEquals("com.whatsapp", data.apps.single().packageName)
    }

    @Test
    fun `§3 app_usage data with null lastUsedMs round trip`() {
        val json = """
        {"apps": [{"packageName": "…", "label": "…", "lastUsedMs": null, "installedMs": 0, "isSystem": false}]}
        """.trimIndent()
        val data = roundTrips<AppUsageData>(json)
        assertNull(data.apps.single().lastUsedMs)
    }

    @Test
    fun `§3 media_index summary with scoped and unscoped folders round trip`() {
        val json = """
        {"scope": ["DCIM/Camera/", "Pictures/Screenshots/", "Android/media/com.whatsapp/", "Android/media/org.telegram.messenger/", "Download/"],
         "folders": [{"folderKey": "DCIM/Camera", "bytes": 0, "count": 0,
                      "buckets": {"under3m": {"count": 0, "bytes": 0}, "m3to4": {"count": 0, "bytes": 0}, "over4m": {"count": 0, "bytes": 0}, "unknown": {"count": 0, "bytes": 0}}},
                     {"folderKey": "Movies/TV", "bytes": 0, "count": 0, "buckets": null}],
         "largest": [],
         "topLevelFiles": [{"name": "…", "sizeBytes": 0, "modifiedMs": 0}],
         "oldApks": []}
        """.trimIndent()
        val data = roundTrips<MediaIndexSummaryData>(json)
        assertEquals("DCIM/Camera", data.folders[0].folderKey)
        assertEquals(0, data.folders[0].buckets!!.under3m!!.count)
        assertNull(data.folders[1].buckets)
    }

    @Test
    fun `§3 media_index rule_candidates round trip`() {
        val json = """
        {"scope": ["DCIM/Camera/"], "items": [{"mediaId": "external_primary:images:1", "relativePath": "DCIM/Camera/",
         "displayName": "a.jpg", "mimeType": "image/jpeg", "sizeBytes": 10, "dateTakenMs": 5, "dateAddedMs": 5,
         "isFavourite": false, "inKeep": false}]}
        """.trimIndent()
        val data = roundTrips<MediaIndexCandidatesData>(json)
        assertEquals(1, data.items.size)
    }

    @Test
    fun `§3 phone_selftest data round trip`() {
        val json = """
        {"checks": [{"id": "trash_dialog", "outcome": "pass", "resultCode": -1, "detail": "one dialog for 1 item", "atMs": 0}]}
        """.trimIndent()
        val data = roundTrips<SelftestData>(json)
        assertEquals(-1, data.checks.single().resultCode)
    }

    @Test
    fun `§3 rule_batch params and data round trip`() {
        val params = roundTrips<RuleBatchParams>(
            """{"batchId": "rb-1", "ruleVersion": 3, "items": [{"mediaId": "external_primary:images:1",
               "relativePath": "DCIM/Camera/", "displayName": "a.jpg", "mimeType": "image/jpeg", "sizeBytes": 1,
               "dateTakenMs": 1, "dateAddedMs": 1, "isFavourite": false, "inKeep": false}]}""",
        )
        assertEquals(3, params.ruleVersion)
        val data = RuleBatchData(
            batchId = "rb-1",
            items = listOf(RuleBatchItem("external_primary:images:1", "trashed", null)),
        )
        roundTrips<RuleBatchData>(ProtocolJson.encodeToString(RuleBatchData.serializer(), data))
    }

    @Test
    fun `§4 proposal with items round trip`() {
        val json = """
        {"proposalId": "pr-1", "createdAtMs": 0, "source": "agent", "title": "Trim old media", "reason": "…",
         "bytesEstimate": 0, "status": "open",
         "items": [{"itemId": "it-1", "kind": "trash_media", "target": {"mediaId": "external_primary:images:1"}, "bytesEstimate": 0, "reason": "…"}]}
        """.trimIndent()
        val p = roundTrips<Proposal>(json)
        assertEquals("trash_media", p.items.single().kind)
    }

    @Test
    fun `§4 decision request and answers round trip`() {
        val req = DecisionRequest("req-1", "approve", listOf("it-1"))
        val encoded = ProtocolJson.encodeToString(DecisionRequest.serializer(), req)
        assertTrue(encoded.contains("\"itemIds\":[\"it-1\"]"))
        roundTrips<DecisionResponse>("""{"decisionId": "dc-1", "approvedItemIds": ["it-1"], "expiresAtMs": 0}""")
        roundTrips<DecisionResponse>("""{"decisionId": "dc-2", "approvedItemIds": []}""", ignoreKeys = setOf("expiresAtMs"))
        roundTrips<DecisionCheckResponse>("""{"valid": true, "proposalId": "pr-1", "approvedItemIds": ["it-1"], "expiresAtMs": 0}""")
    }

    @Test
    fun `§4 execution report round trip`() {
        val body = ExecutionRequest(
            requestId = "req-1",
            decisionId = "dc-1",
            items = listOf(ExecutionItemRequest("it-1", "done", null, null)),
        )
        roundTrips<ExecutionRequest>(ProtocolJson.encodeToString(ExecutionRequest.serializer(), body))
        roundTrips<ExecutionResponse>("""{"accepted": true}""")
    }

    @Test
    fun `§5 rules get and put round trip`() {
        val json = """
        {"ruleVersion": 3,
         "photoWindow": {"enabled": false, "reminderDays": 90, "cutoffDays": 120, "autoMode": false,
                         "folders": ["DCIM/Camera/", "Pictures/Screenshots/", "Android/media/com.whatsapp/", "Android/media/org.telegram.messenger/", "Download/"],
                         "keepMarker": "Keep"},
         "batchesRunOneTap": 0}
        """.trimIndent()
        val rules = roundTrips<RulesResponse>(json)
        assertEquals(5, rules.photoWindow.folders.size)
        assertEquals("Keep", rules.photoWindow.keepMarker)
        val put = RulesPutRequest("req-1", 3, rules.photoWindow)
        roundTrips<RulesPutRequest>(ProtocolJson.encodeToString(RulesPutRequest.serializer(), put))
    }

    @Test
    fun `§6 chat send and events round trip`() {
        roundTrips<ChatSendResponse>("""{"accepted": true, "conversationId": "main"}""")
        roundTrips<ChatMessageData>("""{"role": "user", "text": "…", "atMs": 0}""")
        roundTrips<ChatToolData>("""{"name": "phone_media_index", "status": "queued"}""")
        roundTrips<ChatProposalData>("""{"proposalId": "pr-1", "title": "…"}""")
        roundTrips<ChatRunData>("""{"status": "started", "reason": "owner"}""")
        roundTrips<JsonObject>("{}")
    }

    @Test
    fun `A8 chat tool and run events carry the optional summaries and timings`() {
        val tool = roundTrips<ChatToolData>(
            """{"name": "decisions", "status": "done", "callId": "call_1", "argsSummary": "limit 10",
                "resultSummary": "3 entries", "durationMs": 1200, "atMs": 1791403050000}""",
        )
        assertEquals("3 entries", tool.resultSummary)
        assertEquals(1200L, tool.durationMs)
        val run = roundTrips<ChatRunData>("""{"status": "ended", "reason": "owner", "atMs": 1791403050000, "durationMs": 9000}""")
        assertEquals(9000L, run.durationMs)
        roundTrips<ChatProposalData>("""{"proposalId": "pr-1", "title": "…", "atMs": 1791403050000}""")
        // Old apps and new servers: an unknown key is ignored, a missing optional key is null.
        assertEquals(null, ProtocolJson.decodeFromString<ChatToolData>("""{"name": "x", "status": "done", "future": 1}""").argsSummary)
    }

    @Test
    fun `§6 chat event with type discriminator`() {
        val e = ChatEvent(1, "message", buildJsonObject { put("role", JsonPrimitive("assistant")) })
        roundTrips<ChatEvent>(ProtocolJson.encodeToString(ChatEvent.serializer(), e))
    }

    @Test
    fun `§7 device status round trip`() {
        val body = DeviceStatusRequest(
            requestId = "req-1",
            appVersion = "0.2.0",
            permissions = DevicePermissions(
                usageAccess = true, allFiles = true, mediaRead = true,
                manageMedia = false, batteryUnrestricted = true,
            ),
            lastWorkerRunMs = 0,
        )
        roundTrips<DeviceStatusRequest>(ProtocolJson.encodeToString(DeviceStatusRequest.serializer(), body))
        roundTrips<DeviceStatusResponse>("""{"accepted": true}""")
    }

    @Test
    fun `§7 server status and agent settings round trip`() {
        val statusJson = """
        {"serverTimeMs": 0, "device": {"deviceId": "…", "lastSeenMs": 0, "permissions": {}},
         "pendingJobs": 0, "openProposals": 0, "lastSnapshotMs": 0,
         "agent": {"model": {"provider": "zai", "id": "glm-5.3-flash"}}}
        """.trimIndent()
        // The example's empty permissions object vs the model's five defaults:
        // tolerated as a protocol-optional map; DevicePermissions is fully
        // round-tripped in the §7 device status test.
        val status = roundTrips<StatusResponse>(statusJson, ignoreKeys = setOf("permissions"))
        assertEquals("glm-5.3-flash", status.agent!!.model.id)
        val settingsJson = """
        {"conversationId": "main", "model": {"provider": "zai", "id": "glm-5.3-flash"},
         "allowlist": [{"provider": "zai", "id": "glm-5.3-flash", "metered": false}],
         "usage": {"inputTokens": 0, "outputTokens": 0}}
        """.trimIndent()
        val settings = roundTrips<AgentSettingsResponse>(settingsJson)
        assertFalse(settings.allowlist.single().metered)
    }

    @Test
    fun `canonical keeps wire types - string 1 and number 1 compare unequal (fix 02-6)`() {
        val asString = ProtocolJson.parseToJsonElement("""{"v": "1"}""")
        val asNumber = ProtocolJson.parseToJsonElement("""{"v": 1}""")
        val asBool = ProtocolJson.parseToJsonElement("""{"v": true}""")
        val asStringAgain = ProtocolJson.parseToJsonElement("""{"v": "1"}""")
        assertEquals(canonical(asString), canonical(asStringAgain))
        assertFalse("string vs number must differ", canonical(asString) == canonical(asNumber))
        assertFalse("number vs boolean must differ", canonical(asNumber) == canonical(asBool))
        assertFalse("string vs boolean must differ", canonical(asString) == canonical(asBool))
    }

    @Test
    fun `params parsers cover every job kind`() {
        val selftest = roundTrips<SelftestParams>(
            """{"checks": ["trash_dialog", "manage_media_skip", "trash_restorable", "clear_cache_intent", "workmanager_ran"]}""",
        )
        assertEquals(5, selftest.checks.size)
        val summary = roundTrips<MediaIndexParams>("""{"mode": "summary", "folders": []}""")
        assertEquals("summary", summary.mode)
        assertNull(summary.olderThanDays)
    }
}
