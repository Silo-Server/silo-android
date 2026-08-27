package org.siloserver.silo.android.cast

import org.siloserver.silo.model.catalog.ItemDetail
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.repository.CatalogRepository

/** Artwork resolved locally from the content identity in Remote Control state. */
data class SiloCastArtwork(
    val posterUrl: String? = null,
    val posterThumbhash: String? = null,
    val backdropUrl: String? = null,
    val backdropThumbhash: String? = null,
) {
    val isEmpty: Boolean get() = posterUrl == null && backdropUrl == null
}

/**
 * Episodes use their series' portrait poster while retaining the episode
 * still/backdrop for wide and blurred surfaces.
 */
internal suspend fun resolveCastArtwork(
    repository: CatalogRepository,
    contentId: String,
): SiloCastArtwork {
    val detail = repository.detailOrNull(contentId) ?: return SiloCastArtwork()
    val series = detail.seriesId
        ?.takeIf { detail.type == "episode" }
        ?.let { repository.detailOrNull(it) }
    return SiloCastArtwork(
        posterUrl = series?.posterUrl ?: detail.posterUrl,
        posterThumbhash = if (series?.posterUrl != null) series.posterThumbhash else detail.posterThumbhash,
        backdropUrl = detail.backdropUrl ?: series?.backdropUrl,
        backdropThumbhash = if (detail.backdropUrl != null) detail.backdropThumbhash else series?.backdropThumbhash,
    )
}

private suspend fun CatalogRepository.detailOrNull(contentId: String): ItemDetail? =
    getCachedItemDetail(contentId)
        ?: (getItemDetail(contentId) as? ApiResult.Success)?.data
