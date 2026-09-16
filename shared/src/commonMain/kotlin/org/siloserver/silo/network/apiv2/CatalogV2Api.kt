package org.siloserver.silo.network.apiv2

import io.ktor.client.HttpClient
import io.ktor.client.statement.HttpResponse
import io.ktor.http.encodeURLPathPart
import io.ktor.client.request.*
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.siloserver.silo.model.catalog.AudiobookGroup
import org.siloserver.silo.model.catalog.BrowseItem
import org.siloserver.silo.model.catalog.ItemDetail
import org.siloserver.silo.model.catalog.Season
import org.siloserver.silo.model.catalog.SeasonsResponse
import org.siloserver.silo.model.catalog.EpisodesResponse
import org.siloserver.silo.model.catalog.FileVersion
import org.siloserver.silo.model.catalog.Person
import org.siloserver.silo.network.*

/** Bounded native browse reads. A refused cursor never falls back to page one. */
class CatalogV2Api(
    private val client: HttpClient,
    private val gate: ApiV2Gate,
    private val tokenManager: TokenManager? = null,
) {
    suspend fun browse(
        query: CatalogQueryV2,
        continuation: CatalogContinuationV2? = null,
        imageSize: String? = null,
    ): ApiResult<CatalogPageV2<BrowseItem>> {
        if (query.limit !in 1..100) return invalid("Page size must be between 1 and 100.")
        val usePost = query.groups.isNotEmpty()
        val key = "${if (usePost) "POST" else "GET"}/catalog:$imageSize:${SiloJson.encodeToString(query)}"
        val scope = continuation?.scope ?: tokenManager?.snapshotCurrentScope()
        if (continuation != null && continuation.requestKey != key) return invalid("The query changed. Reload from the first page.")
        val result = ownedV2Call<CatalogPageWireV2, CatalogPageWireV2>(gate, tokenManager, scope, OwnerPolicy.IDENTITY, null, { _ ->
            if (usePost) client.post("/api/v2/catalog/query") {
                scope?.let { authScope(it) }
                imageSize?.let { parameter("image_size", it) }
                contentType(ContentType.Application.Json)
                val body = SiloJson.encodeToJsonElement(CatalogQueryV2.serializer(), query).jsonObject.toMutableMap()
                continuation?.let { body["cursor"] = JsonPrimitive(it.cursor) }
                setBody(JsonObject(body))
            } else client.get("/api/v2/catalog") {
                scope?.let { authScope(it) }
                parameter("source", query.source)
                parameter("scope", query.scope)
                parameter("library_id", query.libraryId)
                parameter("collection_id", query.collectionId)
                parameter("person_id", query.personId)
                parameter("section_id", query.sectionId)
                parameter("type", query.type)
                parameter("q", query.q)
                parameter("name_prefix", query.namePrefix)
                parameter("match", query.match)
                parameter("group", query.group)
                parameter("query_limit", query.queryLimit)
                parameter("skip_total", query.skipTotal)
                parameter("limit", query.limit)
                imageSize?.let { parameter("image_size", it) }
                query.sort?.let { parameter("sort", if (query.order == "desc") "-$it" else it) }
                continuation?.let { parameter("cursor", it.cursor) }
            }
        }) { it }
        return when (result) {
            is ApiResult.Success -> {
                val body = result.data
                val next = next(key, body.page, continuation, scope)
                when (next) {
                    is ApiResult.Success -> ApiResult.Success(CatalogPageV2(body.items, body.total, body.totalExact,
                        next.data, body.effectiveSort, body.searchDiagnostics, body.windowCursor))
                    is ApiResult.Error -> next
                    is ApiResult.NetworkError -> next
                }
            }
            is ApiResult.Error -> result.restartMessage()
            is ApiResult.NetworkError -> result
        }
    }

    suspend fun audiobookGroups(
        libraryId: String, groupBy: String, sort: String = "name", q: String? = null,
        limit: Int = 50, skipTotal: Boolean = false, continuation: CatalogContinuationV2? = null,
    ): ApiResult<CatalogPageV2<AudiobookGroup>> {
        if (limit !in 1..100) return invalid("Page size must be between 1 and 100.")
        val params = linkedMapOf("library_id" to libraryId, "group_by" to groupBy, "sort" to sort,
            "q" to q, "limit" to limit.toString(), "skip_total" to skipTotal.toString())
        val key = "GET/catalog/audiobook-groups:${SiloJson.encodeToString(params)}"
        val scope = continuation?.scope ?: tokenManager?.snapshotCurrentScope()
        if (continuation != null && continuation.requestKey != key) return invalid("The query changed. Reload from the first page.")
        val result = ownedV2Call<AudiobookGroupsWireV2, AudiobookGroupsWireV2>(gate, tokenManager, scope, OwnerPolicy.IDENTITY, null, { _ ->
            client.get("/api/v2/catalog/audiobook-groups") {
                scope?.let { authScope(it) }
                params.forEach { (name, value) -> parameter(name, value) }
                continuation?.let { parameter("cursor", it.cursor) }
            }
        }) { it }
        return when (result) {
            is ApiResult.Success -> next(key, result.data.page, continuation, scope).map {
                CatalogPageV2(result.data.items, result.data.total, result.data.totalExact, it)
            }
            is ApiResult.Error -> result.restartMessage()
            is ApiResult.NetworkError -> result
        }
    }

    suspend fun filters(libraryId: String? = null, source: String? = null, collectionId: String? = null,
        skipTechnical: Boolean = false): ApiResult<CatalogFiltersV2> = read<CatalogFiltersV2, CatalogFiltersV2>({ scope ->
        client.get("/api/v2/catalog/filters") {
            scope?.let { authScope(it) }
            parameter("library_id", libraryId)
            parameter("source", source)
            parameter("collection_id", collectionId)
            parameter("skip_technical", skipTechnical)
        }
    }) { it }

    suspend fun searchFacet(scope: CatalogFacetScopeV2, facet: String, prefix: String): ApiResult<CatalogFacetMatchesV2> = read<CatalogFacetMatchesV2, CatalogFacetMatchesV2>({ viewer ->
        client.get("/api/v2/catalog/filters/search") {
            viewer?.let { authScope(it) }
            parameter("library_id", scope.libraryId)
            parameter("source", scope.source)
            parameter("collection_id", scope.collectionId)
            parameter("facet", facet)
            parameter("q", prefix)
            parameter("limit", 100)
        }
    }) { it }

    suspend fun searchCapabilities(): ApiResult<CatalogSearchCapabilitiesV2> = read<CatalogSearchCapabilitiesV2, CatalogSearchCapabilitiesV2>({ scope ->
        client.get("/api/v2/catalog/search/capabilities") { scope?.let { authScope(it) } }
    }) { it }

    suspend fun libraryCollections(libraryId: String): ApiResult<LibraryCollectionTabV2> = read<LibraryCollectionTabV2, LibraryCollectionTabV2>({ scope ->
        client.get("/api/v2/library/$libraryId/collections") { scope?.let { authScope(it) } }
    }) { it }

    suspend fun itemDetail(id: String): ApiResult<ItemDetail> =
        read<ItemDetailReadV2, ItemDetail>({ scope ->
            client.get("/api/v2/catalog/items/${id.encodeURLPathPart()}") { scope?.let { authScope(it) } }
        }) { it.toDomain() }

    suspend fun seriesSeasons(id: String): ApiResult<SeasonsResponse> =
        read<DetailCollectionReadV2<Season>, SeasonsResponse>({ scope ->
            client.get("/api/v2/catalog/series/${id.encodeURLPathPart()}/seasons") { scope?.let { authScope(it) } }
        }) { it.requireComplete(); SeasonsResponse(it.items) }

    suspend fun seasonEpisodes(id: String, number: Int): ApiResult<EpisodesResponse> =
        read<DetailCollectionReadV2<EpisodeListItemReadV2>, EpisodesResponse>({ scope ->
            client.get("/api/v2/catalog/series/${id.encodeURLPathPart()}/seasons/$number/episodes") { scope?.let { authScope(it) } }
        }) { it.requireComplete(); EpisodesResponse(it.items.map { row -> row.toDomain() }) }

    suspend fun itemVersions(id: String): ApiResult<List<FileVersion>> =
        read<DetailCollectionReadV2<FileVersionReadV2>, List<FileVersion>>({ scope ->
            client.get("/api/v2/catalog/items/${id.encodeURLPathPart()}/versions") { scope?.let { authScope(it) } }
        }) { it.requireComplete(); it.items.map { row -> row.toDomain() } }

    suspend fun itemEpisodes(id: String): ApiResult<EpisodesResponse> =
        read<DetailCollectionReadV2<EpisodeListItemReadV2>, EpisodesResponse>({ scope ->
            client.get("/api/v2/catalog/items/${id.encodeURLPathPart()}/episodes") { scope?.let { authScope(it) } }
        }) { it.requireComplete(); EpisodesResponse(it.items.map { row -> row.toDomain() }) }

    suspend fun person(id: Long): ApiResult<Person> =
        read<PersonReadV2, Person>({ scope ->
            client.get("/api/v2/catalog/people/$id") { scope?.let { authScope(it) } }
        }) { it.toDomain() }

    suspend fun people(query: String?, limit: Int = 20): ApiResult<List<Person>> {
        if (limit !in 1..100) return ApiResult.Error(0, "validation_failed", "People search limit must be between 1 and 100.")
        return read<DetailCollectionReadV2<PersonReadV2>, List<Person>>({ scope ->
            client.get("/api/v2/catalog/people") {
                scope?.let { authScope(it) }
                parameter("q", query)
                parameter("limit", limit)
            }
        }) { it.requireComplete(); it.items.map { person -> person.toDomain() } }
    }

    // These operations currently accept no cursor. Refuse an unexpected partial
    // envelope so callers cannot mistake an incomplete hierarchy for the full one.
    private fun DetailCollectionReadV2<*>.requireComplete() {
        require(page?.hasMore != true && page?.nextCursor.isNullOrBlank()) { "The server returned an unsupported continuation." }
    }

    private suspend inline fun <reified T, R> read(
        crossinline block: suspend (AuthScopeSnapshot?) -> HttpResponse, crossinline convert: (T) -> R,
    ): ApiResult<R> = ownedV2Call<T, R>(gate, tokenManager, tokenManager?.snapshotCurrentScope(), OwnerPolicy.IDENTITY, null, block, convert)

    private fun next(key: String, page: PageInfo, previous: CatalogContinuationV2?, scope: AuthScopeSnapshot?): ApiResult<CatalogContinuationV2?> {
        if (!page.hasMore) return ApiResult.Success(null)
        val cursor = page.nextCursor
        if (cursor.isNullOrBlank() || cursor in previous?.seen.orEmpty()) return invalid("The server returned invalid pagination. Reload from the first page.")
        return ApiResult.Success(CatalogContinuationV2(key, cursor, scope, previous?.seen.orEmpty() + cursor))
    }

    private fun invalid(message: String) = ApiResult.Error(0, "invalid_cursor", message)
    private fun ApiResult.Error.restartMessage() = if (error == "invalid_cursor")
        copy(message = "This result expired or changed. Reload from the first page.") else this
}
