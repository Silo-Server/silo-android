package org.siloserver.silo.android.ui.screens.downloads

import org.siloserver.silo.android.ui.util.formatBytes
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.DownloadForOffline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.material3.ScaffoldDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.siloserver.silo.android.ui.components.LoadingIndicator
import kotlinx.coroutines.launch
import org.siloserver.silo.android.ui.navigation.LocalBottomChromeInset
import org.koin.compose.viewmodel.koinViewModel

/**
 * Downloads tab. Header card shows section counts + storage usage; the list
 * below splits into Active (queued / in-flight, with progress), Ready
 * (completed, sorted by added-at via the server's record order), and
 * Failed (collapsed by default in v1 — just shows the count for now).
 *
 * @param onItemClick Called when a download item is tapped — typically
 *                    routes to ItemDetail.
 * @param showTopBar  Whether to show the screen's own top bar (false when
 *                    used as tab content under the main BottomNavBar).
 * @param onBackClick Back navigation handler for standalone mode.
 */
@Composable
fun DownloadsScreen(
    onItemClick: (DownloadItem) -> Unit,
    onReadEbook: (String, Int?) -> Unit = { _, _ -> },
    showTopBar: Boolean = false,
    onBackClick: (() -> Unit)? = null,
    contentTopPadding: Dp = 0.dp,
    viewModel: DownloadsViewModel = koinViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    // Multi-select delete (mirrors iOS's edit-mode selection). Selection unit
    // is a top-level entry — selecting a series selects all its episodes.
    var isSelecting by remember { mutableStateOf(false) }
    var selectedEntryIds by remember { mutableStateOf(emptySet<String>()) }
    var pendingBulkDelete by remember { mutableStateOf<Pair<List<String>, Long>?>(null) }
    val topLevelEntries = state.sections.flatMap { it.entries }
    val selectedEntries = topLevelEntries.filter { it.id in selectedEntryIds }
    val selectedRecordIds = selectedEntries.flatMap { it.recordIds }
    val selectedBytes = selectedEntries.sumOf { it.totalBytesUsed }
    // Prune selections whose entries were deleted or refreshed away.
    LaunchedEffect(state.sections) {
        val ids = topLevelEntries.map { it.id }.toSet()
        if (!selectedEntryIds.all { it in ids }) {
            selectedEntryIds = selectedEntryIds.filterTo(mutableSetOf()) { it in ids }
        }
    }

    fun exitSelectMode() {
        isSelecting = false
        selectedEntryIds = emptySet()
    }
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    // Surface delete/refresh failures — without this the error in uiState
    // was never rendered anywhere.
    LaunchedEffect(state.error) {
        val message = state.error ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message)
        viewModel.clearError()
    }

    Scaffold(
        topBar = {
            if (showTopBar) {
                org.siloserver.silo.android.ui.components.SiloTopBar(
                    title = "Downloads",
                    onBackClick = onBackClick,
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.background,
        // Embedded in the tab shell (showTopBar = false) the shared floating
        // header already accounts for the status bar via contentTopPadding;
        // letting Scaffold add its own status-bar inset doubles the top gap.
        contentWindowInsets = if (showTopBar) ScaffoldDefaults.contentWindowInsets else WindowInsets(0),
    ) { padding ->
        when {
            state.isLoading -> {
                LoadingIndicator(
                    modifier = Modifier.padding(
                        top = padding.calculateTopPadding() + contentTopPadding,
                    ),
                )
            }
            state.isEmpty && state.subscriptions.isEmpty() -> {
                val online = rememberOnlineState()
                DownloadsEmptyState(
                    isOnline = online,
                    modifier = Modifier.padding(
                        top = padding.calculateTopPadding() + contentTopPadding,
                    ),
                )
            }
            else -> Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
            ) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(top = 8.dp, bottom = 8.dp + LocalBottomChromeInset.current),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    item(contentType = "downloads-top-padding") {
                        Spacer(modifier = Modifier.height(contentTopPadding))
                    }

                    item(contentType = "downloads-total") {
                        DownloadsTotalCard(
                            totalBytesUsed = state.totalBytesUsed,
                            sectionCount = state.sections.size,
                        )
                    }

                    item(contentType = "downloads-actions") {
                        DownloadsActionRow(
                            hasMonitoredDownloads = state.subscriptions.isNotEmpty(),
                            isRunningMonitoredDownloads = state.isRunningMonitoredDownloads,
                            isReclaiming = state.isReclaiming,
                            showReclaimWatched = !state.keepWatchedDownloads,
                            onRunMonitoredDownloads = viewModel::runMonitoredDownloadsNow,
                            onReclaimWatched = viewModel::calculateReclaimWatched,
                        )
                    }

                    item(contentType = "downloads-monitored") {
                        MonitoredDownloadsCard(
                            subscriptions = state.subscriptions,
                            isRunning = state.isRunningMonitoredDownloads,
                            onRunNow = viewModel::runMonitoredDownloadsNow,
                        )
                    }

                    if (state.sections.isNotEmpty()) {
                        item(contentType = "downloads-select-bar") {
                            DownloadsSelectBar(
                                isSelecting = isSelecting,
                                selectedCount = selectedEntryIds.size,
                                allSelected = topLevelEntries.isNotEmpty() &&
                                    selectedEntryIds.size == topLevelEntries.size,
                                onEnterSelect = { isSelecting = true },
                                onToggleSelectAll = {
                                    selectedEntryIds = if (selectedEntryIds.size == topLevelEntries.size) {
                                        emptySet()
                                    } else {
                                        topLevelEntries.map { it.id }.toSet()
                                    }
                                },
                                onDone = { exitSelectMode() },
                            )
                        }
                    }

                    state.sections.forEach { section ->
                        renderSection(
                            section = section,
                            onItemClick = onItemClick,
                            onReadEbook = { item -> onReadEbook(item.contentId, item.fileId) },
                            onOpenExternalDownload = { item ->
                                if (!openDownloadInExternalApp(context, item)) {
                                    coroutineScope.launch {
                                        snackbarHostState.showSnackbar("No app found to open this file.")
                                    }
                                }
                            },
                            onDeleteSingle = { item -> viewModel.removeDownload(item.id) },
                            onDeleteEntry = { entry -> viewModel.removeEntry(entry) },
                            onDeleteSection = { sec -> viewModel.removeSection(sec) },
                            selecting = isSelecting,
                            selectedIds = selectedEntryIds,
                            onToggleSelect = { entry ->
                                selectedEntryIds = if (entry.id in selectedEntryIds) {
                                    selectedEntryIds - entry.id
                                } else {
                                    selectedEntryIds + entry.id
                                }
                            },
                        )
                    }

                    if (isSelecting && selectedEntryIds.isNotEmpty()) {
                        // Keep the last rows reachable above the floating bar.
                        item(contentType = "downloads-bulk-spacer") {
                            Spacer(modifier = Modifier.height(72.dp))
                        }
                    }
                }

                if (isSelecting && selectedEntryIds.isNotEmpty()) {
                    Button(
                        onClick = { pendingBulkDelete = selectedRecordIds to selectedBytes },
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(
                                start = 16.dp,
                                top = 16.dp,
                                end = 16.dp,
                                bottom = 16.dp + LocalBottomChromeInset.current,
                            ),
                    ) {
                        Text("Delete ${selectedEntryIds.size} · Free ${formatBytes(selectedBytes)}")
                    }
                }
            }
        }
    }

    pendingBulkDelete?.let { (ids, _) ->
        AlertDialog(
            onDismissRequest = { pendingBulkDelete = null },
            title = { Text("Delete downloaded files?") },
            text = {
                Text(
                    "This removes the downloaded cop${if (ids.size == 1) "y" else "ies"} from this device. " +
                        "Nothing is deleted from the server library.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.removeDownloadIds(ids)
                        pendingBulkDelete = null
                        exitSelectMode()
                    },
                ) {
                    Text(
                        text = if (ids.size == 1) "Delete Download" else "Delete ${ids.size} Downloads",
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingBulkDelete = null }) { Text("Cancel") }
            },
        )
    }

    state.reclaimPlan?.let { plan ->
        AlertDialog(
            onDismissRequest = viewModel::clearReclaimPlan,
            title = { Text("Reclaim Watched") },
            text = {
                Text(
                    if (plan.count == 0) {
                        "No watched downloads are ready to reclaim."
                    } else {
                        "Delete ${plan.count} watched download${if (plan.count == 1) "" else "s"} and free ${formatBytes(plan.totalBytes)}?"
                    },
                )
            },
            confirmButton = {
                TextButton(
                    enabled = plan.count > 0 && !state.isReclaiming,
                    onClick = viewModel::reclaimWatched,
                ) {
                    Text("Reclaim")
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::clearReclaimPlan) {
                    Text(if (plan.count == 0) "Close" else "Cancel")
                }
            },
        )
    }
}

