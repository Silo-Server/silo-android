package org.siloserver.silo.repository

import org.siloserver.silo.model.diagnostics.DiagnosticsStatusResponse
import org.siloserver.silo.model.diagnostics.DiagnosticsUploadResult
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.network.api.DiagnosticsApi

class DiagnosticsRepository(private val api: DiagnosticsApi) {

    suspend fun status(): ApiResult<DiagnosticsStatusResponse> = api.getStatus()

    suspend fun upload(manifestJson: ByteArray, bundleBytes: ByteArray): DiagnosticsUploadResult =
        api.uploadReport(manifestJson, bundleBytes)
}
