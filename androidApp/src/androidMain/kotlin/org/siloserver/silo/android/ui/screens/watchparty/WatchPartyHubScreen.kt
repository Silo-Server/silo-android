package org.siloserver.silo.android.ui.screens.watchparty

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import org.siloserver.silo.android.cast.SiloCastSessionManager
import org.siloserver.silo.android.ui.components.SiloConfirmDialog
import org.siloserver.silo.android.ui.components.SiloTopBar
import org.siloserver.silo.model.feature.WatchPartyExposure
import org.siloserver.silo.model.watchtogether.MemberRole
import org.siloserver.silo.model.watchtogether.RoomSnapshot
import org.siloserver.silo.repository.WatchPartyPendingAction
import org.siloserver.silo.repository.WatchTogetherRepository
import org.siloserver.silo.viewmodel.WatchPartyHubViewModel
import org.siloserver.silo.watchtogether.RoomSession
import org.siloserver.silo.watchtogether.WATCH_PARTY_CODE_LENGTH
import org.siloserver.silo.watchtogether.WatchPartyAvailability
import org.siloserver.silo.watchtogether.WatchPartyDestination
import org.siloserver.silo.watchtogether.normalizeWatchPartyCode

/**
 * The Watch Party hub, opened from the profile menu, a detail page's Watch
 * Party action, or an invitation. It shows whether this server supports
 * Watch Party, returns to the live party, rejoins the recent one, and hosts
 * or joins.
 *
 * [inviteId] and [hostId] name one-shot handoffs held by [WatchPartyHandoff];
 * each is taken once, so recreation or a restored route never replays it.
 */
