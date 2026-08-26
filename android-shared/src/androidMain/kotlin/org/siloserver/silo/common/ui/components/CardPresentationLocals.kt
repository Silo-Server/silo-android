package org.siloserver.silo.common.ui.components

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import org.siloserver.silo.model.settings.CardPresentation
import org.siloserver.silo.model.settings.PosterSizePreset

/** Current family-synchronized card presentation, with safe native defaults. */
val LocalCardPresentation = staticCompositionLocalOf { CardPresentation.DEFAULT }

fun Dp.forPosterPreset(preset: PosterSizePreset): Dp = when (preset) {
    PosterSizePreset.COMPACT -> this * 0.84f
    PosterSizePreset.STANDARD -> this
    PosterSizePreset.LARGE -> this * 1.22f
}

/** Maps a fixed poster grid to an adjacent density without platform pixels. */
fun Int.forPosterPreset(preset: PosterSizePreset): Int = when (preset) {
    PosterSizePreset.COMPACT -> this + 1
    PosterSizePreset.STANDARD -> this
    PosterSizePreset.LARGE -> (this - 1).coerceAtLeast(1)
}
