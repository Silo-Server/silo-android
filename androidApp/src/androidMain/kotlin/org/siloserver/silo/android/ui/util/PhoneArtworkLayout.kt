package org.siloserver.silo.android.ui.util

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.coerceIn
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.max
import kotlin.math.floor
import org.siloserver.silo.android.ui.components.MediaGridDefaults
import org.siloserver.silo.android.ui.screens.detail.ExpandedDetailBreakpoint
import org.siloserver.silo.android.ui.screens.detail.expandedDetailHorizontalPadding
import org.siloserver.silo.android.ui.screens.detail.expandedDetailPosterWidth
import org.siloserver.silo.network.ArtworkWidths
import org.siloserver.silo.repository.ArtworkLayout
import org.siloserver.silo.repository.ArtworkSurface

/**
 * The per-slot widths the phone and tablet app asks the server for, from the
 * layouts that draw each surface. Each width is the larger of the screen's two
 * orientations, so rotating keeps a listing's widths and its cached copy;
 * folding or unfolding changes the screen, and so the widths.
 *
 * Rail cards grow with the card-size preference, and grids widen their cells
 * by dropping columns. Grids are sized at the Normal grid density: Comfortable
 * and Compact are a browse screen's own choice, made after its listing loads.
 */
class PhoneArtworkLayout(
    private val screen: () -> Screen,
    private val posterScale: () -> Float = { 1f },
) : ArtworkLayout {
    /** The screen's size in dp in its current orientation, and its density. */
    data class Screen(val widthDp: Int, val heightDp: Int, val density: Float)

    override fun cardScale(): Float = posterScale()

    override fun widths(surface: ArtworkSurface, cardScale: Float): ArtworkWidths {
        val (widthDp, heightDp, density) = screen()
        val portrait = slots(surface, widthDp.dp, cardScale)
        val landscape = slots(surface, heightDp.dp, cardScale)
        fun wider(a: Dp?, b: Dp?) = listOfNotNull(a, b).maxOrNull()?.value
        return ArtworkWidths.ofDp(
            density,
            poster = wider(portrait.poster, landscape.poster),
            backdrop = wider(portrait.backdrop, landscape.backdrop),
            logo = wider(portrait.logo, landscape.logo),
            still = wider(portrait.still, landscape.still),
            photo = wider(portrait.photo, landscape.photo),
        )
    }

    private class Slots(
        val poster: Dp? = null,
        val backdrop: Dp? = null,
        val logo: Dp? = null,
        val still: Dp? = null,
        val photo: Dp? = null,
    )

    private fun slots(surface: ArtworkSurface, window: Dp, cardScale: Float): Slots = when (surface) {
        // MediaCard posters, and BackdropCard for Continue Watching and Next Up.
        ArtworkSurface.HOME, ArtworkSurface.DISCOVER ->
            Slots(poster = RailPosterWidth * cardScale, backdrop = RailBackdropWidth * cardScale)
        ArtworkSurface.SIMILAR -> Slots(poster = RailPosterWidth * cardScale)
        ArtworkSurface.DETAIL -> detail(window)
        // EpisodeList cards.
        ArtworkSurface.EPISODES, ArtworkSurface.SEASON_EPISODES -> Slots(still = 240.dp)
        ArtworkSurface.BROWSE -> Slots(poster = gridCellWidth(window, MediaGridDefaults.PosterGridMinWidth * cardScale))
    }

    private fun detail(window: Dp): Slots {
        if (window < ExpandedDetailBreakpoint) {
            // DetailHeroArtwork crops the backdrop to the full width and a height
            // of 1.18 × the width, clamped to 430–540dp. The logo spans the width
            // inside 28dp margins. The poster is sized by the expanded hero alone:
            // Cast, the audiobook player and playback artwork read it too.
            val heroHeight = (window * 1.18f).coerceIn(430.dp, 540.dp)
            return Slots(
                backdrop = max(window, heroHeight * 16f / 9f),
                logo = window - 56.dp,
                photo = CastPhotoSize,
            )
        }
        // ExpandedDetailHero spans the backdrop over the window, from 88dp
        // above the poster to its bottom. The logo is 72% of the text column.
        val padding = expandedDetailHorizontalPadding(window)
        val poster = expandedDetailPosterWidth(window)
        val heroHeight = 88.dp + poster * 1.5f
        return Slots(
            poster = poster,
            backdrop = max(window, heroHeight * 16f / 9f),
            logo = (window - padding * 2 - poster - 28.dp) * 0.72f,
            photo = CastPhotoSize,
        )
    }

    /** The cell CatalogGrid's adaptive columns of [minCell] give a [window]-wide screen. */
    private fun gridCellWidth(window: Dp, minCell: Dp): Dp {
        val available = window - 32.dp
        val spacing = MediaGridDefaults.PosterGridHorizontalSpacing
        val columns = floor((available + spacing) / (minCell + spacing)).toInt().coerceAtLeast(1)
        return (available - spacing * (columns - 1)) / columns
    }

    private companion object {
        /** CastCrewSection's headshot circle. */
        val CastPhotoSize = 76.dp

        /** MediaCard and BackdropCard at the standard card size. */
        val RailPosterWidth = 120.dp
        val RailBackdropWidth = 280.dp
    }
}
