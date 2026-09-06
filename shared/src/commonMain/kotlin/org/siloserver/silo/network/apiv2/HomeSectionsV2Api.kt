package org.siloserver.silo.network.apiv2

import io.ktor.client.HttpClient
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.serialization.json.*
import kotlinx.coroutines.ensureActive
import org.siloserver.silo.model.section.SectionsResponse
import org.siloserver.silo.model.section.ResolvedSection
import org.siloserver.silo.model.section.HomeSectionItemsResponse
import org.siloserver.silo.network.*

class HomeSectionsV2Api(private val client: HttpClient, private val tokens: TokenManager,
    private val gate: ApiV2Gate = ApiV2Gate.Unrestricted) {
    suspend fun capture() = tokens.snapshotCurrentScope()?.takeIf { !it.profileId.isNullOrBlank() }
    suspend fun current(owner: AuthScopeSnapshot): Boolean {
        val now = capture()
        return owner.isSameIdentityAs(now) && owner.serverUrl == now?.serverUrl && owner.profileId == now?.profileId &&
            owner.profileToken == now?.profileToken && owner.credentialGenerationId == now?.credentialGenerationId
    }
    suspend fun dismiss(surface: String, itemId: String, anchor: String, owner: AuthScopeSnapshot): ApiResult<Unit> {
        if (surface !in setOf("continue_watching", "next_up") || itemId.isBlank() || anchor.isBlank())
            return ApiResult.Error(422, "validation_failed", "A Home dismissal needs its observed item and anchor.")
        if (!current(owner)) return changed()
        kotlinx.coroutines.currentCoroutineContext().ensureActive()
        val result = safeApiV2Call<Unit>(gate) {
            client.put("/api/v2/home/dismissals/$surface/${itemId.encodeURLPathPart()}") {
                authScope(owner)
                requireSiloAuth()
                contentType(ContentType.Application.Json)
                setBody(buildJsonObject { put(if (surface == "continue_watching") "progress_updated_at" else "series_id", anchor) })
            }.also { check(!it.status.isSuccess() || it.status == HttpStatusCode.NoContent) }
        }
        if (!current(owner)) return changed()
        kotlinx.coroutines.currentCoroutineContext().ensureActive()
        return result
    }

    suspend fun list(owner: AuthScopeSnapshot): ApiResult<SectionsResponse> {
        if (!current(owner)) return changed()
        val result = safeApiV2Call<JsonObject>(gate) {
            client.get("/api/v2/home/sections") { authScope(owner); requireSiloAuth() }
                .also { check(!it.status.isSuccess() || it.status == HttpStatusCode.OK) }
        }
        if (!current(owner)) return changed()
        return when (result) {
            is ApiResult.Success -> try {
                val sections = result.data["sections"] as? JsonArray ?: error("Missing sections")
                sections.forEach { row ->
                    val id = row.jsonObject["id"] as? JsonPrimitive
                    check(id?.isString == true && id.content.isNotBlank())
                    val cards = row.jsonObject["items"] as? JsonArray ?: error("Missing cards")
                    check(cards.all { card ->
                        val contentId = card.jsonObject["content_id"] as? JsonPrimitive
                        contentId?.isString == true && contentId.content.isNotBlank()
                    })
                }
                ApiResult.Success(SiloJson.decodeFromJsonElement<SectionsResponse>(result.data))
            } catch (_: Exception) { ApiResult.Error(0, "invalid_home_sections", "The server returned unsupported home sections.") }
            is ApiResult.Error -> result
            is ApiResult.NetworkError -> result
        }
    }
    suspend fun section(id: String, owner: AuthScopeSnapshot): ApiResult<HomeSectionItemsResponse> {
        if (id.isBlank()) return ApiResult.Error(422, "validation_failed", "Invalid home section.")
        if (!current(owner)) return changed()
        val result = safeApiV2Call<JsonObject>(gate) {
            client.get("/api/v2/home/sections/${id.encodeURLPathPart()}/items") { authScope(owner); requireSiloAuth() }
                .also { check(!it.status.isSuccess() || it.status == HttpStatusCode.OK) }
        }
        if (!current(owner)) return changed()
        return when (result) {
            is ApiResult.Success -> try {
                val row = result.data
                val returnedId = row["id"] as? JsonPrimitive
                check(returnedId?.isString == true && returnedId.content == id)
                val cards = row["items"] as? JsonArray ?: error("Missing cards")
                check(cards.all { card ->
                    val contentId = card.jsonObject["content_id"] as? JsonPrimitive
                    contentId?.isString == true && contentId.content.isNotBlank()
                })
                val section = SiloJson.decodeFromJsonElement<ResolvedSection>(row)
                ApiResult.Success(HomeSectionItemsResponse(section, section.items))
            } catch (_: Exception) { ApiResult.Error(0, "invalid_home_section", "The server returned an unsupported home section.") }
            is ApiResult.Error -> result
            is ApiResult.NetworkError -> result
        }
    }
    private fun changed() = ApiResult.Error(0, "home_authority_changed", "The initiating home identity changed.")
}
