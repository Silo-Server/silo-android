package org.siloserver.silo.android.ui.screens.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.siloserver.silo.android.ui.theme.SiloNavPillBorder
import org.siloserver.silo.android.ui.theme.SiloOverlayPillSurface
import org.siloserver.silo.android.ui.theme.SiloPageBackground

/**
 * The controls floating over a detail page's artwork: a back button, an
 * optional trailing control, and a pinned strip and title that fade in as the
 * hero scrolls away. Shared by library titles and request details, so both
 * pages carry the same chrome.
 */
@Composable
internal fun DetailTopChrome(
    title: String,
    scroll: DetailScrollState,
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
) {
    Box(modifier = modifier.fillMaxSize()) {
        // The strip fades in first so the controls gain a backing as the
        // artwork leaves, then the title arrives once the hero is mostly gone —
        // the two ranges and the smoothstep are iOS's.
        val barAlpha = detailHeaderProgress(scroll.offsetDp, HeaderBarFadeFromDp, HeaderBarFadeToDp)
        val titleAlpha = detailHeaderProgress(scroll.offsetDp, HeaderTitleFadeFromDp, HeaderTitleFadeToDp)
        if (barAlpha > 0f) {
            // Runs from the very top of the window, not from below the status
            // bar: the page is edge to edge, so insetting the strip left the
            // status-bar band uncovered above it.
            val statusBarHeight = WindowInsets.statusBars
                .asPaddingValues()
                .calculateTopPadding()
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .height(statusBarHeight + DetailHeaderBarHeight)
                    .graphicsLayer { alpha = barAlpha }
                    .background(SiloPageBackground)
                    .drawBehind {
                        drawRect(
                            color = Color.White.copy(alpha = 0.10f),
                            topLeft = Offset(0f, size.height - 1f),
                            size = Size(size.width, 1f),
                        )
                    },
            )
        }
        if (titleAlpha > 0f && title.isNotBlank()) {
            Text(
                text = title,
                color = Color.White,
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .height(DetailHeaderBarHeight)
                    .fillMaxWidth()
                    // Clear of the controls on either side, and centred on
                    // them: both sit in the same strip.
                    .padding(horizontal = 72.dp)
                    .wrapContentHeight(Alignment.CenterVertically)
                    .graphicsLayer { alpha = titleAlpha },
            )
        }

        // These glyphs sit on hero artwork that can be any colour, so they keep
        // a disc — the bottom-nav pill, made translucent. A dark disc holds a
        // white glyph over a pale poster and still lets the artwork through.
        IconButton(
            onClick = onBackClick,
            modifier = Modifier
                .align(Alignment.TopStart)
                .statusBarsPadding()
                // Same geometry as the Home header's actions: a 40dp target
                // 16dp from the edge, sitting directly below the status bar.
                .padding(horizontal = 16.dp)
                .size(40.dp)
                .clip(CircleShape)
                .background(SiloOverlayPillSurface)
                .border(1.dp, SiloNavPillBorder, CircleShape),
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Back",
                tint = Color.White,
            )
        }
        if (trailing != null) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .statusBarsPadding()
                    .padding(horizontal = 16.dp),
            ) {
                trailing()
            }
        }
    }
}
