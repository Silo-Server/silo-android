package com.continuum.app.tv.ui.components

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import com.continuum.app.model.catalog.ItemDetail
import com.continuum.app.model.section.ResolvedSection
import com.continuum.app.model.section.SectionItem
import com.continuum.app.network.ApiResult
import com.continuum.app.repository.CatalogRepository
import com.continuum.app.tv.ui.theme.RowDimens
import com.continuum.app.tv.ui.theme.Spacing
import com.continuum.app.tv.ui.theme.TvSmoothBringIntoViewSpec
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/**
 * Android TV port of tvOS `TVSkylineSectionFeed`: shared by Home and library
 * Recommended so their lower row band, focus marquee, and ambient backdrop
 * stay pixel-aligned.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TvSkylineSectionFeed(
    sections: List<ResolvedSection>,
    onItemClick: (String) -> Unit,
    modifier: Modifier = Modifier,
    focusRequest: Int = 0,
    onInitialContentFocus: () -> Unit = {},
    iconForSection: (ResolvedSection) -> ImageVector? = { null },
    onSeeAllClickForSection: (ResolvedSection) -> (() -> Unit)? = { null },
    showProgressForSection: (ResolvedSection) -> Boolean = { it.isTvProgressRow() },
    styleForSection: (ResolvedSection) -> TvRowStyle = {
        if (it.isTvProgressRow()) TvRowStyle.Backdrop else TvRowStyle.Poster
    },
    cardActions: (ResolvedSection, SectionItem) -> TvMediaCardActions = { _, _ -> TvMediaCardActions() },
    onContentUpFallbackChanged: (((() -> Boolean)?) -> Unit)? = null,
) {
    val rows = remember(sections) { sections.filter { it.items.isNotEmpty() } }
    val tintState = rememberAmbientBackdropTintState()

    val catalogRepository: CatalogRepository = koinInject()
    val fetchDetail: suspend (String) -> ItemDetail? = remember(catalogRepository) {
        { contentId ->
            (catalogRepository.getItemDetail(contentId) as? ApiResult.Success)?.data
        }
    }
    val marquee = rememberTvFocusMarqueeState(fetchDetail = fetchDetail)
    val initialMarqueeSeed = remember(rows) {
        rows.firstOrNull()?.let { section ->
            section.items.firstOrNull()?.let { item ->
                TvSkylineMarqueeSeed(item = item, rowTitle = section.title)
            }
        }
    }

    LaunchedEffect(initialMarqueeSeed?.item?.contentId, initialMarqueeSeed?.rowTitle) {
        val seed = initialMarqueeSeed ?: return@LaunchedEffect
        if (marquee.content == null) {
            marquee.seedInitialPreview(seed.item, seed.rowTitle)
        }
    }

    val rowBandState = rememberLazyListState()
    var focusedRowIndex by remember(rows) { mutableIntStateOf(-1) }
    val focusManager = LocalFocusManager.current
    val rowBandScope = rememberCoroutineScope()
    // Skyline matches tvOS' view-aligned row stack: vertical motion is owned by
    // this feed, while each row's LazyRow still handles horizontal card scroll.
    val onItemFocused: (SectionItem, String, Int) -> Unit = { item, rowTitle, rowIndex ->
        marquee.preview(item, rowTitle)
        focusedRowIndex = rowIndex
    }

    LaunchedEffect(focusedRowIndex, rows.size) {
        if (focusedRowIndex in rows.indices) {
            rowBandState.animateScrollToItem(focusedRowIndex)
        }
    }

    val currentContentUpFallback = rememberUpdatedState<() -> Boolean> {
        val currentRow = focusedRowIndex
        when {
            currentRow <= 0 || currentRow !in rows.indices ->
                // Top row (or unfocused): report not-handled so the shell hands
                // focus to the menu bar.
                false
            // Previous row is already laid out: move immediately so the returned
            // value is HONEST — the old code launched the move asynchronously and
            // returned `true` before it ran, so a failed move stranded focus
            // (neither moved up nor escalated to the menu).
            focusManager.moveFocus(FocusDirection.Up) -> true
            else -> {
                // Previous row is scrolled off; bring it on-screen first, then
                // move once the scroll settles (animateScrollToItem suspends until
                // it does, so the row is laid out before moveFocus).
                rowBandScope.launch {
                    rowBandState.animateScrollToItem(currentRow - 1)
                    withFrameNanos { }
                    focusManager.moveFocus(FocusDirection.Up)
                }
                true
            }
        }
    }

    DisposableEffect(onContentUpFallbackChanged) {
        onContentUpFallbackChanged?.invoke { currentContentUpFallback.value() }
        onDispose { onContentUpFallbackChanged?.invoke(null) }
    }

    LaunchedEffect(marquee.content?.heroBackdropUrl) {
        marquee.content?.let { tintState.set(it.source, it.heroBackdropUrl) }
    }

    val firstRowFocusRequester = remember { FocusRequester() }
    var initialFocusRequested by remember { mutableStateOf(false) }
    var firstRowFocusRequest by remember { mutableIntStateOf(0) }

    val firstRowId = rows.firstOrNull()?.id
    fun requestFirstRowFocus(): Boolean {
        if (firstRowId == null) return false
        firstRowFocusRequest++
        onInitialContentFocus()
        return true
    }

    LaunchedEffect(firstRowId) {
        if (initialFocusRequested || firstRowId == null) return@LaunchedEffect
        delay(120)
        requestFirstRowFocus()
        initialFocusRequested = true
    }

    LaunchedEffect(focusRequest, firstRowId) {
        if (focusRequest == 0 || firstRowId == null) return@LaunchedEffect
        requestFirstRowFocus()
    }

    CompositionLocalProvider(
        LocalAmbientBackdropTint provides tintState,
        LocalBringIntoViewSpec provides TvSkylineBringIntoViewSpec,
    ) {
        BoxWithConstraints(
            modifier = modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
        ) {
            TvRootHeroBackdrop(
                content = marquee.content,
                modifier = Modifier.fillMaxSize(),
            )

            val bandHeight = maxHeight * TvSkylineRowBandHeightFraction
            val trailingPreviewPadding = (bandHeight - TvSkylineRowBandBottomInset).coerceAtLeast(0.dp)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(bandHeight)
                    .align(Alignment.BottomStart)
                    .clipToBounds(),
            ) {
                LazyColumn(
                    state = rowBandState,
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(TvSkylineRowPreviewSpacing),
                    contentPadding = PaddingValues(
                        top = 0.dp,
                        bottom = trailingPreviewPadding,
                    ),
                ) {
                    itemsIndexed(
                        items = rows,
                        key = { _, row -> row.id },
                        contentType = { _, _ -> "skyline-section-row" },
                    ) { rowIndex, section ->
                        val isFirstRow = section.id == firstRowId
                        val showProgress = showProgressForSection(section)
                        TvMediaRow(
                            title = section.title,
                            items = section.items,
                            onItemClick = onItemClick,
                            icon = iconForSection(section),
                            onSeeAllClick = onSeeAllClickForSection(section),
                            showProgress = showProgress,
                            style = styleForSection(section),
                            startPadding = Spacing.safeArea,
                            endPadding = Spacing.safeArea,
                            itemSpacing = TvSkylineItemSpacing,
                            rowTopPadding = TvSkylineRowCardVerticalPadding,
                            rowBottomPadding = TvSkylineRowCardVerticalPadding,
                            posterWidth = RowDimens.DensePosterWidth,
                            firstItemFocusRequester = firstRowFocusRequester
                                .takeIf { isFirstRow },
                            firstItemFocusRequest = if (isFirstRow) firstRowFocusRequest else 0,
                            onItemFocused = { item -> onItemFocused(item, section.title, rowIndex) },
                            cardActions = { item -> cardActions(section, item) },
                        )
                    }
                }
            }

            TvFocusMarquee(
                content = marquee.content,
                startPadding = Spacing.safeArea,
                bottomPadding = bandHeight + TvSkylineMarqueeBottomGap,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

fun ResolvedSection.isTvProgressRow(): Boolean {
    val type = sectionType.lowercase()
    return type.contains("continue") ||
        type.contains("in_progress") ||
        type.contains("next_up") ||
        type.contains("up_next")
}

private data class TvSkylineMarqueeSeed(
    val item: SectionItem,
    val rowTitle: String,
)

/** tvOS MediaRow cardSpacing 40pt maps to 20dp. */
private val TvSkylineItemSpacing = 20.dp

