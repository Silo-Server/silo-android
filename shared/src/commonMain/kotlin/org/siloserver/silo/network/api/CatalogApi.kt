package org.siloserver.silo.network.api

import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.http.*
import org.siloserver.silo.model.catalog.*
import org.siloserver.silo.network.ApiResult

class CatalogApi(private val client: HttpClient) {

    suspend fun getCatalog(
        source: String? = null,
        query: String? = null,
        mediaType: String? = null,
        libraryId: Int? = null,
        genre: String? = null,
        contentRating: String? = null,
        sort: String? = null,
        order: String? = null,
        offset: Int? = null,
        limit: Int? = null,
        namePrefix: String? = null,
        yearMin: Int? = null,
        yearMax: Int? = null,
        snapshotAt: String? = null,
        queryGroups: List<CatalogQueryGroup> = emptyList(),
        match: String? = null,
    ): ApiResult<CatalogResponse> = safeApiCall {
        client.get("/api/v1/catalog") {
            source?.let { parameter("source", it) }
            query?.let { parameter("q", it) }
            mediaType?.let { parameter("type", it) }
            libraryId?.let { parameter("library_id", it) }
            genre?.let { parameter("genre", it) }
            contentRating?.let { parameter("content_rating", it) }
            sort?.let { parameter("sort", it) }
            order?.let { parameter("order", it) }
            offset?.let { parameter("offset", it) }
            limit?.let { parameter("limit", it) }
            namePrefix?.let { parameter("name_prefix", it) }
            yearMin?.let { parameter("year_min", it) }
            yearMax?.let { parameter("year_max", it) }
            snapshotAt?.let { parameter("snapshot", it) }
            match?.let { parameter("match", it) }
            catalogQueryGroupParameters(queryGroups)
        }
    }

    suspend fun getAudiobookGroups(
        libraryId: Int,
        groupBy: String,
        sort: String = "name",
        offset: Int? = null,
        limit: Int? = null,
        query: String? = null,
        includeTotal: Boolean? = null,
    ): ApiResult<AudiobookGroupsResponse> = safeApiCall {
        client.get("/api/v1/catalog/audiobook-groups") {
            parameter("library_id", libraryId)
            parameter("group_by", groupBy)
            parameter("sort", sort)
            offset?.let { parameter("offset", it) }
            limit?.let { parameter("limit", it) }
            query?.let { parameter("q", it) }
            includeTotal?.let { parameter("include_total", it) }
        }
    }

    /**
     * Facet vocabularies. [source]/[collectionId] scope the options to one
     * catalog source (e.g. `source=library_collection`) so a collection's
     * filter panel only offers values its own members actually have.
     */
    suspend fun getFilters(
        libraryId: Int? = null,
        includeTechnical: Boolean = false,
        source: String? = null,
        collectionId: String? = null,
    ): ApiResult<CatalogFiltersResponse> = safeApiCall {
        client.get("/api/v1/catalog/filters") {
            libraryId?.let { parameter("library_id", it) }
            if (includeTechnical) parameter("include_technical", "true")
            source?.let { parameter("source", it) }
            collectionId?.let { parameter("collection_id", it) }
        }
    }

    suspend fun getItemDetail(id: String): ApiResult<ItemDetail> = safeApiCall {
        client.get("/api/v1/catalog/items/$id")
    }

    suspend fun getItemVersions(id: String): ApiResult<List<FileVersion>> = safeApiCall {
        client.get("/api/v1/catalog/items/$id/versions")
    }

    suspend fun getItemEpisodes(id: String): ApiResult<EpisodesResponse> = safeApiCall {
        client.get("/api/v1/catalog/items/$id/episodes")
    }

    suspend fun getSeasons(seriesId: String): ApiResult<SeasonsResponse> = safeApiCall {
        client.get("/api/v1/catalog/series/$seriesId/seasons")
    }

    suspend fun getEpisodes(
        seriesId: String,
        seasonNumber: Int
    ): ApiResult<EpisodesResponse> = safeApiCall {
        client.get("/api/v1/catalog/series/$seriesId/seasons/$seasonNumber/episodes")
    }

    suspend fun getWatchDetail(id: String): ApiResult<WatchDetail> = safeApiCall {
        client.get("/api/v1/watch/$id")
    }

    suspend fun searchPeople(query: String? = null): ApiResult<List<Person>> = safeApiCall {
        client.get("/api/v1/people") {
            query?.let { parameter("q", it) }
        }
    }

    suspend fun getPerson(id: Long): ApiResult<Person> = safeApiCall {
        client.get("/api/v1/people/$id")
    }

    /** Queues a server-side metadata refresh for a person (fire-and-forget). */
    suspend fun refreshPerson(id: Long): ApiResult<Unit> = safeApiCall {
        client.post("/api/v1/people/$id/refresh")
    }

    /**
     * Filmography for a person — wraps `/api/v1/catalog?source=person&person_id=...`.
     * Mirrors the iOS `personCatalogItems` helper.
     */
    suspend fun getPersonItems(
        personId: Long,
        mediaType: String? = null,
        offset: Int? = null,
        limit: Int? = null,
        snapshotAt: String? = null,
    ): ApiResult<CatalogResponse> = safeApiCall {
        client.get("/api/v1/catalog") {
            parameter("source", "person")
            parameter("person_id", personId.toString())
            parameter("sort", "year")
            parameter("order", "desc")
            mediaType?.let { parameter("type", it) }
            offset?.let { parameter("offset", it) }
            limit?.let { parameter("limit", it) }
            snapshotAt?.let { parameter("snapshot", it) }
        }
    }
}

/**
 * Encodes structured catalog filter groups as the server's bracketed query
 * params (`groups[g][rules][r][field]`, …). Range ops carry indexed values.
 * Shared by every `/api/v1/catalog` caller so the encoding has one definition.
 */
internal fun HttpRequestBuilder.catalogQueryGroupParameters(groups: List<CatalogQueryGroup>) {
    groups.forEachIndexed { groupIndex, group ->
        parameter("groups[$groupIndex][match]", group.match)
        group.rules.forEachIndexed { ruleIndex, rule ->
            parameter("groups[$groupIndex][rules][$ruleIndex][field]", rule.field)
            parameter("groups[$groupIndex][rules][$ruleIndex][op]", rule.op)
            if (rule.values.isNotEmpty()) {
                rule.values.forEachIndexed { valueIndex, value ->
                    parameter("groups[$groupIndex][rules][$ruleIndex][value][$valueIndex]", value)
                }
            } else {
                parameter("groups[$groupIndex][rules][$ruleIndex][value]", rule.value)
            }
        }
    }
}
