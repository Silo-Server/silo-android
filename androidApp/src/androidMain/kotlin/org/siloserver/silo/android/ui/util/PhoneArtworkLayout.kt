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
import org.siloserver.silo.model.settings.CardPosterSize
import org.siloserver.silo.network.ArtworkWidths
import org.siloserver.silo.repository.ArtworkLayout
import org.siloserver.silo.repository.ArtworkSurface

/**
 * The per-slot widths the phone and tablet app asks the server for, from the
 * layouts that draw each surface. Each width is the larger of the screen's two
 * orientations, so rotating keeps a listing's widths and its cached copy;
 * folding or unfolding changes the screen, and so the widths.
 *
 * Rail cards scale with the card-size preference, which loads from the server
 * after launch, so rails ask for the largest size. Grids adapt their column
 * count to that preference instead, so their cells barely change.
 */
class PhoneArtworkLayout(private val screen: () -> Screen) : ArtworkLayout {
    /** The screen's size in dp in its current orientation, and its density. */
    data class Screen(val widthDp: Int, val heightDp: Int, val density: Float)

    override fun widths(surface: ArtworkSurface): ArtworkWidths {
        val (widthDp, heightDp, density) = screen()
        val portrait = slots(surface, widthDp.dp)
        val landscape = slots(surface, heightDp.dp)
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

    private fun slots(surface: ArtworkSurface, window: Dp): Slots = when (surface) {
        // MediaCard posters, and BackdropCard for Continue Watching and Next Up.
        ArtworkSurface.HOME, ArtworkSurface.DISCOVER -> Slots(poster = RailPosterWidth, backdrop = RailBackdropWidth)
        ArtworkSurface.SIMILAR -> Slots(poster = RailPosterWidth)
        ArtworkSurface.DETAIL -> detail(window)
        // EpisodeList cards.
        ArtworkSurface.EPISODES, ArtworkSurface.SEASON_EPISODES -> Slots(still = 240.dp)
        ArtworkSurface.BROWSE -> Slots(poster = gridCellWidth(window))
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

    /** The cell CatalogGrid's adaptive columns give a [window]-wide screen at Normal density. */
    private fun gridCellWidth(window: Dp): Dp {
        val available = window - 32.dp
        val spacing = MediaGridDefaults.PosterGridHorizontalSpacing
        val columns = floor((available + spacing) / (MediaGridDefaults.PosterGridMinWidth + spacing)).toInt().coerceAtLeast(1)
        return (available - spacing * (columns - 1)) / columns
    }

    private companion object {
        /** CastCrewSection's headshot circle. */
        val CastPhotoSize = 76.dp

        /** MediaCard and BackdropCard at the largest card size. */
        val RailScale = CardPosterSize.entries.maxOf { it.posterScale }
        val RailPosterWidth = 120.dp * RailScale
        val RailBackdropWidth = 280.dp * RailScale
    }
}
