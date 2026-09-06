package org.siloserver.silo.network.apiv2

import io.ktor.client.HttpClient
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.serialization.json.*
import org.siloserver.silo.model.section.*
import org.siloserver.silo.network.*

class LibrarySectionItemsV2Api(private val client: HttpClient, private val tokens: TokenManager,
    private val gate: ApiV2Gate = ApiV2Gate.Unrestricted) {
    suspend fun capture() = tokens.snapshotCurrentScope()?.takeIf { !it.profileId.isNullOrBlank() }
    suspend fun current(owner: AuthScopeSnapshot): Boolean {
        val now = capture()
        return owner.isSameIdentityAs(now) && owner.serverUrl == now?.serverUrl && owner.profileId == now?.profileId &&
            owner.profileToken == now?.profileToken && owner.credentialGenerationId == now?.credentialGenerationId
    }
    suspend fun read(libraryId: Int, sectionId: String, owner: AuthScopeSnapshot): ApiResult<HomeSectionItemsResponse> {
        if (libraryId <= 0 || sectionId.isBlank()) return ApiResult.Error(422, "validation_failed", "Invalid library section.")
        if (!current(owner)) return changed()
        val result = safeApiV2Call<JsonObject>(gate) {
            client.get("/api/v2/library/$libraryId/sections/${sectionId.encodeURLPathPart()}/items") {
                authScope(owner); requireSiloAuth()
            }.also { check(!it.status.isSuccess() || it.status == HttpStatusCode.OK) }
        }
        if (!current(owner)) return changed()
        return when (result) {
            is ApiResult.Success -> try {
                val json = result.data
                val id = json["id"] as? JsonPrimitive
                check(id?.isString == true && id.content == sectionId)
                val items = json["items"] as? JsonArray ?: error("Missing items")
                check(items.all { item ->
                    val contentId = item.jsonObject["content_id"] as? JsonPrimitive
                    contentId?.isString == true && contentId.content.isNotBlank()
                })
                val section = SiloJson.decodeFromJsonElement<ResolvedSection>(json)
                ApiResult.Success(HomeSectionItemsResponse(section, section.items))
            } catch (_: Exception) { ApiResult.Error(0, "invalid_library_section", "The server returned unsupported library section items.") }
            is ApiResult.Error -> result
            is ApiResult.NetworkError -> result
        }
    }
    private fun changed() = ApiResult.Error(0, "library_section_authority_changed", "The initiating library section identity changed.")
}
