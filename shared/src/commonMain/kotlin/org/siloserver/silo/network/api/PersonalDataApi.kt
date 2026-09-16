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
import org.siloserver.silo.network.apiv2.UserLibrariesV2
import org.siloserver.silo.network.apiv2.identityChanged
import org.siloserver.silo.network.apiv2.ownedV2Call
import org.siloserver.silo.network.apiv2.safeApiV2Call

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

    // --- History ---

    private val historyV2 = HistoryV2Api(client, apiV2Gate, tokenManager)

    suspend fun listHistory(continuation: HistoryContinuationV2? = null, limit: Int = 40): ApiResult<HistoryPageV2> =
        historyV2.page(limit = limit, continuation = continuation)
}
