package org.siloserver.silo.android.ui.screens.requests

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PriorityHigh
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.siloserver.silo.android.ui.components.MediaGridDefaults
import org.siloserver.silo.android.ui.components.skeleton
import org.siloserver.silo.android.ui.theme.SiloNavPillBorder
import org.siloserver.silo.android.ui.theme.SiloOnSurface
import org.siloserver.silo.android.ui.theme.SiloOverlayPillSurface
import org.siloserver.silo.android.ui.theme.SiloPageBackground
import org.siloserver.silo.android.ui.theme.SiloSecondaryText
import org.siloserver.silo.android.ui.theme.SiloSurfaceContainer
import org.siloserver.silo.android.ui.theme.SiloSurfaceElevated
import org.siloserver.silo.common.cards.LocalCardPresentation
import org.siloserver.silo.common.requests.RequestColors
import org.siloserver.silo.common.requests.color
import org.siloserver.silo.common.ui.components.DeferImagePresentationWhileScrolling
import org.siloserver.silo.common.ui.components.ThumbhashImage
import org.siloserver.silo.model.request.MediaRequest
import org.siloserver.silo.model.request.RequestDisplayState
import org.siloserver.silo.model.request.RequestMediaResult
import org.siloserver.silo.model.request.RequestProgress
import org.siloserver.silo.model.request.RequestStatusTint
import org.siloserver.silo.model.request.requestPosterUrl

/** Card geometry shared with the library rows ([org.siloserver.silo.android.ui.components.MediaCard]). */
internal const val RequestPosterAspect = 2f / 3.3f

@Composable
internal fun requestCardWidth(): Dp = 120.dp * LocalCardPresentation.current.posterSize.posterScale

/** Chrome fills shared by the summary cards and quiet buttons (Apple's resting/selected chrome). */
internal val RequestChromeRestingFill = Color.White.copy(alpha = 0.07f)
internal val RequestChromeRestingBorder = Color.White.copy(alpha = 0.09f)
internal val RequestChromeSelectedFill = Color.White.copy(alpha = 0.14f)

internal fun RequestStatusTint.uiColor(): Color = color(neutral = SiloSecondaryText)

/**
 * Poster card for a TMDB search/discover result, in the library card's
 * grammar. A title that's already requested or in the library gets a small
 * corner badge instead of a status caption.
 */
@Composable
fun RequestMediaCard(
    item: RequestMediaResult,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    width: Dp = requestCardWidth(),
) {
    RequestPosterCard(
        title = item.title,
        year = item.year,
        posterPath = item.posterPath,
        progress = RequestProgress.of(item.availability, item.request),
        showsStatusInCaption = false,
        onClick = onClick,
        modifier = modifier,
        width = width,
    )
}

/** Poster card for one of the user's own requests: its status is the caption. */
@Composable
fun RequestRecordCard(
    record: MediaRequest,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    width: Dp = requestCardWidth(),
) {
    RequestPosterCard(
        title = record.title,
        year = record.year,
        posterPath = record.posterPath,
        progress = RequestProgress.of(record),
        showsStatusInCaption = true,
        onClick = onClick,
        modifier = modifier,
        width = width,
    )
}

@Composable
private fun RequestPosterCard(
    title: String,
    year: Int?,
    posterPath: String?,
    progress: RequestProgress?,
    showsStatusInCaption: Boolean,
    onClick: () -> Unit,
    modifier: Modifier,
    width: Dp,
) {
    val caption = LocalCardPresentation.current.caption
    val label = listOfNotNull(title, year?.takeIf { it > 0 }?.toString(), progress?.shortLabel).joinToString(", ")
    Column(
        modifier = modifier
            .width(width)
            .clickable(onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = label },
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(RequestPosterAspect)
                .clip(RoundedCornerShape(8.dp))
                .background(SiloSurfaceElevated),
        ) {
            val url = requestPosterUrl(posterPath)
            if (url != null) {
                ThumbhashImage(
                    url = url,
                    thumbhash = null,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.matchParentSize(),
                )
            } else {
                Text(
                    text = title,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = SiloSecondaryText,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(12.dp),
                )
            }
            if (!showsStatusInCaption && progress != null) {
                RequestPosterBadge(
                    state = progress.display,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(6.dp),
                )
            }
        }
        if (caption.showsTitle) {
            Text(
                text = title,
                fontSize = 14.sp,
                lineHeight = 18.sp,
                fontWeight = FontWeight.Bold,
                color = SiloOnSurface,
                maxLines = 2,
                minLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            when {
                showsStatusInCaption && progress != null -> RequestStatusLabel(progress = progress)
                caption.showsMetadata && year != null && year > 0 -> Text(
                    text = year.toString(),
                    fontSize = 12.sp,
                    color = SiloSecondaryText,
                    maxLines = 1,
                )
            }
        }
    }
}

