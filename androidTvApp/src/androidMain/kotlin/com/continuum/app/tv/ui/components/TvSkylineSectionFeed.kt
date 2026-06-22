package com.continuum.app.tv.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.vector.ImageVector
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
    val onItemFocused: (SectionItem, String, Int) -> Unit = { item, rowTitle, _ ->
        marquee.preview(item, rowTitle)
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

    CompositionLocalProvider(LocalAmbientBackdropTint provides tintState) {
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
                // Scope the cinematic bring-into-view spec to the row band only.
                // Focused cards drive the single LazyListState scroll mutex via
                // their intrinsic bringIntoView; the spec keeps the travel smooth
                // without leaking onto the sibling hero backdrop/marquee.
                CompositionLocalProvider(
                    LocalBringIntoViewSpec provides TvSmoothBringIntoViewSpec,
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
