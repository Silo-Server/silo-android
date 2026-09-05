package org.siloserver.silo.network.api

import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.http.*
import org.siloserver.silo.model.catalog.BrowseItem
import org.siloserver.silo.model.catalog.CatalogResponse
import org.siloserver.silo.model.personal.*
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.network.AuthScopeSnapshot
import org.siloserver.silo.network.authScope
import org.siloserver.silo.network.apiv2.ApiV2Gate
import org.siloserver.silo.network.apiv2.ProgressCollection
import org.siloserver.silo.network.apiv2.safeApiV2Call

/** The v2 contract caps `limit` at 200; ask for the maximum so the walk is as short as possible. */
private const val PROGRESS_PAGE_SIZE = 200

/**
 * Runaway guard only (100 pages × 200 = 20,000 entries). Hitting it with
 * `has_more` still true is reported as [PROGRESS_INCOMPLETE_ERROR], never
 * as a silent prefix.
 */
private const val PROGRESS_MAX_PAGES = 100

/** [ApiResult.Error.error] when the progress walk stopped before the last page. */
const val PROGRESS_INCOMPLETE_ERROR = "progress_incomplete"

class PersonalDataApi(
    private val client: HttpClient,
    private val apiV2Gate: ApiV2Gate = ApiV2Gate.Unrestricted,
) {

    // --- User Libraries ---

    suspend fun listUserLibraries(): ApiResult<List<UserLibrary>> = safeApiCall {
        client.get("/api/v1/user/libraries")
    }

    // --- Favorites ---

    suspend fun listFavorites(
        offset: Int = 0,
        limit: Int = 40
    ): ApiResult<CatalogResponse> = safeApiCall {
        client.get("/api/v1/favorites") {
            parameter("offset", offset)
            parameter("limit", limit)
        }
    }

    suspend fun checkFavorite(itemId: String): ApiResult<Boolean> = safeStatusCall {
        client.get("/api/v1/favorites/$itemId")
    }

    suspend fun addFavorite(itemId: String, scope: AuthScopeSnapshot? = null): ApiResult<Unit> = safeApiCall {
        client.put("/api/v1/favorites/$itemId") { scope?.let { authScope(it) } }
    }

    suspend fun removeFavorite(itemId: String, scope: AuthScopeSnapshot? = null): ApiResult<Unit> = safeApiCall {
        client.delete("/api/v1/favorites/$itemId") { scope?.let { authScope(it) } }
    }

    // --- Watchlist ---

    suspend fun listWatchlist(
        offset: Int = 0,
        limit: Int = 40
    ): ApiResult<CatalogResponse> = safeApiCall {
        client.get("/api/v1/watchlist") {
            parameter("offset", offset)
            parameter("limit", limit)
        }
    }

    suspend fun checkWatchlist(itemId: String): ApiResult<Boolean> = safeStatusCall {
        client.get("/api/v1/watchlist/$itemId")
    }

    suspend fun addToWatchlist(itemId: String): ApiResult<Unit> = safeApiCall {
        client.put("/api/v1/watchlist/$itemId")
    }

    suspend fun removeFromWatchlist(itemId: String): ApiResult<Unit> = safeApiCall {
        client.delete("/api/v1/watchlist/$itemId")
    }

    // --- History ---

    suspend fun listHistory(
        offset: Int = 0,
        limit: Int = 40
    ): ApiResult<CatalogResponse> = safeApiCall {
        client.get("/api/v1/history") {
            parameter("offset", offset)
            parameter("limit", limit)
        }
    }

    // --- Progress ---

    /**
     * Pilot v2 operation (listProgress): v2 only, no v1 fallback. v1 returned
     * the whole list; v2 pages by opaque cursor, so every page is walked here.
     *
     * The result is either the complete list or an error — never a silent
     * prefix. A page failure returns that page's error; exceeding
     * [PROGRESS_MAX_PAGES] with more pages left returns an
     * [ApiResult.Error] whose `error` is [PROGRESS_INCOMPLETE_ERROR], so
     * continue-watching consumers never treat older entries as absent.
     */
    suspend fun listProgress(): ApiResult<ProgressListResponse> {
        val entries = mutableListOf<ProgressEntry>()
        var cursor: String? = null
        var pages = 0
        while (true) {
            val page = safeApiV2Call<ProgressCollection>(apiV2Gate) {
                client.get("/api/v2/progress") {
                    parameter("limit", PROGRESS_PAGE_SIZE)
                    cursor?.let { parameter("cursor", it) }
                }
            }
            val collection = when (page) {
                is ApiResult.Success -> page.data
                is ApiResult.Error -> return page
                is ApiResult.NetworkError -> return page
            }
            collection.items.mapTo(entries) {
                ProgressEntry(
                    mediaItemId = it.mediaItemId,
                    positionSeconds = it.positionSeconds,
                    durationSeconds = it.durationSeconds,
                    completed = it.completed,
                    updatedAt = it.updatedAt,
                )
            }
            val next = collection.page.nextCursor
            pages++
            if (!collection.page.hasMore || next == null) break
            if (pages >= PROGRESS_MAX_PAGES) {
                return ApiResult.Error(
                    code = 0,
                    error = PROGRESS_INCOMPLETE_ERROR,
                    message = "Progress list still has more pages after $PROGRESS_MAX_PAGES pages of " +
                        "$PROGRESS_PAGE_SIZE; refusing to return a partial list.",
                )
            }
            cursor = next
        }
        return ApiResult.Success(ProgressListResponse(progress = entries))
    }

    suspend fun syncProgress(
        request: SyncProgressRequest,
        scope: AuthScopeSnapshot? = null,
    ): ApiResult<Unit> = safeApiCall {
        client.post("/api/v1/sync/progress") {
            scope?.let { authScope(it) }
            contentType(ContentType.Application.Json)
            setBody(request)
        }
    }

    // --- Ratings ---

    suspend fun listRatings(): ApiResult<RatingsResponse> = safeApiCall {
        client.get("/api/v1/ratings")
    }

    suspend fun getRating(itemId: String): ApiResult<RatingEntry> = safeApiCall {
        client.get("/api/v1/ratings/$itemId")
    }

    suspend fun setRating(itemId: String, rating: Int, scope: AuthScopeSnapshot? = null): ApiResult<Unit> = safeApiCall {
        client.put("/api/v1/ratings/$itemId") {
            scope?.let { authScope(it) }
            contentType(ContentType.Application.Json)
            setBody(SetRatingRequest(rating))
        }
    }

    suspend fun deleteRating(itemId: String, scope: AuthScopeSnapshot? = null): ApiResult<Unit> = safeApiCall {
        client.delete("/api/v1/ratings/$itemId") { scope?.let { authScope(it) } }
    }

    // --- Watched ---

    /** Mark an item (movie / series / season / episode) as watched. Server resolves leaf targets. */
    suspend fun markWatched(itemId: String, scope: AuthScopeSnapshot? = null): ApiResult<Unit> = safeApiCall {
        client.post("/api/v1/watched/$itemId") { scope?.let { authScope(it) } }
    }

    /** Mark an item as unwatched. */
    suspend fun markUnwatched(itemId: String, scope: AuthScopeSnapshot? = null): ApiResult<Unit> = safeApiCall {
        client.delete("/api/v1/watched/$itemId") { scope?.let { authScope(it) } }
    }

    // --- Continue Watching dismissals ---

    /** Hide an item from the home Continue Watching row without deleting progress. */
    suspend fun dismissContinueWatching(
        itemId: String,
        progressUpdatedAt: String
    ): ApiResult<Unit> = safeApiCall {
        client.put("/api/v1/home/dismissals/continue_watching/$itemId") {
            contentType(ContentType.Application.Json)
            setBody(ContinueWatchingDismissalRequest(progressUpdatedAt))
        }
    }

    /** Undo a Continue Watching dismissal. */
    suspend fun undismissContinueWatching(itemId: String): ApiResult<Unit> = safeApiCall {
        client.delete("/api/v1/home/dismissals/continue_watching/$itemId")
    }

    /** Hide a Next Up episode that is presented inside a Continue Watching row. */
    suspend fun dismissNextUp(itemId: String, seriesId: String): ApiResult<Unit> = safeApiCall {
        client.put("/api/v1/home/dismissals/next_up/$itemId") {
            contentType(ContentType.Application.Json)
            setBody(NextUpDismissalRequest(seriesId))
        }
    }
}
