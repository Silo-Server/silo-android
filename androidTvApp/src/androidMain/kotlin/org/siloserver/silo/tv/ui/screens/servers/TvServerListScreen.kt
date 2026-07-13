package org.siloserver.silo.tv.ui.screens.servers

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
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
import androidx.compose.ui.graphics.Color
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
import org.siloserver.silo.model.server.ServerEntry
import org.siloserver.silo.tv.ui.components.TvDialogOption
import org.siloserver.silo.tv.ui.components.TvOptionDialog
import org.siloserver.silo.tv.ui.theme.Spacing
import org.siloserver.silo.tv.ui.theme.FocusedContainer
import org.siloserver.silo.tv.ui.theme.FocusedContent
import org.siloserver.silo.tv.ui.theme.InterFamily
import kotlinx.coroutines.delay
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

    BackHandler(enabled = true) { onBack() }

    LaunchedEffect(state.switchedTo) {
        val destination = state.switchedTo
        if (destination != null) {
            viewModel.onSwitchConsumed()
            onSwitched(destination)
        }
    }

    LaunchedEffect(state.needsServerSetup) {
        // The active server was removed and none remain — there is nothing to
        // sign into, so bounce to server setup rather than leaving the stale
        // shell behind this list pointed at an empty baseUrl.
        if (state.needsServerSetup) {
            viewModel.onServerSetupConsumed()
            onAddServer()
        }
    }

    LaunchedEffect(state.servers.size) {
        // Anchor focus on the first row whenever the list materializes so
        // d-pad navigation has somewhere to land.
        if (state.servers.isNotEmpty()) {
            repeat(TvInitialFocusRetryCount) {
                if (runCatching { firstFocus.requestFocus() }.isSuccess) return@LaunchedEffect
                delay(TvInitialFocusRetryDelayMs)
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(ServerSettingsBackground)
            .padding(start = 44.dp, top = Spacing.safeArea, end = 44.dp, bottom = Spacing.xxxl),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(32.dp)) {
            Column(
                modifier = Modifier.width(200.dp),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                Text(
                    text = "CONNECTION",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White.copy(alpha = 0.55f),
                )
                Text(
                    text = "Manage Servers",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White,
                )
                Text(
                    text = "Choose, rename, add, or remove a Silo server.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.62f),
                )
            }

            Column(
                modifier = Modifier.widthIn(max = ServerListMaxWidth),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = "Saved Servers",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White.copy(alpha = 0.62f),
                )
                AddServerTile(
                    onClick = onAddServer,
                    modifier = Modifier.focusRequester(
                        if (state.servers.isEmpty()) firstFocus else FocusRequester.Default,
                    ),
                )

                LazyColumn(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
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
                            onRemove = { confirmRemove = entry },
                            modifier = rowModifier,
                        )
                    }
                }
            }
        }
    }

    confirmRemove?.let { target ->
        // Removing the active server signs the user out of it — say so
        // explicitly instead of showing the same generic confirm as any
        // other row, so it isn't a silent footgun.
        val isActiveTarget = target.id == state.activeId
        TvOptionDialog(
            title = if (isActiveTarget) {
                "Sign out & remove ${target.displayName}?"
            } else {
                "Remove ${target.displayName}?"
            },
            options = listOf(
                TvDialogOption(
                    key = "confirm",
                    title = if (isActiveTarget) "Sign out & remove" else "Remove",
                    subtitle = if (isActiveTarget) {
                        "This is the server you're signed into — removing it will sign you out of it."
                    } else {
                        null
                    },
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

}

private const val TvInitialFocusRetryCount = 4
private const val TvInitialFocusRetryDelayMs = 50L
private val ServerSettingsBackground = Color(0xFF17181A)
private val ServerListMaxWidth = 620.dp
private val ServerRowShape = RoundedCornerShape(8.dp)

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun AddServerTile(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val foreground = if (isFocused) FocusedContent else Color.White
    Card(
        onClick = onClick,
        interactionSource = interactionSource,
        colors = CardDefaults.colors(
            containerColor = Color.White.copy(alpha = 0.055f),
            focusedContainerColor = FocusedContainer,
            focusedContentColor = FocusedContent,
        ),
        shape = CardDefaults.shape(shape = ServerRowShape),
        scale = CardDefaults.scale(focusedScale = 1f),
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Default.Add,
                contentDescription = null,
                tint = foreground,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(Spacing.sm))
            Text(
                text = "Add Server",
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = InterFamily,
                color = foreground,
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
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val interactionSource = remember(entry.id) { MutableInteractionSource() }
        val isFocused by interactionSource.collectIsFocusedAsState()
        val foreground = if (isFocused) FocusedContent else Color.White
        Card(
            onClick = onSelect,
            interactionSource = interactionSource,
            colors = CardDefaults.colors(
                containerColor = if (isActive) {
                    Color.White.copy(alpha = 0.10f)
                } else {
                    Color.White.copy(alpha = 0.055f)
                },
                focusedContainerColor = FocusedContainer,
                focusedContentColor = FocusedContent,
            ),
            shape = CardDefaults.shape(shape = ServerRowShape),
            scale = CardDefaults.scale(focusedScale = 1f),
            // The TV Card is already focusable; adding .focusable() here creates
            // a dead second focus stop (no visual, OK does nothing). Keep only
            // the weight, matching AddServerTile.
            modifier = Modifier.weight(1f),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = entry.fetchedName?.takeIf { it.isNotBlank() } ?: entry.displayName,
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = InterFamily,
                        fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Normal,
                        color = foreground,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = entry.url,
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = InterFamily,
                        color = foreground.copy(alpha = if (isFocused) 0.68f else 0.62f),
                    )
                }
                if (isActive) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = "Active",
                        tint = foreground.copy(alpha = 0.72f),
                        modifier = Modifier.size(20.dp),
                    )
                } else if (isPending) {
                    Text(
                        text = "Switching…",
                        style = MaterialTheme.typography.labelSmall,
                        color = foreground.copy(alpha = 0.72f),
                    )
                }
            }
        }

        Surface(
            onClick = onRemove,
            colors = ClickableSurfaceDefaults.colors(
                containerColor = Color.White.copy(alpha = 0.055f),
                contentColor = MaterialTheme.colorScheme.error,
                focusedContainerColor = FocusedContainer,
                focusedContentColor = FocusedContent,
            ),
            shape = ClickableSurfaceDefaults.shape(shape = ServerRowShape),
            scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        ) {
            Box(
                modifier = Modifier
                    .size(width = 48.dp, height = 48.dp),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = "Remove",
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}
