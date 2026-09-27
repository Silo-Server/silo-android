package org.siloserver.silo.tv.ui.screens.player

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
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
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.zIndex
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
import org.siloserver.silo.tv.ui.focus.TvRestoreFocusOnModalDismiss
import org.siloserver.silo.tv.ui.focus.requestFocusUntilObserved
import org.siloserver.silo.tv.ui.focus.tvModalFocusBoundary
import org.siloserver.silo.tv.ui.screens.watchparty.PARTY_KEY_OPTIONS
import org.siloserver.silo.tv.ui.screens.watchparty.TV_WATCH_PARTY_HOST_AWAY_NOTE
import org.siloserver.silo.tv.ui.screens.watchparty.TV_WATCH_PARTY_HOST_LEAVE_NOTE
import org.siloserver.silo.tv.ui.screens.watchparty.TvPartyNotice
import org.siloserver.silo.tv.ui.screens.watchparty.TvPartyRoomActions
import org.siloserver.silo.tv.ui.screens.watchparty.TvWatchPartyConfirmDialog
import org.siloserver.silo.tv.ui.screens.watchparty.TvWatchPartyInvitePage
import org.siloserver.silo.tv.ui.screens.watchparty.TvWatchPartyOptionsOverlay
import org.siloserver.silo.tv.ui.screens.watchparty.TvWatchPartyRoomView
import org.siloserver.silo.tv.ui.screens.watchparty.rememberTvPartyFocus
import org.siloserver.silo.tv.ui.screens.watchparty.rememberTvStagedPreview
import org.siloserver.silo.tv.ui.screens.watchparty.rememberTvWatchPartyReconnectNotice
import org.siloserver.silo.tv.ui.screens.watchparty.tvPartyAnchorKey
import org.siloserver.silo.tv.ui.screens.watchparty.tvWatchPartyLeftBehind
import org.siloserver.silo.tv.ui.screens.watchparty.tvWatchPartyNameList
import org.siloserver.silo.tv.ui.screens.watchparty.tvWatchPartyWaitingFor
import org.siloserver.silo.tv.ui.theme.DarkBackground
import org.siloserver.silo.watchtogether.WatchPartyAvailabilityRepository
import org.siloserver.silo.watchtogether.watchPartyEligibility
import org.siloserver.silo.watchtogether.watchPartyErrorMessage
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
 * The party room over the retained player, as tvOS does it: Back inside party
 * playback opens the lobby itself, with Return to playback as the primary
 * action. Invitation, playback policy, Return everyone to lobby, End, and
 * Leave all live where they do in the lobby (the code pill and "···").
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
    val connection by repository.connectionState.collectAsState()
    val suggestions by repository.suggestions.collectAsState()
    val features = availability.features
    val eligibility = watchPartyEligibility(
        room = room,
        features = features,
        busy = pending != null,
        personalVotesKnown = false,
    )
    val isHost = eligibility.isHost
    val serverUrl = rememberProfileServerUrl()
    val inviteUrl = room?.takeIf { isHost }?.let { watchPartyInviteUrl(serverUrl, it.invitePath) }
    val reconnect = rememberTvWatchPartyReconnectNotice(connection = connection, inRoom = room != null)
    val staged = rememberTvStagedPreview(
        contentId = room?.selectedContentId,
        fileId = room?.selectedFileId,
        libraryId = room?.selectedLibraryId,
        suggestions = suggestions,
    )
    val focus = rememberTvPartyFocus()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var optionsOpen by remember { mutableStateOf(false) }
    var inviteOpen by remember { mutableStateOf(false) }
    var confirmEnd by remember { mutableStateOf(false) }
    var confirmLeave by remember { mutableStateOf(false) }
    val modalOpen = optionsOpen || inviteOpen || confirmEnd || confirmLeave
    var opener by remember { mutableStateOf<String?>(null) }
    fun openModal(open: () -> Unit) {
        opener = focus.focusedKey
        open()
    }
    val anchor = room?.let { tvPartyAnchorKey(it, features, suggestions) } ?: PARTY_KEY_OPTIONS
    // The focusable popup hands focus to its first control (the code pill)
    // on its own, so put it on Return to playback once the room is laid out.
    LaunchedEffect(anchor, room != null) {
        if (room == null || optionsOpen || inviteOpen || confirmEnd || confirmLeave) return@LaunchedEffect
        requestFocusUntilObserved(
            maxAttempts = PANEL_FOCUS_ATTEMPTS,
            awaitAttempt = { withFrameNanos { } },
            requestFocus = focus.requester(anchor)::requestFocus,
            isFocused = { focus.isFocused(anchor) },
        )
    }

    Popup(
        alignment = Alignment.Center,
        onDismissRequest = onClose,
        properties = PopupProperties(
            focusable = true,
            dismissOnBackPress = true,
            dismissOnClickOutside = false,
            clippingEnabled = false,
        ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(DarkBackground)
                .tvModalFocusBoundary()
                .then(rememberTvDialogInitialFocus(focus.requester(anchor))),
        ) {
            if (room != null) {
                TvWatchPartyRoomView(
                    room = room,
                    suggestions = suggestions,
                    features = features,
                    eligibility = eligibility,
                    personalVotesKnown = false,
                    connection = connection,
                    staged = staged,
                    focus = focus,
                    notice = reconnect,
                    actions = TvPartyRoomActions(
                        onInvite = { openModal { inviteOpen = true } },
                        onOptions = { openModal { optionsOpen = true } },
                        onReturnToPlayback = onClose,
                    ),
                )
            }
        }
    }

    if (optionsOpen && room != null) {
        TvWatchPartyOptionsOverlay(
            room = room,
            features = features,
            eligibility = eligibility,
            onSetMode = {},
            onSetPolicy = { policy ->
                scope.launch {
                    val result = repository.updatePolicy(policy)
                    if (result !is ApiResult.Success) {
                        Toast.makeText(
                            context,
                            watchPartyErrorMessage(result, "Couldn't change who can play and pause."),
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                }
            },
            onStopPlayback = {
                optionsOpen = false
                onReturnToLobby()
            },
            onEnd = {
                optionsOpen = false
                confirmEnd = true
            },
            onLeave = {
                optionsOpen = false
                if (isHost) confirmLeave = true else onLeave()
            },
            onDismiss = { optionsOpen = false },
        )
    }
    if (inviteOpen && room != null) {
        TvWatchPartyInvitePage(
            code = room.code,
            inviteUrl = inviteUrl,
            backdropUrl = staged?.backdropUrl,
            backdropThumbhash = staged?.backdropThumbhash,
            onDismiss = { inviteOpen = false },
        )
    }
    if (confirmEnd) {
        TvWatchPartyConfirmDialog(
            title = "End this party for everyone?",
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
    if (confirmLeave) {
        TvWatchPartyConfirmDialog(
            title = "Leave the party?",
            message = TV_WATCH_PARTY_HOST_LEAVE_NOTE,
            confirmLabel = "Leave party",
            destructive = true,
            onConfirm = {
                confirmLeave = false
                onLeave()
            },
            onDismiss = { confirmLeave = false },
        )
    }
    TvRestoreFocusOnModalDismiss(
        visible = modalOpen,
        opener = opener?.let(focus::requester),
        isOpenerFocused = { focus.isFocused(opener) },
    )
}

private const val LEFT_BEHIND_NOTICE_MS = 6_000L
private const val PANEL_FOCUS_ATTEMPTS = 10
