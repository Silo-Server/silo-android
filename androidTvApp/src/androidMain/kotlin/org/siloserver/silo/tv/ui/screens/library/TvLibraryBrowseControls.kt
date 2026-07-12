package org.siloserver.silo.tv.ui.screens.library

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.outlined.Cancel
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import org.siloserver.silo.model.catalog.CatalogFiltersResponse
import org.siloserver.silo.tv.ui.components.tvSkylinePanelChrome

/**
 * Sort + Filter controls for the library Browse grid, 1:1 with tvOS
 * `TVBrowseControlRow` / `TVBrowseSortPanel` / `TVBrowseFilterPanel`
 * (`TVBrowseFilterUI.swift`): capsule pills above the grid; the sort panel
 * lists the media type's sort keys (re-picking the active key flips
 * direction); the filter panel is a two-level facet list → value list with
 * multi-select, match all/any, and reset.
 */

/** tvOS `continuumSecondaryText` (#99EDEDED). */
private val BrowseSecondaryText = Color(0x99EDEDED)

// ============================================================================
// Control row
// ============================================================================

@Composable
fun TvBrowseControlRow(
    sortLabel: String,
    sortDirection: String,
    filterCount: Int,
    onSort: () -> Unit,
    onFilter: () -> Unit,
    onClearFilters: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    // Clearing removes the Clear pill from composition; focus must hop to the
    // Filter pill first or it would snap away to the nearest surviving scope.
    val filterPillFocusRequester = remember { FocusRequester() }

    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BrowseControlPill(onClick = onSort) { foreground ->
            Icon(
                imageVector = Icons.Filled.SwapVert,
                contentDescription = null,
                tint = foreground,
                modifier = Modifier.size(16.dp),
            )
            Text(
                text = "Sort · $sortLabel",
                style = MaterialTheme.typography.labelLarge.copy(fontSize = 13.sp, lineHeight = 16.sp),
                color = foreground,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
            )
            Text(
                text = sortDirection,
                style = MaterialTheme.typography.labelLarge.copy(fontSize = 13.sp, lineHeight = 16.sp),
                color = if (foreground == Color.Black) {
                    Color.Black.copy(alpha = 0.55f)
                } else {
                    BrowseSecondaryText
                },
                fontWeight = FontWeight.Medium,
                maxLines = 1,
            )
        }

        BrowseControlPill(
            onClick = onFilter,
            active = filterCount > 0,
            modifier = Modifier.focusRequester(filterPillFocusRequester),
        ) { foreground ->
            Icon(
                imageVector = Icons.Filled.FilterList,
                contentDescription = null,
                tint = foreground,
                modifier = Modifier.size(16.dp),
            )
            Text(
                text = "Filter",
                style = MaterialTheme.typography.labelLarge.copy(fontSize = 13.sp, lineHeight = 16.sp),
                color = foreground,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
            )
            if (filterCount > 0) {
                Box(
                    modifier = Modifier
                        .background(Color.Black.copy(alpha = 0.25f), RoundedCornerShape(999.dp))
                        .padding(horizontal = 6.dp, vertical = 1.dp),
                ) {
                    Text(
                        text = filterCount.toString(),
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        fontSize = 11.sp,
                        lineHeight = 13.sp,
                        color = foreground,
                    )
                }
            }
        }

        if (filterCount > 0) {
            BrowseControlPill(
                onClick = {
                    runCatching { filterPillFocusRequester.requestFocus() }
                    onClearFilters()
                },
            ) { foreground ->
                Icon(
                    imageVector = Icons.Outlined.Cancel,
                    contentDescription = null,
                    tint = foreground,
                    modifier = Modifier.size(16.dp),
                )
                Text(
                    text = "Clear filters",
                    style = MaterialTheme.typography.labelLarge.copy(fontSize = 13.sp, lineHeight = 16.sp),
                    color = foreground,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                )
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun BrowseControlPill(
    onClick: () -> Unit,
    active: Boolean = false,
    modifier: Modifier = Modifier,
    content: @Composable (foreground: Color) -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    val container = when {
        isFocused -> Color.White.copy(alpha = 0.94f)
        active -> Color.White.copy(alpha = 0.22f)
        else -> Color.White.copy(alpha = 0.08f)
    }
    val foreground = if (isFocused) Color.Black else Color.White

    Surface(
        onClick = onClick,
        interactionSource = interactionSource,
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(999.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = container,
            contentColor = foreground,
            focusedContainerColor = Color.White.copy(alpha = 0.94f),
            focusedContentColor = Color.Black,
            pressedContainerColor = Color.White.copy(alpha = 0.94f),
            pressedContentColor = Color.Black,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.04f),
        border = ClickableSurfaceDefaults.border(
            border = Border(
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.06f)),
                shape = RoundedCornerShape(999.dp),
            ),
            focusedBorder = Border(
                border = BorderStroke(0.dp, Color.Transparent),
                shape = RoundedCornerShape(999.dp),
            ),
        ),
        modifier = modifier,
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
        ) {
            content(foreground)
        }
    }
}

