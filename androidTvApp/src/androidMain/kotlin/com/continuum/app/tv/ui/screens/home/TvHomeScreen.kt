package com.continuum.app.tv.ui.screens.home

import androidx.compose.foundation.background
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.tv.material3.MaterialTheme
import com.continuum.app.model.section.ResolvedSection
import com.continuum.app.tv.ui.components.TvErrorScreen
import com.continuum.app.tv.ui.components.TvLoadingScreen
import com.continuum.app.tv.ui.components.TvMediaCardActions
import com.continuum.app.tv.ui.components.TvRowStyle
import com.continuum.app.tv.ui.components.TvSkylineSectionFeed
import com.continuum.app.tv.ui.components.isTvProgressRow
import com.continuum.app.tv.ui.util.visibleOnTv
import com.continuum.app.viewmodel.HomeViewModel
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
    onContentUpFallbackChanged: (((() -> Boolean)?) -> Unit)? = null,
    viewModel: HomeViewModel = koinViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    val visibleSections = remember(state.sections) { state.sections.visibleOnTv() }

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
            onContentUpFallbackChanged = onContentUpFallbackChanged,
            onSetWatched = viewModel::setWatched,
            onToggleFavorite = viewModel::toggleFavorite,
            onToggleWatchlist = viewModel::toggleWatchlist,
            onDismissContinueWatching = viewModel::dismissContinueWatching,
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
    onContentUpFallbackChanged: (((() -> Boolean)?) -> Unit)?,
    onSetWatched: (String, Boolean) -> Unit = { _, _ -> },
    onToggleFavorite: (String, Boolean) -> Unit = { _, _ -> },
    onToggleWatchlist: (String, Boolean) -> Unit = { _, _ -> },
    onDismissContinueWatching: (String, String) -> Unit = { _, _ -> },
) {
    TvSkylineSectionFeed(
        sections = sections,
        onItemClick = onItemClick,
        focusRequest = focusRequest,
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
            if (section.isTvProgressRow()) TvRowStyle.Backdrop else TvRowStyle.Poster
        },
        cardActions = { section, item ->
            val isProgressRow = section.isTvProgressRow()
            TvMediaCardActions(
                onSetWatched = { watched -> onSetWatched(item.contentId, watched) },
                onToggleFavorite = { fav -> onToggleFavorite(item.contentId, fav) },
                onToggleWatchlist = { wl -> onToggleWatchlist(item.contentId, wl) },
                onRemoveFromContinueWatching = if (isProgressRow && item.progressUpdatedAt != null) {
                    {
                        item.progressUpdatedAt?.let { ts ->
                            onDismissContinueWatching(item.contentId, ts)
                        }
                    }
                } else {
                    null
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
