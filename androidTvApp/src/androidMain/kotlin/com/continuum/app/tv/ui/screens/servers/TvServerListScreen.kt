package com.continuum.app.tv.ui.screens.servers

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
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
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.continuum.app.model.server.ServerEntry
import com.continuum.app.tv.ui.components.TvDialogOption
import com.continuum.app.tv.ui.components.TvOptionDialog
import com.continuum.app.tv.ui.components.TvTextInputDialog
import com.continuum.app.tv.ui.theme.Spacing
import org.koin.compose.viewmodel.koinViewModel

/**
 * Multi-server picker for the TV app — focus-aware list of saved servers
 * with an "Add Server" tile at the top. Long-press / Menu opens an action
 * sheet with Remove (rename is intentionally omitted on TV: easier to
 * remove + re-add than to edit a string with the on-screen keyboard).
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun TvServerListScreen(
    onAddServer: () -> Unit,
    onSwitched: (TvServerSwitchDestination) -> Unit,
    onBack: () -> Unit,
    viewModel: TvServerListViewModel = koinViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    val firstFocus = remember { FocusRequester() }
    var confirmRemove by remember { mutableStateOf<ServerEntry?>(null) }
    var renameTarget by remember { mutableStateOf<ServerEntry?>(null) }

    BackHandler(enabled = true) { onBack() }

    LaunchedEffect(state.switchedTo) {
        val destination = state.switchedTo
        if (destination != null) {
            viewModel.onSwitchConsumed()
            onSwitched(destination)
        }
    }

    LaunchedEffect(state.servers.size) {
        // Anchor focus on the first row whenever the list materializes so
        // d-pad navigation has somewhere to land.
        if (state.servers.isNotEmpty()) runCatching { firstFocus.requestFocus() }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 64.dp, vertical = 40.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
            Text(
                text = "Servers",
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
                text = "Choose which Silo server to connect to.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(Spacing.sm))

            AddServerTile(
                onClick = onAddServer,
                modifier = Modifier.focusRequester(
                    if (state.servers.isEmpty()) firstFocus else FocusRequester.Default,
                ),
            )

            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                items(state.servers, key = { it.id }) { entry ->
                    val rowModifier = if (entry == state.servers.firstOrNull()) {
                        Modifier.focusRequester(firstFocus)
                    } else Modifier

                    ServerRow(
                        entry = entry,
                        isActive = entry.id == state.activeId,
                        isPending = entry.id == state.pendingSwitchToId,
                        onSelect = { viewModel.onSelect(entry.id) },
                        onRename = { renameTarget = entry },
                        onRemove = { confirmRemove = entry },
                        modifier = rowModifier,
                    )
                }
            }
        }
    }

    confirmRemove?.let { target ->
        TvOptionDialog(
            title = "Remove ${target.displayName}?",
            options = listOf(
                TvDialogOption(
                    key = "confirm",
                    title = "Remove",
                    onClick = {
                        viewModel.onRemove(target.id)
                        confirmRemove = null
                    },
                ),
                TvDialogOption(
                    key = "cancel",
                    title = "Cancel",
                    onClick = { confirmRemove = null },
                ),
            ),
            onDismiss = { confirmRemove = null },
        )
    }

    renameTarget?.let { target ->
        TvTextInputDialog(
            title = "Rename server",
            label = "Display name",
            confirmLabel = "Save",
            initialValue = target.displayName,
            allowBlank = true,
            onConfirm = { name ->
                viewModel.onRename(target.id, name)
                renameTarget = null
            },
            onDismiss = { renameTarget = null },
        )
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun AddServerTile(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        onClick = onClick,
        colors = CardDefaults.colors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        ),
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(Spacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Default.Add,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(28.dp),
            )
            Spacer(Modifier.width(Spacing.sm))
            Text(
                text = "Add Server",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ServerRow(
    entry: ServerEntry,
    isActive: Boolean,
    isPending: Boolean,
    onSelect: () -> Unit,
    onRename: () -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Card(
            onClick = onSelect,
            colors = CardDefaults.colors(
                containerColor = if (isActive) {
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                } else {
                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                },
            ),
            modifier = Modifier
                .weight(1f)
                .focusable(),
        ) {
            Row(
                modifier = Modifier.padding(Spacing.md),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = entry.displayName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Normal,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = entry.url,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (isActive) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = "Active",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(28.dp),
                    )
                } else if (isPending) {
                    Text(
                        text = "Switching…",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }

        Surface(
            onClick = onRename,
            colors = ClickableSurfaceDefaults.colors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                contentColor = MaterialTheme.colorScheme.onSurface,
                focusedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.25f),
                focusedContentColor = MaterialTheme.colorScheme.onSurface,
            ),
        ) {
            Box(
                modifier = Modifier.size(width = 64.dp, height = 64.dp),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Default.Edit,
                    contentDescription = "Rename",
                    modifier = Modifier.size(28.dp),
                )
            }
        }

        Surface(
            onClick = onRemove,
            colors = ClickableSurfaceDefaults.colors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                contentColor = MaterialTheme.colorScheme.error,
                focusedContainerColor = MaterialTheme.colorScheme.error.copy(alpha = 0.25f),
                focusedContentColor = MaterialTheme.colorScheme.error,
            ),
        ) {
            Box(
                modifier = Modifier
                    .size(width = 64.dp, height = 64.dp),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = "Remove",
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(28.dp),
                )
            }
        }
    }
}
