package org.siloserver.silo.tv.ui.components

import android.graphics.Bitmap
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.tv.material3.MaterialTheme
import org.siloserver.silo.common.ui.components.ThumbhashImage
import org.siloserver.silo.model.section.SectionItem

/**
 * Page-level Skyline backdrop for tvOS-style root screens. Faithful Android
 * port of the reworked tvOS `TVRootHeroBackdrop` (§5.4): crisp (un-blurred)
 * artwork anchored in the top-RIGHT corner at ~0.64w × 0.70h, faded out toward
 * the leading edge and the bottom by a two-axis corner mask into a color
 * sampled from the art itself, which is carried (dimmed) diagonally
 * topEnd → bottomStart across the page so the metadata and rows sit on the same
 * tint. The shell owns the single shared top-navigation shadow so this artwork
 * does not double-darken Home relative to other root pages.
 *
 * Driven by whichever row card holds focus — pass that item's marquee
 * [content]; when null the page renders flat on the app background.
 */
@Composable
fun TvRootHeroBackdrop(
    content: TvMarqueeContent?,
    modifier: Modifier = Modifier,
    emptyWashColor: Color? = null,
    animateTransition: Boolean = true,
) {
    val tintState = LocalAmbientBackdropTint.current
    val ambientAccent = tintState.accent

    // tvOS first-frame parity: the FIRST artwork/tint to arrive snaps in with
    // no animation — a cold entry paints the finished hero instead of fading
    // it up from the black background. Only content-to-content swaps (normal
    // D-pad browsing) keep the ambient crossfade.
    var hasDisplayedArtwork by remember { mutableStateOf(false) }
    val snapInitialArtwork = content != null && !hasDisplayedArtwork
    LaunchedEffect(content != null) {
        if (content != null) hasDisplayedArtwork = true
    }
    var hasDisplayedTint by remember { mutableStateOf(false) }
    val snapInitialTint = ambientAccent != null && !hasDisplayedTint
    LaunchedEffect(ambientAccent != null) {
        if (ambientAccent != null) hasDisplayedTint = true
    }

    val targetAccent = ambientAccent ?: emptyWashColor ?: MaterialTheme.colorScheme.background
    // Kept as State and read only inside the Canvas draw lambda below: reading
    // the animating colour here would recompose this whole backdrop (and its
    // Crossfade subtree) on every frame of the 500ms tint tween.
    val animatedTint = animateColorAsState(
        targetValue = targetAccent,
        animationSpec = tween(
            durationMillis = if (snapInitialTint) 0 else TvMarqueeCrossfadeMs,
            easing = TvMarqueeEasing,
        ),
        label = "tvRootHeroBackdropTint",
    )

    val isVisible = content != null
    val hasTintOnlyWash = !isVisible && ambientAccent != null
    val hasEmptyWash = !isVisible && ambientAccent == null && emptyWashColor != null
    val leadingWashAlpha = when {
        isVisible -> 1.0f
        hasTintOnlyWash -> 0.34f
        hasEmptyWash -> 0.30f
        else -> 0.0f
    }
    val midWashAlpha = when {
        isVisible -> 0.5f
        hasTintOnlyWash -> 0.18f
        hasEmptyWash -> 0.15f
        else -> 0.0f
    }
    val trailingWashAlpha = when {
        isVisible -> 0.18f
        hasTintOnlyWash -> 0.08f
        hasEmptyWash -> 0.07f
        else -> 0.0f
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        // Diagonal sampled-tint wash: richest in the top-right behind the art,
        // carried dimmed to the bottom-left (tvOS stops 1.0 / 0.5 / 0.18).
        Canvas(modifier = Modifier.fillMaxSize()) {
            val displayedTint = if (animateTransition) animatedTint.value else targetAccent
            drawRect(
                brush = Brush.linearGradient(
                    colorStops = smoothedWashStops(
                        tint = displayedTint,
                        leadingAlpha = leadingWashAlpha,
                        midAlpha = midWashAlpha,
                        trailingAlpha = trailingWashAlpha,
                    ),
                    start = Offset(size.width, 0f),
                    end = Offset(0f, size.height),
                ),
            )
        }

        if (hasTintOnlyWash || hasEmptyWash) {
            DitheredWashOverlay(
                alpha = if (hasEmptyWash) EmptyWashDitherAlpha else TintOnlyDitherAlpha,
                modifier = Modifier.fillMaxSize(),
            )
        }

        if (animateTransition) {
            Crossfade(
                targetState = content,
                animationSpec = tween(
                    if (snapInitialArtwork) 0 else TvMarqueeCrossfadeMs,
                    easing = TvMarqueeEasing,
                ),
                label = "tvRootHeroBackdropArt",
            ) { value ->
                if (value?.heroBackdropUrl != null) {
                    CornerAnchoredArt(
                        url = value.heroBackdropUrl,
                        thumbhash = value.heroBackdropThumbhash,
                    )
                } else {
                    Box(modifier = Modifier.fillMaxSize())
                }
            }
        } else if (content?.heroBackdropUrl != null) {
            CornerAnchoredArt(
                url = content.heroBackdropUrl,
                thumbhash = content.heroBackdropThumbhash,
            )
        }

    }
}

