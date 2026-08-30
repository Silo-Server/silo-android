package org.siloserver.silo.android.ui.screens.recommendations

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import org.siloserver.silo.android.ui.screens.personal.FavoritesGridContent
import org.siloserver.silo.android.ui.screens.personal.WatchlistGridContent
import org.siloserver.silo.android.ui.screens.personal.PersonalListControlsRow
import org.siloserver.silo.android.ui.screens.personal.PersonalListSource
import org.siloserver.silo.android.ui.screens.personal.queryState
import org.siloserver.silo.android.ui.screens.personal.rememberPersonalListControls
import org.siloserver.silo.viewmodel.PersonalListUiState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.siloserver.silo.android.ui.screens.home.HomeSectionRow
import org.siloserver.silo.viewmodel.RecommendationsViewModel
import org.siloserver.silo.android.ui.components.MediaRowsSkeleton
import org.siloserver.silo.android.ui.navigation.LocalBottomChromeInset
import org.siloserver.silo.common.ui.components.DeferImagePresentationWhileScrolling
import org.siloserver.silo.common.diagnostics.DiagnosticsListLogger
import org.siloserver.silo.common.diagnostics.DiagnosticsListSnapshot
import org.siloserver.silo.common.diagnostics.DiagnosticsListSurface
import org.koin.compose.viewmodel.koinViewModel

