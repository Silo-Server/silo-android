package org.siloserver.silo.tv.ui.screens.player

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.zIndex
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import org.siloserver.silo.common.ui.components.rememberProfileServerUrl
import org.siloserver.silo.model.watchtogether.MemberRole
import org.siloserver.silo.model.watchtogether.RoomPhase
import org.siloserver.silo.model.watchtogether.RoomSnapshot
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.repository.WatchTogetherRepository
import org.siloserver.silo.tv.ui.components.rememberTvDialogInitialFocus
import org.siloserver.silo.tv.ui.focus.TvControlState
import org.siloserver.silo.tv.ui.focus.TvRestoreFocusOnModalDismiss
import org.siloserver.silo.tv.ui.focus.tvModalFocusBoundary
import org.siloserver.silo.tv.ui.screens.watchparty.TV_WATCH_PARTY_HOST_AWAY_NOTE
import org.siloserver.silo.tv.ui.screens.watchparty.TV_WATCH_PARTY_HOST_LEAVE_NOTE
import org.siloserver.silo.tv.ui.screens.watchparty.TvPartyActionRow
import org.siloserver.silo.tv.ui.screens.watchparty.TvPartyInvitation
import org.siloserver.silo.tv.ui.screens.watchparty.TvPartyMemberRow
import org.siloserver.silo.tv.ui.screens.watchparty.TvPartyNotice
import org.siloserver.silo.tv.ui.screens.watchparty.TvPartySectionLabel
import org.siloserver.silo.tv.ui.screens.watchparty.TvWatchPartyConfirmDialog
import org.siloserver.silo.tv.ui.screens.watchparty.rememberTvWatchPartyReconnectNotice
import org.siloserver.silo.tv.ui.screens.watchparty.tvWatchPartyLeftBehind
import org.siloserver.silo.tv.ui.screens.watchparty.tvWatchPartyNameList
import org.siloserver.silo.tv.ui.screens.watchparty.tvWatchPartyWaitingFor
import org.siloserver.silo.tv.ui.theme.DarkBackground
import org.siloserver.silo.watchtogether.WatchPartyAvailabilityRepository
import org.siloserver.silo.watchtogether.watchPartyErrorMessage
import org.siloserver.silo.watchtogether.watchPartyEligibility
import org.siloserver.silo.watchtogether.watchPartyInviteUrl

/**
 * The Watch Party layer over the TV player: the non-focusable status overlay
 * and the party panel that Back opens over the retained player.
 *
 * The panel is a focusable popup; the player keeps running underneath and
 * closing the panel never tears it down. Focus returns to the player when it
 * closes. Kept out of [TvPlayerScreen] so that composable stays within ART's
 * JIT method-size limit.
 */
@Composable
internal fun TvWatchPartyPlayerOverlays(
    watchParty: TvWatchPartyScreenController,
    panelOpen: Boolean,
    isInPictureInPictureMode: Boolean,
    playerFocus: FocusRequester,
    playerFocused: Boolean,
    onPanelOpenChange: (Boolean) -> Unit,
    /** The player's terminal exit; it leaves the party on the way out. */
    exit: () -> Unit,
    repository: WatchTogetherRepository = koinInject(),
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val room by watchParty.playback.room.collectAsState()
    val latestPlayerFocused by rememberUpdatedState(playerFocused)
    if (!isInPictureInPictureMode && !panelOpen) {
        TvWatchPartyStatusOverlay(watchParty = watchParty, room = room, repository = repository)
    }
    if (!isInPictureInPictureMode && panelOpen) {
        TvWatchPartyPanel(
            room = room,
            repository = repository,
            onClose = { onPanelOpenChange(false) },
            onReturnToLobby = {
                onPanelOpenChange(false)
                scope.launch {
                    val result = repository.stopPlayback()
                    if (result !is ApiResult.Success) {
                        Toast.makeText(
                            context,
                            watchPartyErrorMessage(result, "Couldn't stop playback."),
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                }
            },
            onLeave = {
                onPanelOpenChange(false)
                exit()
            },
            onEndForEveryone = {
                onPanelOpenChange(false)
                watchParty.endForEveryone()
                exit()
            },
        )
    }
    TvRestoreFocusOnModalDismiss(
        visible = panelOpen && !isInPictureInPictureMode,
        opener = playerFocus,
        isOpenerFocused = { latestPlayerFocused },
    )
}

/**
 * Status that explains what the party is doing, top-center. Never focusable
 * and never announced on every update; it only reports.
 */
@Composable
private fun TvWatchPartyStatusOverlay(
    watchParty: TvWatchPartyScreenController,
    room: RoomSnapshot?,
    repository: WatchTogetherRepository,
) {
    val catchingUp by watchParty.playback.catchingUp.collectAsState()
    val connection by repository.connectionState.collectAsState()
    val reconnect = rememberTvWatchPartyReconnectNotice(connection = connection, inRoom = room != null)

    // "Continuing without …": members the room stopped waiting for, shown
    // for a few seconds each time the room leaves someone behind.
    var leftBehind by remember { mutableStateOf<List<String>>(emptyList()) }
    var leftBehindNonce by remember { mutableIntStateOf(0) }
    LaunchedEffect(watchParty) {
        var previous: RoomSnapshot? = null
        watchParty.playback.room.collect { next ->
            val names = tvWatchPartyLeftBehind(previous, next)
            if (names.isNotEmpty()) {
                leftBehind = names
                leftBehindNonce++
            }
            previous = next
        }
    }
    LaunchedEffect(leftBehindNonce) {
        if (leftBehindNonce == 0) return@LaunchedEffect
        delay(LEFT_BEHIND_NOTICE_MS)
        leftBehind = emptyList()
    }

    val playing = room?.phase == RoomPhase.Playing
    val hostAway = playing && room?.selfRole != MemberRole.Host && room?.hostConnected == false
    val waitingFor = if (catchingUp) null else tvWatchPartyWaitingFor(room)
    if (reconnect == null && !hostAway && !catchingUp && waitingFor == null && leftBehind.isEmpty()) return

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(top = 20.dp)
            .zIndex(6f),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (reconnect != null) TvPartyNotice(title = reconnect)
            if (hostAway) TvPartyNotice(title = "The host disconnected", detail = TV_WATCH_PARTY_HOST_AWAY_NOTE)
            if (catchingUp) {
                TvPartyNotice(
                    title = "Catching up to the party",
                    detail = "The party kept playing".takeIf { room?.selfIgnoreWait == true },
                )
            }
            if (waitingFor != null) {
                TvPartyNotice(
                    title = if (waitingFor.isEmpty()) {
                        "Syncing playback"
                    } else {
                        "Syncing playback · Waiting for ${tvWatchPartyNameList(waitingFor)}"
                    },
                )
            }
            if (leftBehind.isNotEmpty()) {
                TvPartyNotice(title = "Continuing without ${tvWatchPartyNameList(leftBehind)}")
            }
        }
    }
}

