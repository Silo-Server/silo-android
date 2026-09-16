package org.siloserver.silo.network.apiv2

import io.ktor.client.HttpClient
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.serialization.Serializable
import org.siloserver.silo.model.catalog.BrowseItem
import org.siloserver.silo.network.*

@Serializable private data class SimilarCards(val items: List<BrowseItem>, val page: PageInfo? = null)

class SimilarCardsV2Api(private val client: HttpClient, private val tokens: TokenManager, private val gate: ApiV2Gate) {
    suspend fun capture(): AuthScopeSnapshot? = tokens.captureProfileScope()
    suspend fun current(owner: AuthScopeSnapshot): Boolean = owner.stillOwns(tokens, OwnerPolicy.FULL)
    suspend fun list(id: String, limit: Int, owner: AuthScopeSnapshot): ApiResult<List<BrowseItem>> {
        if (id.isBlank() || limit !in 1..50) return ApiResult.Error(422, "validation_failed", "Invalid similar-card request.")
        return ownedV2Call<SimilarCards, List<BrowseItem>>(gate, tokens, owner, OwnerPolicy.FULL, HttpStatusCode.OK, { scope ->
            client.get("/api/v2/recommendations/similar/${id.encodeURLPathPart()}") {
                authScope(scope!!); requireSiloAuth(); parameter("limit", limit)
            }
        }) { body ->
            require((body.page == null || (!body.page.hasMore && body.page.nextCursor == null)) && body.items.size <= limit &&
                body.items.none { it.contentId.isBlank() } && body.items.map { it.contentId }.toSet().size == body.items.size) {
                "The server returned incomplete similar cards."
            }
            body.items
        }
    }
}