/**
 * Corner badge for a discovery poster whose title is already requested or in
 * the library: a white glyph on a filled status disc, in the watched badge's
 * shape.
 */
@Composable
fun RequestPosterBadge(
    state: RequestDisplayState,
    modifier: Modifier = Modifier,
    size: Dp = 20.dp,
) {
    val icon = when (state) {
        RequestDisplayState.Pending -> Icons.Filled.Schedule
        RequestDisplayState.OnTheWay -> Icons.Filled.ArrowDownward
        RequestDisplayState.InLibrary -> Icons.Filled.Check
        is RequestDisplayState.NeedsAttention -> Icons.Filled.PriorityHigh
        is RequestDisplayState.Unavailable -> return
    }
    Box(
        modifier = modifier
            .size(size)
            .shadow(4.dp, CircleShape, ambientColor = Color.Black.copy(alpha = 0.35f), spotColor = Color.Black.copy(alpha = 0.35f))
            .clip(CircleShape)
            .background(state.tint.uiColor()),
        contentAlignment = Alignment.Center,
    ) {
        Icon(imageVector = icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(size * 0.6f))
    }
}

/** Status dot and label: the caption under request cards and the line under row tracks. */
@Composable
fun RequestStatusLabel(
    progress: RequestProgress,
    modifier: Modifier = Modifier,
    text: String = progress.shortLabel,
    fontSize: TextUnit = 12.sp,
    fontWeight: FontWeight = FontWeight.Normal,
    color: Color = SiloSecondaryText,
    maxLines: Int = 1,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Box(
            modifier = Modifier
                .padding(top = (fontSize.value * 0.45f).dp)
                .size(6.dp)
                .background(progress.tint.uiColor(), CircleShape),
        )
        Text(
            text = text,
            fontSize = fontSize,
            lineHeight = (fontSize.value * 1.3f).sp,
            fontWeight = fontWeight,
            color = color,
            maxLines = maxLines,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Horizontal poster rail shared by every request surface. */
@Composable
internal fun <T> RequestCardRail(
    items: List<T>,
    key: (T) -> Any,
    card: @Composable (T) -> Unit,
) {
    val rowState = rememberLazyListState()
    DeferImagePresentationWhileScrolling(rowState) {
        LazyRow(
            state = rowState,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(horizontal = 16.dp),
        ) {
            items(items, key = key) { card(it) }
        }
    }
}

/**
 * Section header in the detail pages' editorial grammar: an optional tracked
 * eyebrow over the title, and an optional trailing link.
 */
@Composable
internal fun RequestsSectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    label: String? = null,
    trailing: String? = null,
    onTrailingClick: (() -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (!label.isNullOrBlank()) {
                Text(
                    text = label.uppercase(),
                    fontSize = 11.sp,
                    lineHeight = 14.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.6.sp,
                    color = SiloOnSurface.copy(alpha = 0.55f),
                    maxLines = 1,
                )
            }
            Text(
                text = title,
                fontSize = 22.sp,
                lineHeight = 28.sp,
                fontWeight = FontWeight.SemiBold,
                color = SiloOnSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (trailing != null && onTrailingClick != null) {
            Text(
                text = "$trailing ›",
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = SiloSecondaryText,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable(onClick = onTrailingClick)
                    .padding(horizontal = 4.dp, vertical = 4.dp),
            )
        }
    }
}

/**
 * The pinned top row of a large-title page: a back disc, the page title faded
 * in once the large title scrolls away, and optional actions. The page's own
 * first item is the large title.
 */
@Composable
internal fun RequestsLargeTitleBar(
    title: String,
    titleProgress: Float,
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier,
    actions: @Composable () -> Unit = {},
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(SiloPageBackground.copy(alpha = titleProgress))
            .statusBarsPadding()
            .height(56.dp)
            .padding(horizontal = 16.dp),
    ) {
        IconButton(
            onClick = onBackClick,
            modifier = Modifier
                .align(Alignment.CenterStart)
                .size(40.dp)
                .clip(CircleShape)
                .background(SiloOverlayPillSurface)
                .border(1.dp, SiloNavPillBorder, CircleShape),
        ) {
            Icon(imageVector = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
        }
        Text(
            text = title,
            fontSize = 17.sp,
            fontWeight = FontWeight.SemiBold,
            color = Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .align(Alignment.Center)
                .padding(horizontal = 56.dp)
                .graphicsLayer { alpha = titleProgress },
        )
        Box(modifier = Modifier.align(Alignment.CenterEnd)) { actions() }
    }
}

/** The large title a page scrolls under its pinned bar. */
@Composable
internal fun RequestsLargeTitle(title: String, modifier: Modifier = Modifier) {
    Text(
        text = title,
        fontSize = 34.sp,
        lineHeight = 40.sp,
        fontWeight = FontWeight.Bold,
        color = SiloOnSurface,
        modifier = modifier.padding(horizontal = 16.dp),
    )
}

/** The page's search field, in the library search's capsule. */
@Composable
internal fun RequestsSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    modifier: Modifier = Modifier,
) {
    TextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        placeholder = { Text("Search movies & series", color = SiloSecondaryText) },
        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = SiloSecondaryText) },
        trailingIcon = if (query.isNotEmpty()) {
            {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(Icons.Filled.Close, contentDescription = "Clear search", tint = SiloSecondaryText)
                }
            }
        } else {
            null
        },
        singleLine = true,
        shape = RoundedCornerShape(50),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { onSearch() }),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = RequestChromeSelectedFill,
            unfocusedContainerColor = RequestChromeSelectedFill,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
            cursorColor = SiloOnSurface,
            focusedTextColor = SiloOnSurface,
            unfocusedTextColor = SiloOnSurface,
        ),
    )
}

