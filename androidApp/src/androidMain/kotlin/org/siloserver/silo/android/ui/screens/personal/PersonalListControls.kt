package org.siloserver.silo.android.ui.screens.personal

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.activity.compose.LocalActivity
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModelStoreOwner
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf
import org.siloserver.silo.android.ui.components.SortFilterControlsRow
import org.siloserver.silo.android.ui.components.SortMenuOption
import org.siloserver.silo.android.ui.screens.browse.FilterSheet
import org.siloserver.silo.catalog.filter.BrowseFacetMediaType
import org.siloserver.silo.network.ServerRegistry
import org.siloserver.silo.viewmodel.PersonalListQuery
import org.siloserver.silo.viewmodel.PersonalListViewModel

/** `/catalog?source=` values the saved lists fetch through. */
object PersonalListSource {
    const val Watchlist = "watchlist"
    const val Favorites = "favorites"
}

/**
 * The sort/filter controls for one saved list, resolved against the
 * Activity's ViewModelStore keyed by [source] and the active server/profile
 * so the For You inline grid and the standalone Watchlist / Favorites screens
 * share one selection (nav back-stack entries would otherwise each get their
 * own), while a profile or server switch starts fresh instead of inheriting
 * the previous identity's query and facet vocabulary.
 */
@Composable
fun rememberPersonalListControls(source: String): PersonalListControlsViewModel {
    val registry: ServerRegistry = koinInject()
    val serverId by registry.activeServerId.collectAsState()
    val entry by registry.activeEntry.collectAsState()
    val identity = "${serverId ?: "-"}:${entry?.profileId ?: "-"}"
    val key = "personal-controls-$source-$identity"
    val activity = LocalActivity.current as? ComponentActivity
    return if (activity != null) {
        koinViewModel(
            viewModelStoreOwner = activity as ViewModelStoreOwner,
            key = key,
            parameters = { parametersOf(source) },
        )
    } else {
        koinViewModel(key = key, parameters = { parametersOf(source) })
    }
}

/** Pushes the controls' derived query into the list ViewModel whenever it changes. */
@Composable
fun ApplyPersonalListQuery(controls: PersonalListControlsViewModel, listViewModel: PersonalListViewModel) {
    val state by controls.uiState.collectAsState()
    LaunchedEffect(state.query) { listViewModel.applyQuery(state.query) }
}

/** The controls' current query, for callers that hand it to a grid. */
@Composable
fun PersonalListControlsViewModel.queryState(): State<PersonalListQuery> {
    val state by uiState.collectAsState()
    return remember(state.query) { mutableStateOf(state.query) }
}

/**
 * Sort ▾ · Filter (n) · Reset with the item count on the trailing side — the
 * phone counterpart of the TV `PersonalControlHeader`, built on the shared
 * [SortFilterControlsRow]. Sits in the grid's spanning header so it scrolls
 * with the content and stays reachable when the list is empty.
 */
@Composable
fun PersonalListControlsRow(
    controls: PersonalListControlsViewModel,
    total: Int,
    modifier: Modifier = Modifier,
) {
    val state by controls.uiState.collectAsState()
    var showFilterSheet by remember { mutableStateOf(false) }

    SortFilterControlsRow(
        modifier = modifier,
        sortLabel = state.sort.label,
        sortActive = state.sort != PersonalListSort.ListOrder,
        sortOptions = PersonalListSort.entries.mapIndexed { index, sort ->
            SortMenuOption(
                id = sort.name,
                label = sort.label,
                selectedLabel = if (sort.hasDirection) "${sort.label}  ·  ${sort.directionLabel(state.order)}" else sort.label,
                flipsOnReselect = sort.hasDirection,
                dividerAbove = index == 1,
            )
        },
        selectedSortId = state.sort.name,
        onSelectSort = { id -> controls.selectSort(PersonalListSort.valueOf(id)) },
        filterCount = state.activeFacetCount,
        onOpenFilters = { showFilterSheet = true },
        showReset = state.isCustomised,
        onReset = controls::resetAll,
        trailing = {
            if (total > 0) {
                Text(
                    text = if (total == 1) "1 title" else "$total titles",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
    )

    if (showFilterSheet) {
        FilterSheet(
            currentFilters = state.filters,
            availableFilters = state.availableFilters,
            mediaType = BrowseFacetMediaType.Video,
            onCommit = controls::applyFilters,
            onDismiss = { showFilterSheet = false },
        )
    }
}
