package com.continuum.app.android.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import com.continuum.app.common.ui.components.ThumbhashImage

/**
 * Page-level tinted gradient sampled from the active hero's dominant color.
 * Mirrors iOS `HomeView.heroTintBackground`.
 * Sits behind the scrollable content so the tint extends past the hero
 * region without leaving a seam where the artwork ends.
 */
@Composable
internal fun HeroTintBackground(tint: Color) {
    val background = MaterialTheme.colorScheme.background
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    0.0f to tint,
                    0.35f to tint.copy(alpha = 0.55f),
                    0.8f to background,
                    1.0f to background,
                ),
            ),
    )
}

/**
 * Full-bleed blurred backdrop painted at the page level. Ignores the top
 * window inset so the blur reaches behind the status bar / chrome and
 * fades into the page tint via a vertical mask. No-op when [url] is null
 * or blank. Mirrors iOS `HomeView.heroBackdropImage`.
 */
@Composable
internal fun HeroBackdropImage(
    url: String?,
    thumbhash: String?,
) {
    if (url.isNullOrBlank()) return
    val config = LocalConfiguration.current
    val backdropHeight = (config.screenHeightDp.dp * 0.72f).coerceIn(420.dp, 580.dp) + 260.dp
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(backdropHeight)
            .graphicsLayer {
                compositingStrategy = CompositingStrategy.Offscreen
            }
            .drawWithContent {
                drawContent()
                drawRect(
                    brush = Brush.verticalGradient(
                        0.0f to Color.Black,
                        0.42f to Color.Black,
                        0.66f to Color.Black.copy(alpha = 0.7f),
                        0.86f to Color.Black.copy(alpha = 0.25f),
                        1.0f to Color.Transparent,
                    ),
                    blendMode = BlendMode.DstIn,
                )
            },
    ) {
        ThumbhashImage(
            url = url,
            thumbhash = thumbhash,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            // Heavily blurred + full-screen: full-res decode is wasted, so cap it.
            decodeSizePx = 360,
            modifier = Modifier
                .fillMaxSize()
                .blur(22.dp)
                .graphicsLayer { scaleX = 1.04f; scaleY = 1.04f },
        )

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.34f)),
        )

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(140.dp)
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Black.copy(alpha = 0.54f), Color.Transparent),
                    ),
                ),
        )
    }
}
