package org.siloserver.silo.repository.port

import org.siloserver.silo.model.catalog.CatalogResponse
import org.siloserver.silo.model.catalog.EpisodesResponse
import org.siloserver.silo.model.catalog.ItemDetail
import org.siloserver.silo.model.catalog.SeasonsResponse
import org.siloserver.silo.model.personal.UserLibrary
import org.siloserver.silo.model.section.ResolvedSection
import org.siloserver.silo.network.ApiResult

/**
 * Ownership captured before a cacheable network request starts. Identity-scoped
 * cache implementations must reject the write when this generation is stale.
 */
data class CatalogCacheWriteLease(val identityGeneration: Long)

/**
 * Offline read cache for catalog browse (Track B). Backs repository-level
 * cache-with-fallback in [org.siloserver.silo.repository.PersonalDataRepository]
 * (library list) and [org.siloserver.silo.repository.CatalogRepository] (a
 * library's default first page): a successful network result is cached; a later
 * offline/5xx failure serves the cached copy so the Libraries tab + grids render
 * with no network.
 *
 * commonMain port (so the shared repositories can depend on it) with a Room-backed
 * Android impl bound in the platform module; default no-op keeps tests/non-Android
 * network-only. Scope `(serverId, profileId)` is resolved inside the impl.
 */
interface CatalogCachePort {
    suspend fun cacheLibraries(libraries: List<UserLibrary>) {}
    suspend fun cacheLibraries(libraries: List<UserLibrary>, lease: CatalogCacheWriteLease) {
        cacheLibraries(libraries)
    }
    suspend fun getCachedLibraries(): List<UserLibrary>? = null

    /** Cache the default (unfiltered, first-page) browse for a library. */
    suspend fun cacheDefaultLibraryPage(libraryId: Int, response: CatalogResponse) {}
    suspend fun cacheDefaultLibraryPage(
        libraryId: Int,
        response: CatalogResponse,
        lease: CatalogCacheWriteLease,
    ) {
        cacheDefaultLibraryPage(libraryId, response)
    }
    suspend fun getCachedDefaultLibraryPage(libraryId: Int): CatalogResponse? = null

    /** Cache a library's resolved "Recommended" sections (for the offline landing tab). */
    suspend fun cacheLibrarySections(libraryId: Int, sections: List<ResolvedSection>) {}
    suspend fun cacheLibrarySections(
        libraryId: Int,
        sections: List<ResolvedSection>,
        lease: CatalogCacheWriteLease,
    ) {
        cacheLibrarySections(libraryId, sections)
    }
    suspend fun getCachedLibrarySections(libraryId: Int): List<ResolvedSection>? = null

    /** Cache an item's detail page (tap-a-title-offline). */
    suspend fun cacheItemDetail(contentId: String, detail: ItemDetail) {}
    suspend fun cacheItemDetail(
        contentId: String,
        detail: ItemDetail,
        lease: CatalogCacheWriteLease,
    ) {
        cacheItemDetail(contentId, detail)
    }
    suspend fun getCachedItemDetail(contentId: String): ItemDetail? = null

    /** Cache a series' season list + a season's episode list (offline series detail). */
    suspend fun cacheSeasons(seriesId: String, response: SeasonsResponse) {}
    suspend fun cacheSeasons(
        seriesId: String,
        response: SeasonsResponse,
        lease: CatalogCacheWriteLease,
    ) {
        cacheSeasons(seriesId, response)
    }
    suspend fun getCachedSeasons(seriesId: String): SeasonsResponse? = null
    suspend fun cacheEpisodes(seriesId: String, seasonNumber: Int, response: EpisodesResponse) {}
    suspend fun cacheEpisodes(
        seriesId: String,
        seasonNumber: Int,
        response: EpisodesResponse,
        lease: CatalogCacheWriteLease,
    ) {
        cacheEpisodes(seriesId, seasonNumber, response)
    }
    suspend fun getCachedEpisodes(seriesId: String, seasonNumber: Int): EpisodesResponse? = null
}

/** Network-only default. */
object NoOpCatalogCachePort : CatalogCachePort

/**
 * Whether a failed result should fall back to cache: offline ([ApiResult.NetworkError],
 * which also covers parse failures) or a transient server 5xx. Never on a 4xx —
 * auth/permission/not-found must not silently serve stale data.
 */
fun ApiResult<*>.canServeCache(): Boolean = when (this) {
    is ApiResult.NetworkError -> true
    is ApiResult.Error -> code in 500..599
    is ApiResult.Success -> false
}
