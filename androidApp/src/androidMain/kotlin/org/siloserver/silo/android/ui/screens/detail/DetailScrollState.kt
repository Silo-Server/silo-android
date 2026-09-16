package org.siloserver.silo.android.ui.screens.detail

import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp

/**
 * How far the detail feed has scrolled, in dp, shared between the feed that
 * measures it and the pinned header that reacts to it.
 *
 * Mirrors iOS `PhoneDetailScrollState`
 * (silo-apple `Screens/Detail/Phone/PhoneDetailHero.swift`), including its
 * plateau folding: nothing in the header changes below [HeaderIdleDp] or above
 * [HeaderSettledDp], so both ends collapse onto their endpoints and the header
 * stops recomposing while its rendered output is static.
 */
@Stable
class DetailScrollState {
    var offsetDp by mutableFloatStateOf(0f)
        private set

    /**
     * Continuous scroll distance for the hero parallax, in dp.
     *
     * Kept separate from [offsetDp]: the header folds its plateaus onto the
     * endpoints to avoid recomposing while nothing changes, but the parallax
     * has to track every pixel of the first [ParallaxRangeDp].
     */
    var parallaxDp by mutableFloatStateOf(0f)
        private set

    fun update(rawDp: Float) {
        val forParallax = rawDp.coerceIn(0f, ParallaxRangeDp)
        if (kotlin.math.abs(forParallax - parallaxDp) >= 0.5f) parallaxDp = forParallax

        val clamped = rawDp.coerceIn(0f, HeaderSettledDp)
        val normalized = if (clamped <= HeaderIdleDp) 0f else clamped
        if (kotlin.math.abs(normalized - offsetDp) < 0.5f) return
        offsetDp = normalized
    }
}

val LocalDetailScrollState = compositionLocalOf<DetailScrollState?> { null }

/** Below this the header is fully clear. */
const val HeaderIdleDp = 150f

/** Above this the header is fully settled. */
const val HeaderSettledDp = 480f

/** Background strip fades across this range. */
const val HeaderBarFadeFromDp = 200f
const val HeaderBarFadeToDp = 360f

/** The title fades in later, once the hero has mostly gone. */
const val HeaderTitleFadeFromDp = 400f
const val HeaderTitleFadeToDp = 480f

/**
 * Hero parallax, matching iOS `PhoneDetailParallaxArtwork`: the artwork trails
 * the scroll by [ParallaxFactor] over the first [ParallaxRangeDp], so the page
 * appears to move faster than the picture behind it, and a scrim reaches
 * [ParallaxScrimMaxAlpha] across [ParallaxScrimRangeDp].
 */
const val ParallaxRangeDp = 540f
const val ParallaxFactor = 0.52f
const val ParallaxScrimRangeDp = 360f
const val ParallaxScrimMaxAlpha = 0.30f

/** iOS `phoneDetailSmoothProgress`: clamped ramp with a smoothstep ease. */
fun detailHeaderProgress(value: Float, from: Float, to: Float): Float {
    val t = ((value - from) / (to - from)).coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}

/**
 * Height of the pinned strip, below the status bar. Matches the Home/tab
 * header so the two bars are the same size: a 40dp action row plus 8dp.
 */
val DetailHeaderBarHeight = 48.dp
