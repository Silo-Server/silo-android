package org.siloserver.silo.android.ui.screens.cast

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import org.koin.compose.koinInject
import org.siloserver.silo.android.cast.SiloCastArtwork
import org.siloserver.silo.android.cast.resolveCastArtwork
import org.siloserver.silo.repository.CatalogRepository

/**
 * Poster/backdrop artwork for the cast remote, resolved from the `contentId`
 * already present in the control playback state — no wire-protocol field
 * needed. Mirrors silo-apple's `SiloControlArtworkResolver`: cached item
 * detail first, then the API, degrading silently to no artwork.
 *
 * Episodes use their series' portrait poster — an episode's own poster is a
 * landscape still, wrong for the remote's 2:3 card. Resolution itself lives
 * outside Compose so the Android media session can publish the same artwork.
 */
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
