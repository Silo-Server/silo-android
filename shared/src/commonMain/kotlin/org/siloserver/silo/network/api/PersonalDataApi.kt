package org.siloserver.silo.network.api

import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.http.*
import org.siloserver.silo.model.personal.*
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.network.TokenManager
import org.siloserver.silo.network.authScope
import org.siloserver.silo.network.singleAttempt
import org.siloserver.silo.network.requireSiloAuth
import org.siloserver.silo.network.apiv2.HistoryV2Api
import org.siloserver.silo.network.apiv2.HistoryContinuationV2
import org.siloserver.silo.network.apiv2.HistoryPageV2
import org.siloserver.silo.network.apiv2.ApiV2Gate
import org.siloserver.silo.network.apiv2.OwnerPolicy
import org.siloserver.silo.network.apiv2.Problem
import org.siloserver.silo.network.apiv2.UserLibrariesV2
import org.siloserver.silo.network.apiv2.identityChanged
import org.siloserver.silo.network.apiv2.ownedV2Call
import org.siloserver.silo.network.apiv2.safeApiV2Call
import org.siloserver.silo.network.AuthScopeSnapshot
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

class PersonalDataApi(
    private val client: HttpClient,
    private val apiV2Gate: ApiV2Gate = ApiV2Gate.Unrestricted,
    /**
     * Source of the identity a multi-request operation is pinned to. Null
     * (single-scope tests) leaves each request on the globally-active scope.
     */
    private val tokenManager: TokenManager? = null,
) {

    suspend fun writePersonal(handle: org.siloserver.silo.repository.port.PersonalWriteHandle): ApiResult<Unit> {
        val scope = handle.scope
        if (tokenManager == null || scope != tokenManager.snapshotCurrentScope())
            return identityChanged()
        val command = handle.command
        if (!command.valid()) return ApiResult.Error(422, "validation_failed", "Invalid personal-data command.")
        val result = safeApiV2Call<Unit>(apiV2Gate) {
            client.request(command.path) {
                method = HttpMethod.parse(command.method)
                authScope(scope)
                requireSiloAuth()
                singleAttempt()
                command.body?.let { contentType(ContentType.Application.Json); setBody(it) }
            }.also { check(!it.status.isSuccess() || it.status == HttpStatusCode.NoContent) }
        }
        if (scope != tokenManager.snapshotCurrentScope())
            return identityChanged()
        return result
    }

    // --- User Libraries ---

    suspend fun listUserLibraries(): ApiResult<List<UserLibrary>> {
        val scope = tokenManager?.snapshotCurrentScope()
        if (tokenManager != null && scope == null) return identityChanged()
        // A captured account with no profile is valid for preselection discovery.
        return ownedV2Call<UserLibrariesV2, List<UserLibrary>>(apiV2Gate, tokenManager, scope, OwnerPolicy.IDENTITY, HttpStatusCode.OK, { owner ->
            client.get("/api/v2/user/libraries") { owner?.let { authScope(it) }; requireSiloAuth() }
        }) { it.project() }
    }

    // --- Download playback progress ---

    /**
     * Sends one queued download playback position, pinned to [scope], through
     * `POST /api/v2/sync/progress` with its original event time. The server
     * applies its watched threshold and newest-write-wins, so a stale event
     * cannot overwrite newer progress from another device.
     */
    suspend fun syncDownloadProgress(
        scope: AuthScopeSnapshot,
        contentId: String,
        positionSeconds: Double,
        durationSeconds: Double?,
        updatedAt: String,
    ): ApiResult<DownloadProgressReceipt> {
        val body = ProgressSyncRequestV2(listOf(ProgressSyncItemV2(
            mediaItemId = contentId,
            positionMs = (positionSeconds * 1000).toLong(),
            durationMs = ((durationSeconds ?: 0.0) * 1000).toLong(),
            updatedAt = updatedAt,
        )))
        return ownedV2Call<ProgressSyncResponseV2, DownloadProgressReceipt>(apiV2Gate, tokenManager, scope, OwnerPolicy.PROFILE, HttpStatusCode.OK, { owner ->
            client.post("/api/v2/sync/progress") {
                authScope(owner!!); requireSiloAuth(); singleAttempt()
                contentType(ContentType.Application.Json); setBody(body)
            }
        }) { response ->
            val item = response.items.singleOrNull()
            require(item != null && item.index == 0 && item.mediaItemId == contentId) { "The server returned no result for this progress." }
            when (item.status) {
                "success" -> DownloadProgressReceipt.ACCEPTED
                // Any other failure (a storage error) is worth sending again.
                else -> if (item.failure?.status == HttpStatusCode.NotFound.value) DownloadProgressReceipt.NOT_FOUND
                    else DownloadProgressReceipt.RETRY
            }
        }
    }

    // --- History ---

    private val historyV2 = HistoryV2Api(client, apiV2Gate, tokenManager)

    suspend fun listHistory(continuation: HistoryContinuationV2? = null, limit: Int = 40): ApiResult<HistoryPageV2> =
        historyV2.page(limit = limit, continuation = continuation)
}

enum class DownloadProgressReceipt { ACCEPTED, NOT_FOUND, RETRY }

@Serializable
private data class ProgressSyncRequestV2(val items: List<ProgressSyncItemV2>)

@Serializable
private data class ProgressSyncItemV2(
    @SerialName("media_item_id") val mediaItemId: String,
    @SerialName("position_ms") val positionMs: Long,
    @SerialName("duration_ms") val durationMs: Long,
    @SerialName("updated_at") val updatedAt: String,
)

@Serializable
private data class ProgressSyncResponseV2(val items: List<ProgressSyncResultV2>)

@Serializable
private data class ProgressSyncResultV2(
    val index: Int,
    @SerialName("media_item_id") val mediaItemId: String,
    val status: String,
    val failure: Problem? = null,
)
