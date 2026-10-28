package org.siloserver.silo.android.ui.screens.home

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import org.koin.compose.viewmodel.koinViewModel
import org.siloserver.silo.android.ui.components.EmptyStateView
import org.siloserver.silo.android.ui.components.ErrorView
import org.siloserver.silo.android.ui.components.SiloTopBar
import org.siloserver.silo.model.section.ProfileSectionSetting
import org.siloserver.silo.viewmodel.HomeSectionsEditorViewModel

/**
 * Editor for the active profile's Home rows. The order and visibility live
 * on the server, so they apply to this profile on every device and show in
 * the admin's Home sections card; each change saves at once.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeSectionsEditor(
    onDismiss: () -> Unit,
    viewModel: HomeSectionsEditorViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsState()
    // Re-read on every open: another device or the admin may have changed
    // the layout since this editor was last shown.
    LaunchedEffect(viewModel) { viewModel.load() }
    val rows = remember { mutableStateListOf<ProfileSectionSetting>() }
    var draggingId by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(state.rows, draggingId) {
        if (draggingId == null) {
            rows.clear()
            rows.addAll(state.rows)
        }
    }
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(state.saveError) {
        val message = state.saveError ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message)
        viewModel.dismissSaveError()
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            containerColor = MaterialTheme.colorScheme.background,
            snackbarHost = { SnackbarHost(snackbarHostState) },
            topBar = {
                SiloTopBar(
                    title = "Home Sections",
                    onBackClick = onDismiss,
                    containerColor = MaterialTheme.colorScheme.background,
                )
            },
        ) { padding ->
            PullToRefreshBox(
                isRefreshing = state.isLoading && rows.isNotEmpty(),
                onRefresh = viewModel::load,
                modifier = Modifier.fillMaxSize().padding(padding),
            ) {
                when {
                    state.isLoading && rows.isEmpty() -> Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) { CircularProgressIndicator() }
                    state.loadError != null && rows.isEmpty() -> ErrorView(
                        message = state.loadError ?: "Could not load Home sections.",
                        onRetry = viewModel::load,
                    )
                    rows.isEmpty() -> EmptyStateView(
                        title = "No Home sections",
                        subtitle = "This profile's Home has no rows to arrange yet.",
                    )
                    else -> HomeSectionReorderList(
                        rows = rows,
                        draggingId = draggingId,
                        onDraggingIdChange = { draggingId = it },
                        onDragFinished = { viewModel.setOrder(rows.map { it.id }) },
                        onMove = viewModel::move,
                        onToggleHidden = { row -> viewModel.setHidden(row.id, !row.hidden) },
                    )
                }
            }
        }
    }
}

@Composable
private fun HomeSectionReorderList(
    rows: MutableList<ProfileSectionSetting>,
    draggingId: String?,
    onDraggingIdChange: (String?) -> Unit,
    onDragFinished: () -> Unit,
    onMove: (id: String, offset: Int) -> Unit,
    onToggleHidden: (ProfileSectionSetting) -> Unit,
) {
    val listState = rememberLazyListState()
    var dragOffset by remember { mutableFloatStateOf(0f) }
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        userScrollEnabled = draggingId == null,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        item(key = "instructions") {
            Text(
                text = "Open eye: shown on Home. Closed eye: hidden. Drag the handle to reorder. " +
                    "Changes save to this profile and apply on every device.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp),
            )
        }
        items(rows, key = { it.id }) { section ->
            val isVisible = !section.hidden
            val isDragging = draggingId == section.id
            val index = rows.indexOfFirst { it.id == section.id }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .graphicsLayer { translationY = if (isDragging) dragOffset else 0f }
                    .background(if (isDragging) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surface)
                    .padding(horizontal = 8.dp, vertical = 6.dp)
                    .graphicsLayer { alpha = if (isVisible) 1f else 0.42f }
                    // Dragging is touch-only; TalkBack and switch users move
                    // rows with these actions instead.
                    .semantics {
                        customActions = buildList {
                            if (index > 0) {
                                add(CustomAccessibilityAction("Move up") { onMove(section.id, -1); true })
                            }
                            if (index in 0 until rows.lastIndex) {
                                add(CustomAccessibilityAction("Move down") { onMove(section.id, 1); true })
                            }
                        }
                    },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { onToggleHidden(section) }) {
                    Icon(
                        imageVector = if (isVisible) Icons.Filled.Visibility else Icons.Filled.VisibilityOff,
                        contentDescription = if (isVisible) "Hide ${section.title}" else "Show ${section.title}",
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = section.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
                    Text(
                        text = if (isVisible) "Shown on Home" else "Hidden from Home",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .pointerInput(section.id, rows.size) {
                            detectDragGestures(
                                onDragStart = {
                                    onDraggingIdChange(section.id)
                                    dragOffset = 0f
                                },
                                // A cancelled drag still keeps the moves it
                                // made, so it saves like a finished one.
                                onDragCancel = {
                                    dragOffset = 0f
                                    onDragFinished()
                                    onDraggingIdChange(null)
                                },
                                onDragEnd = {
                                    dragOffset = 0f
                                    onDragFinished()
                                    onDraggingIdChange(null)
                                },
                                onDrag = { change, amount ->
                                    change.consume()
                                    dragOffset += amount.y
                                    val fromIndex = rows.indexOfFirst { it.id == section.id }
                                    val fromInfo = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == section.id }
                                    if (fromIndex < 0 || fromInfo == null) return@detectDragGestures
                                    val draggedCenter = fromInfo.offset + dragOffset + fromInfo.size / 2f
                                    val targetInfo = listState.layoutInfo.visibleItemsInfo.firstOrNull { info ->
                                        info.key != "instructions" && draggedCenter.toInt() in info.offset..(info.offset + info.size)
                                    } ?: return@detectDragGestures
                                    val targetId = targetInfo.key as? String ?: return@detectDragGestures
                                    val toIndex = rows.indexOfFirst { it.id == targetId }
                                    if (toIndex >= 0 && toIndex != fromIndex) {
                                        val moved = rows.removeAt(fromIndex)
                                        rows.add(toIndex, moved)
                                        dragOffset = 0f
                                    }
                                },
                            )
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Filled.DragHandle, contentDescription = "Reorder ${section.title}")
                }
            }
        }
        item(key = "bottom") { Spacer(Modifier.size(24.dp)) }
    }
}
