package com.example.phonesteward.logic.files

import com.example.phonesteward.net.ApiException
import com.example.phonesteward.net.RestoredRequest
import com.example.phonesteward.net.StewardApi

/**
 * A11.3: the send step of the restore reporter — one report, one `POST /v1/files/restored`. A 4xx answer the server
 * will never accept drops the report (the paths are validated the same way as `delete_files` targets); offline or a
 * server error keeps it for the next sync.
 */
class ApiRestoreSender(private val api: () -> StewardApi) : (RestoredReport) -> RestoreUpload {

    override fun invoke(report: RestoredReport): RestoreUpload = try {
        api().reportRestored(RestoredRequest(report.requestId, report.proposalId, report.items))
        RestoreUpload.SENT
    } catch (e: ApiException) {
        // 401 (the token no longer works) and 429 (too many tries) are not the report's fault: keep it for the next sync.
        if (e.httpStatus in 400..499 && e.httpStatus != 401 && e.httpStatus != 429) RestoreUpload.REFUSED
        else RestoreUpload.RETRY_LATER
    } catch (_: Exception) {
        RestoreUpload.RETRY_LATER
    }
}
