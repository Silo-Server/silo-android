package org.siloserver.silo.common.overlays

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.siloserver.silo.overlays.CardOverlayPrefs
import org.siloserver.silo.overlays.OverlayData
import org.siloserver.silo.overlays.OverlayPosition
import org.siloserver.silo.overlays.OverlayRegistry

/**
 * Card layout variant — controls the corner insets so wide/hero cards
 * leave headroom for a title block or progress bar. Mirrors Apple's
 * `CardOverlays.Variant`.
 */
enum class CardOverlayVariant {
    /** Standard 2:3 poster card. */
    Poster,

    /** Backdrop card (continue watching, hero strip) — extra bottom room. */
    Wide,

    /** Large backdrop (detail-page hero, featured carousel). */
    Hero,
}

/**
 * Renders all enabled overlay badges for an item, grouped into the four
 * corner stacks defined by the user's prefs. Designed to fill a card's
 * existing poster [Box] as an overlay layer (over the image, under any
 * focus chrome) — it lays out edge-to-edge and never intercepts touches.
 *
 * Presentation-only: the caller decides whether to show it (gate on
 * `store.enabled`) and supplies [data] + [prefs]. This composable does
 * not fetch anything.
 *
 * Android port of Apple's `CardOverlays`
 * (iosApp/Overlays/CardOverlays.swift).
 *
 * Usage:
 * ```
 * Box {
 *     PosterImage(...)
 *     CardOverlays(data = data, prefs = prefs, variant = CardOverlayVariant.Poster)
 * }
 * ```
 *
 * @param scale optical multiplier for wide/hero cards; posters measure their actual width.
 * @param bottomInset overrides the variant's bottom corner inset (unscaled)
 *   for cards that draw nothing but a thin progress bar along the bottom edge.
 */
@Composable
fun CardOverlays(
    data: OverlayData,
    prefs: CardOverlayPrefs,
    modifier: Modifier = Modifier,
    variant: CardOverlayVariant = CardOverlayVariant.Poster,
    scale: Float = 1f,
    forceOpaqueBackground: Boolean = false,
    bottomInset: Dp? = null,
) {
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        // The web Home carousel's 185-unit overlay layer is the cross-platform
        // visual reference. Reading the actual logical width also covers
        // adaptive grids, phone density choices, TV rails, and fill-width cards.
        val resolvedScale = if (variant == CardOverlayVariant.Poster) {
            maxWidth.value
                .takeIf { it.isFinite() && it > 0f }
                ?.div(185f)
                ?: scale
        } else {
            scale
        }
        val preset = remember(prefs.preset, resolvedScale) {
            OverlayPresetStyles.style(prefs.preset).scaled(resolvedScale)
        }
        for (position in OverlayPosition.entries) {
            CornerStack(
                position = position,
                data = data,
                prefs = prefs,
                preset = preset,
                variant = variant,
                scale = resolvedScale,
                forceOpaqueBackground = forceOpaqueBackground,
                bottomInset = bottomInset,
            )
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.BoxScope.CornerStack(
    position: OverlayPosition,
    data: OverlayData,
    prefs: CardOverlayPrefs,
    preset: OverlayPresetStyle,
    variant: CardOverlayVariant,
    scale: Float,
    forceOpaqueBackground: Boolean,
    bottomInset: Dp?,
) {
    // Resolved once per (item, prefs, preset): this runs for four corners of
    // every card on every card composition, and rails recompose a lot.
    val badges = remember(position, data, prefs, preset) {
        OverlayRegistry.enabled(position, prefs)
            .mapNotNull { OverlayBadgeRenderState.resolve(it, data, prefs, preset) }
    }
    if (badges.isEmpty()) return

    Column(
        modifier = Modifier
            .align(anchor(position))
            .padding(insets(position, variant, scale, bottomInset)),
        horizontalAlignment = horizontalAlignment(position),
        verticalArrangement = Arrangement.spacedBy(preset.gap),
    ) {
        for (state in badges) {
            OverlayBadge(
                state = state,
                preset = preset,
                forceOpaqueBackground = forceOpaqueBackground,
            )
        }
    }
}

private fun anchor(position: OverlayPosition): Alignment =
    when (position) {
        OverlayPosition.TopLeft -> Alignment.TopStart
        OverlayPosition.TopRight -> Alignment.TopEnd
        OverlayPosition.BottomLeft -> Alignment.BottomStart
        OverlayPosition.BottomRight -> Alignment.BottomEnd
    }

private fun horizontalAlignment(position: OverlayPosition): Alignment.Horizontal =
    when (position) {
        OverlayPosition.TopLeft, OverlayPosition.BottomLeft -> Alignment.Start
        OverlayPosition.TopRight, OverlayPosition.BottomRight -> Alignment.End
    }

/**
 * Per-corner insets. `Wide`/`Hero` leave more bottom room because a title
 * block / progress bar typically sits under the image. Mirrors Apple's
 * `insets(for:)`.
 */
private fun insets(
    position: OverlayPosition,
    variant: CardOverlayVariant,
    scale: Float,
    bottomInsetOverride: Dp?,
): PaddingValues {
    val safeScale = scale.coerceAtLeast(0.1f)
    val bottomInset: Dp = (
        bottomInsetOverride ?: when (variant) {
            CardOverlayVariant.Poster -> 8.dp
            CardOverlayVariant.Wide -> 24.dp
            CardOverlayVariant.Hero -> 16.dp
        }
    ) * safeScale
    val sideInset: Dp = (if (variant == CardOverlayVariant.Hero) 16.dp else 8.dp) * safeScale
    val topInset: Dp = (if (variant == CardOverlayVariant.Hero) 16.dp else 8.dp) * safeScale
    return when (position) {
        OverlayPosition.TopLeft ->
            PaddingValues(top = topInset, start = sideInset)
        OverlayPosition.TopRight ->
            PaddingValues(top = topInset, end = sideInset)
        OverlayPosition.BottomLeft ->
            PaddingValues(bottom = bottomInset, start = sideInset)
        OverlayPosition.BottomRight ->
            PaddingValues(bottom = bottomInset, end = sideInset)
    }
}