/**
 * Compact card above the list — title + section count on the left,
 * total storage used on the right. Per-section size + per-row size are
 * shown inline (see SectionHeaderRow / AggregateRow), so the card just
 * summarises the whole device.
 */
@Composable
private fun DownloadsTotalCard(
    totalBytesUsed: Long,
    sectionCount: Int,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "Downloads",
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.SemiBold,
                fontSize = 16.sp,
            )
            Text(
                text = "$sectionCount media type${if (sectionCount == 1) "" else "s"}",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 13.sp,
            )
        }
        Text(
            text = formatBytes(totalBytesUsed),
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.Medium,
            fontSize = 14.sp,
        )
    }
}

/**
 * Select / Select All / Done controls above the sections — the phone
 * analogue of iOS's toolbar edit-mode buttons ("N Selected" nav title).
 */
@Composable
private fun DownloadsSelectBar(
    isSelecting: Boolean,
    selectedCount: Int,
    allSelected: Boolean,
    onEnterSelect: () -> Unit,
    onToggleSelectAll: () -> Unit,
    onDone: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (isSelecting) {
            TextButton(onClick = onToggleSelectAll) {
                Text(if (allSelected) "Clear" else "Select All")
            }
            Spacer(modifier = Modifier.weight(1f))
            Text(
                text = "$selectedCount Selected",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 13.sp,
            )
            TextButton(onClick = onDone) { Text("Done") }
        } else {
            Spacer(modifier = Modifier.weight(1f))
            TextButton(onClick = onEnterSelect) { Text("Select") }
        }
    }
}

