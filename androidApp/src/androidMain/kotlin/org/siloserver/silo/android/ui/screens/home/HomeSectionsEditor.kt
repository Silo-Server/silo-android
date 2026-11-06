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
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import org.siloserver.silo.android.ui.components.EmptyStateView
import org.siloserver.silo.android.ui.components.ErrorView
import org.siloserver.silo.android.ui.components.SiloTopBar
import org.siloserver.silo.common.R as CommonR
import org.siloserver.silo.common.i18n.sectionTitle
import org.siloserver.silo.common.i18n.stringResourceWithNames
import org.siloserver.silo.model.section.ResolvedSection
import org.siloserver.silo.network.ServerRegistry
import org.siloserver.silo.repository.ProfileRepository
import org.siloserver.silo.viewmodel.HomeViewModel

/** Profile-scoped editor for the populated rows returned by Home. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeSectionsEditor(onDismiss: () -> Unit) {
    val viewModel: HomeViewModel = koinViewModel(key = "home-sections-editor")
    val state by viewModel.uiState.collectAsState()
    val preferences: HomeSectionPreferencesStore = koinInject()
    val preferenceRevision by preferences.revision.collectAsState()
    val registry: ServerRegistry = koinInject()
    val serverId by registry.activeServerId.collectAsState()
    val entry by registry.activeEntry.collectAsState()
    val profileRepository: ProfileRepository = koinInject()
    val profileId by produceState<String?>(
        initialValue = entry?.profileId,
        key1 = entry?.id,
        key2 = entry?.profileId,
        key3 = entry?.accountId,
    ) {
        value = entry?.profileId ?: profileRepository.getActiveProfileId()
    }
    val arranged = remember(state.sections, preferenceRevision, serverId, entry?.accountId, profileId) {
        preferences.arrangedSections(
            state.sections,
            includingHidden = true,
            profileId = profileId,
        )
    }
    val rows = remember { mutableStateListOf<ResolvedSection>() }
    var draggingId by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(arranged, draggingId) {
        if (draggingId == null) {
            rows.clear()
            rows.addAll(arranged)
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            containerColor = MaterialTheme.colorScheme.background,
            topBar = {
                SiloTopBar(
                    title = stringResource(CommonR.string.home_editor_title),
                    onBackClick = onDismiss,
                    containerColor = MaterialTheme.colorScheme.background,
                )
            },
        ) { padding ->
            PullToRefreshBox(
                isRefreshing = state.isRefreshing,
                onRefresh = viewModel::refresh,
                modifier = Modifier.fillMaxSize().padding(padding),
            ) {
                when {
                    state.isLoading && rows.isEmpty() -> Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) { CircularProgressIndicator() }
                    state.error != null && rows.isEmpty() -> ErrorView(
                        message = state.error ?: stringResource(CommonR.string.home_editor_loadFailed),
                        onRetry = viewModel::loadSections,
                    )
                    rows.isEmpty() -> EmptyStateView(
                        title = stringResource(CommonR.string.home_editor_empty),
                        subtitle = stringResource(CommonR.string.home_editor_emptyBody),
                    )
                    else -> HomeSectionReorderList(
                        rows = rows,
                        preferences = preferences,
                        profileId = profileId,
                        preferenceRevision = preferenceRevision,
                        draggingId = draggingId,
                        onDraggingIdChange = { draggingId = it },
                    )
                }
            }
        }
    }
}

@Composable
private fun HomeSectionReorderList(
    rows: MutableList<ResolvedSection>,
    preferences: HomeSectionPreferencesStore,
    profileId: String?,
    preferenceRevision: Long,
    draggingId: String?,
    onDraggingIdChange: (String?) -> Unit,
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
                text = stringResource(CommonR.string.home_editor_instructions),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp),
            )
        }
        items(rows, key = { it.id }) { section ->
            // SharedPreferences itself is not observable. Reading the store's
            // revision here makes each row recompose immediately after an eye
            // toggle instead of showing stale state until the dialog reopens.
            val isVisible = remember(section.id, profileId, preferenceRevision) {
                preferences.isVisible(section.id, profileId)
            }
            val isDragging = draggingId == section.id
            val title = sectionTitle(section)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .graphicsLayer { translationY = if (isDragging) dragOffset else 0f }
                    .background(if (isDragging) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surface)
                    .padding(horizontal = 8.dp, vertical = 6.dp)
                    .graphicsLayer { alpha = if (isVisible) 1f else 0.42f },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(
                    onClick = {
                        preferences.setVisible(section.id, !isVisible, profileId)
                    },
                ) {
                    Icon(
                        imageVector = if (isVisible) Icons.Filled.Visibility else Icons.Filled.VisibilityOff,
                        contentDescription = if (isVisible) {
                            stringResourceWithNames(CommonR.string.home_editor_hide, title)
                        } else {
                            stringResourceWithNames(CommonR.string.home_editor_show, title)
                        },
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = title, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
                    Text(
                        text = pluralStringResource(CommonR.plurals.rowPage_item, section.items.size, section.items.size),
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
                                onDragCancel = {
                                    dragOffset = 0f
                                    onDraggingIdChange(null)
                                },
                                onDragEnd = {
                                    preferences.setOrder(rows.map { it.id }, profileId)
                                    dragOffset = 0f
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
                                        // Persist each accepted move. If the
                                        // system cancels the pointer gesture,
                                        // the rows never snap back on exit.
                                        preferences.setOrder(rows.map { it.id }, profileId)
                                        dragOffset = 0f
                                    }
                                },
                            )
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Filled.DragHandle,
                        contentDescription = stringResourceWithNames(CommonR.string.home_editor_reorder, title),
                    )
                }
            }
        }
        item(key = "bottom") { Spacer(Modifier.size(24.dp)) }
    }
}
