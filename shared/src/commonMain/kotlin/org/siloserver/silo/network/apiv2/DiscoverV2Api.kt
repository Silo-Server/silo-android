package org.siloserver.silo.network.apiv2

import io.ktor.client.HttpClient
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import org.siloserver.silo.model.recommendation.*
import org.siloserver.silo.model.section.SectionItem
import org.siloserver.silo.network.*

@Serializable private data class DiscoverCollection(val items: List<DiscoverV2Row>, val page: PageInfo)
@Serializable private data class DiscoverV2Row(
    val type: String, val title: String, val kind: String? = null, val key: String? = null,
    val items: List<SectionItem>,
)

class DiscoverV2Api(private val client: HttpClient, private val tokens: TokenManager,
    private val gate: ApiV2Gate = ApiV2Gate.Unrestricted) {
    suspend fun capture(): AuthScopeSnapshot? = tokens.snapshotCurrentScope()?.takeIf { !it.profileId.isNullOrBlank() }
    suspend fun current(owner: AuthScopeSnapshot): Boolean {
        val now = capture()
        return owner.isSameIdentityAs(now) && owner.serverUrl == now?.serverUrl && owner.profileId == now?.profileId &&
            owner.profileToken == now?.profileToken && owner.credentialGenerationId == now?.credentialGenerationId
    }
    suspend fun read(owner: AuthScopeSnapshot): ApiResult<DiscoverResponse> {
        if (!current(owner)) return changed()
        val result = safeApiV2Call<JsonObject>(gate) {
            client.get("/api/v2/recommendations/discover") { authScope(owner); requireSiloAuth() }
                .also { check(!it.status.isSuccess() || it.status == HttpStatusCode.OK) }
        }
        if (!current(owner)) return changed()
        return when (result) {
            is ApiResult.Success -> try {
                val rows = result.data["items"] as? JsonArray ?: error("Missing rows")
                rows.forEach { row ->
                    val cards = row.jsonObject["items"] as? JsonArray ?: error("Missing cards")
                    check(cards.all { (it.jsonObject["content_id"] as? JsonPrimitive)?.isString == true })
                }
                val body = SiloJson.decodeFromJsonElement<DiscoverCollection>(result.data)
                check(!body.page.hasMore && body.page.nextCursor == null)
                check(body.items.all { row -> row.items.all { it.contentId.isNotBlank() } })
                ApiResult.Success(DiscoverResponse(body.items.map { DiscoverRow(it.type, it.title, it.kind, it.key, it.items) }))
            } catch (_: Exception) {
                ApiResult.Error(0, "invalid_discover", "The server returned unsupported Discover rows.")
            }
            is ApiResult.Error -> result
            is ApiResult.NetworkError -> result
        }
    }
    private fun changed() = ApiResult.Error(0, "discover_authority_changed", "The initiating Discover identity changed.")
}
