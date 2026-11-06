package com.example.phonesteward.net

import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * The chat stream (protocol §6): a blocking SSE read over
 * `GET /v1/chat/stream?after=<seq>`. Reconnects send `Last-Event-ID` (and
 * `after`), so the server replays missed events. Pure JVM; the caller runs
 * it on a coroutine and stops it by cancelling.
 */
class ChatStreamClient(
    baseUrl: String,
    private val tokenProvider: () -> String?,
    private val http: OkHttpClient = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS) // streams stay open
        .build(),
) : AutoCloseable {

    private val base = baseUrl.trimEnd('/')

    /** Set by [close]; a held stream handle must not reconnect (fix 02-1). */
    @Volatile
    private var closed = false

    /**
     * Reads events until the connection drops or the thread is interrupted.
     * Returns normally on a clean close so the caller can reconnect.
     */
    fun stream(after: Long?, onEvent: (ChatEvent) -> Unit): Boolean {
        var path = "/v1/chat/stream"
        if (after != null) path += "?after=$after"
        val request = Request.Builder()
            .url("$base$path")
            .header("Accept", "text/event-stream")
            .apply { if (after != null) header("Last-Event-ID", after.toString()) }
            .apply { tokenProvider()?.let { header("Authorization", "Bearer $it") } }
            .build()
        if (closed) throw IllegalStateException("client closed")
        return try {
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    // The server answered a protocol error: report and let the caller decide.
                    val body = response.body?.string().orEmpty()
                    throw errorOf(response.code, body)
                }
                val source = response.body?.source() ?: return false
                val parser = SseParser { id, type, data ->
                    val parsed = try {
                        ProtocolJson.parseToJsonElement(data)
                    } catch (e: Exception) {
                        com.example.phonesteward.logic.obs.Diagnostics.error("stream.parse", e)
                        return@SseParser
                    }
                    val obj = parsed as? kotlinx.serialization.json.JsonObject
                    val event = ChatEvent(
                        id = id ?: return@SseParser,
                        // The type rides the SSE `event:` line; fall back to a
                        // `type` field inside data, then to "message".
                        type = type
                            ?: obj?.get("type")?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
                            ?: EVENT_FALLBACK,
                        data = obj ?: kotlinx.serialization.json.JsonObject(emptyMap()),
                    )
                    onEvent(event)
                }
                while (!Thread.currentThread().isInterrupted) {
                    val line = source.readUtf8Line() ?: return false // clean close
                    parser.feedLine(line)
                }
                false
            }
        } catch (e: ApiException) {
            throw e
        } catch (e: Exception) {
            com.example.phonesteward.logic.obs.Diagnostics.error("stream.transport", e)
            false // transport drop: the caller reconnects with its own cursor
        }
    }

    private fun errorOf(status: Int, body: String): ApiException =
        try {
            val envelope = ProtocolJson.decodeFromString(ErrorEnvelope.serializer(), body)
            ApiException(status, envelope.error.code, envelope.error.message, envelope.error.retryAfterSeconds)
        } catch (_: Exception) {
            ApiException(status, "UNKNOWN")
        }

    override fun close() {
        closed = true
        ClientRelease.release(http)
    }

    private companion object {
        // The SSE `event:` line carries the type in the server's wire format;
        // when the server folds it into data instead, this keeps events decodable.
        const val EVENT_FALLBACK = "message"
    }
}
