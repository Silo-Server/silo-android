package org.siloserver.silo.tv.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Shared surface chrome for every Skyline anchored menu — the cascade
 * selector + flyout (§5.3) and the Stage-5 profile dropdown (§5.8) — so they
 * read as one family. Port of tvOS `TVSkylinePanelChrome` (`TVTopMenuBar.swift`).
 *
 * An opaque vertical tint (lighter at the top, as if lit from above)
 * finished with a gradient hairline that brightens along the top lip and a
 * drop shadow, so the panel floats over the page on its own depth rather than
 * needing a page scrim to darken everything behind it.
 *
 * Opaque, not near-opaque: Compose has no `.regularMaterial` blur, and
 * without one a 92% fill let the synopsis and Continue Watching cards read
 * through the menu as a second layer of text.
 */
fun Modifier.tvSkylinePanelChrome(corner: Dp = 11.dp): Modifier {
    val shape = RoundedCornerShape(corner)
    return this
        .shadow(
            elevation = 4.dp,
            shape = shape,
            clip = false,
            ambientColor = Color.Black.copy(alpha = 0.35f),
            spotColor = Color.Black.copy(alpha = 0.35f),
        )
        .shadow(
            elevation = 20.dp,
            shape = shape,
            clip = false,
            ambientColor = Color.Black.copy(alpha = 0.55f),
            spotColor = Color.Black.copy(alpha = 0.55f),
        )
        .clip(shape)
        .background(
            Brush.verticalGradient(
                colors = listOf(
                    Color(0xFF1D1F24),
                    Color(0xFF17181B),
                ),
            ),
        )
        .border(
            width = 0.5.dp,
            brush = Brush.verticalGradient(
                colors = listOf(
                    Color.White.copy(alpha = 0.22f),
                    Color.White.copy(alpha = 0.05f),
                ),
            ),
            shape = shape,
        )
}
