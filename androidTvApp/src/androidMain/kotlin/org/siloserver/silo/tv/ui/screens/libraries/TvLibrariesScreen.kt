package org.siloserver.silo.tv.ui.screens.libraries

import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.tv.material3.MaterialTheme
import org.siloserver.silo.tv.ui.components.TvCatalogEmptyState
import org.siloserver.silo.tv.ui.components.TvErrorScreen
import org.siloserver.silo.tv.ui.components.TvLoadingScreen
import org.siloserver.silo.tv.ui.screens.library.TvLibraryDetailScreen
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun TvLibrariesScreen(
    onItemClick: (contentId: String, libraryId: Int) -> Unit,
    onLibraryCollectionClick: (
        libraryId: Int,
        collectionId: String,
        title: String,
        libraryType: String,
    ) -> Unit,
    // User-created collections resolve via a different catalog source, so they
    // route to the user-collection detail rather than the library one (#69).
    onUserCollectionClick: (collectionId: String, title: String) -> Unit,
    onInitialContentFocus: () -> Unit = {},
    /** Plays a shuffle the library page started, bound to that library. */
    onPlayShuffle: (shuffle: org.siloserver.silo.model.shuffle.Shuffle, libraryId: Int?) -> Unit,
    viewModel: TvLibrariesViewModel = koinViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    val selectedLibrary = state.libraries.firstOrNull { it.id == state.selectedLibraryId }
        ?: state.libraries.firstOrNull()
    // A library hidden or shown on another device, picked up on foreground.
    val hiddenLibrariesRevision by viewModel.hiddenLibrariesRevision.collectAsState()
    LaunchedEffect(viewModel, hiddenLibrariesRevision) {
        viewModel.onHiddenLibrariesRevision(hiddenLibrariesRevision)
    }

    when {
        state.isLoading && state.libraries.isEmpty() -> TvLoadingScreen(
            modifier = Modifier.background(MaterialTheme.colorScheme.background),
        )
        state.error != null && state.libraries.isEmpty() -> TvErrorScreen(
            message = state.error!!,
            onRetry = viewModel::load,
            modifier = Modifier.background(MaterialTheme.colorScheme.background),
        )
        state.libraries.isEmpty() -> TvCatalogEmptyState(
            message = "No libraries available for this profile.",
            modifier = Modifier.background(MaterialTheme.colorScheme.background),
        )
        selectedLibrary != null -> {
            key(selectedLibrary.id) {
                TvLibraryDetailScreen(
                    libraryId = selectedLibrary.id,
                    libraryTitle = selectedLibrary.name,
                    libraryType = selectedLibrary.type,
                    onItemClick = { onItemClick(it, selectedLibrary.id) },
                    onCollectionClick = { collectionId, title, isUserCollection ->
                        if (isUserCollection) {
                            onUserCollectionClick(collectionId, title)
                        } else {
                            onLibraryCollectionClick(
                                selectedLibrary.id,
                                collectionId,
                                title,
                                selectedLibrary.type,
                            )
                        }
                    },
                    onInitialContentFocus = onInitialContentFocus,
                    onShuffleStarted = { shuffle -> onPlayShuffle(shuffle, selectedLibrary.id) },
                )
            }
        }
    }

}
