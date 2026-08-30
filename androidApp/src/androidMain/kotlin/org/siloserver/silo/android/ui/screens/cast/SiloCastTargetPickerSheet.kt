package org.siloserver.silo.android.ui.screens.cast

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Tv
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import org.koin.compose.koinInject
import org.siloserver.silo.android.cast.SiloCastController
import org.siloserver.silo.cast.SiloCastLaunchRequest
import org.siloserver.silo.cast.SiloCastProtocol
import org.siloserver.silo.common.cast.SiloCastTarget
import org.siloserver.silo.network.AndroidServerRegistry
import org.siloserver.silo.network.ServerRegistry

/**
 * TV picker sheet, mirroring silo-apple's `SiloControlTargetPickerView`:
 * searching → 8 s timeout empty state → found list with per-row context
 * ("Playing now", server name, cross-server and update-required hints) and a
 * per-row connecting spinner. Rows are tappable end to end.
 *
 * With a [launchRequest] the sheet lists every discovered TV (cross-server
 * casts do the v2 profile handoff); without one it filters to TVs on the
 * active server, which are the only ones controllable without a handoff.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SiloCastTargetPickerSheet(
    launchRequest: SiloCastLaunchRequest? = null,
    onDismiss: () -> Unit,
    onLaunched: () -> Unit = {},
    controller: SiloCastController = koinInject(),
    serverRegistry: ServerRegistry = koinInject(),
) {
    val state by controller.state.collectAsState()
    val activeServerId = serverRegistry.activeEntry.collectAsState().value?.id
    var searchTimedOut by remember { mutableStateOf(false) }
    // Remote-only mode keeps the sheet open through the connect (the row shows
    // a spinner, like Apple's picker) and dismisses once the session is up.
    var pendingConnectDeviceId by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(controller) {
        controller.startBrowsing()
        delay(8_000)
        searchTimedOut = true
    }

    // pendingConnectDeviceId must be a key: tapping the already-connected TV
    // changes only the pending id (the connection state never moves), and the
    // sheet still has to dismiss.
    LaunchedEffect(pendingConnectDeviceId, state.connectedTarget?.deviceId, state.isConnecting) {
        val pending = pendingConnectDeviceId ?: return@LaunchedEffect
        if (!state.isConnecting && state.connectedTarget?.deviceId == pending) {
            onDismiss()
        }
    }

    val displayedTargets = if (launchRequest != null) {
        state.targets
    } else {
        state.targets.filter {
            AndroidServerRegistry.serverIdsMatch(it.serverId, activeServerId)
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 4.dp),
        ) {
            Box(modifier = Modifier.fillMaxWidth()) {
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.align(Alignment.CenterStart),
                ) { Text("Done") }
                Text(
                    if (launchRequest == null) "Remote Control" else "Play on Silo TV",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.align(Alignment.Center),
                )
            }

            when {
                displayedTargets.isNotEmpty() -> {
                    displayedTargets.forEach { target ->
                        TargetRow(
                            target = target,
                            launchRequest = launchRequest,
                            activeServerId = activeServerId,
                            isConnectingToThis = state.connectingDeviceId == target.deviceId,
                            onSelect = {
                                if (launchRequest == null) {
                                    pendingConnectDeviceId = target.deviceId
                                    controller.connect(target)
                                } else {
                                    // Launch mode dismisses straight into the
                                    // remote — the connect/handoff handshake
                                    // renders there (Apple's auto-present).
                                    controller.launchOnTarget(target, launchRequest)
                                    onDismiss()
                                    onLaunched()
                                }
                            },
                        )
                    }
                }
                searchTimedOut -> {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 36.dp),
                    ) {
                        Icon(
                            Icons.Outlined.Tv,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(36.dp),
                        )
                        Text(
                            "No Silo TVs Found",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            "Foreground Silo TVs on this network appear here.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
                else -> {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 36.dp),
                    ) {
                        CircularProgressIndicator()
                        Text(
                            "Searching for Silo TVs…",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            state.error?.let { error ->
                Text(
                    error,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }

            Spacer(modifier = Modifier.height(20.dp))
        }
    }
}

@Composable
private fun TargetRow(
    target: SiloCastTarget,
    launchRequest: SiloCastLaunchRequest?,
    activeServerId: String?,
    isConnectingToThis: Boolean,
    onSelect: () -> Unit,
) {
    val needsUpdate = target.version < SiloCastProtocol.version
    val enabled = !needsUpdate
    val targetsActiveServer = AndroidServerRegistry.serverIdsMatch(target.serverId, activeServerId)

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onSelect)
            .padding(vertical = 10.dp),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(38.dp)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh, CircleShape),
        ) {
            Icon(
                Icons.Outlined.Tv,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(20.dp),
            )
        }

        Column(modifier = Modifier.weight(1f)) {
            Text(
                target.name,
                style = MaterialTheme.typography.titleSmall,
                color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val subtitle: Pair<String, androidx.compose.ui.graphics.Color>? = when {
                needsUpdate ->
                    "Update Silo on this TV to use your profile" to MaterialTheme.colorScheme.onSurfaceVariant
                target.isPlaying ->
                    "Playing now" to MaterialTheme.colorScheme.primary
                targetsActiveServer && target.serverName != null ->
                    target.serverName!! to MaterialTheme.colorScheme.onSurfaceVariant
                target.serverId != null && !targetsActiveServer ->
                    "Will temporarily use your server" to MaterialTheme.colorScheme.onSurfaceVariant
                else -> null
            }
            subtitle?.let { (text, color) ->
                Text(text, style = MaterialTheme.typography.bodySmall, color = color)
            }
        }

        if (isConnectingToThis) {
            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
        }
    }
}
