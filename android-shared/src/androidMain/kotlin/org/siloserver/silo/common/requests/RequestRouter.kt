package org.siloserver.silo.common.requests

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import org.siloserver.silo.model.request.MediaRequest
import org.siloserver.silo.model.request.RequestMediaResult
import org.siloserver.silo.model.request.libraryItemToOpen
import org.siloserver.silo.repository.RequestDetailCache
import org.siloserver.silo.repository.RequestsRepository
import org.siloserver.silo.repository.cacheKey

/**
 * The standard taps on request cards and rows, on phone and TV: a title that
 * reads "In library" opens the library item; anything else opens the request
 * detail, seeded with what the tapped card already knows so the page is
 * complete on its first frame. Only an approval queue opens a page pinned to
 * one request; every other way in opens an ordinary page.
 */
class RequestRouter internal constructor(
    private val repository: RequestsRepository,
    private val openLibraryItem: () -> (String) -> Unit,
    private val openRequestDetail: () -> (mediaType: String, tmdbId: Int) -> Unit,
) {
    fun openResult(result: RequestMediaResult) {
        repository.cache.seed(result)
        repository.cache.unpinModeration(RequestDetailCache.Key(result.mediaType, result.tmdbId))
        result.libraryItemToOpen()?.let { openLibraryItem()(it) } ?: openRequestDetail()(result.mediaType, result.tmdbId)
    }

    fun openRecord(record: MediaRequest) {
        repository.cache.unpinModeration(record.cacheKey())
        record.libraryItemToOpen()?.let { openLibraryItem()(it) } ?: openRequestDetail()(record.mediaType, record.tmdbId)
    }

    /** Opens someone else's request from an approval queue: the page decides on that exact request. */
    fun openModerationRecord(record: MediaRequest) {
        repository.cache.pinModeration(record)
        openRequestDetail()(record.mediaType, record.tmdbId)
    }
}

@Composable
fun rememberRequestRouter(
    repository: RequestsRepository,
    onLibraryItemClick: (String) -> Unit,
    onRequestDetailClick: (mediaType: String, tmdbId: Int) -> Unit,
): RequestRouter {
    val library = rememberUpdatedState(onLibraryItemClick)
    val detail = rememberUpdatedState(onRequestDetailClick)
    return remember(repository) { RequestRouter(repository, { library.value }, { detail.value }) }
}