/**
 * Phone Recommendations ("For You") screen.
 *
 * Mirrors iOS `RecommendationsView.swift` (phone): a saved-shortcuts pill
 * row (Watchlist / Favorites) above the feed, and the iOS sparkles empty
 * state. The feed itself follows the Libraries "Recommended" shape — plain
 * HomeSectionRow rows in server order, no hero carousel — so the browse
 * surfaces read as one app. The screen title + actions header is supplied by the shared
 * `MainAppTopBar` in `MainScreen` (matching iOS `TabTopBarActions`); the
 * saved-list selection is hoisted there so the header title can name what is
 * on screen (For You / Watchlist / Favorites).
 *
 * The pill row scrolls with the content rather than pinning, so nothing is
 * clipped along a hard edge — rows slide under the header glass instead.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecommendationsScreen(
    onItemClick: (String) -> Unit,
    savedListSelection: ForYouList?,
    onSavedListSelectionChange: (ForYouList?) -> Unit,
    /**
     * What the screen is actually showing, for the header title. Differs from
     * [savedListSelection] only in the empty-feed fallback, which shows the
     * Watchlist without turning that into an explicit selection.
     */
    onDisplayedListChange: (ForYouList?) -> Unit = {},
    contentTopPadding: Dp = 0.dp,
    viewModel: RecommendationsViewModel = koinViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    val inFallback = !state.isLoading && state.error == null && state.sections.isEmpty()
    val displayedList = if (inFallback) savedListSelection ?: ForYouList.Watchlist else savedListSelection
    LaunchedEffect(displayedList) { onDisplayedListChange(displayedList) }
    val diagnosticsListSnapshot = remember(state.sections) {
        DiagnosticsListSnapshot.fromKeys(
            keys = state.sections.map { it.id },
            rowKeys = state.sections.map { section -> section.items.map { it.contentId } },
        )
    }
    LaunchedEffect(diagnosticsListSnapshot, state.isLoading) {
        if (!state.isLoading && state.sections.isNotEmpty()) {
            DiagnosticsListLogger.snapshot(
                DiagnosticsListSurface.PHONE_FOR_YOU,
                diagnosticsListSnapshot,
            )
        }
    }

    // Self-heal the "For You" fallback. The shared VM loads only in init{} and
    // survives tab switches (saveState/restoreState), so an empty server
    // response would otherwise leave the tab dead until a profile switch or
    // restart. Re-load on ON_RESUME whenever the feed is still empty — returning
    // to the tab after watching something re-fetches without any user action.
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentState by rememberUpdatedState(state)
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME &&
                currentState.sections.isEmpty() &&
                !currentState.isLoading &&
                !currentState.isRefreshing
            ) {
                viewModel.refresh()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    when {
        state.isLoading && state.sections.isEmpty() -> {
            // Skeleton in the shape of the feed (pill row + poster rows) so the
            // tab is never a blank black page while recommendations load.
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = contentTopPadding + 16.dp),
                verticalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                Box(modifier = Modifier.padding(horizontal = 16.dp)) {
                    SavedShortcutsRow(
                        onWatchlistClick = { onSavedListSelectionChange(ForYouList.Watchlist) },
                        onFavoritesClick = { onSavedListSelectionChange(ForYouList.Favorites) },
                    )
                }
                MediaRowsSkeleton(rowCount = 3)
            }
        }

        state.error != null && state.sections.isEmpty() -> {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 24.dp, vertical = contentTopPadding + 24.dp),
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(
                    imageVector = Icons.Outlined.ErrorOutline,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = state.error ?: "Failed to load recommendations",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Try reloading your personalized feed.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(20.dp))
                Button(onClick = { viewModel.loadRecommendations() }) {
                    Text("Retry")
                }
            }
        }

        state.sections.isEmpty() -> {
            // iOS savedListsFallback: when the server has nothing to suggest
            // (e.g. embeddings disabled), the shortcut row becomes an inline
            // selector — Watchlist by default — over the saved-list grid,
            // instead of navigating away or showing an empty promise.
            val selection = savedListSelection ?: ForYouList.Watchlist
            val header: @Composable () -> Unit = {
                Column {
                    SavedShortcutsRow(
                        onWatchlistClick = { onSavedListSelectionChange(ForYouList.Watchlist) },
                        onFavoritesClick = { onSavedListSelectionChange(ForYouList.Favorites) },
                        selection = selection,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "No recommendations yet — showing your saved titles.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    // Explicit retry so the fallback is recoverable in place — the
                    // embedded grids carry their own pull-to-refresh, so we do
                    // NOT wrap them in another PullToRefreshBox (nesting misbehaves).
                    OutlinedButton(onClick = { viewModel.refresh() }) {
                        Text("Check again")
                    }
                }
            }
            SavedListGrid(
                list = selection,
                onItemClick = onItemClick,
                contentTopPadding = contentTopPadding,
                header = header,
            )
        }

        else -> {
            // Watchlist / Favorites toggle IN PLACE over the recommendations feed
            // instead of navigating to a separate page (Jim 2026-07-09 — a
            // deliberate divergence from iOS, which navigates when recs exist).
            // The pill row leads the content and scrolls with it; null selection
            // shows the recommendation sections, and re-tapping the active pill
            // returns to them.
            val pills: @Composable () -> Unit = {
                SavedShortcutsRow(
                    onWatchlistClick = {
                        onSavedListSelectionChange(
                            if (savedListSelection == ForYouList.Watchlist) null else ForYouList.Watchlist,
                        )
                    },
                    onFavoritesClick = {
                        onSavedListSelectionChange(
                            if (savedListSelection == ForYouList.Favorites) null else ForYouList.Favorites,
                        )
                    },
                    selection = savedListSelection,
                )
            }
            when (savedListSelection) {
                null -> PullToRefreshBox(
                    isRefreshing = state.isRefreshing,
                    onRefresh = { viewModel.refresh() },
                    modifier = Modifier.fillMaxSize(),
                ) {
                    val listState = rememberLazyListState()
                    DeferImagePresentationWhileScrolling(listState) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        // Content starts under the header glass and keeps room for
                        // the floating bottom nav while preserving iOS section
                        // rhythm inside the list. Top = header + the grid's own
                        // 16dp inset so the pills sit at the same y in both modes.
                        contentPadding = PaddingValues(
                            top = contentTopPadding + 16.dp,
                            bottom = 24.dp + LocalBottomChromeInset.current,
                        ),
                        // iOS sectionSpacing (phone) = largePadding (24).
                        verticalArrangement = Arrangement.spacedBy(24.dp),
                    ) {
                        item(key = "savedShortcuts") {
                            Box(modifier = Modifier.padding(horizontal = 16.dp)) { pills() }
                        }
                        items(
                            items = state.sections,
                            key = { it.id },
                        ) { section ->
                            // No "See All" — iOS has no such affordance, so the
                            // row omits it when onSeeAllClick is null.
                            HomeSectionRow(
                                section = section,
                                onItemClick = onItemClick,
                            )
                        }
                    }
                    }
                }
                else -> SavedListGrid(
                    list = savedListSelection,
                    onItemClick = onItemClick,
                    contentTopPadding = contentTopPadding,
                    header = pills,
                )
            }
        }
    }
}

