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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Tv
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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
 *
 * Rokus (silo-roku `docs/silocontrol.md` section 4.4) list beside the other
 * TVs. A phone cannot open the Roku app, so a remembered Roku that discovery
 * doesn't find gets "Open Silo on your Roku", and Connect by Address adds one
 * from its pairing link or the address its Connect a Phone screen shows, for
 * networks that block multicast.
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
    val activeServer = serverRegistry.activeEntry.collectAsState().value
    val activeServerId = activeServer?.id
    val activeServerIdentity = activeServer?.verifiedServerId
    var searchTimedOut by remember { mutableStateOf(false) }
    // Remote-only mode keeps the sheet open through the connect (the row shows
    // a spinner, like Apple's picker) and dismisses once the session is up.
    var pendingConnectDeviceId by remember { mutableStateOf<String?>(null) }
    var showAddressEntry by remember { mutableStateOf(false) }

    fun select(target: SiloCastTarget) {
        if (launchRequest == null) {
            pendingConnectDeviceId = target.deviceId
            controller.connect(target)
        } else {
            // Launch mode dismisses straight into the remote — the
            // connect/handoff handshake renders there (Apple's auto-present).
            controller.launchOnTarget(target, launchRequest)
            onDismiss()
            onLaunched()
        }
    }

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
            AndroidServerRegistry.serversMatch(it.serverId, it.serverIdentity, activeServerId, activeServerIdentity)
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
                            activeServerIdentity = activeServerIdentity,
                            isConnectingToThis = state.connectingDeviceId == target.deviceId,
                            needsPairing = controller.needsPairing(target),
                            onSelect = { select(target) },
                            onForget = if (controller.isRememberedRoku(target.deviceId)) {
                                { controller.forgetRoku(target.deviceId) }
                            } else {
                                null
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

            // Rokus can't be opened from a phone; say what to do instead.
            val missingRoku = remember(state.targets, searchTimedOut) {
                if (searchTimedOut) controller.missingRememberedRoku() else null
            }
            missingRoku?.let { roku ->
                Text(
                    "${roku.name} isn't showing up. Open Silo on your Roku.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }

            if (showAddressEntry) {
                AddRokuByAddress(controller = controller, onAdded = ::select)
            } else {
                TextButton(
                    onClick = { showAddressEntry = true },
                    modifier = Modifier.padding(top = 4.dp),
                ) { Text("Connect by Address") }
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
    activeServerIdentity: String?,
    isConnectingToThis: Boolean,
    needsPairing: Boolean,
    onSelect: () -> Unit,
    /** A Roku added by link or address: forgets it and its pairing key. */
    onForget: (() -> Unit)?,
) {
    val needsUpdate = target.version < SiloCastProtocol.version
    val targetsActiveServer =
        AndroidServerRegistry.serversMatch(target.serverId, target.serverIdentity, activeServerId, activeServerIdentity)
    // A Roku plays only from its own server for now (silo-roku
    // `docs/silocontrol.md` section 8.4); it would refuse the handoff.
    val rokuOnOtherServer = target.isRoku && target.serverId != null && !targetsActiveServer
    val enabled = !needsUpdate && !rokuOnOtherServer

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
                rokuOnOtherServer ->
                    "Plays from ${target.serverName ?: "another server"}" to MaterialTheme.colorScheme.onSurfaceVariant
                needsPairing ->
                    "Scan the pairing code on this Roku" to MaterialTheme.colorScheme.onSurfaceVariant
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
        } else if (onForget != null) {
            IconButton(onClick = onForget) {
                Icon(
                    Icons.Outlined.Close,
                    contentDescription = "Forget ${target.name}",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

/**
 * Adds a Roku from a typed (or pasted) pairing link or address, then selects
 * it. Scanning the Roku's QR code with the camera opens the same link.
 */
@Composable
private fun AddRokuByAddress(
    controller: SiloCastController,
    onAdded: (SiloCastTarget) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf("") }
    var adding by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }

    fun add() {
        if (adding || text.isBlank()) return
        adding = true
        problem = null
        scope.launch {
            try {
                onAdded(controller.addRoku(text))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                problem = e.message ?: "Couldn't add that Roku."
            } finally {
                adding = false
            }
        }
    }

    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp),
    ) {
        Text(
            "On your Roku, open Silo's profile menu and choose Connect a Phone. Enter the address it shows, " +
                "or scan its code with your camera.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = text,
                onValueChange = {
                    text = it
                    problem = null
                },
                singleLine = true,
                placeholder = { Text("192.168.1.20:52817") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { add() }),
                modifier = Modifier.weight(1f),
            )
            if (adding) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            } else {
                TextButton(onClick = ::add, enabled = text.isNotBlank()) { Text("Connect") }
            }
        }
        problem?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
    }
}
