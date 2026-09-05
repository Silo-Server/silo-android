package org.siloserver.silo.network.apiv2

import io.ktor.client.HttpClient
import io.ktor.client.request.*
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.siloserver.silo.network.*

@Serializable
data class MembershipEntryV2(
    @SerialName("item_id") val itemId: String,
    @SerialName("added_at") val addedAt: String,
)

/** Confirmed response belongs to this captured authority, even if the UI has moved. */
data class MembershipAcknowledgementV2 internal constructor(
    val itemId: String,
    val present: Boolean,
    val scope: AuthScopeSnapshot?,
)

class MembershipV2Api(
    private val client: HttpClient,
    private val gate: ApiV2Gate = ApiV2Gate.Unrestricted,
    private val tokenManager: TokenManager? = null,
) {
    suspend fun favorite(itemId: String, scope: AuthScopeSnapshot? = null) = read("favorites", itemId, scope)
    suspend fun watchlist(itemId: String, scope: AuthScopeSnapshot? = null) = read("watchlist", itemId, scope)
    suspend fun addFavorite(itemId: String, scope: AuthScopeSnapshot? = null) = write("favorites", itemId, true, scope)
    suspend fun removeFavorite(itemId: String, scope: AuthScopeSnapshot? = null) = write("favorites", itemId, false, scope)
    suspend fun addToWatchlist(itemId: String, scope: AuthScopeSnapshot? = null) = write("watchlist", itemId, true, scope)
    suspend fun removeFromWatchlist(itemId: String, scope: AuthScopeSnapshot? = null) = write("watchlist", itemId, false, scope)

    private suspend fun read(list: String, itemId: String, capturedScope: AuthScopeSnapshot?): ApiResult<MembershipEntryV2?> {
        val scope = capturedScope ?: tokenManager?.snapshotCurrentScope()
        changedViewer(scope)?.let { return it }
        val result = safeApiV2Call<MembershipEntryV2>(gate) {
            client.get("/api/v2/$list/$itemId") { scope?.let { authScope(it) } }
        }
        currentCoroutineContext().ensureActive()
        changedViewer(scope)?.let { return it }
        return when (result) {
            is ApiResult.Success -> if (result.data.itemId == itemId) ApiResult.Success(result.data)
                else ApiResult.Error(0, "invalid_response", "The membership response names a different item.")
            is ApiResult.Error -> if (result.code == 404) ApiResult.Success(null) else result
            is ApiResult.NetworkError -> result
        }
    }

    private suspend fun write(list: String, itemId: String, present: Boolean,
        capturedScope: AuthScopeSnapshot?): ApiResult<MembershipAcknowledgementV2> {
        val scope = capturedScope ?: tokenManager?.snapshotCurrentScope()
        changedViewer(scope)?.let { return it }
        val result = safeApiV2Call<Unit>(gate) {
            client.request("/api/v2/$list/$itemId") {
                method = if (present) HttpMethod.Put else HttpMethod.Delete
                scope?.let { authScope(it) }
                singleAttempt()
            }.also { response ->
                check(!response.status.isSuccess() || response.status == HttpStatusCode.NoContent) {
                    "The membership mutation returned an unexpected success status."
                }
            }
        }
        currentCoroutineContext().ensureActive()
        // Do not discard a confirmed old-scope acknowledgement. The caller can
        // resolve its exact recorded command without publishing into the new UI.
        return result.map { MembershipAcknowledgementV2(itemId, present, scope) }
    }

    private suspend fun changedViewer(scope: AuthScopeSnapshot?): ApiResult.Error? =
        if (scope != null && !scope.isSameIdentityAs(tokenManager?.snapshotCurrentScope()))
            ApiResult.Error(0, "identity_changed", "The active viewer changed.") else null
}
