package com.continuum.app.android.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.continuum.app.android.ui.theme.ContinuumSurfaceElevated

private val ShimmerHighlight = Color.White.copy(alpha = 0.06f)

/**
 * One shared shimmer phase (0..1) for a screen's skeleton placeholders. Hoist
 * once per loading screen and pass it to [Modifier.skeleton] so every box sweeps
 * off a single animation. It only runs while the skeleton is in composition
 * (i.e. during load) and is replaced by real content the moment data arrives, so
 * it is not an always-on cost.
 */
@Composable
fun rememberShimmerProgress(): Float {
    val transition = rememberInfiniteTransition(label = "skeleton")
    val progress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1100, easing = LinearEasing), RepeatMode.Restart),
        label = "shimmer",
    )
    return progress
}

/** Fills the node with the skeleton base color plus a sweeping shimmer highlight. */
fun Modifier.skeleton(
    progress: Float,
    shape: Shape = RoundedCornerShape(8.dp),
): Modifier = this
    .clip(shape)
    .drawBehind {
        drawRect(ContinuumSurfaceElevated)
        val w = size.width
        val travel = w * 2f
        val start = -w + progress * travel
        drawRect(
            brush = Brush.horizontalGradient(
                colors = listOf(Color.Transparent, ShimmerHighlight, Color.Transparent),
                startX = start,
                endX = start + w,
            ),
        )
    }

/**
 * Skeleton for a horizontal media rail: a short title bar above a row of
 * poster-shaped boxes. Mirrors [MediaRow]'s layout so swapping to real content
 * doesn't shift the page.
 */
@Composable
fun MediaRowSkeleton(
    progress: Float,
    modifier: Modifier = Modifier,
    posterWidth: Dp = 120.dp,
    posterHeight: Dp = 180.dp,
    count: Int = 5,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .height(18.dp)
                .width(150.dp)
                .skeleton(progress),
        )
        Spacer(modifier = Modifier.height(12.dp))
        Row(
            modifier = Modifier.padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            repeat(count) {
                Box(
                    modifier = Modifier
                        .width(posterWidth)
                        .height(posterHeight)
                        .skeleton(progress),
                )
            }
        }
    }
}

/**
 * Skeleton for a poster grid: fixed rows of poster-shaped boxes that fill the
 * width. Used for catalog / browse / "similar" loading states so the grid is
 * laid out from frame one and only the pixels resolve in.
 */
@Composable
fun PosterGridSkeleton(
    progress: Float,
    modifier: Modifier = Modifier,
    columns: Int = 3,
    rows: Int = 4,
    posterAspect: Float = 2f / 3.3f,
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        repeat(rows) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                repeat(columns) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .aspectRatio(posterAspect)
                            .skeleton(progress),
                    )
                }
            }
        }
    }
}

/**
 * Loading skeleton for a media detail page: a hero band, a couple of title /
 * metadata bars, and a "more like this" rail — so the page is laid out from the
 * first frame instead of a spinner over black that snaps into the full screen.
 */
@Composable
fun DetailLoadingSkeleton(modifier: Modifier = Modifier) {
    val shimmer = rememberShimmerProgress()
    Column(modifier = modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 10f)
                .skeleton(shimmer, RoundedCornerShape(0.dp)),
        )
        Spacer(modifier = Modifier.height(20.dp))
        Box(
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .fillMaxWidth(0.7f)
                .height(26.dp)
                .skeleton(shimmer),
        )
        Spacer(modifier = Modifier.height(12.dp))
        Box(
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .fillMaxWidth(0.4f)
                .height(16.dp)
                .skeleton(shimmer),
        )
        Spacer(modifier = Modifier.height(28.dp))
        MediaRowSkeleton(progress = shimmer)
    }
}

/** A vertical stack of [MediaRowSkeleton]s sharing one shimmer — for row-feed loading states. */
@Composable
fun MediaRowsSkeleton(
    modifier: Modifier = Modifier,
    rowCount: Int = 4,
) {
    val shimmer = rememberShimmerProgress()
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        repeat(rowCount) {
            MediaRowSkeleton(progress = shimmer)
        }
    }
}
