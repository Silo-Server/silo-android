package org.siloserver.silo.network.apiv2

import io.ktor.client.HttpClient
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import org.siloserver.silo.model.catalog.BrowseItem
import org.siloserver.silo.network.*

@Serializable private data class SimilarCards(val items: List<BrowseItem>, val page: PageInfo)

class SimilarCardsV2Api(private val client: HttpClient, private val tokens: TokenManager,
    private val gate: ApiV2Gate = ApiV2Gate.Unrestricted) {
    suspend fun capture(): AuthScopeSnapshot? = tokens.snapshotCurrentScope()?.takeIf { !it.profileId.isNullOrBlank() }
    suspend fun current(owner: AuthScopeSnapshot): Boolean {
        val now=capture()
        return owner.isSameIdentityAs(now) && owner.serverUrl==now?.serverUrl && owner.profileId==now?.profileId &&
            owner.profileToken==now?.profileToken && owner.credentialGenerationId==now?.credentialGenerationId
    }
    suspend fun list(id: String, limit: Int, owner: AuthScopeSnapshot): ApiResult<List<BrowseItem>> {
        if(id.isBlank() || limit !in 1..50) return ApiResult.Error(422,"validation_failed","Invalid similar-card request.")
        if(!current(owner)) return changed()
        val result=safeApiV2Call<JsonObject>(gate) {
            client.get("/api/v2/recommendations/similar/${id.encodeURLPathPart()}") {
                authScope(owner); requireSiloAuth(); parameter("limit",limit)
            }.also { check(!it.status.isSuccess() || it.status==HttpStatusCode.OK) }
        }
        if(!current(owner)) return changed()
        return when(result) {
            is ApiResult.Success -> try {
                val rows = result.data["items"] as? JsonArray ?: error("Missing cards")
                check(rows.all { row -> (row.jsonObject["content_id"] as? JsonPrimitive)?.isString == true })
                val body=SiloJson.decodeFromJsonElement<SimilarCards>(result.data)
                if(body.page.hasMore || body.page.nextCursor!=null || body.items.size>limit ||
                    body.items.any {it.contentId.isBlank()} || body.items.map {it.contentId}.toSet().size!=body.items.size)
                    ApiResult.Error(0,"invalid_similar_cards","The server returned incomplete similar cards.")
                else ApiResult.Success(body.items)
            } catch (_: Exception) { ApiResult.Error(0,"invalid_similar_cards","The server returned unsupported similar cards.") }
            is ApiResult.Error -> result
            is ApiResult.NetworkError -> result
        }
    }
    private fun changed()=ApiResult.Error(0,"similar_authority_changed","The original similar-card identity changed.")
}