/** A glanceable card with a title, optional status parts, and a chevron. */
@Composable
internal fun RequestSummaryCard(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    parts: List<Pair<RequestStatusTint, String>> = emptyList(),
    leadingIcon: ImageVector? = null,
    leadingTint: Color = SiloOnSurface,
) {
    val shape = RoundedCornerShape(18.dp)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clip(shape)
            .background(RequestChromeRestingFill)
            .border(1.dp, RequestChromeRestingBorder, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (leadingIcon != null) {
            Icon(imageVector = leadingIcon, contentDescription = null, tint = leadingTint, modifier = Modifier.size(24.dp))
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(text = title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = SiloOnSurface)
            if (parts.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    parts.forEach { (tint, text) ->
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                            Box(modifier = Modifier.size(6.dp).background(tint.uiColor(), CircleShape))
                            Text(text = text, fontSize = 12.sp, color = SiloSecondaryText)
                        }
                    }
                }
            }
        }
        Text(text = "›", fontSize = 20.sp, color = SiloSecondaryText)
    }
}

/** Space above each grouped section on pages that pass zero item spacing. */
internal val RequestGroupGap = 24.dp

/**
 * A grouped list section: a muted header with a count over a rounded card of
 * rows. Each row is its own lazy item, so a long queue composes only what's on
 * screen; the card's corners come from the first and last rows.
 */
