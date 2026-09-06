package org.siloserver.silo.repository

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import org.siloserver.silo.model.ebook.SaveEbookReaderConfigRequest
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.network.AuthScopeSnapshot
import org.siloserver.silo.network.apiv2.EbookReaderV2Api
import org.siloserver.silo.network.apiv2.GuardedEbookConfig

/** One opened reader owns one validator. Conflict/uncertainty requires an explicit reload. */
class EbookConfigSession(private val api: EbookReaderV2Api, private val contentId: String,
    private val scope: AuthScopeSnapshot) {
    private val mutex = Mutex()
    private var current: GuardedEbookConfig? = null
    suspend fun load(): ApiResult<JsonObject> = mutex.withLock {
        current = null
        when (val result = api.config(contentId, scope)) {
            is ApiResult.Success -> { current = result.data; ApiResult.Success(result.data.value.config) }
            is ApiResult.Error -> result
            is ApiResult.NetworkError -> result
        }
    }
    suspend fun saveAndroidDisplay(settings: JsonObject): ApiResult<Unit> = mutex.withLock {
        val captured = current ?: return@withLock ApiResult.Error(0, "config_reload_required", "Reader settings are saved locally. Reopen the reader to reload server settings.")
        // Fence before suspension so cancellation and any uncertain response cannot reuse a validator.
        current = null
        val request = SaveEbookReaderConfigRequest(JsonObject(captured.value.config + ("android_reader" to settings)))
        when (val result = api.config(contentId, scope, request, captured.etag)) {
            is ApiResult.Success -> { current = result.data; ApiResult.Success(Unit) }
            is ApiResult.Error -> result
            is ApiResult.NetworkError -> result
        }
    }
}
