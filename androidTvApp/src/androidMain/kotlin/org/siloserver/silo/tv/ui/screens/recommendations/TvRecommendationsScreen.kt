package org.siloserver.silo.tv.ui.screens.recommendations

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Button
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import org.siloserver.silo.tv.ui.components.TvErrorScreen
import org.siloserver.silo.tv.ui.components.TvLoadingScreen
import org.siloserver.silo.tv.ui.components.TvMediaRow
import org.siloserver.silo.tv.ui.components.TvRowStyle
import org.siloserver.silo.tv.ui.components.TvHeroActionPill
import org.siloserver.silo.tv.ui.components.TvPillVariant
import org.siloserver.silo.tv.ui.screens.personal.TvFavoritesInline
import org.siloserver.silo.tv.ui.screens.personal.TvWatchlistInline
import org.siloserver.silo.tv.ui.shell.TvTopMenuLayout
import org.siloserver.silo.tv.ui.theme.Spacing
import org.siloserver.silo.tv.ui.util.visibleOnTv
import org.siloserver.silo.viewmodel.RecommendationsViewModel
import org.koin.compose.viewmodel.koinViewModel

/**
 * "For You" tab. Reuses the shared [RecommendationsViewModel] that drives
 * the phone `/recommendations/discover` feed. Layout mirrors [TvHomeScreen]
 * (rows down the page) minus the featured hero — the discover API returns
 * section-style rows, not a hero card.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun TvRecommendationsScreen(
    onItemClick: (contentId: String) -> Unit,
    onInitialContentFocus: () -> Unit = {},
    focusRequest: Int = 0,
    viewModel: RecommendationsViewModel = koinViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    val visibleSections = remember(state.sections) { state.sections.visibleOnTv() }
    val watchlistFocusRequester = remember { FocusRequester() }
    var savedListSelection by remember { mutableStateOf<SavedListSelection?>(null) }

    // Match tvOS: recommendations remain the landing content when available;
    // an empty successful response defaults to the inline Watchlist fallback.
    LaunchedEffect(state.isLoading, state.error, visibleSections) {
        if (!state.isLoading && state.error == null && visibleSections.isEmpty() && savedListSelection == null) {
            savedListSelection = SavedListSelection.Watchlist
        }
    }

    // The saved-list shortcuts are the stable first row in every state. Focus
    // Watchlist once per entry, matching tvOS, without letting later refreshes
    // pull focus away from the user's current position.
    var initialFocusRequested by remember { mutableStateOf(false) }
    var lastAppliedFocusRequest by remember { mutableStateOf(-1) }
    LaunchedEffect(focusRequest) {
        if (initialFocusRequested && focusRequest == lastAppliedFocusRequest) return@LaunchedEffect
        runCatching { watchlistFocusRequester.requestFocus() }
        onInitialContentFocus()
        initialFocusRequested = true
        lastAppliedFocusRequest = focusRequest
    }

    // TV has no pull-to-refresh, so ON_RESUME is the only quiet self-heal path.
    // The shared VM loads once in init and retains its state across tab swaps, so
    // an empty discover response otherwise leaves this tab a permanent dead end
    // until a profile switch/restart. If the feed is still the empty fallback
    // when the user returns (e.g. after watching and rating content), re-check.
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                val current = viewModel.uiState.value
                if (!current.isLoading && current.sections.visibleOnTv().isEmpty()) {
                    viewModel.refresh()
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        when {
            savedListSelection == SavedListSelection.Watchlist -> TvWatchlistInline(
                onItemClick = onItemClick,
                modifier = Modifier.padding(top = TvTopMenuLayout.contentTopInset + 60.dp),
            )
            savedListSelection == SavedListSelection.Favorites -> TvFavoritesInline(
                onItemClick = onItemClick,
                modifier = Modifier.padding(top = TvTopMenuLayout.contentTopInset + 60.dp),
            )
            state.isLoading && state.sections.isEmpty() -> TvLoadingScreen(
                modifier = Modifier.background(MaterialTheme.colorScheme.background),
            )
            state.error != null && state.sections.isEmpty() -> TvErrorScreen(
                message = state.error ?: "Failed to load recommendations",
                onRetry = viewModel::loadRecommendations,
                modifier = Modifier.background(MaterialTheme.colorScheme.background),
            )
            visibleSections.isEmpty() -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background)
                        .padding(horizontal = Spacing.safeArea),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.AutoAwesome,
                            contentDescription = null,
                            tint = Color.White.copy(alpha = 0.72f),
                            modifier = Modifier.size(28.dp),
                        )
                        Text(
                            text = "Not enough data yet",
                            style = MaterialTheme.typography.headlineSmall,
                            color = Color.White,
                        )
                        Text(
                            text = "Watch and rate more content to unlock personalized recommendations.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.White.copy(alpha = 0.7f),
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Button(
                            onClick = viewModel::loadRecommendations,
                            contentPadding = PaddingValues(horizontal = 32.dp, vertical = 12.dp),
                        ) {
                            Text("Check again", style = MaterialTheme.typography.labelLarge)
                        }
                    }
                }
            }
            else -> {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background),
                    verticalArrangement = Arrangement.spacedBy(18.dp),
                    contentPadding = PaddingValues(
                        top = Spacing.heroTopSafe + 58.dp,
                        bottom = 24.dp,
                    ),
                ) {
                    items(
                        items = visibleSections,
                        key = { it.id },
                        contentType = { "recommendation-section-row" },
                    ) { section ->
                        TvMediaRow(
                            title = section.title,
                            items = section.items,
                            onItemClick = onItemClick,
                            style = TvRowStyle.Poster,
                        )
                    }
                    item { Spacer(modifier = Modifier.height(8.dp)) }
                }
            }
        }

        if (savedListSelection != null && visibleSections.isEmpty()) {
            Text(
                text = "No recommendations yet — showing your saved titles.",
                style = MaterialTheme.typography.labelSmall.copy(
                    fontSize = 11.sp,
                    lineHeight = 14.sp,
                ),
                color = Color.White.copy(alpha = 0.62f),
                modifier = Modifier.padding(
                    start = Spacing.safeArea,
                    top = TvTopMenuLayout.contentTopInset + 40.dp,
                ),
            )
        }

        Row(
            modifier = Modifier.padding(
                start = Spacing.safeArea,
                top = TvTopMenuLayout.contentTopInset,
            ),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            TvHeroActionPill(
                label = "For You",
                icon = Icons.Outlined.AutoAwesome,
                variant = TvPillVariant.Hollow,
                selected = savedListSelection == null,
                heightOverride = 32.dp,
                horizontalPaddingOverride = 13.dp,
                iconSizeOverride = 10.dp,
                iconLabelSpacingOverride = 5.dp,
                restBorderWidthOverride = 0.75.dp,
                focusedBorderWidthOverride = 1.5.dp,
                focusedScaleOverride = 1.045f,
                focusedGlowElevationOverride = 9.dp,
                labelStyle = MaterialTheme.typography.labelSmall.copy(
                    fontSize = 12.sp,
                    lineHeight = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                ),
                onClick = { savedListSelection = null },
            )
            TvHeroActionPill(
                label = "Watchlist",
                icon = Icons.Filled.Bookmark,
                variant = TvPillVariant.Hollow,
                selected = savedListSelection == SavedListSelection.Watchlist,
                focusRequester = watchlistFocusRequester,
                heightOverride = 32.dp,
                horizontalPaddingOverride = 13.dp,
                iconSizeOverride = 10.dp,
                iconLabelSpacingOverride = 5.dp,
                restBorderWidthOverride = 0.75.dp,
                focusedBorderWidthOverride = 1.5.dp,
                focusedScaleOverride = 1.045f,
                focusedGlowElevationOverride = 9.dp,
                labelStyle = MaterialTheme.typography.labelSmall.copy(
                    fontSize = 12.sp,
                    lineHeight = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                ),
                onClick = { savedListSelection = SavedListSelection.Watchlist },
            )
            TvHeroActionPill(
                label = "Favorites",
                icon = Icons.Filled.Favorite,
                variant = TvPillVariant.Hollow,
                selected = savedListSelection == SavedListSelection.Favorites,
                heightOverride = 32.dp,
                horizontalPaddingOverride = 13.dp,
                iconSizeOverride = 10.dp,
                iconLabelSpacingOverride = 5.dp,
                restBorderWidthOverride = 0.75.dp,
                focusedBorderWidthOverride = 1.5.dp,
                focusedScaleOverride = 1.045f,
                focusedGlowElevationOverride = 9.dp,
                labelStyle = MaterialTheme.typography.labelSmall.copy(
                    fontSize = 12.sp,
                    lineHeight = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                ),
                onClick = { savedListSelection = SavedListSelection.Favorites },
            )
        }
    }
}

private enum class SavedListSelection {
    Watchlist,
    Favorites,
}
