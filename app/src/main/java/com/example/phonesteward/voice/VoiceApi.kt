package com.example.phonesteward.voice

import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import com.example.phonesteward.net.ClientRelease
import com.example.phonesteward.net.ProtocolJson

/** A voice request that failed: a protocol code (`VOICE_UNAVAILABLE`, `VOICE_FAILED`) or `TRANSPORT`. */
class VoiceException(val code: String, message: String? = null) :
    RuntimeException("$code${message?.let { ": $it" } ?: ""}")

/** One speech answer: the audio bytes and their content type (`audio/wav` or `audio/mpeg`). */
class SpeechAudio(val bytes: ByteArray, val contentType: String)

/**
 * A15.5/A15.6 app half: the phone-side client for the two voice routes. Pure JVM (OkHttp +
 * kotlinx.serialization), synchronous like [com.example.phonesteward.net.StewardApi]; the
 * controllers call it from a coroutine. A token is attached to every request.
 */
class VoiceApi(
    baseUrl: String,
    private val tokenProvider: () -> String? = { null },
    private val http: OkHttpClient = defaultClient(),
) : AutoCloseable {

    private val base = baseUrl.trimEnd('/')

    @Volatile
    private var closed = false

    private fun ensureOpen() {
        check(!closed) { "client closed" }
    }

    /** Uploads the recording and returns the transcribed (and cleaned) text. */
    fun transcribe(audio: ByteArray, contentType: String): String {
        val request = builder("/v1/voice/transcribe")
            .post(audio.toRequestBody(contentType.toMediaType()))
            .build()
        return call(request) { body ->
            ProtocolJson.parseToJsonElement(body).jsonObject["text"]?.jsonPrimitive?.contentOrNull.orEmpty()
        }
    }

    /** Asks for speech and returns the audio with the server's content type. */
    fun speak(text: String, voice: String? = null): SpeechAudio {
        val payload = buildJsonObject {
            put("text", text)
            if (voice != null) put("voice", voice)
        }.toString()
        val request = builder("/v1/voice/speak")
            .post(payload.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
        return callAudio(request)
    }

    private fun builder(path: String): Request.Builder {
        val builder = Request.Builder().url("$base$path")
        tokenProvider()?.let { builder.header("Authorization", "Bearer $it") }
        return builder
    }

    private fun <T> call(request: Request, parse: (String) -> T): T {
        ensureOpen()
        val response = try {
            http.newCall(request).execute()
        } catch (e: Exception) {
            throw VoiceException("TRANSPORT", e.message)
        }
        response.use {
            val body = try {
                it.body?.string().orEmpty()
            } catch (e: Exception) {
                throw VoiceException("TRANSPORT", e.message)
            }
            if (!it.isSuccessful) throw errorOf(it.code, body)
            return parse(body)
        }
    }

    private fun callAudio(request: Request): SpeechAudio {
        ensureOpen()
        val response = try {
            http.newCall(request).execute()
        } catch (e: Exception) {
            throw VoiceException("TRANSPORT", e.message)
        }
        response.use {
            if (!it.isSuccessful) {
                val body = try { it.body?.string().orEmpty() } catch (_: Exception) { "" }
                throw errorOf(it.code, body)
            }
            val bytes = try {
                it.body?.bytes() ?: ByteArray(0)
            } catch (e: Exception) {
                throw VoiceException("TRANSPORT", e.message)
            }
            return SpeechAudio(bytes, it.header("Content-Type")?.substringBefore(';') ?: "application/octet-stream")
        }
    }

    private fun errorOf(status: Int, body: String): VoiceException {
        val code = try {
            (ProtocolJson.parseToJsonElement(body).jsonObject["error"] as? JsonObject)
                ?.get("code")?.jsonPrimitive?.contentOrNull
        } catch (_: Exception) {
            null
        }
        return VoiceException(code ?: "HTTP_$status")
    }

    override fun close() {
        closed = true
        ClientRelease.release(http)
    }

    companion object {
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS) // a 10 MB upload and a long synthesis
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()
    }
}
