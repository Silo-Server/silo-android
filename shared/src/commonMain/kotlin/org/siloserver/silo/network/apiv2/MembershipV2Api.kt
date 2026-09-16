package org.siloserver.silo.network.apiv2

import io.ktor.client.HttpClient
import io.ktor.client.request.*
import io.ktor.http.HttpMethod
import io.ktor.http.encodeURLPathPart
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.siloserver.silo.network.*

@Serializable
data class MembershipEntryV2(
    @SerialName("item_id") val itemId: String,
    @SerialName("added_at") val addedAt: String,
)

class MembershipV2Api(
    private val client: HttpClient,
    private val gate: ApiV2Gate,
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
        val result = ownedV2Call<MembershipEntryV2, MembershipEntryV2?>(gate, tokenManager, scope, OwnerPolicy.IDENTITY, null, { owner ->
            client.get("/api/v2/$list/${itemId.encodeURLPathPart()}") { owner?.let { authScope(it) } }
        }) { entry ->
            require(entry.itemId == itemId) { "The membership response names a different item." }
            entry
        }
        return if (result is ApiResult.Error && result.code == 404) ApiResult.Success(null) else result
    }

    private suspend fun write(list: String, itemId: String, present: Boolean,
        capturedScope: AuthScopeSnapshot?): ApiResult<Unit> {
        val scope = capturedScope ?: tokenManager?.snapshotCurrentScope()
        if (scope != null && !scope.stillOwns(tokenManager, OwnerPolicy.IDENTITY)) return identityChanged()
        // No post-guard: do not discard a confirmed old-scope acknowledgement. The
        // caller can resolve its exact recorded command without publishing into the new UI.
        return ownedV2Call<Unit, Unit>(gate, tokenManager, null, OwnerPolicy.IDENTITY, HttpStatusCode.NoContent, { _ ->
            client.request("/api/v2/$list/${itemId.encodeURLPathPart()}") {
                method = if (present) HttpMethod.Put else HttpMethod.Delete
                scope?.let { authScope(it) }
                singleAttempt()
            }
        }) { }
    }
}
