package org.siloserver.silo.android.ui.screens.cast

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import org.koin.compose.koinInject
import org.siloserver.silo.model.catalog.ItemDetail
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.repository.CatalogRepository

/**
 * Poster/backdrop artwork for the cast remote, resolved from the `contentId`
 * already present in the control playback state — no wire-protocol field
 * needed. Mirrors silo-apple's `SiloControlArtworkResolver`: cached item
 * detail first, then the API, degrading silently to no artwork.
 *
 * Episodes use their series' portrait poster — an episode's own poster is a
 * landscape still, wrong for the remote's 2:3 card. The episode's backdrop
 * (the still) is kept for the blurred background, where it looks right.
 */
data class SiloCastArtwork(
    val posterUrl: String? = null,
    val posterThumbhash: String? = null,
    val backdropUrl: String? = null,
    val backdropThumbhash: String? = null,
) {
    val isEmpty: Boolean get() = posterUrl == null && backdropUrl == null
}

@Composable
fun rememberSiloCastArtwork(contentId: String?): SiloCastArtwork {
    val repository: CatalogRepository = koinInject()
    // Key the state as well as the effect so the previous title's artwork is
    // removed synchronously, before the new detail lookup suspends.
    var artwork by remember(contentId) { mutableStateOf(SiloCastArtwork()) }
    LaunchedEffect(contentId) {
        artwork = if (contentId.isNullOrBlank()) {
            SiloCastArtwork()
        } else {
            resolveCastArtwork(repository, contentId)
        }
    }
    return artwork
}

private suspend fun resolveCastArtwork(
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
