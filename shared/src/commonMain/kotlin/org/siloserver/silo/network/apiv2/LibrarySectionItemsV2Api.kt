package org.siloserver.silo.network.apiv2

import io.ktor.client.HttpClient
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.serialization.json.*
import org.siloserver.silo.model.section.*
import org.siloserver.silo.network.*

class LibrarySectionItemsV2Api(private val client: HttpClient, private val tokens: TokenManager, private val gate: ApiV2Gate) {
    suspend fun capture(): AuthScopeSnapshot? = tokens.captureProfileScope()
    suspend fun current(owner: AuthScopeSnapshot): Boolean = owner.stillOwns(tokens, OwnerPolicy.FULL)
    suspend fun read(libraryId: Int, sectionId: String, owner: AuthScopeSnapshot): ApiResult<HomeSectionItemsResponse> {
        if (libraryId <= 0 || sectionId.isBlank()) return ApiResult.Error(422, "validation_failed", "Invalid library section.")
        return ownedV2Call<JsonObject, HomeSectionItemsResponse>(gate, tokens, owner, OwnerPolicy.FULL, HttpStatusCode.OK, { scope ->
            client.get("/api/v2/library/$libraryId/sections/${sectionId.encodeURLPathPart()}/items") {
                authScope(scope!!); requireSiloAuth()
            }
        }) { row ->
            val section = decodeSectionV2(row)
            require(section.id == sectionId) { "Wrong section identity." }
            HomeSectionItemsResponse(section, section.items)
        }
    }
    suspend fun list(libraryId: Int, owner: AuthScopeSnapshot): ApiResult<SectionsResponse> {
        if (libraryId <= 0) return ApiResult.Error(422, "validation_failed", "Invalid library.")
        return ownedV2Call<JsonObject, SectionsResponse>(gate, tokens, owner, OwnerPolicy.FULL, HttpStatusCode.OK, { scope ->
            client.get("/api/v2/library/$libraryId/sections") { authScope(scope!!); requireSiloAuth() }
        }) { decodeSectionsV2(it) }
    }
}
