package org.siloserver.silo.common.overlays

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.platform.LocalConfiguration
import org.siloserver.silo.common.cards.LocalCardPresentation
import org.siloserver.silo.model.settings.CardPosterSize
import org.siloserver.silo.overlays.POSTER_REFERENCE_WIDTH

/**
 * How large a card's overlay layer draws: every badge length and the
 * watch-status corner are their reference lengths times this.
 *
 * It is the card's scale times the viewer's text size, so badges grow with
 * the card and with a larger system font the way the card's title does. The
 * same rule as Apple's `OverlayScale` (silo-apple `CardOverlays.swift`).
 */
object OverlayScale {
    /**
     * Badges grow with text size up to this much; past it they would cover
     * a small poster. Smaller-than-default text never shrinks them.
     */
    const val MAX_TEXT_FACTOR: Float = 1.35f

    fun value(cardScale: Float, fontScale: Float): Float = cardScale * textFactor(fontScale)

    /**
     * A poster's lengths are measured on the web's 185-unit card and scale
     * with its width. A wide card's are drawn at their reference size on a
     * standard-size card and follow the poster-size setting from there. A
     * hero has no card-size setting.
     */
    fun cardScale(variant: CardOverlayVariant, cardWidthDp: Float, posterSize: CardPosterSize): Float =
        when (variant) {
            CardOverlayVariant.Poster -> cardWidthDp / POSTER_REFERENCE_WIDTH.toFloat()
            CardOverlayVariant.Wide -> posterSize.posterScale
            CardOverlayVariant.Hero -> 1f
        }

    /**
     * How much text has grown from the default size, held to
     * `1..MAX_TEXT_FACTOR`. [fontScale] is the system font scale
     * (`Configuration.fontScale`), on phone and TV alike.
     */
    fun textFactor(fontScale: Float): Float =
        if (fontScale.isNaN()) 1f else fontScale.coerceIn(1f, MAX_TEXT_FACTOR)
}

/**
 * The text factor for the system font scale. Read from the configuration
 * rather than [androidx.compose.ui.platform.LocalDensity], which the TV theme
 * recalibrates for its own type scale.
 */
@Composable
@ReadOnlyComposable
internal fun overlayTextFactor(): Float = OverlayScale.textFactor(LocalConfiguration.current.fontScale)

/**
 * A wide card's [OverlayScale]: the poster-size multiplier times the text
 * factor. Wide cards that draw their own watched check (episode thumbnails)
 * scale it by this, so the check matches the badges.
 */
@Composable
@ReadOnlyComposable
fun wideCardOverlayScale(): Float = OverlayScale.value(
    OverlayScale.cardScale(CardOverlayVariant.Wide, cardWidthDp = 0f, LocalCardPresentation.current.posterSize),
    LocalConfiguration.current.fontScale,
)
