package org.siloserver.silo.repository

import org.siloserver.silo.model.catalog.CatalogQueryGroup
import org.siloserver.silo.model.catalog.CatalogResponse
import org.siloserver.silo.model.section.HomeLayoutResponse
import org.siloserver.silo.model.section.HomeSectionItemsResponse
import org.siloserver.silo.model.section.LibraryCollection
import org.siloserver.silo.model.section.LibraryCollectionsResponse
import org.siloserver.silo.model.section.SectionsResponse
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.network.DefaultIdentityTransitionBarrier
import org.siloserver.silo.network.IdentityTransitionBarrier
import org.siloserver.silo.network.api.SectionApi
import org.siloserver.silo.network.map
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

class SectionRepository(
    private val sectionApi: SectionApi,
    /** Offline read cache for a library's Recommended sections (Track B). No-op by default. */
    private val catalogCache: CatalogCachePort = NoOpCatalogCachePort,
    private val identityTransitions: IdentityTransitionBarrier = DefaultIdentityTransitionBarrier(),
    private val homeRequestDispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    private val homeRequestScope = CoroutineScope(SupervisorJob() + homeRequestDispatcher)
    private val homeRequestMutex = Mutex()
    private val homeSectionsInFlight =
        mutableMapOf<Long, Deferred<ApiResult<SectionsResponse>>>()
    private val homeSectionItemsInFlight =
        mutableMapOf<Pair<Long, String>, Deferred<ApiResult<HomeSectionItemsResponse>>>()

    /** Fetches the home screen layout configuration. */
    suspend fun getHomeLayout(): ApiResult<HomeLayoutResponse> =
        sectionApi.getHomeLayout()

    /** Fetches all home screen sections (with items pre-resolved). */
    suspend fun getHomeSections(): ApiResult<SectionsResponse> {
        val identityGeneration = identityTransitions.generation.value
        val request = homeRequestMutex.withLock {
            homeSectionsInFlight[identityGeneration] ?: run {
                lateinit var created: Deferred<ApiResult<SectionsResponse>>
                created = homeRequestScope.async(start = CoroutineStart.LAZY) {
                    try {
                        sectionApi.getHomeSections()
                    } finally {
                        homeRequestMutex.withLock {
                            if (homeSectionsInFlight[identityGeneration] === created) {
                                homeSectionsInFlight.remove(identityGeneration)
                            }
                        }
                    }
                }
                homeSectionsInFlight[identityGeneration] = created
                created.start()
                created
            }
        }
        return request.await()
    }

    /** Fetches the items within a specific home section. */
    suspend fun getHomeSectionItems(sectionId: String): ApiResult<HomeSectionItemsResponse> {
        val requestKey = identityTransitions.generation.value to sectionId
        val request = homeRequestMutex.withLock {
            homeSectionItemsInFlight[requestKey] ?: run {
                lateinit var created: Deferred<ApiResult<HomeSectionItemsResponse>>
                created = homeRequestScope.async(start = CoroutineStart.LAZY) {
                    try {
                        sectionApi.getHomeSectionItems(sectionId)
                    } finally {
                        homeRequestMutex.withLock {
                            if (homeSectionItemsInFlight[requestKey] === created) {
                                homeSectionItemsInFlight.remove(requestKey)
                            }
                        }
                    }
                }
                homeSectionItemsInFlight[requestKey] = created
                created.start()
                created
            }
        }
        return request.await()
    }

    /** Fetches a library's resolved sections (offline: last cached sections). */
    suspend fun getLibrarySections(libraryId: Int): ApiResult<SectionsResponse> {
        val requestIdentityGeneration = identityTransitions.generation.value
        val cacheWriteLease = CatalogCacheWriteLease(requestIdentityGeneration)
        val result = sectionApi.getLibrarySections(libraryId)
        if (result is ApiResult.Success) {
            if (requestIdentityGeneration == identityTransitions.generation.value) {
                catalogCache.cacheLibrarySections(libraryId, result.data.sections, cacheWriteLease)
            }
            return result
        }
        if (result.canServeCache()) {
            catalogCache.getCachedLibrarySections(libraryId)
                ?.let { return ApiResult.Success(SectionsResponse(sections = it)) }
        }
        return result
    }

    /** Fetches items within a specific library section. */
    suspend fun getLibrarySectionItems(
        libraryId: Int,
        sectionId: String,
    ): ApiResult<HomeSectionItemsResponse> =
        sectionApi.getLibrarySectionItems(libraryId, sectionId)

    /** Lists collections within a library as a flat list. Callers that need
     *  the grouped layout should use [getLibraryCollectionsGrouped]. */
    suspend fun getLibraryCollections(libraryId: Int): ApiResult<List<LibraryCollection>> =
        sectionApi.getLibraryCollections(libraryId).map { it.collections }

    /** Lists collections within a library, preserving group structure. */
    suspend fun getLibraryCollectionsGrouped(libraryId: Int): ApiResult<LibraryCollectionsResponse> =
        sectionApi.getLibraryCollections(libraryId)

    /** Fetches items within a library collection. */
    /** Pages a library collection's items via the catalog resolver. */
    suspend fun getLibraryCollectionItems(
        collectionId: String,
        offset: Int = 0,
        limit: Int = 60,
        sort: String? = null,
        order: String? = null,
        queryGroups: List<CatalogQueryGroup> = emptyList(),
        match: String? = null,
    ): ApiResult<CatalogResponse> =
        sectionApi.getLibraryCollectionItems(
            collectionId = collectionId,
            offset = offset,
            limit = limit,
            sort = sort,
            order = order,
            queryGroups = queryGroups,
            match = match,
        )
}