@Composable
fun WatchPartyHubScreen(
    inviteId: String?,
    hostId: String?,
    onBack: () -> Unit,
    onOpenLobby: (roomId: String) -> Unit,
    onOpenPlayer: (RoomSnapshot) -> Unit,
    viewModel: WatchPartyHubViewModel = koinViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    val handoff: WatchPartyHandoff = koinInject()
    val repository: WatchTogetherRepository = koinInject()
    val roomSession: RoomSession = koinInject()
    val exposure: WatchPartyExposure = koinInject()
    val enabled by exposure.enabled.collectAsState()
    val castManager: SiloCastSessionManager = koinInject()
    val castState by castManager.castState.collectAsState()
    val casting = castState.isConnected
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var code by rememberSaveable { mutableStateOf("") }
    var dismissedEnded by rememberSaveable { mutableStateOf<String?>(null) }
    var confirmHostLeave by rememberSaveable { mutableStateOf(false) }

    val available = state.availability is WatchPartyAvailability.Available
    // Keep the handoff until this server supports room and playback
    // capabilities, including while a failed probe waits for Retry.
    LaunchedEffect(inviteId, available, enabled, casting) {
        if (!available || !enabled || casting) return@LaunchedEffect
        handoff.takeInvite(inviteId)?.let(viewModel::joinInvite)
    }

    // A detail page's "Watch Party": host with that item once the server is
    // known to support hosting. Unsupported servers show why instead.
    LaunchedEffect(hostId, available, enabled, casting) {
        if (!available || !enabled || casting) return@LaunchedEffect
        handoff.takeHost(hostId)?.let(viewModel::hostWithItem)
    }

    LaunchedEffect(state.destination, casting) {
        if (casting) return@LaunchedEffect
        val destination = state.destination ?: return@LaunchedEffect
        viewModel.consumeDestination()
        when (destination) {
            is WatchPartyDestination.Lobby -> onOpenLobby(destination.roomId)
            is WatchPartyDestination.Player -> {
                val room = repository.roomSnapshot.value?.takeIf { it.roomId == destination.roomId }
                if (room != null) onOpenPlayer(room) else onOpenLobby(destination.roomId)
            }
        }
    }

    val busy = state.busy != null
    val current = state.current

    // Reload the recent party whenever the hub is shown again after a party
    // ended or was left, so Rejoin reflects what just happened.
    LaunchedEffect(current?.roomId, state.ended) {
        if (current == null) viewModel.refresh()
    }
    val join: () -> Unit = {
        if (!busy) viewModel.join(code)
    }

    // A party uses this device's player. Wait for the receiver to disconnect
    // before joining, creating, or consuming a detail/invitation handoff.
    if (enabled && casting) {
        SiloConfirmDialog(
            title = "Stop casting to use Watch Party?",
            body = "Casting will stop. Watch Party plays on this device.",
            confirmLabel = "Stop casting",
            onConfirm = castManager::disconnect,
            onDismiss = onBack,
        )
        return
    }

    Scaffold(
        topBar = { SiloTopBar(title = "Watch Party", onBackClick = onBack) },
    ) { padding ->
        Column(
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            // Why the last party ended; Rejoin when the party itself goes on.
            val ended = state.ended
            val endedKey = ended?.let { "${it.roomId}:${it.reason}" }
            val showEnded = ended != null && current == null && dismissedEnded != endedKey
            val endedRejoin = showEnded && enabled && available && ended != null &&
                watchPartyEndedOffersRejoin(ended.reason) && state.recent?.roomId == ended.roomId
            if (showEnded && ended != null) {
                WatchPartyBanner(
                    text = watchPartyEndedMessage(ended.reason),
                    action = {
                        if (endedRejoin) {
                            TextButton(onClick = viewModel::rejoinRecent, enabled = !busy) { Text("Rejoin") }
                        }
                        IconButton(onClick = { dismissedEnded = endedKey }) {
                            Icon(Icons.Filled.Close, contentDescription = "Dismiss")
                        }
                    },
                )
            }

            if (!enabled) {
                Text(
                    "Watch Party is turned off. Turn it on in Settings, under Experimental.",
                    style = MaterialTheme.typography.bodyLarge,
                )
                return@Column
            }

            when (val availability = state.availability) {
                null -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(12.dp))
                    Text("Checking this server…", style = MaterialTheme.typography.bodyLarge)
                }
                is WatchPartyAvailability.Unsupported -> Text(
                    "This server doesn't support Watch Party yet.",
                    style = MaterialTheme.typography.bodyLarge,
                )
                WatchPartyAvailability.NotAllowed -> Text(
                    "Your account can't use Watch Party.",
                    style = MaterialTheme.typography.bodyLarge,
                )
                is WatchPartyAvailability.ProbeFailed -> WatchPartyBanner(
                    text = "Couldn't check whether this server supports Watch Party.",
                    action = {
                        TextButton(onClick = { viewModel.refresh(force = true) }) { Text("Retry") }
                    },
                )
                is WatchPartyAvailability.Available -> Unit
            }

            // Errors stay inline; an uncertain create keeps its room id for Retry.
            state.error?.let { message ->
                WatchPartyBanner(
                    text = message,
                    action = {
                        if (state.createRetryable) {
                            TextButton(onClick = viewModel::retryCreate, enabled = !busy) { Text("Retry") }
                        }
                        IconButton(onClick = viewModel::clearError) {
                            Icon(Icons.Filled.Close, contentDescription = "Dismiss")
                        }
                    },
                )
            }

            if (busy) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(12.dp))
                    Text(
                        when (state.busy) {
                            WatchPartyPendingAction.Create -> "Starting your party…"
                            WatchPartyPendingAction.Join -> "Joining…"
                            WatchPartyPendingAction.Stage -> "Adding your title…"
                            else -> "Working…"
                        },
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            }

            if (current != null) {
                Text(
                    "You're in a Watch Party" + current.code.takeIf { it.isNotBlank() }?.let { " ($it)" }.orEmpty() + ".",
                    style = MaterialTheme.typography.bodyLarge,
                )
                Button(
                    onClick = viewModel::returnToParty,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                ) { Text("Return to Party") }
                OutlinedButton(
                    onClick = {
                        if (current.selfRole == MemberRole.Host) confirmHostLeave = true else roomSession.depart()
                    },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                ) { Text("Leave party") }
                Text(
                    "Leave this party to host or join another one.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                return@Column
            }

            if (!available) return@Column

            state.recent?.takeIf { !endedRejoin }?.let { recent ->
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Recent party", style = MaterialTheme.typography.titleSmall)
                    OutlinedButton(
                        onClick = viewModel::rejoinRecent,
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    ) {
                        Text(
                            recent.title?.takeIf { it.isNotBlank() }?.let { "Rejoin $it (${recent.code})" }
                                ?: "Rejoin ${recent.code}",
                        )
                    }
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Host", style = MaterialTheme.typography.titleSmall)
                Text(
                    "You pick what everyone watches. Guests can suggest titles.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(
                    onClick = { viewModel.host() },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                ) { Text("Host a party") }
            }

            HorizontalDivider()

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Join", style = MaterialTheme.typography.titleSmall)
                OutlinedTextField(
                    value = code,
                    onValueChange = { input ->
                        code = input.uppercase()
                            .filterNot { it.isWhitespace() || it == '-' }
                            .take(WATCH_PARTY_CODE_LENGTH)
                    },
                    label = { Text("Party code") },
                    singleLine = true,
                    enabled = !busy,
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Characters,
                        autoCorrectEnabled = false,
                        keyboardType = KeyboardType.Ascii,
                        imeAction = ImeAction.Go,
                    ),
                    keyboardActions = KeyboardActions(onGo = {
                        if (normalizeWatchPartyCode(code) != null) join()
                    }),
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(
                    onClick = join,
                    enabled = !busy && normalizeWatchPartyCode(code) != null,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                ) { Text("Join with code") }
                OutlinedButton(
                    onClick = {
                        val pasted = clipboard.getText()?.text?.trim().orEmpty()
                        if (pasted.isEmpty()) {
                            Toast.makeText(context, "Copy an invitation or party code first.", Toast.LENGTH_SHORT).show()
                        } else {
                            viewModel.join(pasted)
                        }
                    },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                ) {
                    Icon(Icons.Outlined.ContentPaste, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Paste invitation")
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (confirmHostLeave) {
        WatchPartyHostLeaveDialog(
            onConfirm = {
                confirmHostLeave = false
                roomSession.depart()
            },
            onDismiss = { confirmHostLeave = false },
        )
    }
}
