package org.siloserver.silo.android.ui.screens.requests

import org.siloserver.silo.android.ui.components.SiloDropdownMenuItem
import org.siloserver.silo.android.ui.components.SiloDropdownMenu
import org.siloserver.silo.common.requests.rememberRequestRouter
import org.siloserver.silo.model.request.MediaRequest
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.DownloadForOffline
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import org.siloserver.silo.android.ui.components.EmptyStateView
import org.siloserver.silo.android.ui.components.ErrorView
import org.siloserver.silo.android.ui.components.rememberShimmerProgress
import org.siloserver.silo.android.ui.theme.SiloNavPillBorder
import org.siloserver.silo.android.ui.theme.SiloOverlayPillSurface
import org.siloserver.silo.android.ui.theme.SiloSecondaryText
import org.siloserver.silo.model.request.MyRequestsBucket
import org.siloserver.silo.viewmodel.MyRequestsViewModel

/**
 * The signed-in user's requests as a grouped list in the Downloads manager's
 * shape: a stage track on every row, swipe to cancel, Open once a title
 * lands, and a filter menu in the bar.
 */
@Composable
fun MyRequestsScreen(
    onBackClick: () -> Unit,
    onRequestDetailClick: (mediaType: String, tmdbId: Int) -> Unit,
    onLibraryItemClick: (String) -> Unit,
    viewModel: MyRequestsViewModel = koinViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    val router = rememberRequestRouter(koinInject(), onLibraryItemClick, onRequestDetailClick)
    var filter by remember { mutableStateOf<MyRequestsBucket?>(null) }

    // A filter whose last request moved on would leave a blank list.
    LaunchedEffect(state.buckets) {
        if (filter != null && state.buckets.none { it.first == filter }) filter = null
    }

    RequestsLargeTitlePage(
        title = "My Requests",
        onBackClick = onBackClick,
        isRefreshing = state.isRefreshing,
        onRefresh = viewModel::refresh,
        itemSpacing = 0.dp,
        actions = {
            if (state.buckets.size > 1) {
                FilterMenu(buckets = state.buckets, filter = filter, onFilter = { filter = it })
            }
        },
    ) {
        when {
            state.error != null && state.buckets.isEmpty() -> item(key = "error") {
                ErrorView(message = state.error.orEmpty(), onRetry = viewModel::load, modifier = Modifier.padding(top = 60.dp))
            }
            state.isLoading && state.buckets.isEmpty() -> item(key = "loading") {
                Box(modifier = Modifier.padding(top = RequestGroupGap)) { RequestRowsSkeleton(progress = rememberShimmerProgress()) }
            }
            state.isEmpty -> item(key = "empty") {
                EmptyStateView(
                    title = "No requests yet",
                    subtitle = "Movies and series you request will show up here",
                    icon = Icons.Outlined.Inbox,
                    modifier = Modifier.padding(top = 80.dp),
                )
            }
            else -> {
                state.actionErrorMessage?.let { message ->
                    item(key = "action-error") {
                        Text(
                            text = message,
                            fontSize = 12.sp,
                            color = SiloSecondaryText,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth().padding(top = RequestGroupGap).padding(horizontal = 16.dp),
                        )
                    }
                }
                state.buckets.filter { filter == null || it.first == filter }.forEach { (bucket, requests) ->
                    requestGroupedSection(key = "bucket:${bucket.name}", title = bucket.title, items = requests, itemKey = { it.id }) { record, showDivider ->
                        MyRequestRow(
                            record = record,
                            isBusy = state.cancellingId == record.id,
                            showDivider = showDivider,
                            onOpen = { router.openRecord(record) },
                            onOpenInLibrary = record.libraryContentId?.takeIf { it.isNotBlank() }?.let { id -> { onLibraryItemClick(id) } },
                            onCancel = if (state.canCancel(record)) ({ viewModel.cancel(record) }) else null,
                        )
                    }
                }
            }
        }
    }
}

/** One control in the bar instead of a chip row that repeats the section headers. */
@Composable
private fun FilterMenu(
    buckets: List<Pair<MyRequestsBucket, List<MediaRequest>>>,
    filter: MyRequestsBucket?,
    onFilter: (MyRequestsBucket?) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(
            onClick = { open = true },
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(if (filter != null) Color.White.copy(alpha = 0.30f) else SiloOverlayPillSurface)
                .border(1.dp, SiloNavPillBorder, CircleShape),
        ) {
            Icon(
                imageVector = Icons.Filled.FilterList,
                contentDescription = filter?.let { "Filter: ${it.title}" } ?: "Filter",
                tint = Color.White,
            )
        }
        SiloDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            FilterItem(label = "All Requests", icon = Icons.Outlined.Inventory2, selected = filter == null) {
                open = false
                onFilter(null)
            }
            buckets.forEach { (bucket, requests) ->
                val icon = when (bucket) {
                    MyRequestsBucket.InMotion -> Icons.Outlined.DownloadForOffline
                    MyRequestsBucket.NeedsAttention -> Icons.Outlined.ErrorOutline
                    MyRequestsBucket.Landed -> Icons.Outlined.CheckCircle
                }
                FilterItem(label = "${bucket.title} (${requests.size})", icon = icon, selected = filter == bucket) {
                    open = false
                    onFilter(bucket)
                }
            }
        }
    }
}

@Composable
private fun FilterItem(label: String, icon: ImageVector, selected: Boolean, onClick: () -> Unit) {
    SiloDropdownMenuItem(
        text = { Text(label) },
        leadingIcon = {
            Box(modifier = Modifier.size(24.dp), contentAlignment = Alignment.Center) {
                if (selected) Icon(Icons.Filled.Check, contentDescription = "Selected") else Icon(icon, contentDescription = null)
            }
        },
        onClick = onClick,
    )
}
