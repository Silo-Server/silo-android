package org.siloserver.silo.tv.ui.screens.home

import androidx.compose.foundation.background
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.tv.material3.MaterialTheme
import org.siloserver.silo.model.section.ResolvedSection
import org.siloserver.silo.tv.ui.components.TvErrorScreen
import org.siloserver.silo.tv.ui.components.TvLoadingScreen
import org.siloserver.silo.tv.ui.components.TvMediaCardActions
import org.siloserver.silo.tv.ui.components.TvSkylineSectionFeed
import org.siloserver.silo.tv.ui.components.isTvProgressRow
import org.siloserver.silo.viewmodel.HomeViewModel
import org.koin.compose.viewmodel.koinViewModel

/**
 * Android TV Home screen: the Skyline landing, matching tvOS Home by rendering
 * all visible non-empty server sections through the shared `TvSkylineSectionFeed`.
 */
@Composable
fun TvHomeScreen(
    onItemClick: (contentId: String) -> Unit,
    onPlayItem: (contentId: String, type: String?, resumePositionSeconds: Double?) -> Unit = { _, _, _ -> },
    onSeeAll: () -> Unit = {},
    onOpenForYou: () -> Unit = {},
    onInitialContentFocus: () -> Unit = {},
    focusRequest: Int = 0,
    detailReturnFocusRequest: Int = 0,
    detailReturnCardFocusRequester: FocusRequester? = null,
    firstRowFocusRequester: FocusRequester? = null,
    firstRowContainerFocusRequester: FocusRequester? = null,
    shouldRefreshOnResume: () -> Boolean = { true },
    onContentUpFallbackChanged: (((() -> Boolean)?) -> Unit)? = null,
    viewModel: HomeViewModel = koinViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    val visibleSections = remember(state.sections) { state.sections.normalizeTvHomeSections() }

    // TV has no pull-to-refresh, so ON_RESUME is the only manual freshness
    // path — refresh quietly whenever the user returns to Home (e.g. after
    // finishing playback), complementing the realtime socket.
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                if (shouldRefreshOnResume()) {
                    viewModel.refreshFromRealtime()
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    when {
        state.isLoading && state.sections.isEmpty() -> TvLoadingScreen(
            modifier = Modifier.background(MaterialTheme.colorScheme.background),
        )
        state.error != null && state.sections.isEmpty() -> TvErrorScreen(
            message = state.error!!,
            onRetry = viewModel::loadSections,
            modifier = Modifier.background(MaterialTheme.colorScheme.background),
        )
        else -> TvHomeContent(
            sections = visibleSections,
            onItemClick = onItemClick,
            onSeeAll = onSeeAll,
            onOpenForYou = onOpenForYou,
            onInitialContentFocus = onInitialContentFocus,
            focusRequest = focusRequest,
            detailReturnFocusRequest = detailReturnFocusRequest,
            detailReturnCardFocusRequester = detailReturnCardFocusRequester,
            firstRowFocusRequester = firstRowFocusRequester,
            firstRowContainerFocusRequester = firstRowContainerFocusRequester,
            onContentUpFallbackChanged = onContentUpFallbackChanged,
            onSetWatched = viewModel::setWatched,
            onToggleFavorite = viewModel::toggleFavorite,
            onToggleWatchlist = viewModel::toggleWatchlist,
            onDismissContinueWatching = viewModel::dismissContinueWatching,
            onDismissNextUp = viewModel::dismissNextUp,
        )
    }
}

@Composable
private fun TvHomeContent(
    sections: List<ResolvedSection>,
    onItemClick: (String) -> Unit,
    onSeeAll: () -> Unit = {},
    onOpenForYou: () -> Unit = {},
    onInitialContentFocus: () -> Unit,
    focusRequest: Int,
    detailReturnFocusRequest: Int,
    detailReturnCardFocusRequester: FocusRequester?,
    firstRowFocusRequester: FocusRequester?,
    firstRowContainerFocusRequester: FocusRequester?,
    onContentUpFallbackChanged: (((() -> Boolean)?) -> Unit)?,
    onSetWatched: (String, Boolean) -> Unit = { _, _ -> },
    onToggleFavorite: (String, Boolean) -> Unit = { _, _ -> },
    onToggleWatchlist: (String, Boolean) -> Unit = { _, _ -> },
    onDismissContinueWatching: (String, String) -> Unit = { _, _ -> },
    onDismissNextUp: (String, String) -> Unit = { _, _ -> },
) {
    TvSkylineSectionFeed(
        sections = sections,
        onItemClick = onItemClick,
        focusRequest = focusRequest,
        detailReturnFocusRequest = detailReturnFocusRequest,
        detailReturnCardFocusRequester = detailReturnCardFocusRequester,
        firstRowFocusRequester = firstRowFocusRequester,
        firstRowContainerRequester = firstRowContainerFocusRequester,
        onInitialContentFocus = onInitialContentFocus,
        onContentUpFallbackChanged = onContentUpFallbackChanged,
        iconForSection = { section ->
            if (section.isTvProgressRow()) Icons.Filled.PlayCircle else null
        },
        onSeeAllClickForSection = { section ->
            if (section.isForYouRow()) onOpenForYou else null
        },
        showProgressForSection = { section ->
            section.isTvProgressRow()
        },
        styleForSection = { section ->
            section.tvHomeRowStyle()
        },
        cardActions = { section, item ->
            val isProgressRow = section.isTvProgressRow()
            val progressUpdatedAt = item.progressUpdatedAt
            val seriesId = item.seriesId
            TvMediaCardActions(
                onSetWatched = { watched -> onSetWatched(item.contentId, watched) },
                onToggleFavorite = { fav -> onToggleFavorite(item.contentId, fav) },
                onToggleWatchlist = { wl -> onToggleWatchlist(item.contentId, wl) },
                onRemoveFromContinueWatching = when {
                    !isProgressRow -> null
                    progressUpdatedAt != null -> {
                        { onDismissContinueWatching(item.contentId, progressUpdatedAt) }
                    }
                    seriesId != null -> {
                        { onDismissNextUp(item.contentId, seriesId) }
                    }
                    else -> null
                },
            )
        },
    )
}

private fun ResolvedSection.isForYouRow(): Boolean {
    val type = sectionType.lowercase()
    return type == "for_you" ||
        type == "foryou" ||
        type == "recommendations" ||
        title.equals("for you", ignoreCase = true) ||
        title.equals("recommended for you", ignoreCase = true)
}