/**
 * The party panel: members and their status, the invitation (code for
 * everyone, QR of the invitation link for the host), and D2's actions.
 */
@Composable
private fun TvWatchPartyPanel(
    room: RoomSnapshot?,
    repository: WatchTogetherRepository,
    onClose: () -> Unit,
    onReturnToLobby: () -> Unit,
    onLeave: () -> Unit,
    onEndForEveryone: () -> Unit,
    availability: WatchPartyAvailabilityRepository = koinInject(),
) {
    val pending by repository.pendingAction.collectAsState()
    val eligibility = watchPartyEligibility(
        room = room,
        features = availability.features,
        busy = pending != null,
        personalVotesKnown = false,
    )
    val isHost = eligibility.isHost
    val playing = room?.phase == RoomPhase.Playing
    val stopSupported = availability.features?.stopPlayback == true
    val serverUrl = rememberProfileServerUrl()
    val inviteUrl = room?.takeIf { isHost }?.let { watchPartyInviteUrl(serverUrl, it.invitePath) }
    val backFocus = remember { FocusRequester() }
    var confirmEnd by remember { mutableStateOf(false) }

    Popup(
        alignment = Alignment.CenterEnd,
        onDismissRequest = onClose,
        properties = PopupProperties(
            focusable = true,
            dismissOnBackPress = true,
            dismissOnClickOutside = false,
            clippingEnabled = false,
        ),
    ) {
        Row(
            modifier = Modifier
                .width(PANEL_WIDTH)
                .fillMaxHeight()
                .background(DarkBackground.copy(alpha = 0.90f))
                .border(0.6.dp, Color.White.copy(alpha = 0.16f))
                .padding(horizontal = 24.dp, vertical = 28.dp)
                .tvModalFocusBoundary()
                .then(rememberTvDialogInitialFocus(backFocus)),
            horizontalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Column(
                modifier = Modifier.weight(0.5f),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = "Watch Party",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White,
                )
                TvPartyActionRow(
                    title = "Back to playback",
                    onClick = onClose,
                    modifier = Modifier.focusRequester(backFocus),
                )
                if (isHost && playing && stopSupported) {
                    TvPartyActionRow(
                        title = "Return everyone to lobby",
                        subtitle = "Stops playback so you can pick something else.",
                        state = TvControlState.transient(eligibility.canStop),
                        onClick = onReturnToLobby,
                    )
                }
                if (isHost) {
                    TvPartyActionRow(
                        title = "End party for everyone",
                        destructive = true,
                        state = TvControlState.transient(eligibility.canEnd),
                        onClick = { confirmEnd = true },
                    )
                }
                TvPartyActionRow(
                    title = "Leave party",
                    subtitle = if (isHost) TV_WATCH_PARTY_HOST_LEAVE_NOTE else null,
                    onClick = onLeave,
                )
            }
            Column(
                modifier = Modifier
                    .weight(0.5f)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (room != null) {
                    TvPartyInvitation(code = room.code, inviteUrl = inviteUrl, qrSize = 104.dp)
                    TvPartySectionLabel("In this party (${room.members.size})")
                    room.members.sortedByDescending { it.isHost }.forEach { member ->
                        TvPartyMemberRow(member = member, room = room)
                    }
                    if (!isHost && !room.hostConnected) {
                        TvPartyNotice(title = "The host disconnected", detail = TV_WATCH_PARTY_HOST_AWAY_NOTE)
                    }
                }
            }
        }
    }

    if (confirmEnd) {
        TvWatchPartyConfirmDialog(
            title = "End the party for everyone?",
            message = "Playback stops for everyone and the party closes.",
            confirmLabel = "End party",
            destructive = true,
            onConfirm = {
                confirmEnd = false
                onEndForEveryone()
            },
            onDismiss = { confirmEnd = false },
        )
    }
}

private val PANEL_WIDTH = 620.dp
private const val LEFT_BEHIND_NOTICE_MS = 6_000L
