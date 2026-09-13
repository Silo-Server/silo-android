package org.siloserver.silo.network.apiv2

import io.ktor.client.HttpClient
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.serialization.Serializable
import org.siloserver.silo.model.recommendation.*
import org.siloserver.silo.model.section.SectionItem
import org.siloserver.silo.network.*

@Serializable private data class DiscoverCollection(val items: List<DiscoverV2Row>, val page: PageInfo? = null)
@Serializable private data class DiscoverV2Row(
    val type: String, val title: String, val kind: String? = null, val key: String? = null,
    val items: List<SectionItem>,
)

class DiscoverV2Api(private val client: HttpClient, private val tokens: TokenManager, private val gate: ApiV2Gate) {
    suspend fun capture(): AuthScopeSnapshot? = tokens.captureProfileScope()
    suspend fun current(owner: AuthScopeSnapshot): Boolean = owner.stillOwns(tokens, OwnerPolicy.FULL)
    suspend fun read(owner: AuthScopeSnapshot): ApiResult<DiscoverResponse> =
        ownedV2Call<DiscoverCollection, DiscoverResponse>(gate, tokens, owner, OwnerPolicy.FULL, HttpStatusCode.OK, { scope ->
            client.get("/api/v2/recommendations/discover") { authScope(scope!!); requireSiloAuth() }
        }) { body ->
            require(body.page == null || (!body.page.hasMore && body.page.nextCursor == null)) { "Discover is not paged." }
            require(body.items.all { row -> row.items.all { it.contentId.isNotBlank() } }) { "Blank card identity." }
            DiscoverResponse(body.items.map { DiscoverRow(it.type, it.title, it.kind, it.key, it.items) })
        }
}