internal fun <T> LazyListScope.requestGroupedSection(
    key: String,
    title: String,
    items: List<T>,
    itemKey: (T) -> Any,
    row: @Composable (item: T, showDivider: Boolean) -> Unit,
) {
    if (items.isEmpty()) return
    item(key = "$key:header", contentType = "group-header") {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = RequestGroupGap)
                .padding(horizontal = 32.dp, vertical = 8.dp),
        ) {
            Text(text = title, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = SiloSecondaryText, modifier = Modifier.weight(1f))
            Text(text = items.size.toString(), fontSize = 14.sp, fontWeight = FontWeight.Medium, color = SiloSecondaryText)
        }
    }
    itemsIndexed(items, key = { _, item -> "$key:${itemKey(item)}" }, contentType = { _, _ -> "group-row" }) { index, item ->
        val corner = 20.dp
        val shape = RoundedCornerShape(
            topStart = if (index == 0) corner else 0.dp,
            topEnd = if (index == 0) corner else 0.dp,
            bottomStart = if (index == items.lastIndex) corner else 0.dp,
            bottomEnd = if (index == items.lastIndex) corner else 0.dp,
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .clip(shape)
                .background(SiloSurfaceContainer),
        ) {
            row(item, index > 0)
        }
    }
}

/** A quiet error line in the status line's slot. */
@Composable
internal fun RequestRowErrorLine(text: String, modifier: Modifier = Modifier) {
    Text(
        text = "⚠ $text",
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        color = RequestColors.Rose,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}

// ── Loading placeholders ─────────────────────────────────────

/** A rail of quiet poster placeholders in the final layout's shape. */
@Composable
internal fun RequestRailSkeleton(progress: Float, title: String? = null, cardCount: Int = 4) {
    val width = requestCardWidth()
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (title != null) {
            RequestsSectionHeader(title = title)
        } else {
            Box(modifier = Modifier.padding(horizontal = 16.dp).width(160.dp).height(22.dp).skeleton(progress))
        }
        Row(modifier = Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            repeat(cardCount) {
                Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Box(modifier = Modifier.width(width).aspectRatio(RequestPosterAspect).skeleton(progress))
                    Box(modifier = Modifier.width(width * 0.7f).height(10.dp).skeleton(progress, RoundedCornerShape(3.dp)))
                }
            }
        }
    }
}

/** Grouped-row placeholders for My Requests and Approvals. */
@Composable
internal fun RequestRowsSkeleton(progress: Float, rows: Int = 5) {
    Column(modifier = Modifier.padding(top = 8.dp)) {
        Box(modifier = Modifier.padding(horizontal = 32.dp, vertical = 8.dp).width(96.dp).height(14.dp).skeleton(progress, RoundedCornerShape(3.dp)))
        Column(
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(SiloSurfaceContainer)
                .padding(vertical = 4.dp),
        ) {
            repeat(rows) {
                Row(modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(modifier = Modifier.width(46.dp).height(69.dp).skeleton(progress, RoundedCornerShape(6.dp)))
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(modifier = Modifier.fillMaxWidth(0.6f).height(14.dp).skeleton(progress, RoundedCornerShape(3.dp)))
                        Box(modifier = Modifier.fillMaxWidth(0.4f).height(10.dp).skeleton(progress, RoundedCornerShape(3.dp)))
                        Spacer(modifier = Modifier.height(2.dp))
                        Box(modifier = Modifier.fillMaxWidth().height(4.dp).skeleton(progress, RoundedCornerShape(2.dp)))
                        Box(modifier = Modifier.fillMaxWidth(0.5f).height(10.dp).skeleton(progress, RoundedCornerShape(3.dp)))
                    }
                }
            }
        }
    }
}

/** Column count of the library search grid at [width]. */
internal fun requestGridColumns(width: Dp, minCellWidth: Dp): Int =
    ((width - 32.dp + MediaGridDefaults.PosterGridHorizontalSpacing) / (minCellWidth + MediaGridDefaults.PosterGridHorizontalSpacing))
        .toInt()
        .coerceAtLeast(2)
