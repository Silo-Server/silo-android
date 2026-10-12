package org.siloserver.silo.common.requests

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import org.siloserver.silo.model.request.MediaRequest
import org.siloserver.silo.model.request.RequestMediaResult
import org.siloserver.silo.model.request.libraryItemToOpen
import org.siloserver.silo.repository.RequestsRepository

/**
 * The standard taps on request cards and rows, on phone and TV: a title that
 * reads "In library" opens the library item; anything else opens the request
 * detail, seeded with what the tapped card already knows so the page is
 * complete on its first frame.
 */
class RequestRouter internal constructor(
    private val repository: RequestsRepository,
    private val openLibraryItem: () -> (String) -> Unit,
    private val openRequestDetail: () -> (mediaType: String, tmdbId: Int) -> Unit,
) {
    fun openResult(result: RequestMediaResult) {
        repository.cache.seed(result)
        result.libraryItemToOpen()?.let { openLibraryItem()(it) } ?: openRequestDetail()(result.mediaType, result.tmdbId)
    }

    fun openRecord(record: MediaRequest) {
        record.libraryItemToOpen()?.let { openLibraryItem()(it) } ?: openRequestDetail()(record.mediaType, record.tmdbId)
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
