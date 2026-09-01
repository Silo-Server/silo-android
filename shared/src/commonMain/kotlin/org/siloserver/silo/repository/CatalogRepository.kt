package org.siloserver.silo.repository

import org.siloserver.silo.model.catalog.CatalogFiltersResponse
import org.siloserver.silo.model.catalog.CatalogQueryGroup
import org.siloserver.silo.model.catalog.CatalogResponse
import org.siloserver.silo.model.catalog.AudiobookGroupsResponse
import org.siloserver.silo.model.catalog.EpisodesResponse
import org.siloserver.silo.model.catalog.FileVersion
import org.siloserver.silo.model.catalog.ItemDetail
import org.siloserver.silo.model.catalog.Person
import org.siloserver.silo.model.catalog.SeasonsResponse
import org.siloserver.silo.model.catalog.WatchDetail
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.network.DefaultIdentityTransitionBarrier
import org.siloserver.silo.network.IdentityTransitionBarrier
import org.siloserver.silo.network.api.CatalogApi
import org.siloserver.silo.repository.port.CatalogCachePort
import org.siloserver.silo.repository.port.CatalogCacheWriteLease
import org.siloserver.silo.repository.port.NoOpCatalogCachePort
import org.siloserver.silo.repository.port.canServeCache
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class CatalogRepository(
    private val catalogApi: CatalogApi,
    /** Offline read cache for a library's default first page (Track B). No-op by default. */
    private val catalogCache: CatalogCachePort = NoOpCatalogCachePort,
    private val identityTransitions: IdentityTransitionBarrier = DefaultIdentityTransitionBarrier(),
    requestDispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    /**
     * Home may warm a detail immediately before its destination requests the
     * same data. Process-owned, identity-keyed single-flight requests keep that
     * navigation from duplicating calls, and keep useful work alive when Home
     * leaves composition.
     */
    private val detailRequestScope = CoroutineScope(SupervisorJob() + requestDispatcher)
    private val detailRequestMutex = Mutex()
    private val itemDetailInFlight =
        mutableMapOf<Pair<Long, String>, Deferred<ApiResult<ItemDetail>>>()
    private val seasonsInFlight =
        mutableMapOf<Pair<Long, String>, Deferred<ApiResult<SeasonsResponse>>>()
    private data class EpisodesRequestKey(
        val identityGeneration: Long,
        val seriesId: String,
        val seasonNumber: Int,
    )
    private val episodesInFlight =
        mutableMapOf<EpisodesRequestKey, Deferred<ApiResult<EpisodesResponse>>>()

    /** Browse the catalog with optional filters, sorting, and pagination. */
    suspend fun browse(
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
    ): ApiResult<CatalogResponse> {
        val requestIdentityGeneration = identityTransitions.generation.value
        val result = catalogApi.getCatalog(
            source = source,
            query = query,
            mediaType = mediaType,
            libraryId = libraryId,
            genre = genre,
            contentRating = contentRating,
            sort = sort,
            order = order,
            offset = offset,
            limit = limit,
            namePrefix = namePrefix,
            yearMin = yearMin,
            yearMax = yearMax,
            snapshotAt = snapshotAt,
            queryGroups = queryGroups,
            match = match,
        )

        // Only the unfiltered, first-page default browse of a single library is
        // cached for offline (every request-shaping param must be at its default).
        val cacheableLibraryId = libraryId?.takeIf {
            (offset == null || offset == 0) &&
                query == null && genre == null && contentRating == null &&
                namePrefix == null && yearMin == null && yearMax == null &&
                source == null && mediaType == null && snapshotAt == null &&
                queryGroups.isEmpty() && match == null &&
                (sort == null || sort == "added_at") && (order == null || order == "desc")
        } ?: return result

        if (result is ApiResult.Success) {
            writeIfIdentityUnchanged(requestIdentityGeneration) { cacheWriteLease ->
                catalogCache.cacheDefaultLibraryPage(cacheableLibraryId, result.data, cacheWriteLease)
            }
            return result
        }
        if (result.canServeCache()) {
            catalogCache.getCachedDefaultLibraryPage(cacheableLibraryId)?.let { return ApiResult.Success(it) }
        }
        return result
    }

    /** Returns available filter options (genres, studios, etc.) for the catalog. */
    suspend fun getFilters(
        libraryId: Int? = null,
        includeTechnical: Boolean = false,
        source: String? = null,
        collectionId: String? = null,
    ): ApiResult<CatalogFiltersResponse> =
        catalogApi.getFilters(
            libraryId = libraryId,
            includeTechnical = includeTechnical,
            source = source,
            collectionId = collectionId,
        )

    /** Groups audiobook libraries by author, narrator, or series for book-native browsing. */
    suspend fun getAudiobookGroups(
        libraryId: Int,
        groupBy: String,
        sort: String = "name",
        offset: Int? = null,
        limit: Int? = null,
        query: String? = null,
        includeTotal: Boolean? = null,
    ): ApiResult<AudiobookGroupsResponse> =
        catalogApi.getAudiobookGroups(
            libraryId = libraryId,
            groupBy = groupBy,
            sort = sort,
            offset = offset,
            limit = limit,
            query = query,
            includeTotal = includeTotal,
        )

    /** Fetches full metadata for a single catalog item (offline: last cached detail). */
    suspend fun getItemDetail(contentId: String): ApiResult<ItemDetail> {
        val requestIdentityGeneration = identityTransitions.generation.value
        val warmRequest = detailRequestMutex.withLock {
            itemDetailInFlight[requestIdentityGeneration to contentId]
        }
        return warmRequest?.await() ?: fetchItemDetail(contentId, requestIdentityGeneration)
    }

    /**
     * Starts a process-owned live detail warm-up. A destination calling
     * [getItemDetail] while it is active joins this exact request.
     */
    suspend fun warmItemDetail(contentId: String): ApiResult<ItemDetail> {
        val requestIdentityGeneration = identityTransitions.generation.value
        return coalescedDetailRequest(
            requests = itemDetailInFlight,
            key = requestIdentityGeneration to contentId,
        ) {
            fetchItemDetail(contentId, requestIdentityGeneration)
        }
    }

    /** Returns the last cached item detail without touching the network. */
    suspend fun getCachedItemDetail(contentId: String): ItemDetail? =
        catalogCache.getCachedItemDetail(contentId)

    /**
     * Cache-first detail for speculative UI enrichment. Unlike a detail screen,
     * prefetch must not re-download metadata that is already durable locally.
     */
    suspend fun getItemDetailForPrefetch(contentId: String): ApiResult<ItemDetail> {
        catalogCache.getCachedItemDetail(contentId)?.let { return ApiResult.Success(it) }
        return getItemDetail(contentId)
    }

    /** Fetches playback-oriented detail (versions, user progress, intro/credits markers). */
    suspend fun getWatchDetail(contentId: String): ApiResult<WatchDetail> =
        catalogApi.getWatchDetail(contentId)

    /** Lists seasons for a series (offline: last cached seasons). */
    suspend fun getSeasons(seriesId: String): ApiResult<SeasonsResponse> {
        val requestIdentityGeneration = identityTransitions.generation.value
        val warmRequest = detailRequestMutex.withLock {
            seasonsInFlight[requestIdentityGeneration to seriesId]
        }
        return warmRequest?.await() ?: fetchSeasons(seriesId, requestIdentityGeneration)
    }

    /** Starts a process-owned live season-list warm-up for a pending detail route. */
    suspend fun warmSeasons(seriesId: String): ApiResult<SeasonsResponse> {
        val requestIdentityGeneration = identityTransitions.generation.value
        return coalescedDetailRequest(
            requests = seasonsInFlight,
            key = requestIdentityGeneration to seriesId,
        ) {
            fetchSeasons(seriesId, requestIdentityGeneration)
        }
    }

    /** Returns the last cached season list without touching the network. */
    suspend fun getCachedSeasons(seriesId: String): SeasonsResponse? =
        catalogCache.getCachedSeasons(seriesId)

    /** Cache-first season list for speculative detail navigation. */
    suspend fun getSeasonsForPrefetch(seriesId: String): ApiResult<SeasonsResponse> {
        catalogCache.getCachedSeasons(seriesId)?.let { return ApiResult.Success(it) }
        return getSeasons(seriesId)
    }

    /** Lists episodes for a specific season of a series (offline: last cached episodes). */
    suspend fun getEpisodes(seriesId: String, seasonNumber: Int): ApiResult<EpisodesResponse> {
        val requestIdentityGeneration = identityTransitions.generation.value
        val requestKey = EpisodesRequestKey(requestIdentityGeneration, seriesId, seasonNumber)
        val warmRequest = detailRequestMutex.withLock { episodesInFlight[requestKey] }
        return warmRequest?.await()
            ?: fetchEpisodes(seriesId, seasonNumber, requestIdentityGeneration)
    }

    /** Starts a process-owned live episode-list warm-up for a pending detail route. */
    suspend fun warmEpisodes(
        seriesId: String,
        seasonNumber: Int,
    ): ApiResult<EpisodesResponse> {
        val requestIdentityGeneration = identityTransitions.generation.value
        return coalescedDetailRequest(
            requests = episodesInFlight,
            key = EpisodesRequestKey(requestIdentityGeneration, seriesId, seasonNumber),
        ) {
            fetchEpisodes(seriesId, seasonNumber, requestIdentityGeneration)
        }
    }

    /** Returns one cached season's episodes without touching the network. */
    suspend fun getCachedEpisodes(seriesId: String, seasonNumber: Int): EpisodesResponse? =
        catalogCache.getCachedEpisodes(seriesId, seasonNumber)

    /** Cache-first episode list for speculative detail navigation. */
    suspend fun getEpisodesForPrefetch(
        seriesId: String,
        seasonNumber: Int,
    ): ApiResult<EpisodesResponse> {
        catalogCache.getCachedEpisodes(seriesId, seasonNumber)?.let {
            return ApiResult.Success(it)
        }
        return getEpisodes(seriesId, seasonNumber)
    }

    /** Lists all episodes directly attached to an item (e.g. a season content ID). */
    suspend fun getItemEpisodes(contentId: String): ApiResult<EpisodesResponse> =
        catalogApi.getItemEpisodes(contentId)

    /** Lists all available file versions for an item. */
    suspend fun getItemVersions(contentId: String): ApiResult<List<FileVersion>> =
        catalogApi.getItemVersions(contentId)

    /** Searches for people (cast/crew) by name. */
    suspend fun searchPeople(query: String): ApiResult<List<Person>> =
        catalogApi.searchPeople(query)

    /** Queues a server-side metadata refresh for a person. */
    suspend fun refreshPerson(id: Long): ApiResult<Unit> =
        catalogApi.refreshPerson(id)

    /** Fetches details for a specific person. */
    suspend fun getPerson(id: Long): ApiResult<Person> =
        catalogApi.getPerson(id)

    /** Filmography for a person — movies and series they appear in. */
    suspend fun getPersonItems(
        personId: Long,
        mediaType: String? = null,
        offset: Int? = null,
        limit: Int? = null,
        snapshotAt: String? = null,
    ): ApiResult<CatalogResponse> =
        catalogApi.getPersonItems(
            personId = personId,
            mediaType = mediaType,
            offset = offset,
            limit = limit,
            snapshotAt = snapshotAt,
        )

    private suspend fun fetchItemDetail(
        contentId: String,
        requestIdentityGeneration: Long,
    ): ApiResult<ItemDetail> {
        val result = catalogApi.getItemDetail(contentId)
        if (result is ApiResult.Success) {
            writeIfIdentityUnchanged(requestIdentityGeneration) { cacheWriteLease ->
                catalogCache.cacheItemDetail(contentId, result.data, cacheWriteLease)
            }
            return result
        }
        if (result.canServeCache()) {
            catalogCache.getCachedItemDetail(contentId)?.let { return ApiResult.Success(it) }
        }
        return result
    }

    private suspend fun fetchSeasons(
        seriesId: String,
        requestIdentityGeneration: Long,
    ): ApiResult<SeasonsResponse> {
        val result = catalogApi.getSeasons(seriesId)
        if (result is ApiResult.Success) {
            writeIfIdentityUnchanged(requestIdentityGeneration) { cacheWriteLease ->
                catalogCache.cacheSeasons(seriesId, result.data, cacheWriteLease)
            }
            return result
        }
        if (result.canServeCache()) {
            catalogCache.getCachedSeasons(seriesId)?.let { return ApiResult.Success(it) }
        }
        return result
    }

    private suspend fun fetchEpisodes(
        seriesId: String,
        seasonNumber: Int,
        requestIdentityGeneration: Long,
    ): ApiResult<EpisodesResponse> {
        val result = catalogApi.getEpisodes(seriesId, seasonNumber)
        if (result is ApiResult.Success) {
            writeIfIdentityUnchanged(requestIdentityGeneration) { cacheWriteLease ->
                catalogCache.cacheEpisodes(
                    seriesId,
                    seasonNumber,
                    result.data,
                    cacheWriteLease,
                )
            }
            return result
        }
        if (result.canServeCache()) {
            catalogCache.getCachedEpisodes(seriesId, seasonNumber)?.let {
                return ApiResult.Success(it)
            }
        }
        return result
    }

    private suspend fun writeIfIdentityUnchanged(
        requestGeneration: Long,
        write: suspend (CatalogCacheWriteLease) -> Unit,
    ) {
        if (requestGeneration == identityTransitions.generation.value) {
            write(CatalogCacheWriteLease(requestGeneration))
        }
    }

    private suspend fun <K, T> coalescedDetailRequest(
        requests: MutableMap<K, Deferred<T>>,
        key: K,
        request: suspend () -> T,
    ): T {
        val deferred = detailRequestMutex.withLock {
            requests[key] ?: run {
                lateinit var created: Deferred<T>
                created = detailRequestScope.async(start = CoroutineStart.LAZY) {
                    try {
                        request()
                    } finally {
                        detailRequestMutex.withLock {
                            if (requests[key] === created) requests.remove(key)
                        }
                    }
                }
                requests[key] = created
                created.start()
                created
            }
        }
        return deferred.await()
    }
}
