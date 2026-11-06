package com.example.phonesteward.net

import java.util.concurrent.TimeUnit
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import com.example.phonesteward.logic.CrashReport
import com.example.phonesteward.logic.CrashReports
import com.example.phonesteward.logic.CrashUpload

/**
 * `POST /v1/crash` (A5). Separate from [StewardApi] on purpose: it must work before
 * pairing and must not depend on anything that may itself be what crashed. The bearer
 * rides along only when the phone is paired.
 */
class CrashClient(
    baseUrl: String,
    private val token: String?,
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .build(),
) {
    private val url = baseUrl.trimEnd('/') + "/v1/crash"

    fun send(report: CrashReport): CrashUpload = try {
        val request = Request.Builder()
            .url(url)
            .post(CrashReports.encode(report).toRequestBody("application/json; charset=utf-8".toMediaType()))
            .apply { if (token != null) header("Authorization", "Bearer $token") }
            .build()
        http.newCall(request).execute().use { response ->
            when {
                response.isSuccessful -> CrashUpload.SENT
                response.code == 429 || response.code >= 500 -> CrashUpload.RETRY_LATER
                else -> CrashUpload.REFUSED
            }
        }
    } catch (_: Exception) {
        CrashUpload.RETRY_LATER
    }
}
