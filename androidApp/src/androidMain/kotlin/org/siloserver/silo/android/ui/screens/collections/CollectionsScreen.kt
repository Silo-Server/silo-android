package org.siloserver.silo.android.ui.screens.collections

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.CollectionsBookmark
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import org.siloserver.silo.common.ui.OnViewerAccessChanged
import org.siloserver.silo.network.AccessChangeSignals
import org.siloserver.silo.android.ui.components.EmptyStateView
import org.siloserver.silo.android.ui.components.ErrorView
import org.siloserver.silo.android.ui.components.LoadingIndicator
import org.siloserver.silo.android.ui.components.SiloTopBar
import org.siloserver.silo.model.personal.Collection
import org.siloserver.silo.viewmodel.CollectionSection
import org.siloserver.silo.viewmodel.CollectionsViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CollectionsScreen(
    onBackClick: () -> Unit,
    onCollectionClick: (String) -> Unit,
    viewModel: CollectionsViewModel = koinViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    OnViewerAccessChanged(koinInject<AccessChangeSignals>()) { viewModel.refresh() }

    Scaffold(
        topBar = {
            SiloTopBar(
                title = "Collections",
                onBackClick = onBackClick,
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        when {
            state.isLoading -> {
                LoadingIndicator(modifier = Modifier.padding(padding))
            }
            state.error != null && state.collections.isEmpty() && state.groups.isEmpty() -> {
                ErrorView(
                    message = state.error ?: "Unknown error",
                    onRetry = viewModel::loadCollections,
                    modifier = Modifier.padding(padding),
                )
            }
            state.collections.isEmpty() && state.groups.isEmpty() -> {
                EmptyStateView(
                    title = "No collections",
                    subtitle = "Collections from your library will appear here.",
                    icon = Icons.Outlined.CollectionsBookmark,
                    modifier = Modifier.padding(padding),
                )
            }
            else -> {
                PullToRefreshBox(
                    isRefreshing = state.isRefreshing,
                    onRefresh = viewModel::refresh,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                ) {
                    LazyColumn(
                        contentPadding = PaddingValues(16.dp),
                    ) {
                        for (section in state.sections) {
                            item(key = "header:${section.groupId ?: "ungrouped"}") {
                                SectionHeader(section = section)
                            }

                            item(key = "section:${section.groupId ?: "ungrouped"}") {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(MaterialTheme.shapes.medium)
                                        .background(MaterialTheme.colorScheme.surface),
                                ) {
                                    if (section.collections.isEmpty()) {
                                        Text(
                                            text = "No collections in this group.",
                                            style = MaterialTheme.typography.bodySmall,
                                            fontSize = 12.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(horizontal = 16.dp, vertical = 11.dp),
                                        )
                                    } else {
                                        section.collections.forEachIndexed { index, collection ->
                                            if (index > 0) {
                                                HorizontalDivider(
                                                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f),
                                                    modifier = Modifier.padding(start = 16.dp),
                                                )
                                            }
                                            CollectionCard(
                                                collection = collection,
                                                onClick = { onCollectionClick(collection.id) },
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(section: CollectionSection) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp, bottom = 4.dp, start = 4.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = section.name,
            style = MaterialTheme.typography.bodySmall,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun CollectionCard(
    collection: Collection,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = collection.name,
                style = MaterialTheme.typography.bodyMedium,
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = rowSubtitle(collection),
                style = MaterialTheme.typography.bodySmall,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(modifier = Modifier.width(8.dp))

        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(16.dp),
        )
    }
}

private fun rowSubtitle(collection: Collection): String {
    val kind = collection.collectionType.replaceFirstChar { it.uppercase() }
    val count = collection.itemCount
    return if (count != null && count > 0) {
        "$kind - $count item${if (count == 1) "" else "s"}"
    } else {
        kind
    }
}
