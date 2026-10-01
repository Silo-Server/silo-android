package org.siloserver.silo.tv.ui.screens.collections

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Collections
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import org.siloserver.silo.common.ui.OnViewerAccessChanged
import org.siloserver.silo.network.AccessChangeSignals
import org.siloserver.silo.model.personal.Collection
import org.siloserver.silo.tv.ui.focus.rememberTvContentInitialFocus
import org.siloserver.silo.tv.ui.components.TvErrorScreen
import org.siloserver.silo.tv.ui.components.TvLoadingScreen
import org.siloserver.silo.tv.ui.theme.Spacing
import org.siloserver.silo.tv.ui.theme.sectionEyebrow
import org.siloserver.silo.tv.ui.theme.tvPageStartPadding
import org.siloserver.silo.viewmodel.CollectionSection
import org.siloserver.silo.viewmodel.CollectionsViewModel

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun TvCollectionsScreen(
    onCollectionClick: (collectionId: String, title: String) -> Unit,
    onInitialContentFocus: () -> Unit = {},
    viewModel: CollectionsViewModel = koinViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    OnViewerAccessChanged(koinInject<AccessChangeSignals>()) { viewModel.refresh() }
    val firstCollectionFocusRequester = remember { FocusRequester() }
    val firstCollectionId = state.sections.firstNotNullOfOrNull { it.collections.firstOrNull()?.id }

    // Latching "attempted" as "acquired" left the grid with no focus owner when
    // the first request landed during lazy placement, and told the shell that
    // content focus had succeeded anyway.
    val contentInitialFocus = rememberTvContentInitialFocus(
        target = firstCollectionFocusRequester,
        contentKey = firstCollectionId,
        onAcquired = onInitialContentFocus,
    )

    val lifecycleOwner = LocalLifecycleOwner.current
    var skippedFirstResume by remember { mutableStateOf(false) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                if (skippedFirstResume) viewModel.refresh() else skippedFirstResume = true
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .then(contentInitialFocus)
            .background(MaterialTheme.colorScheme.background),
    ) {
        Header()

        when {
            state.isLoading && state.collections.isEmpty() -> TvLoadingScreen()
            state.error != null && state.collections.isEmpty() -> TvErrorScreen(
                message = state.error!!,
                onRetry = viewModel::loadCollections,
            )
            state.collections.isEmpty() && state.groups.isEmpty() -> EmptyState()
            else -> LazyVerticalGrid(
                columns = GridCells.Fixed(4),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(
                    start = tvPageStartPadding(),
                    end = Spacing.safeArea,
                    top = Spacing.lg,
                    bottom = Spacing.xxxl,
                ),
                modifier = Modifier.fillMaxSize(),
            ) {
                state.sections.forEach { section ->
                    item(
                        key = "header-${section.groupId ?: "ungrouped"}",
                        span = { GridItemSpan(maxLineSpan) },
                    ) {
                        SectionHeader(section = section)
                    }
                    if (section.collections.isEmpty()) {
                        item(
                            key = "empty-${section.groupId ?: "ungrouped"}",
                            span = { GridItemSpan(maxLineSpan) },
                        ) { EmptyGroupHint() }
                    } else {
                        items(section.collections, key = { it.id }) { collection ->
                            CollectionCard(
                                collection = collection,
                                onClick = { onCollectionClick(collection.id, collection.name) },
                                focusRequester = firstCollectionFocusRequester
                                    .takeIf { collection.id == firstCollectionId },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Header() {
    val startPadding = tvPageStartPadding()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                start = startPadding,
                end = Spacing.safeArea,
                top = Spacing.xxl,
                bottom = Spacing.lg,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            Text(
                text = "YOUR LIBRARY",
                style = sectionEyebrow,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.46f),
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.md),
            ) {
                Icon(
                    imageVector = Icons.Filled.Collections,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.82f),
                    modifier = Modifier.size(16.dp),
                )
                Text(
                    text = "Collections",
                    style = MaterialTheme.typography.displaySmall,
                    color = MaterialTheme.colorScheme.onBackground,
                )
            }
        }
    }
}

@Composable
private fun SectionHeader(section: CollectionSection) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = Spacing.md, bottom = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = section.name,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onBackground,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun EmptyGroupHint() {
    Text(
        text = "No collections in this group.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = Spacing.sm),
    )
}

@Composable
private fun EmptyState() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Spacing.lg),
        ) {
            Icon(
                imageVector = Icons.Filled.Collections,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(48.dp),
            )
            Text(
                text = "No collections yet",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "Collections from your library will appear here.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun CollectionCard(
    collection: Collection,
    onClick: () -> Unit,
    focusRequester: FocusRequester? = null,
) {
    Card(
        onClick = onClick,
        shape = CardDefaults.shape(shape = RoundedCornerShape(8.dp)),
        modifier = Modifier
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .fillMaxWidth()
            .height(90.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp),
            contentAlignment = Alignment.BottomStart,
        ) {
            Column {
                Text(
                    text = collection.name,
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onBackground,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = collection.collectionType.replaceFirstChar { it.uppercase() },
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}
