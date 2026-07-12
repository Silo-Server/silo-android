package org.siloserver.silo.repository

import org.siloserver.silo.model.catalog.CatalogResponse
import org.siloserver.silo.model.personal.ProgressListResponse
import org.siloserver.silo.model.personal.RatingEntry
import org.siloserver.silo.model.personal.SyncProgressItem
import org.siloserver.silo.model.personal.SyncProgressRequest
import org.siloserver.silo.model.personal.UserLibrary
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.network.api.PersonalDataApi
import org.siloserver.silo.network.map
import org.siloserver.silo.repository.port.CatalogCachePort
import org.siloserver.silo.repository.port.NoOpCatalogCachePort
import org.siloserver.silo.repository.port.NoOpUserItemStatePort
import org.siloserver.silo.repository.port.UserItemStatePort
import org.siloserver.silo.repository.port.canServeCache
import org.siloserver.silo.repository.port.toWriteOutcome

open class PersonalDataRepository(
    private val personalDataApi: PersonalDataApi,
    /**
     * Local-first side-channel (Track B). Defaults to the no-op port so
     * commonMain/tests stay network-only; the Android platform module binds a
     * Room-backed [UserItemStatePort] that records an optimistic projection +
     * outbox op around each content-level mutation below.
     */
    private val userItemStatePort: UserItemStatePort = NoOpUserItemStatePort,
    /** Offline read cache for the library list (Track B). No-op by default. */
    private val catalogCache: CatalogCachePort = NoOpCatalogCachePort,
) {
    // -- Libraries --

    /** Lists the libraries visible to the current user (offline: last cached list). */
    suspend fun listUserLibraries(): ApiResult<List<UserLibrary>> {
        val result = personalDataApi.listUserLibraries()
        if (result is ApiResult.Success) {
            catalogCache.cacheLibraries(result.data)
            return result
        }
        if (result.canServeCache()) {
            catalogCache.getCachedLibraries()?.let { return ApiResult.Success(it) }
        }
        return result
    }

    // -- Favorites --

    /** Lists the user's favorite items with pagination. */
    suspend fun listFavorites(offset: Int = 0, limit: Int = 40): ApiResult<CatalogResponse> =
        personalDataApi.listFavorites(offset, limit)

    /** Checks whether a specific item is in the user's favorites. */
    suspend fun isFavorite(itemId: String): ApiResult<Boolean> =
        personalDataApi.checkFavorite(itemId)

    /**
     * Adds or removes an item from the user's favorites.
     * @param isFavorite true to add, false to remove.
     */
    suspend fun toggleFavorite(itemId: String, isFavorite: Boolean): ApiResult<Unit> {
        val handle = userItemStatePort.recordFavorite(itemId, isFavorite)
        val result = if (isFavorite) {
            personalDataApi.addFavorite(itemId, handle.scope)
        } else {
            personalDataApi.removeFavorite(itemId, handle.scope)
        }
        userItemStatePort.resolve(handle, result.toWriteOutcome())
        return result
    }

    // -- Watchlist --

    /** Lists the user's watchlist items with pagination. */
    suspend fun listWatchlist(offset: Int = 0, limit: Int = 40): ApiResult<CatalogResponse> =
        personalDataApi.listWatchlist(offset, limit)

    /** Checks whether a specific item is on the user's watchlist. */
    suspend fun isInWatchlist(itemId: String): ApiResult<Boolean> =
        personalDataApi.checkWatchlist(itemId)

    /**
     * Adds or removes an item from the user's watchlist.
     * @param isInWatchlist true to add, false to remove.
     */
    suspend fun toggleWatchlist(itemId: String, isInWatchlist: Boolean): ApiResult<Unit> =
        if (isInWatchlist) {
            personalDataApi.addToWatchlist(itemId)
        } else {
            personalDataApi.removeFromWatchlist(itemId)
        }

    // -- History --

    /** Lists the user's watch history with pagination. */
    suspend fun listHistory(offset: Int = 0, limit: Int = 40): ApiResult<CatalogResponse> =
        personalDataApi.listHistory(offset, limit)

    // -- Progress --

    /** Lists all in-progress items for the current user. */
    suspend fun listProgress(): ApiResult<ProgressListResponse> =
        personalDataApi.listProgress()

    /** Syncs local progress state with the server. */
    open suspend fun syncProgress(items: List<SyncProgressItem>): ApiResult<Unit> =
        personalDataApi.syncProgress(SyncProgressRequest(items = items))

    // -- Ratings --

    /** Lists all ratings the current user has set. */
    suspend fun listRatings(): ApiResult<List<RatingEntry>> =
        personalDataApi.listRatings().map { it.ratings }

    /** Gets the user's rating for a specific item. */
    suspend fun getRating(itemId: String): ApiResult<RatingEntry> =
        personalDataApi.getRating(itemId)

    /** Sets or updates the user's star rating (integer 1-5) for a specific item. */
    suspend fun setRating(itemId: String, rating: Int): ApiResult<Unit> {
        val handle = userItemStatePort.recordRating(itemId, rating)
        val result = personalDataApi.setRating(itemId, rating, handle.scope)
        userItemStatePort.resolve(handle, result.toWriteOutcome())
        return result
    }

    /** Removes the user's rating for a specific item. */
    suspend fun deleteRating(itemId: String): ApiResult<Unit> {
        val handle = userItemStatePort.recordRating(itemId, null)
        val result = personalDataApi.deleteRating(itemId, handle.scope)
        userItemStatePort.resolve(handle, result.toWriteOutcome())
        return result
    }

    // -- Watched --

    /**
     * Toggle the watched state for an item. The server resolves leaf
     * targets, so passing a series / season ID marks the appropriate
     * episodes.
     */
    open suspend fun setWatched(itemId: String, watched: Boolean): ApiResult<Unit> {
        val handle = userItemStatePort.recordWatched(itemId, watched)
        val result = if (watched) {
            personalDataApi.markWatched(itemId, handle.scope)
        } else {
            personalDataApi.markUnwatched(itemId, handle.scope)
        }
        userItemStatePort.resolve(handle, result.toWriteOutcome())
        return result
    }

    // -- Continue Watching dismissals --

    /** Hide an item from the home Continue Watching row. */
    open suspend fun dismissContinueWatching(
        itemId: String,
        progressUpdatedAt: String
    ): ApiResult<Unit> =
        personalDataApi.dismissContinueWatching(itemId, progressUpdatedAt)

    /** Undo a Continue Watching dismissal. */
    open suspend fun undismissContinueWatching(itemId: String): ApiResult<Unit> =
        personalDataApi.undismissContinueWatching(itemId)

    open suspend fun dismissNextUp(itemId: String, seriesId: String): ApiResult<Unit> =
        personalDataApi.dismissNextUp(itemId, seriesId)
}