// ============================================================================
// Sort panel
// ============================================================================

@Composable
fun TvBrowseSortPanel(
    options: List<TvLibrarySortOption>,
    currentSort: String,
    order: String,
    onSelect: (TvLibrarySortOption) -> Unit,
    onClose: () -> Unit,
) {
    val currentFocusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(50)
        runCatching { currentFocusRequester.requestFocus() }
    }

    BrowsePanelScrim(onClose = onClose) {
        Column(
            modifier = Modifier
                .width(260.dp)
                .tvSkylinePanelChrome()
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            BrowsePanelMonoHeader(
                text = "SORT BY",
                modifier = Modifier.padding(start = 10.dp, top = 4.dp, bottom = 6.dp),
            )
            options.forEach { option ->
                val isCurrent = option.wireValue == currentSort
                BrowsePanelRow(
                    onClick = { onSelect(option) },
                    focusRequester = currentFocusRequester.takeIf {
                        isCurrent || (options.none { o -> o.wireValue == currentSort } && option == options.first())
                    },
                ) { foreground ->
                    Icon(
                        imageVector = Icons.Filled.Check,
                        contentDescription = null,
                        tint = if (isCurrent) foreground else Color.Transparent,
                        modifier = Modifier.size(15.dp),
                    )
                    Text(
                        text = option.label,
                        style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp, lineHeight = 17.sp),
                        color = foreground,
                        maxLines = 1,
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    if (isCurrent) {
                        Text(
                            text = option.directionLabel(order),
                            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp, lineHeight = 16.sp),
                            color = if (foreground == Color.Black) {
                                Color.Black.copy(alpha = 0.55f)
                            } else {
                                BrowseSecondaryText
                            },
                            maxLines = 1,
                        )
                        Icon(
                            imageVector = if (order == "asc") Icons.Filled.ArrowUpward else Icons.Filled.ArrowDownward,
                            contentDescription = null,
                            tint = foreground,
                            modifier = Modifier.size(13.dp),
                        )
                    }
                }
            }
        }
    }
}

// ============================================================================
// Filter panel (list → values)
// ============================================================================

@Composable
fun TvBrowseFilterPanel(
    libraryType: String,
    facetOptions: CatalogFiltersResponse?,
    initial: TvCatalogFacetSelection,
    onApply: (TvCatalogFacetSelection) -> Unit,
    onClose: () -> Unit,
) {
    var draft by remember { mutableStateOf(initial) }
    // null = top-level facet list; non-null = that facet's value list.
    var openFacet by remember { mutableStateOf<TvCatalogFacet?>(null) }

    val availableFacets = remember(libraryType, facetOptions) {
        TvCatalogFacet.availableFor(libraryType)
            .filter { it.optionPairs(facetOptions).isNotEmpty() }
    }

    val commitAndClose = {
        onApply(draft)
        onClose()
    }
    // Back walks values → filters, then commits and closes (tvOS handleExit).
    val handleBack = {
        if (openFacet != null) openFacet = null else commitAndClose()
    }

    val screenFocusRequester = remember { FocusRequester() }
    LaunchedEffect(openFacet) {
        kotlinx.coroutines.delay(50)
        runCatching { screenFocusRequester.requestFocus() }
    }

    BrowsePanelScrim(onClose = handleBack, dismissOnBack = false) {
        Column(
            modifier = Modifier
                .width(360.dp)
                .height(400.dp)
                .tvSkylinePanelChrome()
                .onPreviewKeyEvent { ev ->
                    when {
                        ev.type == KeyEventType.KeyUp && (ev.key == Key.Back || ev.key == Key.Escape) -> {
                            handleBack()
                            true
                        }
                        // Left from a value list backs out to the facet list.
                        ev.type == KeyEventType.KeyDown && ev.key == Key.DirectionLeft && openFacet != null -> {
                            openFacet = null
                            true
                        }
                        else -> false
                    }
                }
                .padding(16.dp),
        ) {
            // Header
            BrowsePanelMonoHeader(text = if (openFacet == null) "FILTER BY" else "FILTER")
            Text(
                text = openFacet?.title ?: "Filters",
                style = MaterialTheme.typography.titleMedium.copy(
                    fontSize = 19.sp,
                    lineHeight = 23.sp,
                    fontWeight = FontWeight.SemiBold,
                ),
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 3.dp),
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp)
                    .height(1.dp)
                    .background(Color.White.copy(alpha = 0.12f)),
            )

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                when (val facet = openFacet) {
                    null -> FilterListScreen(
                        availableFacets = availableFacets,
                        facetOptions = facetOptions,
                        draft = draft,
                        firstRowFocusRequester = screenFocusRequester,
                        onOpenFacet = { openFacet = it },
                        onDraftChanged = { draft = it },
                        onDone = commitAndClose,
                    )
                    else -> FacetValuesScreen(
                        facet = facet,
                        facetOptions = facetOptions,
                        draft = draft,
                        backRowFocusRequester = screenFocusRequester,
                        onBack = { openFacet = null },
                        onDraftChanged = { draft = it },
                    )
                }
            }
        }
    }
}

