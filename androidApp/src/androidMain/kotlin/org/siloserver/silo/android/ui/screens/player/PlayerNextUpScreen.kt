package org.siloserver.silo.android.ui.screens.player

import android.graphics.Rect
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.siloserver.silo.common.player.videoViewportBounds
import org.siloserver.silo.common.ui.components.DeferImagePresentationWhileScrolling
import org.siloserver.silo.common.ui.components.ThumbhashImage

/** Episode details and controls beside the mounted player's preview pane. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PlayerNextUpScreen(
    nextEpisode: PlayerViewModel.NextEpisodeInfo?,
    onDeckItems: List<PlayerViewModel.OnDeckItem>,
    videoEnded: Boolean,
    countdownSeconds: Int?,
    countdownTotalSeconds: Int,
    autoPlayEnabled: Boolean,
    onPlayNow: () -> Unit,
    onKeepWatching: () -> Unit,
    onToggleAutoPlay: () -> Unit,
    onPlayOnDeckItem: (String) -> Unit,
    onBack: () -> Unit,
    onVideoBoundsChanged: (Rect) -> Unit,
    compactTabletop: Boolean = false,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val sideBySide = maxWidth > maxHeight && !compactTabletop
        val panelAlignment = if (sideBySide) Alignment.Start else Alignment.CenterHorizontally
        val panel: @Composable (Modifier) -> Unit = { panelModifier ->
            Column(
                modifier = panelModifier.widthIn(max = 620.dp),
                horizontalAlignment = panelAlignment,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = when {
                        nextEpisode == null -> "FINISHED"
                        videoEnded -> "PLAYING NEXT"
                        else -> "UP NEXT"
                    },
                    color = Color.White.copy(alpha = 0.52f),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 2.sp,
                )
                if (nextEpisode != null) {
                    nextEpisode.seriesTitle?.takeIf { it.isNotBlank() }?.let { title ->
                        Text(
                            text = title,
                            color = Color.White,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Text(
                        text = nextEpisode.label,
                        color = Color.White,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (nextEpisode.runtimeMinutes > 0) {
                        Text(
                            text = "${nextEpisode.runtimeMinutes} min",
                            color = Color.White.copy(alpha = 0.62f),
                            fontSize = 13.sp,
                        )
                    }
                    nextEpisode.overview?.takeIf { it.isNotBlank() }?.let { overview ->
                        Text(
                            text = overview,
                            color = Color.White.copy(alpha = 0.58f),
                            fontSize = 13.sp,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                } else {
                    Text("End of playback", color = Color.White, fontSize = 17.sp)
                }
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp, panelAlignment),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    NextUpActionButtons(
                        hasNextEpisode = nextEpisode != null,
                        videoEnded = videoEnded,
                        onPlayNow = onPlayNow,
                        onKeepWatching = onKeepWatching,
                        onBack = onBack,
                    )
                    if (countdownSeconds != null) {
                        CountdownRing(countdownSeconds, countdownTotalSeconds)
                    }
                }
                Text(
                    text = "Auto-play is ${if (autoPlayEnabled) "On" else "Off"}",
                    color = Color.White.copy(alpha = 0.54f),
                    fontSize = 12.sp,
                    modifier = Modifier.clip(CircleShape).clickable(onClick = onToggleAutoPlay)
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                )
                if (!compactTabletop && onDeckItems.isNotEmpty()) {
                    Text("On Deck", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    val onDeckState = rememberLazyListState()
                    DeferImagePresentationWhileScrolling(onDeckState) {
                        LazyRow(state = onDeckState, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            items(onDeckItems, key = { it.contentId }) { item ->
                                OnDeckCard(item = item, onClick = { onPlayOnDeckItem(item.contentId) })
                            }
                        }
                    }
                }
            }
        }
        val scrollState = rememberScrollState()
        DeferImagePresentationWhileScrolling(scrollState) {
            if (sideBySide) {
                Row(
                    modifier = Modifier.fillMaxSize().padding(24.dp),
                    horizontalArrangement = Arrangement.spacedBy(24.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    NextUpVideoPane(onVideoBoundsChanged, Modifier.weight(0.45f))
                    panel(Modifier.weight(0.55f).verticalScroll(scrollState))
                }
            } else {
                Column(
                    modifier = Modifier.fillMaxSize().verticalScroll(scrollState)
                        .padding(horizontal = 24.dp, vertical = if (compactTabletop) 12.dp else 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(20.dp),
                ) {
                    if (!compactTabletop) {
                        NextUpVideoPane(onVideoBoundsChanged, Modifier.widthIn(max = 620.dp).fillMaxWidth())
                    }
                    panel(Modifier)
                }
            }
        }
    }
}

@Composable
private fun NextUpVideoPane(onBoundsChanged: (Rect) -> Unit, modifier: Modifier) {
    Box(
        modifier = modifier.aspectRatio(16f / 9f)
            .onGloballyPositioned { onBoundsChanged(it.videoViewportBounds()) }
            .border(1.dp, Color.White.copy(alpha = 0.16f), RoundedCornerShape(8.dp)),
    )
}

@Composable
private fun NextUpActionButtons(
    hasNextEpisode: Boolean,
    videoEnded: Boolean,
    onPlayNow: () -> Unit,
    onKeepWatching: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (hasNextEpisode) {
        Button(
            onClick = onPlayNow,
            modifier = modifier,
            colors = ButtonDefaults.buttonColors(
                containerColor = Color.White,
                contentColor = Color.Black,
            ),
        ) {
            Icon(
                imageVector = Icons.Filled.PlayArrow,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text("Play Now")
        }
    }
    if (!videoEnded) {
        OutlinedButton(
            onClick = onKeepWatching,
            modifier = modifier,
        ) {
            Text("Keep Watching", color = Color.White)
        }
    }
    OutlinedButton(
        onClick = onBack,
        modifier = modifier,
    ) {
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(18.dp),
        )
        Text("Back", color = Color.White)
    }
}

@Composable
private fun OnDeckCard(
    item: PlayerViewModel.OnDeckItem,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .width(180.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(8.dp)),
        ) {
            ThumbhashImage(
                url = item.artUrl,
                thumbhash = item.artThumbhash,
                contentDescription = item.title,
                modifier = Modifier.fillMaxSize(),
            )
            item.progressFraction?.takeIf { it > 0f }?.let { fraction ->
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .height(3.dp)
                        .background(Color.White.copy(alpha = 0.25f)),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(fraction)
                            .fillMaxHeight()
                            .background(MaterialTheme.colorScheme.primary),
                    )
                }
            }
        }
        Text(
            text = item.title,
            color = Color.White,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        item.subtitle?.let { subtitle ->
            Text(
                text = subtitle,
                color = Color.White.copy(alpha = 0.8f),
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** iOS `CountdownRing`: a 44pt circular trim showing seconds remaining. */
@Composable
private fun CountdownRing(
    seconds: Int,
    totalSeconds: Int,
) {
    val fraction = if (totalSeconds > 0) {
        (seconds.toFloat() / totalSeconds.toFloat()).coerceIn(0f, 1f)
    } else {
        0f
    }
    Box(
        modifier = Modifier.size(44.dp).clip(CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val stroke = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round)
            drawCircle(
                color = Color.White.copy(alpha = 0.2f),
                style = stroke,
                radius = size.minDimension / 2f - stroke.width / 2f,
            )
            drawArc(
                color = Color.White,
                startAngle = -90f,
                sweepAngle = 360f * fraction,
                useCenter = false,
                style = stroke,
                topLeft = Offset(stroke.width / 2f, stroke.width / 2f),
                size = androidx.compose.ui.geometry.Size(
                    size.width - stroke.width,
                    size.height - stroke.width,
                ),
            )
        }
        Text(
            text = seconds.toString(),
            color = Color.White,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}
