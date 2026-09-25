package org.siloserver.silo.android.ui.screens.watchparty

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import org.siloserver.silo.android.cast.SiloCastSessionManager
import org.siloserver.silo.android.ui.components.SiloConfirmDialog
import org.siloserver.silo.android.ui.theme.SiloOnSurface
import org.siloserver.silo.android.ui.theme.SiloSecondaryText
import org.siloserver.silo.model.feature.WatchPartyExposure
import org.siloserver.silo.model.watchtogether.MemberRole
import org.siloserver.silo.model.watchtogether.RoomSelectionMode
import org.siloserver.silo.model.watchtogether.RoomSnapshot
import org.siloserver.silo.repository.WatchPartyPendingAction
import org.siloserver.silo.repository.WatchTogetherRepository
import org.siloserver.silo.viewmodel.WatchPartyHubViewModel
import org.siloserver.silo.watchtogether.RoomSession
import org.siloserver.silo.watchtogether.WatchPartyAvailability
import org.siloserver.silo.watchtogether.WatchPartyDestination

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
        if (!busy) viewModel.join(code.trim())
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

    val canEnter = enabled && available && !busy
    Box(Modifier.fillMaxSize()) {
        WatchPartyBackdrop(url = null)
        Column(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)),
        ) {
            WatchPartyTopBar(onBack = onBack)
            Column(
                verticalArrangement = Arrangement.spacedBy(28.dp),
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .imePadding()
                    .navigationBarsPadding()
                    .padding(horizontal = WatchPartyMetrics.pageInset)
                    .padding(bottom = 40.dp),
            ) {
                HubHeadline(Modifier.padding(top = 24.dp))

                // Why the last party ended; Rejoin when the party itself goes on.
                val ended = state.ended
                val endedKey = ended?.let { "${it.roomId}:${it.reason}" }
                val showEnded = ended != null && current == null && dismissedEnded != endedKey
                val endedRejoin = showEnded && enabled && available && ended != null &&
                    watchPartyEndedOffersRejoin(ended.reason) && state.recent?.roomId == ended.roomId
                val banners = showEnded || !enabled || !available || state.error != null || busy
                if (banners) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (showEnded && ended != null) {
                            WatchPartyBanner(
                                text = watchPartyEndedMessage(ended.reason),
                                action = {
                                    if (endedRejoin) {
                                        TextButton(onClick = viewModel::rejoinRecent, enabled = !busy) {
                                            Text("Rejoin", color = SiloOnSurface)
                                        }
                                    }
                                    DismissButton { dismissedEnded = endedKey }
                                },
                            )
                        }
                        if (!enabled) {
                            WatchPartyBanner("Watch Party is turned off. Turn it on in Settings, under Experimental.")
                        } else {
                            when (state.availability) {
                                null -> WatchPartyBanner("Checking Watch Party support…")
                                is WatchPartyAvailability.Unsupported ->
                                    WatchPartyBanner("This server doesn't support Watch Party yet.")
                                WatchPartyAvailability.NotAllowed ->
                                    WatchPartyBanner("Your account can't use Watch Party.")
                                is WatchPartyAvailability.ProbeFailed -> WatchPartyBanner(
                                    "Couldn't check whether this server supports Watch Party.",
                                    tone = WatchPartyBannerTone.Warning,
                                )
                                is WatchPartyAvailability.Available -> Unit
                            }
                        }
                        // Errors stay inline; an uncertain create keeps its room id for Retry.
                        state.error?.let { message ->
                            WatchPartyBanner(
                                text = message,
                                tone = WatchPartyBannerTone.Warning,
                                action = {
                                    if (state.createRetryable) {
                                        TextButton(onClick = viewModel::retryCreate, enabled = !busy) {
                                            Text("Retry", color = SiloOnSurface)
                                        }
                                    }
                                    DismissButton(viewModel::clearError)
                                },
                            )
                        }
                        if (busy) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(
                                    color = SiloSecondaryText,
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp,
                                )
                                Spacer(Modifier.width(12.dp))
                                Text(
                                    when (state.busy) {
                                        WatchPartyPendingAction.Create -> "Starting your party…"
                                        WatchPartyPendingAction.Join -> "Joining…"
                                        WatchPartyPendingAction.Stage -> "Adding your title…"
                                        else -> "Working…"
                                    },
                                    fontSize = WatchPartyMetrics.BODY.sp,
                                    color = SiloSecondaryText,
                                )
                            }
                        }
                    }
                }

                if (!enabled) return@Column

                if (current != null) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        WatchPartyEyebrow("You're in a Watch Party")
                        if (current.code.isNotBlank()) {
                            Text(
                                current.code,
                                fontSize = WatchPartyMetrics.CODE.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace,
                                letterSpacing = (WatchPartyMetrics.CODE * 0.18f).sp,
                                color = SiloOnSurface,
                            )
                        }
                        WatchPartyButton(
                            text = "Return to party",
                            icon = Icons.Filled.PlayArrow,
                            onClick = viewModel::returnToParty,
                            enabled = !busy,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        WatchPartyButton(
                            text = "Leave party",
                            kind = WatchPartyButtonKind.Secondary,
                            onClick = {
                                if (current.selfRole == MemberRole.Host) confirmHostLeave = true else roomSession.depart()
                            },
                            enabled = !busy,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text(
                            "Leave this party to host or join another one.",
                            fontSize = WatchPartyMetrics.CAPTION.sp,
                            color = SiloSecondaryText,
                        )
                    }
                    return@Column
                }

                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    WatchPartyButton(
                        text = "Start a party",
                        icon = Icons.Filled.Add,
                        onClick = { viewModel.host() },
                        enabled = canEnter,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    WatchPartyButton(
                        text = "Start a party and let everyone vote",
                        kind = WatchPartyButtonKind.Secondary,
                        onClick = { viewModel.host(RoomSelectionMode.Vote) },
                        enabled = canEnter,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    state.recent?.takeIf { !endedRejoin }?.let { recent ->
                        WatchPartyButton(
                            text = recent.title?.takeIf { it.isNotBlank() }?.let { "Rejoin $it" }
                                ?: "Rejoin recent party",
                            icon = Icons.Filled.Refresh,
                            kind = WatchPartyButtonKind.Outlined,
                            onClick = viewModel::rejoinRecent,
                            enabled = canEnter,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }

                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    WatchPartyEyebrow("Have a code?")
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        WatchPartyCodeField(
                            value = code,
                            onValueChange = { code = it },
                            enabled = !busy,
                            onGo = { if (code.isNotBlank() && canEnter) join() },
                            modifier = Modifier.weight(1f),
                        )
                        WatchPartyCircleButton(
                            icon = Icons.Outlined.ContentPaste,
                            contentDescription = "Paste",
                            size = 52.dp,
                            shape = RoundedCornerShape(14.dp),
                            enabled = !busy,
                            onClick = {
                                val pasted = clipboard.getText()?.text?.trim().orEmpty()
                                if (pasted.isEmpty()) {
                                    Toast.makeText(context, "Copy an invitation or party code first.", Toast.LENGTH_SHORT).show()
                                } else {
                                    code = pasted
                                }
                            },
                        )
                    }
                    WatchPartyButton(
                        text = "Join party",
                        kind = WatchPartyButtonKind.Secondary,
                        onClick = join,
                        enabled = canEnter && code.isNotBlank(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (state.availability != null && !available) {
                        WatchPartyButton(
                            text = "Check again",
                            kind = WatchPartyButtonKind.Secondary,
                            onClick = { viewModel.refresh(force = true) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
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

@Composable
private fun HubHeadline(modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy((WatchPartyMetrics.BODY * 0.6f).dp)) {
        WatchPartyEyebrow("Watch Party")
        WatchPartyHeroTitle("Watch something\ntogether")
        Text(
            "Pick a title, share the code, and everyone's playback stays in step.",
            fontSize = WatchPartyMetrics.BODY.sp,
            color = SiloSecondaryText,
        )
    }
}

@Composable
private fun DismissButton(onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Icon(Icons.Filled.Close, contentDescription = "Dismiss", tint = SiloSecondaryText)
    }
}

/** "Party code or invite link": monospaced, in the lobby's chrome. */
@Composable
private fun WatchPartyCodeField(
    value: String,
    onValueChange: (String) -> Unit,
    enabled: Boolean,
    onGo: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(14.dp)
    val style = TextStyle(
        fontSize = 17.sp,
        fontWeight = FontWeight.Medium,
        fontFamily = FontFamily.Monospace,
        color = SiloOnSurface,
    )
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        enabled = enabled,
        singleLine = true,
        textStyle = style,
        cursorBrush = SolidColor(SiloOnSurface),
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.Characters,
            autoCorrectEnabled = false,
            keyboardType = KeyboardType.Uri,
            imeAction = ImeAction.Go,
        ),
        keyboardActions = KeyboardActions(onGo = { onGo() }),
        modifier = modifier
            .height(52.dp)
            .clip(shape)
            .background(WatchPartyColors.chromeFill)
            .border(1.dp, WatchPartyColors.chromeBorder, shape)
            .semantics { contentDescription = "Party code or invite link" },
        decorationBox = { inner ->
            Box(Modifier.fillMaxSize().padding(horizontal = 14.dp), contentAlignment = Alignment.CenterStart) {
                if (value.isEmpty()) {
                    Text("Party code or invite link", style = style.copy(color = SiloSecondaryText.copy(alpha = 0.5f)), maxLines = 1)
                }
                inner()
            }
        },
    )
}
