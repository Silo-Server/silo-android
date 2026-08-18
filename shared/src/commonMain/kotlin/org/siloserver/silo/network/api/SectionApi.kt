package org.siloserver.silo.network.api

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.http.*
import org.siloserver.silo.model.catalog.CatalogQueryGroup
import org.siloserver.silo.model.catalog.CatalogResponse
import org.siloserver.silo.model.section.*
import org.siloserver.silo.network.ApiErrorBody
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.network.SiloJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class SectionApi(private val client: HttpClient) {

    // --- Home ---

    suspend fun getHomeLayout(): ApiResult<HomeLayoutResponse> = safeApiCall {
        client.get("/api/v1/home/layout")
    }

    suspend fun getHomeSections(): ApiResult<SectionsResponse> = safeApiCall {
        client.get("/api/v1/home/sections")
    }

    suspend fun getHomeSectionItems(sectionId: String): ApiResult<HomeSectionItemsResponse> = safeApiCall {
        client.get("/api/v1/home/sections/$sectionId/items")
    }

    // --- Library Sections ---

    suspend fun getLibrarySections(libraryId: Int): ApiResult<SectionsResponse> = safeApiCall {
        client.get("/api/v1/library/$libraryId/sections")
    }

    suspend fun getLibrarySectionItems(
        libraryId: Int,
        sectionId: String
    ): ApiResult<HomeSectionItemsResponse> = safeApiCall {
        client.get("/api/v1/library/$libraryId/sections/$sectionId/items")
    }

    // --- Library Collections ---

    suspend fun getLibraryCollections(libraryId: Int): ApiResult<LibraryCollectionsResponse> {
        return try {
            val response = client.get("/api/v1/library/$libraryId/collections")
            val body = response.body<String>()
            if (response.status.isSuccess()) {
                ApiResult.Success(parseLibraryCollectionsResponse(body))
            } else {
                val error = runCatching {
                    SiloJson.decodeFromString<ApiErrorBody>(body)
                }.getOrDefault(ApiErrorBody())
                ApiResult.Error(response.status.value, error.error, error.message)
            }
        } catch (e: Exception) {
            ApiResult.NetworkError(e)
        }
    }

    /**
     * Library-collection items via the paginated catalog resolver
     * (`source=library_collection`), matching silo-apple's
     * `libraryCollectionItems`. The raw
     * `/library/{id}/collections/{id}/items` route serves full membership
     * in one response — a 10k-item language collection in a single body —
     * and is being phased out client-side so the server can bound it.
     *
     * A null [sort] omits both sort params, which is what makes the server
     * fall back to the collection's own order (manual / MDBList / smart).
     */
    suspend fun getLibraryCollectionItems(
        collectionId: String,
        offset: Int = 0,
        limit: Int = 60,
        sort: String? = null,
        order: String? = null,
        queryGroups: List<CatalogQueryGroup> = emptyList(),
        match: String? = null,
    ): ApiResult<CatalogResponse> = safeApiCall {
        client.get("/api/v1/catalog") {
            parameter("source", "library_collection")
            parameter("collection_id", collectionId)
            parameter("offset", offset)
            parameter("limit", limit)
            if (sort != null) {
                parameter("sort", sort)
                order?.let { parameter("order", it) }
            }
            match?.let { parameter("match", it) }
            catalogQueryGroupParameters(queryGroups)
        }
    }

    private fun parseLibraryCollectionsResponse(body: String): LibraryCollectionsResponse {
        val root = SiloJson.parseToJsonElement(body)
        if (root is JsonArray) {
            val flat = root.mapNotNull { parseLibraryCollection(it, kind = null) }
            return LibraryCollectionsResponse(collections = flat)
        }
        val obj = root as? JsonObject ?: return LibraryCollectionsResponse()

        // Grouped shape (the modern server response):
        // { groups: [...], ungrouped?: { sort_order, collections } }
        val groupsArray = obj["groups"] as? JsonArray
        val ungroupedObj = obj["ungrouped"] as? JsonObject

        // Enter the grouped path only when there is real grouped data —
        // an empty `groups` array next to a flat `collections` key would
        // otherwise silently drop the flat list. Matches the iOS parser.
        if ((groupsArray != null && groupsArray.isNotEmpty()) || ungroupedObj != null) {
            val groups = groupsArray
                ?.mapNotNull { parseLibraryCollectionGroup(it) }
                .orEmpty()
            val ungrouped = ungroupedObj?.let { parseUngroupedSection(it) }

            // Flatten for backward compatibility callers.
            val flat = buildList {
                for (group in groups) addAll(group.collections)
                if (ungrouped != null) addAll(ungrouped.collections)
            }
            return LibraryCollectionsResponse(
                collections = flat,
                groups = groups,
                ungrouped = ungrouped,
            )
        }

        // Flat shape: { collections: [...] }
        val flat = (obj["collections"] as? JsonArray)
            ?.mapNotNull { parseLibraryCollection(it, kind = null) }
            .orEmpty()
        return LibraryCollectionsResponse(collections = flat)
    }

    private fun parseLibraryCollectionGroup(element: JsonElement): LibraryCollectionGroup? {
        val obj = element as? JsonObject ?: return null
        val id = obj["id"]?.jsonPrimitive?.contentOrNull ?: return null
        val name = obj["name"]?.jsonPrimitive?.contentOrNull ?: "Group"
        val kind = obj["kind"]?.jsonPrimitive?.contentOrNull ?: "regular"
        val sortMode = obj["sort_mode"]?.jsonPrimitive?.contentOrNull ?: "manual"
        val sortOrder = obj["sort_order"]?.jsonPrimitive?.intOrNull ?: 0
        val collections = (obj["collections"] as? JsonArray)
            ?.mapNotNull { parseLibraryCollection(it, kind = kind) }
            .orEmpty()
        return LibraryCollectionGroup(
            id = id,
            name = name,
            kind = kind,
            sortMode = sortMode,
            sortOrder = sortOrder,
            collections = collections,
        )
    }

    private fun parseUngroupedSection(obj: JsonObject): LibraryUngroupedSection {
        val sortOrder = obj["sort_order"]?.jsonPrimitive?.intOrNull ?: Int.MAX_VALUE
        val collections = (obj["collections"] as? JsonArray)
            ?.mapNotNull { parseLibraryCollection(it, kind = "regular") }
            .orEmpty()
        return LibraryUngroupedSection(sortOrder = sortOrder, collections = collections)
    }

    private fun parseLibraryCollection(element: JsonElement, kind: String?): LibraryCollection? {
        val obj = element as? JsonObject ?: return null
        val id = obj["id"]?.jsonPrimitive?.contentOrNull ?: return null
        // The server emits the collection display string as `title`; `name`
        // is accepted as a fallback so older / mocked payloads still decode.
        val name = obj["title"]?.jsonPrimitive?.contentOrNull
            ?: obj["name"]?.jsonPrimitive?.contentOrNull
            ?: "Collection"
        return LibraryCollection(
            id = id,
            name = name,
            collectionType = obj["collection_type"]?.jsonPrimitive?.contentOrNull,
            itemCount = obj["item_count"]?.jsonPrimitive?.intOrNull
                ?: obj["item_count"]?.jsonPrimitive?.contentOrNull?.toIntOrNull(),
            posterUrl = obj["poster_url"]?.jsonPrimitive?.contentOrNull,
            posterThumbhash = obj["poster_thumbhash"]?.jsonPrimitive?.contentOrNull,
            kind = kind,
            creatorProfileId = obj["creator_profile_id"]?.jsonPrimitive?.contentOrNull,
        )
    }
}
