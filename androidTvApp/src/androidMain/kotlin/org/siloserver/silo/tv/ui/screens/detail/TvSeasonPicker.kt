package org.siloserver.silo.tv.ui.screens.detail

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import org.siloserver.silo.model.catalog.Season
import org.siloserver.silo.model.catalog.isSpecialsForDisplay
import org.siloserver.silo.tv.ui.theme.TvControlCorner

/**
 * Horizontal scroll of season chips. Mirrors `TVSeasonChipRow` on tvOS:
 * selected reads as a filled white pill, focused fades in a translucent fill,
 * idle is outline only. Auto-centers the selected chip when it changes.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun TvSeasonPicker(
    seasons: List<Season>,
    selectedSeason: Int?,
    onSeasonSelected: (Season) -> Unit,
    modifier: Modifier = Modifier,
    horizontalContentPadding: Dp = 0.dp,
    onDirectionUp: (() -> Boolean)? = null,
) {
    if (seasons.isEmpty()) return
    val listState = rememberLazyListState()
    val selectedFocusRequester = remember { FocusRequester() }
    val selectedIndex = remember(selectedSeason, seasons) {
        seasons.indexOfFirst { it.seasonNumber == selectedSeason }.takeIf { it >= 0 }
    }
    // Center the selected chip (Apple uses scrollTo(anchor: .center), not a
    // start-aligned scroll). Bring it into view, then nudge by the delta
    // between the item center and the viewport center.
    LaunchedEffect(selectedIndex) {
        val idx = selectedIndex ?: return@LaunchedEffect
        listState.scrollToItem(idx)
        val info = listState.layoutInfo
        val item = info.visibleItemsInfo.firstOrNull { it.index == idx } ?: return@LaunchedEffect
        val viewportCenter = (info.viewportStartOffset + info.viewportEndOffset) / 2f
        val itemCenter = item.offset + item.size / 2f
        listState.animateScrollBy(itemCenter - viewportCenter)
    }
    LazyRow(
        modifier = modifier
            .focusProperties {
                selectedIndex?.let {
                    enter = { selectedFocusRequester }
                }
            }
            .then(
                if (onDirectionUp != null) {
                    Modifier.onPreviewKeyEvent { event ->
                        if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionUp) {
                            onDirectionUp()
                        } else {
                            false
                        }
                    }
                } else {
                    Modifier
                },
            )
            .focusGroup(),
        state = listState,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
        // Horizontal inset lives inside the scroll viewport (contentPadding),
        // not on the row, so the leftmost chip's focus scale isn't clipped
        // at the row's left edge.
        contentPadding = PaddingValues(horizontal = horizontalContentPadding, vertical = 6.dp),
    ) {
        items(
            seasons,
            key = { "season-${it.seasonNumber}-${it.contentId}" },
            contentType = { "season-chip" },
        ) { season ->
            TvSeasonChip(
                season = season,
                isSelected = season.seasonNumber == selectedSeason,
                onClick = { onSeasonSelected(season) },
                modifier = if (season.seasonNumber == selectedSeason) {
                    Modifier.focusRequester(selectedFocusRequester)
                } else {
                    Modifier
                },
            )
        }
    }
}

/**
 * Combined Series mode row from the approved tvOS page: Show first, followed
 * by every season. These are true capsules with a fixed footprint and no focus
 * scale, so lateral movement never shifts its neighbours.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun TvSeriesModePicker(
    seasons: List<Season>,
    isShowingSeriesOverview: Boolean,
    selectedSeason: Int?,
    onShowSelected: () -> Unit,
    onSeasonSelected: (Season) -> Unit,
    modifier: Modifier = Modifier,
    horizontalContentPadding: Dp = 0.dp,
    onDirectionUp: (() -> Boolean)? = null,
) {
    val listState = rememberLazyListState()
    val selectedFocusRequester = remember { FocusRequester() }
    val selectedIndex = remember(isShowingSeriesOverview, selectedSeason, seasons) {
        if (isShowingSeriesOverview) {
            0
        } else {
            seasons.indexOfFirst { it.seasonNumber == selectedSeason }
                .takeIf { it >= 0 }
                ?.plus(1)
                ?: 0
        }
    }

    LaunchedEffect(selectedIndex, seasons.size) {
        listState.scrollToItem(selectedIndex)
        val info = listState.layoutInfo
        val item = info.visibleItemsInfo.firstOrNull { it.index == selectedIndex }
            ?: return@LaunchedEffect
        val viewportCenter = (info.viewportStartOffset + info.viewportEndOffset) / 2f
        val itemCenter = item.offset + item.size / 2f
        listState.animateScrollBy(itemCenter - viewportCenter)
    }

    LazyRow(
        modifier = modifier
            .focusProperties { enter = { selectedFocusRequester } }
            .then(
                if (onDirectionUp != null) {
                    Modifier.onPreviewKeyEvent { event ->
                        if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionUp) {
                            onDirectionUp()
                        } else {
                            false
                        }
                    }
                } else {
                    Modifier
                },
            )
            .focusGroup(),
        state = listState,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        contentPadding = PaddingValues(horizontal = horizontalContentPadding, vertical = 2.dp),
    ) {
        item(key = "series-show-overview", contentType = "series-mode") {
            TvSeriesModeTab(
                title = "Show",
                isSelected = isShowingSeriesOverview,
                onActivated = onShowSelected,
                modifier = if (isShowingSeriesOverview) {
                    Modifier.focusRequester(selectedFocusRequester)
                } else {
                    Modifier
                },
            )
        }
        items(
            seasons,
            key = { "series-season-${it.seasonNumber}-${it.contentId}" },
            contentType = { "series-mode" },
        ) { season ->
            val selected = !isShowingSeriesOverview && season.seasonNumber == selectedSeason
            TvSeriesModeTab(
                title = tvSeasonPickerLabel(season),
                isSelected = selected,
                onActivated = { onSeasonSelected(season) },
                modifier = if (selected) {
                    Modifier.focusRequester(selectedFocusRequester)
                } else {
                    Modifier
                },
            )
        }
    }
}

@Composable
private fun TvSeriesModeTab(
    title: String,
    isSelected: Boolean,
    onActivated: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val isPressed by interactionSource.collectIsPressedAsState()
    val fill by animateColorAsState(
        targetValue = when {
            isFocused -> Color.White
            isSelected -> Color.White.copy(alpha = 0.20f)
            else -> Color.White.copy(alpha = 0.05f)
        },
        animationSpec = spring(dampingRatio = 0.78f, stiffness = 420f),
        label = "seriesModeFill",
    )
    val labelColor by animateColorAsState(
        targetValue = if (isFocused) Color.Black else Color.White,
        animationSpec = spring(dampingRatio = 0.78f, stiffness = 420f),
        label = "seriesModeLabel",
    )
    val borderWidth = when {
        isFocused -> 0.75.dp
        isSelected -> 1.dp
        else -> 0.75.dp
    }
    val borderColor = if (isFocused) {
        Color.Black.copy(alpha = 0.12f)
    } else {
        Color.White.copy(alpha = if (isSelected) 0.70f else 0.30f)
    }

    Box(
        modifier = modifier
            .graphicsLayer {
                val pressScale = if (isPressed) 0.98f else 1f
                scaleX = pressScale
                scaleY = pressScale
            }
            .shadow(
                elevation = if (isFocused) 6.dp else 0.dp,
                shape = CircleShape,
                clip = false,
                ambientColor = Color.White.copy(alpha = if (isFocused) 0.08f else 0f),
                spotColor = Color.White.copy(alpha = if (isFocused) 0.08f else 0f),
            )
            .height(26.dp)
            // Same outward white focus outline as Play and the selector
            // controls, without changing this row's stable measurements.
            .seriesModeFocusRing(visible = isFocused)
            .background(fill, CircleShape)
            .border(borderWidth, borderColor, CircleShape)
            // tvOS treats Show and each season as focus-driven modes: moving
            // laterally updates the page immediately, without an extra Select.
            // The ViewModel ignores the already-selected season and generation-
            // guards quick successive loads while the existing selectedIndex
            // effect keeps the newly focused tab centered.
            .onFocusChanged { focusState ->
                if (focusState.isFocused && !isSelected) onActivated()
            }
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onActivated,
            )
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium.copy(
                fontSize = 14.sp,
                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
            ),
            color = labelColor,
            maxLines = 1,
        )
    }
}

private fun Modifier.seriesModeFocusRing(visible: Boolean): Modifier = drawWithContent {
    drawContent()
    if (!visible) return@drawWithContent
    val inset = 1.5.dp.toPx()
    val stroke = 1.25.dp.toPx()
    drawRoundRect(
        color = Color.White.copy(alpha = 0.98f),
        topLeft = Offset(-inset, -inset),
        size = Size(size.width + inset * 2f, size.height + inset * 2f),
        cornerRadius = CornerRadius((size.height + inset * 2f) / 2f),
        style = Stroke(width = stroke),
    )
}

/**
 * Squared season chip — mirrors `TVSeasonChip` on tvOS. Owns its own focus
 * visuals (no Surface halo). 22sp label; idle transparent + white@0.25 1.5dp
 * outline; focus white@0.18 fill + scale 1.04; selected white fill / black label.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun TvSeasonChip(
    season: Season,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    var isFocused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(TvControlCorner)

    val fill by animateColorAsState(
        targetValue = when {
            isSelected -> Color.White
            isFocused -> Color.White.copy(alpha = 0.18f)
            else -> Color.Transparent
        },
        animationSpec = spring(dampingRatio = 0.72f, stiffness = 380f),
        label = "chipFill",
    )
    val labelColor by animateColorAsState(
        targetValue = if (isSelected) Color.Black else Color.White,
        animationSpec = spring(dampingRatio = 0.72f, stiffness = 380f),
        label = "chipLabel",
    )
    val borderWidth = if (!isSelected && !isFocused) 1.5.dp else 0.dp
    val borderColor = if (!isSelected && !isFocused) Color.White.copy(alpha = 0.25f) else Color.Transparent
    val scale by animateFloatAsState(
        // Apple: focused 1.04, pressed base * 0.97.
        targetValue = (if (isFocused) 1.04f else 1f) * (if (isPressed) 0.97f else 1f),
        animationSpec = spring(dampingRatio = 0.72f, stiffness = 380f),
        label = "chipScale",
    )

    Box(
        modifier = modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .background(fill, shape)
            .border(borderWidth, borderColor, shape)
            .onFocusChanged { isFocused = it.isFocused }
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 13.dp, vertical = 7.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = tvSeasonPickerLabel(season),
            style = MaterialTheme.typography.titleLarge.copy(
                fontSize = 16.sp,
                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
            ),
            color = labelColor,
        )
    }
}

internal fun tvSeasonPickerLabel(season: Season): String {
    if (season.isSpecialsForDisplay()) return "Specials"
    return season.title?.takeIf { it.isNotBlank() } ?: "Season ${season.seasonNumber}"
}
