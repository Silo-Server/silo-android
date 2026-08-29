package org.siloserver.silo.tv.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.unit.Dp
import org.siloserver.silo.common.cards.LocalCardPresentation
import org.siloserver.silo.model.settings.CardPosterSize

/**
 * Sizing helpers for the server-driven "Cards & Posters" preference
 * ([LocalCardPresentation]). [RowDimens] stays the base token set: rails and
 * standalone cards scale their widths by the active poster size (height
 * follows aspect ratio), while fixed-column grids shift their column count
 * instead so cells keep filling the page width without overlapping focus
 * frames.
 */
@Composable
@ReadOnlyComposable
fun tvCardPosterScale(): Float = LocalCardPresentation.current.posterSize.posterScale

/** A base card dimension scaled by the active poster-size preference. */
@Composable
@ReadOnlyComposable
fun Dp.cardScaled(): Dp = this * tvCardPosterScale()

/**
 * Fixed-column grid count under the active poster size: compact adds a
 * column, large removes one — floored so a grid never drops below a
 * browsable width.
 */
@Composable
@ReadOnlyComposable
fun tvPresetGridColumns(base: Int): Int = when (LocalCardPresentation.current.posterSize) {
    CardPosterSize.Compact -> base + 1
    CardPosterSize.Standard -> base
    CardPosterSize.Large -> (base - 1).coerceAtLeast(TvGridMinColumns)
}

private const val TvGridMinColumns = 3
