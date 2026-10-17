package org.siloserver.silo.repository

import org.siloserver.silo.domain.settings.CatalogLanguageRevision
import org.siloserver.silo.repository.port.CatalogCacheScope
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
import org.siloserver.silo.network.apiv2.CatalogContinuationV2
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
    private val catalogLanguage: CatalogLanguageRevision? = null,
    private val artwork: ArtworkSizing = ArtworkSizing.None,
) {
    /**
     * Home may warm a detail immediately before its destination requests the
     * same data. Process-owned, identity-keyed single-flight requests keep that
     * navigation from duplicating calls, and keep useful work alive when Home
     * leaves composition.
     */
    private val detailRequestScope = CoroutineScope(SupervisorJob() + requestDispatcher)
    private val detailRequestMutex = Mutex()
    private data class DetailRequestKey(
        val identityGeneration: Long,
        val languageRevision: Long,
        val contentId: String,
    )
    private val itemDetailInFlight =
        mutableMapOf<DetailRequestKey, Deferred<ApiResult<ItemDetail>>>()
    private val seasonsInFlight =
        mutableMapOf<DetailRequestKey, Deferred<ApiResult<SeasonsResponse>>>()
    private data class EpisodesRequestKey(
        val identityGeneration: Long,
        val languageRevision: Long,
        val seriesId: String,
        val seasonNumber: Int,
        val surface: ArtworkSurface,
    )
    private val episodesInFlight =
        mutableMapOf<EpisodesRequestKey, Deferred<ApiResult<EpisodesResponse>>>()

    /**
     * Whether a library holds any item at all, whatever the viewer's filters:
     * one unfiltered item, no total. A screen asks only once its own view came
     * back empty, to tell an empty library from filters that match nothing
     * (#451). Read straight from the API so a one-item page never replaces the
     * cached default page [browse] keeps for offline. Null when the server
     * could not answer.
     */
    suspend fun libraryHasItems(libraryId: Int, mediaType: String? = null): Boolean? =
        when (val result = catalogApi.getCatalog(libraryId = libraryId, mediaType = mediaType, limit = 1, skipTotal = true)) {
            is ApiResult.Success -> result.data.items.isNotEmpty()
            else -> null
        }

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
        continuation: CatalogContinuationV2? = null,
        limit: Int? = null,
        namePrefix: String? = null,
        yearMin: Int? = null,
        yearMax: Int? = null,
        queryGroups: List<CatalogQueryGroup> = emptyList(),
        match: String? = null,
    ): ApiResult<CatalogResponse> {
        val requestIdentityGeneration = identityTransitions.generation.value
        val artworkFingerprint = artwork.cacheFingerprint(ArtworkSurface.BROWSE)
        val result = catalogApi.getCatalog(
            widths = if (continuation == null) artwork.request(ArtworkSurface.BROWSE) else null,
            source = source,
            query = query,
            mediaType = mediaType,
            libraryId = libraryId,
            genre = genre,
            contentRating = contentRating,
            sort = sort,
            order = order,
            continuation = continuation,
            limit = limit,
            namePrefix = namePrefix,
            yearMin = yearMin,
            yearMax = yearMax,
            queryGroups = queryGroups,
            match = match,
        )

        // Only the unfiltered, first-page default browse of a single library is
        // cached for offline (every request-shaping param must be at its default).
        val cacheableLibraryId = libraryId?.takeIf {
            continuation == null &&
                query == null && genre == null && contentRating == null &&
                namePrefix == null && yearMin == null && yearMax == null &&
                source == null && mediaType == null &&
                queryGroups.isEmpty() && match == null &&
                (sort == null || sort == "added_at") && (order == null || order == "desc")
        } ?: return result

        if (result is ApiResult.Success) {
            writeIfIdentityUnchanged(requestIdentityGeneration, artworkFingerprint) { cacheWriteLease ->
                catalogCache.cacheDefaultLibraryPage(cacheableLibraryId, result.data, cacheWriteLease)
            }
            return result
        }
        if (result.canServeCache()) {
            catalogCache.getCachedDefaultLibraryPage(cacheableLibraryId)?.let { return ApiResult.Success(it.copy(hasMore = false, continuation = null)) }
        }
        return result
    }

    suspend fun searchFacet(scope: org.siloserver.silo.network.apiv2.CatalogFacetScopeV2, facet: String, prefix: String) =
        catalogApi.searchFacet(scope, facet, prefix)

    suspend fun searchCapabilities() = catalogApi.searchCapabilities()

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
        continuation: CatalogContinuationV2? = null,
        limit: Int? = null,
        query: String? = null,
        includeTotal: Boolean? = null,
    ): ApiResult<AudiobookGroupsResponse> =
        catalogApi.getAudiobookGroups(
            libraryId = libraryId,
            groupBy = groupBy,
            sort = sort,
            continuation = continuation,
            limit = limit,
            query = query,
            includeTotal = includeTotal,
        )

    // Library-scoped reads cannot use the content-only offline cache or Home warmups.
    // Keeping them live prevents versions from leaking between browse contexts.

    /** Fetches full metadata for a single catalog item (offline: last cached detail). */
    /**
     * Live item detail. By default it joins a Home warm-up already in flight
     * for the same title; [joinWarmup] = false sends a fresh request instead,
     * for a reload whose answer must postdate an access change.
     */
    suspend fun getItemDetail(
        contentId: String,
        libraryId: Int? = null,
        joinWarmup: Boolean = true,
    ): ApiResult<ItemDetail> {
        if (libraryId != null) return catalogApi.getItemDetail(contentId, libraryId, artwork.request(ArtworkSurface.DETAIL))
        val requestIdentityGeneration = identityTransitions.generation.value
        val requestLanguageRevision = currentLanguageRevision
        val warmRequest = if (!joinWarmup) null else detailRequestMutex.withLock {
            itemDetailInFlight[DetailRequestKey(requestIdentityGeneration, requestLanguageRevision, contentId)]
        }
        return warmRequest?.await() ?: fetchItemDetail(contentId, requestIdentityGeneration, requestLanguageRevision)
    }

    /**
     * Starts a process-owned live detail warm-up. A destination calling
     * [getItemDetail] while it is active joins this exact request.
     */
    suspend fun warmItemDetail(contentId: String): ApiResult<ItemDetail> {
        val requestIdentityGeneration = identityTransitions.generation.value
        val requestLanguageRevision = currentLanguageRevision
        return coalescedDetailRequest(
            requests = itemDetailInFlight,
            key = DetailRequestKey(requestIdentityGeneration, requestLanguageRevision, contentId),
        ) {
            fetchItemDetail(contentId, requestIdentityGeneration, requestLanguageRevision)
        }
    }

    /** Returns the last cached item detail without touching the network. */
    suspend fun getCachedItemDetail(contentId: String, libraryId: Int? = null): ItemDetail? =
        if (libraryId != null) null else readLocalizedCache { catalogCache.getCachedItemDetail(contentId) }

    /**
     * Cache-first detail for speculative UI enrichment. Unlike a detail screen,
     * prefetch must not re-download metadata that is already durable locally.
     * A warm-up still in flight is newer than that copy, so it joins one.
     */
    suspend fun getItemDetailForPrefetch(contentId: String, libraryId: Int? = null): ApiResult<ItemDetail> {
        if (libraryId != null) return catalogApi.getItemDetail(contentId, libraryId, artwork.request(ArtworkSurface.DETAIL))
        inFlight(itemDetailInFlight, DetailRequestKey(identityTransitions.generation.value, currentLanguageRevision, contentId))
            ?.let { return it.await() }
        getCachedItemDetail(contentId)?.let { return ApiResult.Success(it) }
        return getItemDetail(contentId)
    }

    /** Fetches playback-oriented detail (versions, user progress, intro/credits markers). */
    suspend fun captureWatchAuthority() = catalogApi.captureWatchAuthority()
    suspend fun isWatchAuthorityCurrent(owner: org.siloserver.silo.network.AuthScopeSnapshot) = catalogApi.isWatchAuthorityCurrent(owner)
    suspend fun getWatchDetail(contentId: String, owner: org.siloserver.silo.network.AuthScopeSnapshot, libraryId: Int? = null, fileId: Int? = null) = catalogApi.getWatchDetail(contentId, owner, libraryId, fileId)

    suspend fun getWatchDetail(contentId: String, libraryId: Int? = null): ApiResult<WatchDetail> =
        catalogApi.getWatchDetail(contentId, libraryId)

    /**
     * Lists seasons for a series (offline: last cached seasons).
     *
     * [fresh] reads the server only: it neither joins a request already in
     * flight nor falls back to the cache, both of which can still hold the
     * state from before a write the caller just made.
     */
    suspend fun getSeasons(seriesId: String, libraryId: Int? = null, fresh: Boolean = false): ApiResult<SeasonsResponse> {
        if (libraryId != null) return catalogApi.getSeasons(seriesId, libraryId)
        val requestIdentityGeneration = identityTransitions.generation.value
        val requestLanguageRevision = currentLanguageRevision
        if (fresh) return fetchSeasons(seriesId, requestIdentityGeneration, requestLanguageRevision, serveCacheOnFailure = false)
        val warmRequest = detailRequestMutex.withLock {
            seasonsInFlight[DetailRequestKey(requestIdentityGeneration, requestLanguageRevision, seriesId)]
        }
        return warmRequest?.await() ?: fetchSeasons(seriesId, requestIdentityGeneration, requestLanguageRevision)
    }

    /** Starts a process-owned live season-list warm-up for a pending detail route. */
    suspend fun warmSeasons(seriesId: String): ApiResult<SeasonsResponse> {
        val requestIdentityGeneration = identityTransitions.generation.value
        val requestLanguageRevision = currentLanguageRevision
        return coalescedDetailRequest(
            requests = seasonsInFlight,
            key = DetailRequestKey(requestIdentityGeneration, requestLanguageRevision, seriesId),
        ) {
            fetchSeasons(seriesId, requestIdentityGeneration, requestLanguageRevision)
        }
    }

    /** Returns the last cached season list without touching the network. */
    suspend fun getCachedSeasons(seriesId: String, libraryId: Int? = null): SeasonsResponse? =
        if (libraryId != null) null else readLocalizedCache { catalogCache.getCachedSeasons(seriesId) }

    /** Cache-first season list for speculative detail navigation; joins a warm-up still in flight. */
    suspend fun getSeasonsForPrefetch(seriesId: String, libraryId: Int? = null): ApiResult<SeasonsResponse> {
        if (libraryId != null) return catalogApi.getSeasons(seriesId, libraryId)
        inFlight(seasonsInFlight, DetailRequestKey(identityTransitions.generation.value, currentLanguageRevision, seriesId))
            ?.let { return it.await() }
        getCachedSeasons(seriesId)?.let { return ApiResult.Success(it) }
        return getSeasons(seriesId)
    }

    /**
     * Lists episodes for a specific season of a series (offline: last cached
     * episodes). [fresh] behaves as on [getSeasons]. [surface] is the page the
     * episodes are drawn on, which sizes their stills.
     */
    suspend fun getEpisodes(
        seriesId: String,
        seasonNumber: Int,
        libraryId: Int? = null,
        fresh: Boolean = false,
        surface: ArtworkSurface = ArtworkSurface.EPISODES,
    ): ApiResult<EpisodesResponse> {
        if (libraryId != null) return catalogApi.getEpisodes(seriesId, seasonNumber, libraryId, artwork.request(surface))
        val requestIdentityGeneration = identityTransitions.generation.value
        val requestLanguageRevision = currentLanguageRevision
        if (fresh) {
            return fetchEpisodes(seriesId, seasonNumber, surface, requestIdentityGeneration, requestLanguageRevision,
                serveCacheOnFailure = false)
        }
        val requestKey = EpisodesRequestKey(requestIdentityGeneration, requestLanguageRevision, seriesId, seasonNumber, surface)
        val warmRequest = detailRequestMutex.withLock { episodesInFlight[requestKey] }
        return warmRequest?.await()
            ?: fetchEpisodes(seriesId, seasonNumber, surface, requestIdentityGeneration, requestLanguageRevision)
    }

    /** Starts a process-owned live episode-list warm-up for a pending detail route. */
    suspend fun warmEpisodes(
        seriesId: String,
        seasonNumber: Int,
        surface: ArtworkSurface = ArtworkSurface.EPISODES,
    ): ApiResult<EpisodesResponse> {
        val requestIdentityGeneration = identityTransitions.generation.value
        val requestLanguageRevision = currentLanguageRevision
        return coalescedDetailRequest(
            requests = episodesInFlight,
            key = EpisodesRequestKey(requestIdentityGeneration, requestLanguageRevision, seriesId, seasonNumber, surface),
        ) {
            fetchEpisodes(seriesId, seasonNumber, surface, requestIdentityGeneration, requestLanguageRevision)
        }
    }

    /** Returns one cached season's episodes without touching the network. */
    suspend fun getCachedEpisodes(
        seriesId: String,
        seasonNumber: Int,
        libraryId: Int? = null,
        surface: ArtworkSurface = ArtworkSurface.EPISODES,
    ): EpisodesResponse? =
        if (libraryId != null) null else readLocalizedCache { catalogCache.getCachedEpisodes(seriesId, seasonNumber, surface) }

    /** Cache-first episode list for speculative detail navigation; joins a warm-up still in flight. */
    suspend fun getEpisodesForPrefetch(
        seriesId: String,
        seasonNumber: Int,
        libraryId: Int? = null,
        surface: ArtworkSurface = ArtworkSurface.EPISODES,
    ): ApiResult<EpisodesResponse> {
        if (libraryId != null) return catalogApi.getEpisodes(seriesId, seasonNumber, libraryId, artwork.request(surface))
        val key = EpisodesRequestKey(identityTransitions.generation.value, currentLanguageRevision, seriesId, seasonNumber, surface)
        inFlight(episodesInFlight, key)?.let { return it.await() }
        getCachedEpisodes(seriesId, seasonNumber, surface = surface)?.let {
            return ApiResult.Success(it)
        }
        return getEpisodes(seriesId, seasonNumber, surface = surface)
    }

    /** Lists all episodes directly attached to an item (e.g. a season content ID). */
    suspend fun getItemEpisodes(contentId: String, libraryId: Int? = null): ApiResult<EpisodesResponse> =
        catalogApi.getItemEpisodes(contentId, libraryId)

    /** Lists all available file versions for an item. */
    suspend fun getItemVersions(contentId: String, libraryId: Int? = null): ApiResult<List<FileVersion>> =
        catalogApi.getItemVersions(contentId, libraryId)

    /**
     * Searches for people (cast/crew) by name. [mediaScope] limits results to
     * people with accessible credits in that scope; only send it to a server
     * that advertises `people_media_scope`.
     */
    suspend fun searchPeople(query: String, mediaScope: String? = null): ApiResult<List<Person>> =
        catalogApi.searchPeople(query, mediaScope)

    /** Queues a server-side metadata refresh for a person. */
    suspend fun refreshPerson(id: Long, owner: org.siloserver.silo.network.AuthScopeSnapshot): ApiResult<Unit> =
        catalogApi.refreshPerson(id, owner)

    /** Fetches details for a specific person. */
    suspend fun getPerson(id: Long): ApiResult<Person> =
        catalogApi.getPerson(id)

    suspend fun getPerson(id: Long, owner: org.siloserver.silo.network.AuthScopeSnapshot): ApiResult<Person> =
        catalogApi.getPerson(id, owner)

    /** Filmography for a person — movies and series they appear in. */
    suspend fun getPersonItems(
        personId: Long,
        mediaType: String? = null,
        continuation: CatalogContinuationV2? = null,
        limit: Int? = null,
    ): ApiResult<CatalogResponse> =
        catalogApi.getPersonItems(
            personId = personId,
            mediaType = mediaType,
            continuation = continuation,
            limit = limit,
            widths = if (continuation == null) artwork.request(ArtworkSurface.BROWSE) else null,
        )

    private suspend fun fetchItemDetail(
        contentId: String,
        requestIdentityGeneration: Long,
        requestLanguageRevision: Long,
    ): ApiResult<ItemDetail> {
        val artworkFingerprint = artwork.cacheFingerprint(ArtworkSurface.DETAIL)
        val result = catalogApi.getItemDetail(contentId, widths = artwork.request(ArtworkSurface.DETAIL))
        if (result is ApiResult.Success) {
            writeLocalizedCache(requestIdentityGeneration, requestLanguageRevision, artworkFingerprint) { cacheWriteLease ->
                catalogCache.cacheItemDetail(contentId, result.data, cacheWriteLease)
            }
            return result
        }
        if (result.canServeCache()) {
            getCachedItemDetail(contentId)?.let { return ApiResult.Success(it) }
        }
        return result
    }

    private suspend fun fetchSeasons(
        seriesId: String,
        requestIdentityGeneration: Long,
        requestLanguageRevision: Long,
        serveCacheOnFailure: Boolean = true,
    ): ApiResult<SeasonsResponse> {
        val result = catalogApi.getSeasons(seriesId)
        if (result is ApiResult.Success) {
            writeLocalizedCache(requestIdentityGeneration, requestLanguageRevision) { cacheWriteLease ->
                catalogCache.cacheSeasons(seriesId, result.data, cacheWriteLease)
            }
            return result
        }
        if (serveCacheOnFailure && result.canServeCache()) {
            getCachedSeasons(seriesId)?.let { return ApiResult.Success(it) }
        }
        return result
    }

    private suspend fun fetchEpisodes(
        seriesId: String,
        seasonNumber: Int,
        surface: ArtworkSurface,
        requestIdentityGeneration: Long,
        requestLanguageRevision: Long,
        serveCacheOnFailure: Boolean = true,
    ): ApiResult<EpisodesResponse> {
        val artworkFingerprint = artwork.cacheFingerprint(surface)
        val result = catalogApi.getEpisodes(seriesId, seasonNumber, widths = artwork.request(surface))
        if (result is ApiResult.Success) {
            writeLocalizedCache(requestIdentityGeneration, requestLanguageRevision, artworkFingerprint) { cacheWriteLease ->
                catalogCache.cacheEpisodes(
                    seriesId,
                    seasonNumber,
                    result.data,
                    cacheWriteLease,
                    surface,
                )
            }
            return result
        }
        if (serveCacheOnFailure && result.canServeCache()) {
            getCachedEpisodes(seriesId, seasonNumber, surface = surface)?.let {
                return ApiResult.Success(it)
            }
        }
        return result
    }

    private val localizedCacheMutex = Mutex()
    private val invalidatedLanguageRevisions = mutableMapOf<CatalogCacheScope?, Long>()
    private val currentLanguageRevision: Long
        get() = catalogLanguage?.changes?.revision ?: 0L

    /** Purge durable descriptions before a new language can reuse them. */
    suspend fun invalidateLocalizedMetadata() {
        localizedCacheMutex.withLock { synchronizeLocalizedCache() }
    }

    // Called with localizedCacheMutex held, including across Room writes. An
    // old write completes before the purge, or fails its language check after it.
    private suspend fun synchronizeLocalizedCache(): Long {
        val changes = catalogLanguage?.changes ?: return 0L
        // Purge the writer's scope even after a profile/server switch. Track
        // each owner independently, including changes before lazy construction.
        for ((scope, revision) in changes.scopes) {
            if (revision > (invalidatedLanguageRevisions[scope] ?: 0L)) {
                catalogCache.invalidateDetailMetadata(scope)
                invalidatedLanguageRevisions[scope] = revision
            }
        }
        return changes.revision
    }

    private suspend fun <T> readLocalizedCache(read: suspend () -> T?): T? =
        localizedCacheMutex.withLock {
            val revision = synchronizeLocalizedCache()
            read().takeIf { revision == currentLanguageRevision }
        }

    private suspend fun writeLocalizedCache(
        requestIdentityGeneration: Long,
        requestLanguageRevision: Long,
        artworkFingerprint: String? = null,
        write: suspend (CatalogCacheWriteLease) -> Unit,
    ) {
        localizedCacheMutex.withLock {
            val revision = synchronizeLocalizedCache()
            if (requestLanguageRevision == revision &&
                requestIdentityGeneration == identityTransitions.generation.value
            ) {
                write(CatalogCacheWriteLease(requestIdentityGeneration, artworkFingerprint))
            }
        }
    }

    private suspend fun writeIfIdentityUnchanged(
        requestGeneration: Long,
        artworkFingerprint: String? = null,
        write: suspend (CatalogCacheWriteLease) -> Unit,
    ) {
        if (requestGeneration == identityTransitions.generation.value) {
            write(CatalogCacheWriteLease(requestGeneration, artworkFingerprint))
        }
    }

    private suspend fun <K, T> inFlight(requests: MutableMap<K, Deferred<T>>, key: K): Deferred<T>? =
        detailRequestMutex.withLock { requests[key] }

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