@Composable
private fun DownloadsActionRow(
    hasMonitoredDownloads: Boolean,
    isRunningMonitoredDownloads: Boolean,
    isReclaiming: Boolean,
    showReclaimWatched: Boolean,
    onRunMonitoredDownloads: () -> Unit,
    onReclaimWatched: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        OutlinedButton(
            onClick = onRunMonitoredDownloads,
            enabled = hasMonitoredDownloads && !isRunningMonitoredDownloads,
            modifier = Modifier.weight(1f),
        ) {
            Text(if (isRunningMonitoredDownloads) "Checking..." else "Check Monitored")
        }
        if (showReclaimWatched) {
            Button(
                onClick = onReclaimWatched,
                enabled = !isReclaiming,
                modifier = Modifier.weight(1f),
            ) {
                Text(if (isReclaiming) "Reclaiming..." else "Reclaim Watched")
            }
        }
    }
}

@Composable
private fun MonitoredDownloadsCard(
    subscriptions: List<DownloadSubscriptionUiItem>,
    isRunning: Boolean,
    onRunNow: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.28f))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Monitored",
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 16.sp,
                )
                Text(
                    text = if (subscriptions.isEmpty()) {
                        "No monitored downloads yet"
                    } else {
                        "${subscriptions.size} active monitor${if (subscriptions.size == 1) "" else "s"}"
                    },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 13.sp,
                )
            }
            TextButton(
                onClick = onRunNow,
                enabled = subscriptions.isNotEmpty() && !isRunning,
            ) {
                Text(if (isRunning) "Checking..." else "Check Now")
            }
        }

        subscriptions.take(3).forEach { subscription ->
            Column {
                Text(
                    text = subscription.title,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                )
                Text(
                    text = subscription.lastError ?: subscription.subtitle,
                    color = if (subscription.lastError == null) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                    fontSize = 12.sp,
                    maxLines = 1,
                )
            }
        }
    }
}