@Composable
private fun FilterListScreen(
    availableFacets: List<TvCatalogFacet>,
    facetOptions: CatalogFiltersResponse?,
    draft: TvCatalogFacetSelection,
    firstRowFocusRequester: FocusRequester,
    onOpenFacet: (TvCatalogFacet) -> Unit,
    onDraftChanged: (TvCatalogFacetSelection) -> Unit,
    onDone: () -> Unit,
) {
    if (availableFacets.isEmpty()) {
        Text(
            text = "No filters available",
            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp, lineHeight = 17.sp),
            color = BrowseSecondaryText,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
        )
    }
    availableFacets.forEachIndexed { index, facet ->
        BrowsePanelRow(
            onClick = { onOpenFacet(facet) },
            focusRequester = firstRowFocusRequester.takeIf { index == 0 },
        ) { foreground ->
            Text(
                text = facet.title,
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp, lineHeight = 17.sp),
                color = foreground,
                maxLines = 1,
            )
            Spacer(modifier = Modifier.weight(1f))
            Text(
                text = facetSummary(facet, draft, facetOptions),
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp, lineHeight = 16.sp),
                color = foreground.copy(alpha = 0.68f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 140.dp),
            )
            Icon(
                imageVector = Icons.Filled.ChevronRight,
                contentDescription = null,
                tint = foreground.copy(alpha = 0.55f),
                modifier = Modifier.size(15.dp),
            )
        }
    }

    BrowsePanelMonoHeader(
        text = "MATCH",
        modifier = Modifier.padding(start = 12.dp, top = 14.dp, bottom = 4.dp),
    )
    MatchRow(
        title = "All selected filters",
        isSelected = draft.matchAll,
        onClick = { onDraftChanged(draft.copy(matchAll = true)) },
    )
    MatchRow(
        title = "Any selected filter",
        isSelected = !draft.matchAll,
        onClick = { onDraftChanged(draft.copy(matchAll = false)) },
    )

    BrowsePanelMonoHeader(
        text = "OPTIONS",
        modifier = Modifier.padding(start = 12.dp, top = 14.dp, bottom = 4.dp),
    )
    BrowsePanelRow(
        onClick = {
            if (draft.canReset) onDraftChanged(TvCatalogFacetSelection())
        },
        contentAlpha = if (draft.canReset) 1f else 0.45f,
    ) { foreground ->
        Icon(
            imageVector = Icons.Filled.Refresh,
            contentDescription = null,
            tint = foreground,
            modifier = Modifier.size(15.dp),
        )
        Text(
            text = "Reset filters",
            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp, lineHeight = 17.sp),
            color = foreground,
            maxLines = 1,
        )
    }
    BrowsePanelRow(onClick = onDone) { foreground ->
        Icon(
            imageVector = Icons.Filled.Check,
            contentDescription = null,
            tint = foreground,
            modifier = Modifier.size(15.dp),
        )
        Text(
            text = "Done",
            style = MaterialTheme.typography.bodyMedium.copy(
                fontSize = 14.sp,
                lineHeight = 17.sp,
                fontWeight = FontWeight.SemiBold,
            ),
            color = foreground,
            maxLines = 1,
        )
    }
}