/** Which saved list For You is showing; null is the recommendations feed. */
enum class ForYouList { Watchlist, Favorites }

/** Header title for the current For You content. */
fun ForYouList?.headerTitle(): String = when (this) {
    null -> "For You"
    ForYouList.Watchlist -> "Watchlist"
    ForYouList.Favorites -> "Favorites"
}

@Composable
private fun SavedListGrid(
    list: ForYouList,
    onItemClick: (String) -> Unit,
    contentTopPadding: Dp,
    header: @Composable () -> Unit,
) {
    val contentPadding = PaddingValues(
        top = contentTopPadding,
        bottom = 24.dp + LocalBottomChromeInset.current,
    )
    // Sort/filter controls (TV parity), shared with the standalone
    // Watchlist / Favorites screens through the activity-scoped holder.
    val source = when (list) {
        ForYouList.Watchlist -> PersonalListSource.Watchlist
        ForYouList.Favorites -> PersonalListSource.Favorites
    }
    val controls = rememberPersonalListControls(source)
    val query by controls.queryState()
    val gridHeader: @Composable (PersonalListUiState) -> Unit = { state ->
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            header()
            PersonalListControlsRow(controls = controls, total = state.total)
        }
    }
    when (list) {
        ForYouList.Watchlist -> WatchlistGridContent(
            onItemClick = onItemClick,
            modifier = Modifier.fillMaxSize(),
            contentPadding = contentPadding,
            query = query,
            header = gridHeader,
        )
        ForYouList.Favorites -> FavoritesGridContent(
            onItemClick = onItemClick,
            modifier = Modifier.fillMaxSize(),
            contentPadding = contentPadding,
            query = query,
            header = gridHeader,
        )
    }
}


/**
 * Watchlist / Favorites pill row. Mirrors iOS `SavedShortcutsRow` (phone):
 * HStack spacing 12, capsule pills 40 tall with 15 horizontal padding, a
 * 1.5pt white-30% border, and a 14sp-semibold title with a 13sp-semibold icon.
 */
@Composable
private fun SavedShortcutsRow(
    onWatchlistClick: () -> Unit,
    onFavoritesClick: () -> Unit,
    modifier: Modifier = Modifier,
    /** Non-null renders the pills as an inline selector (fallback mode). */
    selection: ForYouList? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SavedShortcutPill(
            title = "Watchlist",
            icon = Icons.Filled.Bookmark,
            onClick = onWatchlistClick,
            selected = selection == ForYouList.Watchlist,
        )
        SavedShortcutPill(
            title = "Favorites",
            icon = Icons.Filled.Favorite,
            onClick = onFavoritesClick,
            selected = selection == ForYouList.Favorites,
        )
    }
}

@Composable
private fun SavedShortcutPill(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
    selected: Boolean = false,
) {
    OutlinedButton(
        onClick = onClick,
        shape = CircleShape,
        contentPadding = PaddingValues(horizontal = 15.dp),
        border = BorderStroke(1.5.dp, Color.White.copy(alpha = if (selected) 0.9f else 0.3f)),
        colors = ButtonDefaults.outlinedButtonColors(
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
        modifier = Modifier.height(40.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(13.dp),
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = title,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
        )
    }
}