@Suppress("unused")  // kept for reference, no longer wired into the tree
@Composable
private fun DownloadsHeaderCard(
    activeCount: Int,
    readyCount: Int,
    totalBytesUsed: Long,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "Downloads",
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.SemiBold,
                fontSize = 16.sp,
            )
            Text(
                text = buildString {
                    if (activeCount > 0) append("$activeCount active")
                    if (activeCount > 0 && readyCount > 0) append(" · ")
                    if (readyCount > 0) append("$readyCount ready")
                    if (activeCount == 0 && readyCount == 0) append("Nothing downloading")
                },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 13.sp,
            )
        }
        Text(
            text = formatBytes(totalBytesUsed),
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.Medium,
            fontSize = 14.sp,
        )
    }
}

@Composable
private fun SectionHeader(label: String) {
    Text(
        text = label.uppercase(),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontSize = 12.sp,
        fontWeight = FontWeight.Medium,
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 4.dp),
    )
}

/**
 * Empty-state for the Downloads tab. Switches copy + iconography based on
 * connectivity so the user knows whether the list is "you haven't downloaded
 * anything yet" (online) vs. "you're offline and nothing was cached here"
 * (offline — distinct because there might be plenty of stuff on the server).
 *
 * Visual: large circle-tinted icon, headline, supporting copy. Background
 * gradient matches the Home/Downloads header card surface (same dark tint).
 */
@Composable
private fun DownloadsEmptyState(
    isOnline: Boolean,
    modifier: Modifier = Modifier,
) {
    val icon: ImageVector
    val title: String
    val subtitle: String
    val accent: Color
    if (isOnline) {
        icon = Icons.Outlined.DownloadForOffline
        title = "No downloads yet"
        subtitle = "Tap Download on a movie, show, audiobook, or book to save the original file here for offline use."
        accent = MaterialTheme.colorScheme.primary
    } else {
        icon = Icons.Outlined.CloudOff
        title = "You're offline"
        subtitle = "Nothing is downloaded on this device yet. Save media while online and it will be available here without a connection."
        accent = Color(0xFFFFB86B)  // warm amber for offline state
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        // Tinted circle behind the icon for visual weight.
        Box(
            modifier = Modifier
                .size(120.dp)
                .clip(CircleShape)
                .background(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            accent.copy(alpha = 0.18f),
                            accent.copy(alpha = 0.04f),
                        ),
                    ),
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(56.dp),
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )

        Spacer(modifier = Modifier.height(10.dp))

        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(0.85f),
        )
    }
}

/** Lightweight reachability snapshot — true when the device has a validated
 *  internet capability, false on airplane mode / no signal. */
private fun isOnline(context: Context): Boolean {
    val cm = context.getSystemService(ConnectivityManager::class.java) ?: return true
    val net = cm.activeNetwork ?: return false
    val caps = cm.getNetworkCapabilities(net) ?: return false
    return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
        caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
}

/**
 * Reactive online-state for Compose. Initial value is the snapshot at first
 * composition; subsequent changes come from a NetworkCallback registered
 * against an INTERNET-capability request. Callback is unregistered on
 * disposal so it doesn't outlive the composition.
 */
@Composable
private fun rememberOnlineState(): Boolean {
    val context = LocalContext.current
    var online by remember { mutableStateOf(isOnline(context)) }
    DisposableEffect(Unit) {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { online = true }
            override fun onLost(network: Network) { online = isOnline(context) }
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                online = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                    caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            }
        }
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        cm?.registerNetworkCallback(request, callback)
        onDispose { cm?.unregisterNetworkCallback(callback) }
    }
    return online
}
