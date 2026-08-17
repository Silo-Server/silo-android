package org.siloserver.silo.android.ui.screens.personal

import androidx.activity.ComponentActivity
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModelStoreOwner
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf
import org.siloserver.silo.android.ui.screens.browse.FilterSheet
import org.siloserver.silo.catalog.filter.BrowseFacetMediaType
import org.siloserver.silo.viewmodel.PersonalListQuery
import org.siloserver.silo.viewmodel.PersonalListViewModel

/** `/catalog?source=` values the saved lists fetch through. */
object PersonalListSource {
    const val Watchlist = "watchlist"
    const val Favorites = "favorites"
}

/**
 * The sort/filter controls for one saved list, resolved against the
 * Activity's ViewModelStore keyed by [source] so the For You inline grid and
 * the standalone Watchlist / Favorites screens share one selection (nav
 * back-stack entries would otherwise each get their own).
 */
@Composable
fun rememberPersonalListControls(source: String): PersonalListControlsViewModel {
    val activity = LocalContext.current as? ComponentActivity
    return if (activity != null) {
        koinViewModel(
            viewModelStoreOwner = activity as ViewModelStoreOwner,
            key = "personal-controls-$source",
            parameters = { parametersOf(source) },
        )
    } else {
        koinViewModel(key = "personal-controls-$source", parameters = { parametersOf(source) })
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
 * Sort ▾ · Filter (n) pills with the item count on the trailing side — the
 * phone counterpart of the TV `PersonalControlHeader`. Sits in the grid's
 * spanning header so it scrolls with the content and stays reachable when
 * the list is empty.
 */
@Composable
fun PersonalListControlsRow(
    controls: PersonalListControlsViewModel,
    total: Int,
    modifier: Modifier = Modifier,
) {
    val state by controls.uiState.collectAsState()
    var sortMenuOpen by remember { mutableStateOf(false) }
    var showFilterSheet by remember { mutableStateOf(false) }

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box {
            ControlPill(
                icon = Icons.AutoMirrored.Filled.Sort,
                label = state.sort.label,
                active = state.sort != PersonalListSort.RecentlyAdded,
                trailingChevron = true,
                onClick = { sortMenuOpen = true },
            )
            DropdownMenu(
                expanded = sortMenuOpen,
                onDismissRequest = { sortMenuOpen = false },
            ) {
                PersonalListSort.entries.forEachIndexed { index, sort ->
                    val selected = sort == state.sort
                    if (index == 1) HorizontalDivider()
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = if (selected && sort.hasDirection) {
                                    "${sort.label}  ·  ${sort.directionLabel(state.order)}"
                                } else {
                                    sort.label
                                },
                                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                            )
                        },
                        trailingIcon = if (selected) {
                            { Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(18.dp)) }
                        } else {
                            null
                        },
                        onClick = {
                            controls.selectSort(sort)
                            // Re-picking flips direction; keep the menu open so
                            // the new direction label is visible.
                            if (!(selected && sort.hasDirection)) sortMenuOpen = false
                        },
                    )
                }
            }
        }

        ControlPill(
            icon = Icons.Filled.FilterList,
            label = if (state.activeFacetCount > 0) "Filter · ${state.activeFacetCount}" else "Filter",
            active = state.activeFacetCount > 0,
            onClick = { showFilterSheet = true },
        )

        // Reset appears only once something is customised — one tap back to
        // list order with no facets.
        if (state.isCustomised) {
            TextButton(
                onClick = controls::resetAll,
                contentPadding = PaddingValues(horizontal = 8.dp),
                modifier = Modifier.height(34.dp),
            ) {
                Icon(Icons.Filled.Close, contentDescription = null, modifier = Modifier.size(14.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("Reset", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }
        }

        Spacer(modifier = Modifier.weight(1f))

        if (total > 0) {
            Text(
                text = if (total == 1) "1 title" else "$total titles",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

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

/** Same capsule as the For You saved-list pills, brightened when active. */
@Composable
private fun ControlPill(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    active: Boolean,
    onClick: () -> Unit,
    trailingChevron: Boolean = false,
) {
    OutlinedButton(
        onClick = onClick,
        shape = CircleShape,
        contentPadding = PaddingValues(horizontal = 12.dp),
        border = BorderStroke(1.5.dp, Color.White.copy(alpha = if (active) 0.9f else 0.3f)),
        colors = ButtonDefaults.outlinedButtonColors(
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
        modifier = Modifier.height(34.dp),
    ) {
        Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(14.dp))
        Spacer(modifier = Modifier.width(6.dp))
        Text(text = label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
        if (trailingChevron) {
            Spacer(modifier = Modifier.width(2.dp))
            Icon(
                imageVector = Icons.Filled.KeyboardArrowDown,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}
