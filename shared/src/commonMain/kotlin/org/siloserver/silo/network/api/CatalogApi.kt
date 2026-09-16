package org.siloserver.silo.network.api

import io.ktor.client.*
import org.siloserver.silo.model.catalog.*
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.network.AuthScopeSnapshot
import org.siloserver.silo.network.TokenManagerImpl
import org.siloserver.silo.network.map
import org.siloserver.silo.network.apiv2.*
import kotlinx.serialization.json.*

/** Catalog reads on v2; the scoped delegates default to an unauthenticated token manager outside DI. */
class CatalogApi(client: HttpClient, private val v2: CatalogV2Api = CatalogV2Api(client, ApiV2Gate.Unrestricted),
    private val personRefresh: PersonRefreshV2Api = PersonRefreshV2Api(client, TokenManagerImpl(), ApiV2Gate.Unrestricted),
    private val watchDetail: WatchDetailV2Api = WatchDetailV2Api(client, TokenManagerImpl(), ApiV2Gate.Unrestricted)) {

    suspend fun getCatalog(
        source: String? = null, query: String? = null, mediaType: String? = null,
        libraryId: Int? = null, genre: String? = null, contentRating: String? = null,
        sort: String? = null, order: String? = null,
        continuation: CatalogContinuationV2? = null, limit: Int? = null,
        namePrefix: String? = null, yearMin: Int? = null, yearMax: Int? = null,
        queryGroups: List<CatalogQueryGroup> = emptyList(), match: String? = null,
    ): ApiResult<CatalogResponse> {
        val groups = queryGroups.toV2Groups().toMutableList()
        val implicit = buildList {
            genre?.takeIf { it.isNotBlank() }?.let { add(CatalogRuleV2("genre", "contains", JsonPrimitive(it))) }
            when {
                yearMin != null && yearMin > 0 && yearMax != null && yearMax > 0 ->
                    add(CatalogRuleV2("year", "between", JsonArray(listOf(JsonPrimitive(yearMin), JsonPrimitive(yearMax)))))
                yearMin != null && yearMin > 0 -> add(CatalogRuleV2("year", "gte", JsonPrimitive(yearMin)))
                yearMax != null && yearMax > 0 -> add(CatalogRuleV2("year", "lte", JsonPrimitive(yearMax)))
            }
        }
        if (implicit.isNotEmpty()) groups.add(CatalogRuleGroupV2("all", implicit))
        contentRating?.takeIf { it.isNotBlank() }?.let {
            groups.add(CatalogRuleGroupV2("all", listOf(CatalogRuleV2("content_rating", "is", JsonPrimitive(it)))))
        }
        return v2.browse(CatalogQueryV2(source = source ?: "query", q = query, type = mediaType,
            libraryId = libraryId?.toString(), sort = sort, order = order, limit = limit ?: 50,
            namePrefix = namePrefix, groups = groups, match = match), continuation).map { it.toCatalogResponse() }
    }

    suspend fun getAudiobookGroups(libraryId: Int, groupBy: String, sort: String = "name",
        continuation: CatalogContinuationV2? = null, limit: Int? = null, query: String? = null,
        includeTotal: Boolean? = null): ApiResult<AudiobookGroupsResponse> =
        v2.audiobookGroups(libraryId.toString(), groupBy, sort, query, limit ?: 50,
            includeTotal == false, continuation).map {
            AudiobookGroupsResponse(it.total, it.totalExact, it.continuation != null, it.items, it.continuation)
        }

    suspend fun getFilters(libraryId: Int? = null, includeTechnical: Boolean = false,
        source: String? = null, collectionId: String? = null): ApiResult<CatalogFiltersResponse> =
        v2.filters(libraryId?.toString(), source, collectionId, skipTechnical = !includeTechnical).map {
            CatalogFiltersResponse(it.genres, it.studios, it.networks, it.countries, it.contentRatings,
                it.technical?.resolutions, it.technical?.audioLanguages, it.technical?.subtitleLanguages,
                it.originalLanguages, it.authors, it.narrators, it.series, CatalogFacetScopeV2(libraryId?.toString(), source, collectionId))
        }

    suspend fun searchFacet(scope: CatalogFacetScopeV2, facet: String, prefix: String) = v2.searchFacet(scope, facet, prefix)

    suspend fun searchCapabilities() = v2.searchCapabilities()

    suspend fun getItemDetail(id: String, libraryId: Int? = null): ApiResult<ItemDetail> = v2.itemDetail(id, libraryId)

    suspend fun getItemVersions(id: String, libraryId: Int? = null): ApiResult<List<FileVersion>> = v2.itemVersions(id, libraryId)

    suspend fun getItemEpisodes(id: String, libraryId: Int? = null): ApiResult<EpisodesResponse> = v2.itemEpisodes(id, libraryId)

    suspend fun getSeasons(seriesId: String, libraryId: Int? = null): ApiResult<SeasonsResponse> = v2.seriesSeasons(seriesId, libraryId)

    suspend fun getEpisodes(
        seriesId: String,
        seasonNumber: Int,
        libraryId: Int? = null,
    ): ApiResult<EpisodesResponse> = v2.seasonEpisodes(seriesId, seasonNumber, libraryId)

    suspend fun captureWatchAuthority() = watchDetail.capture()
    suspend fun isWatchAuthorityCurrent(owner: AuthScopeSnapshot) = watchDetail.current(owner)
    suspend fun getWatchDetail(id: String, owner: AuthScopeSnapshot, libraryId: Int? = null) = watchDetail.detail(id, owner, libraryId)

    /** Captures the current viewer at call time when the caller has no retained owner. */
    suspend fun getWatchDetail(id: String, libraryId: Int? = null): ApiResult<WatchDetail> {
        val owner = watchDetail.capture()
            ?: return identityChanged()
        return watchDetail.detail(id, owner, libraryId)
    }

    suspend fun searchPeople(query: String? = null): ApiResult<List<Person>> = v2.people(query)

    suspend fun getPerson(id: Long): ApiResult<Person> = v2.person(id)

    suspend fun getPerson(id: Long, owner: AuthScopeSnapshot): ApiResult<Person> = personRefresh.detail(id, owner)

    suspend fun refreshPerson(id: Long, owner: AuthScopeSnapshot): ApiResult<Unit> = personRefresh.refresh(id, owner)

    suspend fun getPersonItems(personId: Long, mediaType: String? = null,
        continuation: CatalogContinuationV2? = null, limit: Int? = null): ApiResult<CatalogResponse> =
        v2.browse(CatalogQueryV2(source = "person", personId = personId.toString(), type = mediaType,
            sort = "year", order = "desc", limit = limit ?: 50), continuation).map { it.toCatalogResponse() }
}

internal fun List<CatalogQueryGroup>.toV2Groups() = map { group ->
    CatalogRuleGroupV2(group.match, group.rules.map { rule ->
        CatalogRuleV2(rule.field, rule.op, if (rule.values.isNotEmpty()) JsonArray(rule.values.map(::JsonPrimitive)) else JsonPrimitive(rule.value))
    })
}

internal fun CatalogPageV2<BrowseItem>.toCatalogResponse() = CatalogResponse(
    total = total, totalExact = totalExact, hasMore = continuation != null, items = items,
    effectiveSort = effectiveSort, continuation = continuation, searchDiagnostics = searchDiagnostics,
)