/** tvOS rowBandPreviewSpacing 10pt maps to 5dp. */
private val TvSkylineRowPreviewSpacing = 5.dp

/** tvOS rowBandCardVerticalPadding 14pt maps to 7dp. */
private val TvSkylineRowCardVerticalPadding = 7.dp

/** tvOS rowBandBottomInset 20pt maps to 10dp. */
private val TvSkylineRowBandBottomInset = 10.dp

/** Portion of the screen reserved for the row stack. */
private const val TvSkylineRowBandHeightFraction = 0.50f

/** Gap between the marquee block and the top of the row band. */
private val TvSkylineMarqueeBottomGap = 16.dp

// Row-band relocation requests are close to row-sized; horizontal card rails
// have much wider viewports and must still use the smooth scroll distance.
private const val TvSkylineVerticalContainerRatio = 3f

private val TvSkylineBringIntoViewSpec: BringIntoViewSpec = object : BringIntoViewSpec {
    override val scrollAnimationSpec: AnimationSpec<Float> = tween(
        durationMillis = 520,
        easing = FastOutSlowInEasing,
    )

    override fun calculateScrollDistance(
        offset: Float,
        size: Float,
        containerSize: Float,
    ): Float {
        val isVerticalRowBand = size > 0f && containerSize <= size * TvSkylineVerticalContainerRatio
        return if (isVerticalRowBand) {
            0f
        } else {
            TvSmoothBringIntoViewSpec.calculateScrollDistance(offset, size, containerSize)
        }
    }
}