@Composable
private fun FacetValuesScreen(
    facet: TvCatalogFacet,
    facetOptions: CatalogFiltersResponse?,
    draft: TvCatalogFacetSelection,
    backRowFocusRequester: FocusRequester,
    onBack: () -> Unit,
    onDraftChanged: (TvCatalogFacetSelection) -> Unit,
) {
    BrowsePanelRow(onClick = onBack, focusRequester = backRowFocusRequester) { foreground ->
        Icon(
            imageVector = Icons.Filled.ChevronLeft,
            contentDescription = null,
            tint = foreground,
            modifier = Modifier.size(15.dp),
        )
        Text(
            text = "All filters",
            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp, lineHeight = 17.sp),
            color = foreground,
            maxLines = 1,
        )
    }

    if (draft.selectedValues(facet).isNotEmpty()) {
        BrowsePanelRow(onClick = { onDraftChanged(draft.cleared(facet)) }) { foreground ->
            Icon(
                imageVector = Icons.Outlined.Cancel,
                contentDescription = null,
                tint = foreground,
                modifier = Modifier.size(15.dp),
            )
            Text(
                text = "Clear ${facet.title}",
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp, lineHeight = 17.sp),
                color = foreground,
                maxLines = 1,
            )
        }
    }

    facet.optionPairs(facetOptions).forEach { (value, label) ->
        val isSelected = draft.isSelected(facet, value)
        BrowsePanelRow(onClick = { onDraftChanged(draft.toggled(facet, value)) }) { foreground ->
            Icon(
                imageVector = if (isSelected) Icons.Filled.CheckCircle else Icons.Outlined.Circle,
                contentDescription = null,
                tint = foreground,
                modifier = Modifier.size(15.dp),
            )
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp, lineHeight = 17.sp),
                color = foreground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun MatchRow(
    title: String,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    BrowsePanelRow(onClick = onClick) { foreground ->
        Icon(
            imageVector = if (isSelected) Icons.Filled.CheckCircle else Icons.Outlined.Circle,
            contentDescription = null,
            tint = foreground,
            modifier = Modifier.size(15.dp),
        )
        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp, lineHeight = 17.sp),
            color = foreground,
            maxLines = 1,
        )
    }
}

private fun facetSummary(
    facet: TvCatalogFacet,
    draft: TvCatalogFacetSelection,
    facetOptions: CatalogFiltersResponse?,
): String {
    val values = draft.selectedValues(facet)
    if (values.isEmpty()) return "Any"
    if (values.size == 1) {
        val only = values.first()
        return facet.optionPairs(facetOptions).firstOrNull { it.first == only }?.second ?: only
    }
    return "${values.size} selected"
}

// ============================================================================
// Shared panel scaffolding
// ============================================================================

/**
 * Full-screen dimmed scrim hosting a centered panel, presented as a
 * window-level focusable Popup (the [org.siloserver.silo.tv.ui.screens.detail.TvMediaInfoDialog]
 * idiom) so focus is contained and the grid underneath stays inert.
 */
@Composable
private fun BrowsePanelScrim(
    onClose: () -> Unit,
    dismissOnBack: Boolean = true,
    content: @Composable () -> Unit,
) {
    Popup(
        alignment = Alignment.Center,
        onDismissRequest = onClose,
        properties = PopupProperties(focusable = true, dismissOnBackPress = dismissOnBack),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.55f)),
            contentAlignment = Alignment.Center,
        ) {
            content()
        }
    }
}

@Composable
private fun BrowsePanelMonoHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.SemiBold,
        fontSize = 11.sp,
        lineHeight = 13.sp,
        letterSpacing = 2.sp,
        color = BrowseSecondaryText,
        modifier = modifier,
    )
}

/**
 * A full-width panel list row: transparent at rest, white rounded fill with
 * black content while focused (tvOS `TVBrowsePanelRowStyle`).
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun BrowsePanelRow(
    onClick: () -> Unit,
    focusRequester: FocusRequester? = null,
    contentAlpha: Float = 1f,
    content: @Composable androidx.compose.foundation.layout.RowScope.(foreground: Color) -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val foreground = (if (isFocused) Color.Black else Color.White).copy(
        alpha = if (isFocused) 1f else contentAlpha,
    )

    Surface(
        onClick = onClick,
        interactionSource = interactionSource,
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.Transparent,
            contentColor = foreground,
            focusedContainerColor = Color.White,
            focusedContentColor = Color.Black,
            pressedContainerColor = Color.White,
            pressedContentColor = Color.Black,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        modifier = Modifier
            .fillMaxWidth()
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 9.dp),
        ) {
            content(foreground)
        }
    }
}
