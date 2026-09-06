package org.siloserver.silo.network.apiv2

import io.ktor.client.HttpClient
import io.ktor.client.request.*
import io.ktor.http.*
import org.siloserver.silo.network.*

/** Cancellation acknowledges a request; job polling determines the terminal outcome. */
class SubtitleAiCancelV2Api(
    private val client: HttpClient,
    private val tokens: TokenManager,
    private val gate: ApiV2Gate = ApiV2Gate.Unrestricted,
) {
    suspend fun cancel(id: Long, expected: AuthScopeSnapshot? = null): ApiResult<Unit> {
        if (id <= 0) return ApiResult.Error(0,"invalid_subtitle_job","A positive job identifier is required.")
        val scope = expected ?: tokens.snapshotCurrentScope() ?: return changed()
        if (!current(scope)) return changed()
        val result = safeApiV2Call<Unit>(gate) {
            client.post("/api/v2/subtitles/ai/jobs/${id.toString()}/cancel") {
                authScope(scope); requireSiloAuth()
                // Declared natural-idempotent for this exact immutable job ID.
            }.also { check(!it.status.isSuccess() || it.status == HttpStatusCode.NoContent) }
        }
        return if (current(scope)) result else changed()
    }

    private suspend fun current(scope: AuthScopeSnapshot): Boolean {
        val now = tokens.snapshotCurrentScope()
        return scope.isSameIdentityAs(now) && scope.profileId == now?.profileId && scope.profileToken == now?.profileToken
    }
    private fun changed() = ApiResult.Error(0,"identity_changed","The subtitle job's account or profile changed.")
}