/**
 * Crisp art block pinned to the top-right corner, masked with a two-axis ramp:
 * opaque only at the top-right, fading to clear toward the leading edge
 * (horizontal ramp) and the bottom (vertical ramp). The two ramps multiply via
 * [BlendMode.DstIn] so the art dissolves into the sampled wash on its left and
 * below.
 */
@Composable
private fun CornerAnchoredArt(
    url: String?,
    thumbhash: String?,
) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val artWidth = maxWidth * ArtWidthFraction
        val artHeight = maxHeight * ArtHeightFraction

        Box(
            modifier = Modifier
                .fillMaxSize(),
            contentAlignment = Alignment.TopEnd,
        ) {
            Box(
                modifier = Modifier
                    .size(artWidth, artHeight)
                    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                    // drawWithCache: the two mask brushes are built once per
                    // size, not on every frame of a crossfade.
                    .drawWithCache {
                        // Horizontal ramp: opaque at trailing (right) edge,
                        // clear toward the leading (left) edge.
                        val horizontalMask = Brush.horizontalGradient(
                            colorStops = arrayOf(
                                0.0f to Color.Transparent,
                                0.68f to Color.Black,
                                1.0f to Color.Black,
                            ),
                            endX = size.width,
                        )
                        // Vertical ramp: opaque at the top, clear toward bottom.
                        val verticalMask = Brush.verticalGradient(
                            colorStops = arrayOf(
                                0.0f to Color.Black,
                                0.58f to Color.Black,
                                1.0f to Color.Transparent,
                            ),
                            endY = size.height,
                        )
                        onDrawWithContent {
                            drawContent()
                            drawRect(brush = horizontalMask, blendMode = BlendMode.DstIn)
                            drawRect(brush = verticalMask, blendMode = BlendMode.DstIn)
                        }
                    },
            ) {
                ThumbhashImage(
                    url = url,
                    thumbhash = thumbhash,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    transparent = true,
                    // The Skyline feed preloads this frame and owns the one
                    // shared art + copy crossfade. A second Coil fade would
                    // make the image visibly lag behind the text.
                    crossfadeMillis = 0,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

/**
 * Backward-compatible entry point for surfaces that still drive the backdrop
 * from a raw [SectionItem] (the library Browse landing, Sweep 3). Adapts the
 * item into the Skyline [TvMarqueeContent] payload and renders the corner-
 * anchored treatment.
 */
@Composable
fun TvRootHeroBackdrop(
    item: SectionItem?,
    modifier: Modifier = Modifier,
) {
    val content = remember(item?.contentId) {
        item?.let { TvMarqueeContent.from(it, rowTitle = "") }
    }
    TvRootHeroBackdrop(content = content, modifier = modifier)
}

private const val ArtWidthFraction = 0.64f
private const val ArtHeightFraction = 0.70f
private const val TintOnlyDitherAlpha = 0.28f
private const val EmptyWashDitherAlpha = 0.34f

private fun smoothedWashStops(
    tint: Color,
    leadingAlpha: Float,
    midAlpha: Float,
    trailingAlpha: Float,
): Array<Pair<Float, Color>> = arrayOf(
    0.00f to tint.copy(alpha = leadingAlpha),
    0.12f to tint.copy(alpha = lerpAlpha(leadingAlpha, midAlpha, 0.18f)),
    0.26f to tint.copy(alpha = lerpAlpha(leadingAlpha, midAlpha, 0.48f)),
    0.45f to tint.copy(alpha = midAlpha),
    0.62f to tint.copy(alpha = lerpAlpha(midAlpha, trailingAlpha, 0.36f)),
    0.80f to tint.copy(alpha = lerpAlpha(midAlpha, trailingAlpha, 0.68f)),
    1.00f to tint.copy(alpha = trailingAlpha),
)

private fun lerpAlpha(start: Float, end: Float, fraction: Float): Float =
    start + (end - start) * fraction

@Composable
private fun DitheredWashOverlay(
    alpha: Float,
    modifier: Modifier = Modifier,
) {
    val noise = remember { createBackdropNoiseBitmap() }
    Canvas(modifier = modifier) {
        val tileWidth = noise.width.toFloat()
        val tileHeight = noise.height.toFloat()
        var y = 0f
        while (y < size.height) {
            var x = 0f
            while (x < size.width) {
                drawImage(
                    image = noise,
                    topLeft = Offset(x, y),
                    alpha = alpha,
                )
                x += tileWidth
            }
            y += tileHeight
        }
    }
}

private fun createBackdropNoiseBitmap(): ImageBitmap {
    val dimension = 96
    val bitmap = Bitmap.createBitmap(dimension, dimension, Bitmap.Config.ARGB_8888)
    val pixels = IntArray(dimension * dimension)
    var state = 0x4F1BBCDC

    for (index in pixels.indices) {
        state = state * 1664525 + 1013904223
        val sample = state ushr 24
        val pixelAlpha = when {
            sample < 16 -> 14
            sample < 42 -> 8
            else -> 0
        }
        pixels[index] = (pixelAlpha shl 24) or 0x00FFFFFF
    }

    bitmap.setPixels(pixels, 0, dimension, 0, 0, dimension, dimension)
    return bitmap.asImageBitmap()
}
