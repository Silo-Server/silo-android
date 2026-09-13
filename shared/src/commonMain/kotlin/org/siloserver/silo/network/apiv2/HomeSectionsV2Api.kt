package org.siloserver.silo.network.apiv2

import io.ktor.client.HttpClient
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.serialization.json.*
import org.siloserver.silo.model.section.SectionsResponse
import org.siloserver.silo.model.section.ResolvedSection
import org.siloserver.silo.model.section.HomeSectionItemsResponse
import org.siloserver.silo.network.*

/** One v2 section row: `items` is required on the wire even though the domain model defaults it. */
internal fun decodeSectionV2(row: JsonObject): ResolvedSection {
    require(row["items"] is JsonArray) { "Missing section items." }
    val section = SiloJson.decodeFromJsonElement<ResolvedSection>(row)
    require(section.id.isNotBlank() && section.items.all { it.contentId.isNotBlank() }) { "Blank section identity." }
    return section
}

internal fun decodeSectionsV2(body: JsonObject): SectionsResponse {
    val rows = body["sections"] as? JsonArray ?: throw IllegalArgumentException("Missing sections.")
    return SectionsResponse(rows.map { decodeSectionV2(it.jsonObject) })
}

class HomeSectionsV2Api(private val client: HttpClient, private val tokens: TokenManager, private val gate: ApiV2Gate) {
    suspend fun capture(): AuthScopeSnapshot? = tokens.captureProfileScope()
    suspend fun current(owner: AuthScopeSnapshot): Boolean = owner.stillOwns(tokens, OwnerPolicy.FULL)
    suspend fun dismiss(surface: String, itemId: String, anchor: String, owner: AuthScopeSnapshot): ApiResult<Unit> {
        if (surface !in setOf("continue_watching", "next_up") || itemId.isBlank() || anchor.isBlank())
            return ApiResult.Error(422, "validation_failed", "A Home dismissal needs its observed item and anchor.")
        return ownedV2Call<Unit, Unit>(gate, tokens, owner, OwnerPolicy.FULL, HttpStatusCode.NoContent, { scope ->
            client.put("/api/v2/home/dismissals/$surface/${itemId.encodeURLPathPart()}") {
                authScope(scope!!)
                requireSiloAuth()
                contentType(ContentType.Application.Json)
                setBody(buildJsonObject { put(if (surface == "continue_watching") "progress_updated_at" else "series_id", anchor) })
            }
        }) { }
    }

    suspend fun list(owner: AuthScopeSnapshot): ApiResult<SectionsResponse> =
        ownedV2Call<JsonObject, SectionsResponse>(gate, tokens, owner, OwnerPolicy.FULL, HttpStatusCode.OK, { scope ->
            client.get("/api/v2/home/sections") { authScope(scope!!); requireSiloAuth() }
        }) { decodeSectionsV2(it) }

    suspend fun section(id: String, owner: AuthScopeSnapshot): ApiResult<HomeSectionItemsResponse> {
        if (id.isBlank()) return ApiResult.Error(422, "validation_failed", "Invalid home section.")
        return ownedV2Call<JsonObject, HomeSectionItemsResponse>(gate, tokens, owner, OwnerPolicy.FULL, HttpStatusCode.OK, { scope ->
            client.get("/api/v2/home/sections/${id.encodeURLPathPart()}/items") { authScope(scope!!); requireSiloAuth() }
        }) { row ->
            val section = decodeSectionV2(row)
            require(section.id == id) { "Wrong section identity." }
            HomeSectionItemsResponse(section, section.items)
        }
    }
}
