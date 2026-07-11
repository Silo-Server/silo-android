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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import org.siloserver.silo.android.ui.screens.personal.FavoritesGridContent
import org.siloserver.silo.android.ui.screens.personal.WatchlistGridContent
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.siloserver.silo.android.ui.screens.home.HomeSectionRow
import org.siloserver.silo.viewmodel.RecommendationsViewModel
import org.siloserver.silo.android.ui.navigation.LocalBottomChromeInset
import org.koin.compose.viewmodel.koinViewModel

/**
 * Phone Recommendations ("For You") screen.
 *
 * Mirrors iOS `RecommendationsView.swift` (phone) 1:1: a saved-shortcuts pill
 * row (Watchlist / Favorites) above the recommendation section rows, the same
 * SectionRow layout used by Home, iOS section spacing, and the iOS sparkles
 * empty state. The screen title + actions header is supplied by the shared
 * `MainAppTopBar` in `MainScreen` (matching iOS `TabTopBarActions`).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecommendationsScreen(
    onItemClick: (String) -> Unit,
    contentTopPadding: Dp = 0.dp,
    viewModel: RecommendationsViewModel = koinViewModel(),
) {
    val state by viewModel.uiState.collectAsState()

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
            // iOS phone loading state is an empty (Color.clear) placeholder.
            Box(modifier = Modifier.fillMaxSize().padding(top = contentTopPadding))
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
            var savedListSelection by rememberSaveable { mutableStateOf(SavedList.Watchlist) }
            Column(modifier = Modifier.fillMaxSize()) {
                Spacer(modifier = Modifier.height(contentTopPadding + 8.dp))
                SavedShortcutsRow(
                    onWatchlistClick = { savedListSelection = SavedList.Watchlist },
                    onFavoritesClick = { savedListSelection = SavedList.Favorites },
                    selection = savedListSelection,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "No recommendations yet — showing your saved titles.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
                Spacer(modifier = Modifier.height(8.dp))
                // Explicit retry so the fallback is recoverable in place — the
                // embedded grids below carry their own pull-to-refresh, so we do
                // NOT wrap them in another PullToRefreshBox (nesting misbehaves).
                OutlinedButton(
                    onClick = { viewModel.refresh() },
                    modifier = Modifier.padding(horizontal = 16.dp),
                ) {
                    Text("Check again")
                }
                Spacer(modifier = Modifier.height(8.dp))
                when (savedListSelection) {
                    SavedList.Watchlist -> WatchlistGridContent(
                        onItemClick = onItemClick,
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(bottom = 24.dp + LocalBottomChromeInset.current),
                    )
                    SavedList.Favorites -> FavoritesGridContent(
                        onItemClick = onItemClick,
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(bottom = 24.dp + LocalBottomChromeInset.current),
                    )
                }
            }
        }

        else -> {
            // Watchlist / Favorites toggle IN PLACE over the recommendations feed
            // instead of navigating to a separate page (Jim 2026-07-09 — a
            // deliberate divergence from iOS, which navigates when recs exist).
            // The pill row is pinned above the content so it is always reachable;
            // null selection shows the recommendation sections, and re-tapping the
            // active pill returns to them.
            var savedListSelection by rememberSaveable { mutableStateOf<SavedList?>(null) }
            Column(modifier = Modifier.fillMaxSize()) {
                Spacer(modifier = Modifier.height(contentTopPadding + 8.dp))
                SavedShortcutsRow(
                    onWatchlistClick = {
                        savedListSelection =
                            if (savedListSelection == SavedList.Watchlist) null else SavedList.Watchlist
                    },
                    onFavoritesClick = {
                        savedListSelection =
                            if (savedListSelection == SavedList.Favorites) null else SavedList.Favorites
                    },
                    selection = savedListSelection,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
                Spacer(modifier = Modifier.height(8.dp))
                when (savedListSelection) {
                    SavedList.Watchlist -> WatchlistGridContent(
                        onItemClick = onItemClick,
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(bottom = 24.dp + LocalBottomChromeInset.current),
                    )
                    SavedList.Favorites -> FavoritesGridContent(
                        onItemClick = onItemClick,
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(bottom = 24.dp + LocalBottomChromeInset.current),
                    )
                    null -> PullToRefreshBox(
                        isRefreshing = state.isRefreshing,
                        onRefresh = { viewModel.refresh() },
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                    ) {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            // Keep room for the floating bottom nav while preserving
                            // iOS section rhythm inside the list.
                            contentPadding = PaddingValues(bottom = 24.dp + LocalBottomChromeInset.current),
                            // iOS sectionSpacing (phone) = largePadding (24).
                            verticalArrangement = Arrangement.spacedBy(24.dp),
                        ) {
                            items(
                                items = state.sections,
                                key = { it.id },
                            ) { section ->
                                HomeSectionRow(
                                    section = section,
                                    onItemClick = onItemClick,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Which saved list the empty-state fallback is showing. */
private enum class SavedList { Watchlist, Favorites }

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
    selection: SavedList? = null,
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
            selected = selection == SavedList.Watchlist,
        )
        SavedShortcutPill(
            title = "Favorites",
            icon = Icons.Filled.Favorite,
            onClick = onFavoritesClick,
            selected = selection == SavedList.Favorites,
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

