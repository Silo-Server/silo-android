package org.siloserver.silo.viewmodel

import org.siloserver.silo.model.catalog.CatalogBoxSetDetail
import org.siloserver.silo.model.catalog.ItemDetail
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.repository.CatalogRepository
import org.siloserver.silo.repository.PersonalDataRepository

/**
 * The box set a movie page shows under its hero, or null for none. Only a
 * movie asks, and only while a library the viewer sees has box sets on (the
 * page's library when it was opened from one), so servers without box sets
 * are never asked. That check reads the library list already loaded, not the
 * server, so a page costs no extra request. A failed read is returned as is:
 * a refresh keeps the row it already shows.
 */
suspend fun loadMovieBoxSet(
    catalogRepository: CatalogRepository,
    personalDataRepository: PersonalDataRepository,
    detail: ItemDetail,
    libraryId: Int?,
): ApiResult<CatalogBoxSetDetail?> {
    if (!detail.type.equals("movie", ignoreCase = true)) return ApiResult.Success(null)
    val libraries = when (val result = personalDataRepository.knownUserLibraries()) {
        is ApiResult.Success -> result.data
        is ApiResult.Error -> return result
        is ApiResult.NetworkError -> return result
    }
    if (libraries.none { it.boxSetsEnabled && (libraryId == null || it.id == libraryId) }) return ApiResult.Success(null)
    return catalogRepository.getItemBoxSet(detail.contentId, libraryId)
}
