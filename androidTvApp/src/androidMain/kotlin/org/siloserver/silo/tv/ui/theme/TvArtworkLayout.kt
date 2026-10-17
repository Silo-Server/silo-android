package org.siloserver.silo.tv.ui.theme

import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.max
import org.siloserver.silo.network.ArtworkWidths
import org.siloserver.silo.repository.ArtworkLayout
import org.siloserver.silo.repository.ArtworkSurface

/**
 * The sizes the TV app draws artwork at. The TV canvas is 960×540dp at every
 * resolution, so a size in pixels is dp × the display's density: 1.33 at a
 * 720p UI, 2.0 at 1080p (most TVs, 4K panels included) and 4.0 at a 4K UI.
 */
object TvArtworkDimens {
    /** The root and detail heroes' art box: 0.64 × 0.70 of the canvas. */
    val HeroArtWidth = 960.dp * 0.64f
    val HeroArtHeight = 540.dp * 0.70f

    /** The width a 16:9 backdrop needs to fill the hero box when cropped: 672dp. */
    val HeroBackdropWidth = max(HeroArtWidth, HeroArtHeight * 16f / 9f)

    /** The focus marquee's title logo slot. */
    val MarqueeLogoWidth = 440.dp
    val MarqueeLogoHeight = 100.dp

    /** The detail page's title logo, which is at most this wide. */
    val DetailLogoWidth = 325.dp

    /** A cast or crew headshot on the detail page. */
    val CastPhotoSize = 100.dp

    /** An episode still outside the series page; on it, [RowDimens.BackdropWidth]. */
    val SeasonEpisodeStillWidth = 230.dp

    /** The widest catalog grid cell: search's adaptive grid. */
    val GridPosterWidth = 138.dp
}

/**
 * The per-slot widths the TV app asks the server for. Home, For You and detail
 * send the same backdrop width, so the hero image Home or For You loaded is the
 * one detail draws. Cards and grid cells grow with the card-size preference
 * (`cardScaled()`); the heroes and logos don't.
 */
class TvArtworkLayout(
    private val displayDensity: () -> Float,
    private val posterScale: suspend () -> Float = { 1f },
) : ArtworkLayout {
    override suspend fun cardScale(): Float = posterScale()

    override fun widths(surface: ArtworkSurface, cardScale: Float): ArtworkWidths {
        val density = displayDensity()
        return when (surface) {
            ArtworkSurface.HOME, ArtworkSurface.DISCOVER -> ArtworkWidths.ofDp(
                density,
                poster = RowDimens.DensePosterWidth.value * cardScale,
                backdrop = TvArtworkDimens.HeroBackdropWidth.value,
                logo = TvArtworkDimens.MarqueeLogoWidth.value,
            )
            ArtworkSurface.DETAIL -> ArtworkWidths.ofDp(
                density,
                backdrop = TvArtworkDimens.HeroBackdropWidth.value,
                logo = TvArtworkDimens.DetailLogoWidth.value,
                photo = TvArtworkDimens.CastPhotoSize.value,
            )
            ArtworkSurface.EPISODES -> ArtworkWidths.ofDp(density, still = RowDimens.BackdropWidth.value * cardScale)
            ArtworkSurface.SEASON_EPISODES ->
                ArtworkWidths.ofDp(density, still = TvArtworkDimens.SeasonEpisodeStillWidth.value * cardScale)
            ArtworkSurface.SIMILAR -> ArtworkWidths.ofDp(density, poster = RowDimens.PosterWidth.value * cardScale)
            ArtworkSurface.BROWSE -> ArtworkWidths.ofDp(density, poster = TvArtworkDimens.GridPosterWidth.value * cardScale)
        }
    }
}
