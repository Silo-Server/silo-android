package org.siloserver.silo.android.ui.screens.watchparty

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf
import org.siloserver.silo.android.ui.theme.SiloOnSurface
import org.siloserver.silo.android.ui.theme.SiloSecondaryText
import org.siloserver.silo.model.watchtogether.GuestControlPolicy
import org.siloserver.silo.model.watchtogether.MemberRole
import org.siloserver.silo.model.watchtogether.RoomSelectionMode
import org.siloserver.silo.model.watchtogether.RoomSnapshot
import org.siloserver.silo.model.watchtogether.Suggestion
import org.siloserver.silo.network.ServerRegistry
import org.siloserver.silo.repository.WatchTogetherRepository
import org.siloserver.silo.viewmodel.WatchPartyConnectionStatus
import org.siloserver.silo.viewmodel.WatchPartyLobbyViewModel
import org.siloserver.silo.watchtogether.WatchPartyDestination
import org.siloserver.silo.watchtogether.watchPartyInviteUrl

/**
 * The Watch Party lobby, after Apple's phone lobby: the staged film's artwork
 * fills the page under the code pill, the hero, the ballot or suggestions,
 * the seats, and one pinned action. The room's phase alone opens the player.
 */
@Composable
fun WatchPartyLobbyScreen(
    roomId: String,
    onBack: () -> Unit,
    onOpenPlayer: (RoomSnapshot) -> Unit,
    /** The party ended; the hub explains why and offers Rejoin when it can. */
    onPartyEnded: () -> Unit,
    /** This device left (Leave, End, or elsewhere). */
    onLeft: () -> Unit,
    onOpenDetail: (contentId: String) -> Unit,
    viewModel: WatchPartyLobbyViewModel = koinViewModel(parameters = { parametersOf(roomId) }),
) {
    val state by viewModel.state.collectAsState()
    val destination by viewModel.destination.collectAsState()
    val repository: WatchTogetherRepository = koinInject()
    val handoff: WatchPartyHandoff = koinInject()
    val serverRegistry: ServerRegistry = koinInject()
    val activeServer by serverRegistry.activeEntry.collectAsState()
    val context = LocalContext.current

    val room = state.room?.takeIf { it.roomId == roomId }
    val eligibility = state.eligibility
    val isHost = room?.selfRole == MemberRole.Host
    var leaving by remember { mutableStateOf(false) }
    var seenRoom by remember { mutableStateOf(false) }
    var pickerMode by rememberSaveable { mutableStateOf<WatchPartyPickMode?>(null) }
    var inviteOpen by rememberSaveable { mutableStateOf(false) }
    var confirmEnd by rememberSaveable { mutableStateOf(false) }
    var confirmHostLeave by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(viewModel) {
        viewModel.messages.collect { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() }
    }

    // Only the room's phase opens the player.
    LaunchedEffect(destination) {
        if (destination !is WatchPartyDestination.Player || leaving) return@LaunchedEffect
        val playing = repository.roomSnapshot.value?.takeIf { it.roomId == roomId } ?: return@LaunchedEffect
        onOpenPlayer(playing)
    }

    LaunchedEffect(room != null) {
        if (room != null) seenRoom = true
    }
    LaunchedEffect(state.ended) {
        val ended = state.ended ?: return@LaunchedEffect
        if (ended.roomId == roomId && !leaving) {
            leaving = true
            onPartyEnded()
        }
    }
    LaunchedEffect(room == null, seenRoom, state.ended) {
        if (room == null && seenRoom && state.ended == null && !leaving) {
            leaving = true
            onLeft()
        }
    }

    fun leaveNow() {
        leaving = true
        viewModel.leave()
        onLeft()
    }

    fun endNow() {
        leaving = true
        viewModel.endForEveryone()
        onLeft()
    }

    val connection = state.connection
    val reconnectSince = (connection as? WatchPartyConnectionStatus.Reconnecting)?.sinceMs
    val showReconnect = rememberWatchPartyReconnectNotice(
        disconnected = room != null && reconnectSince != null,
        since = reconnectSince,
    )
    val staged = rememberWatchPartyStagedTitle(room, state.suggestions)

    if (room == null) {
        Box(Modifier.fillMaxSize()) {
            WatchPartyBackdrop(url = null)
            Column(Modifier.fillMaxSize()) {
                WatchPartyTopBar(onBack = onBack)
            }
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.align(Alignment.Center),
            ) {
                CircularProgressIndicator(color = SiloSecondaryText)
                Text("Connecting to the party…", color = SiloSecondaryText)
            }
        }
        return
    }

    val actions = object : WatchPartyRoomActions {
        override fun invite() {
            inviteOpen = true
        }

        override fun chooseTitle() {
            pickerMode = WatchPartyPickMode.Stage
        }

        override fun suggest() {
            pickerMode = WatchPartyPickMode.Suggest
        }

        override fun start() = viewModel.start().let { }

        override fun setLobbyReady(ready: Boolean) = viewModel.setLobbyReady(ready)

        override fun returnToPlayback() {
            repository.roomSnapshot.value?.takeIf { it.roomId == roomId }?.let(onOpenPlayer)
        }

        override fun setMode(mode: RoomSelectionMode) {
            if (room.selectionMode != mode) viewModel.setMode(mode)
        }

        override fun setPolicy(policy: GuestControlPolicy) {
            if (room.guestControlPolicy != policy) viewModel.setGuestPolicy(policy)
        }

        override fun leave() {
            if (isHost) confirmHostLeave = true else leaveNow()
        }

        override fun end() {
            confirmEnd = true
        }

        override fun vote(suggestion: Suggestion) = viewModel.vote(suggestion).let { }

        override fun promote(suggestion: Suggestion) = viewModel.promote(suggestion).let { }

        override fun queue(suggestion: Suggestion) {
            handoff.rememberPreview(suggestion.toPartyItem())
            viewModel.queue(suggestion)
        }

        override fun remove(suggestion: Suggestion) = viewModel.remove(suggestion)
    }

    WatchPartyRoomContent(
        room = room,
        staged = staged,
        suggestions = state.suggestions,
        personalVotesKnown = state.personalVotesKnown,
        features = state.features,
        eligibility = eligibility,
        connected = connection is WatchPartyConnectionStatus.Connected,
        showReconnect = showReconnect,
        firstConnect = reconnectSince == 0L,
        actions = actions,
        onBack = onBack,
        extraBanner = state.suggestionRetry?.let { draft ->
            {
                WatchPartyBanner(
                    text = "Couldn't confirm your suggestion of ${draft.title}.",
                    tone = WatchPartyBannerTone.Warning,
                    action = {
                        TextButton(onClick = viewModel::retrySuggestion) { Text("Try again", color = SiloOnSurface) }
                        IconButton(onClick = viewModel::dismissSuggestionRetry) {
                            Icon(Icons.Filled.Close, contentDescription = "Dismiss", tint = SiloSecondaryText)
                        }
                    },
                )
            }
        },
    )

    if (inviteOpen) {
        WatchPartyInviteSheet(
            code = room.code,
            inviteUrl = if (isHost) activeServer?.url?.let { watchPartyInviteUrl(it, room.invitePath) } else null,
            onDismiss = { inviteOpen = false },
        )
    }

    pickerMode?.let { mode ->
        WatchPartyPicker(
            mode = mode,
            roomMembers = room.members,
            memberState = viewModel::memberState,
            onPick = { item ->
                handoff.rememberPreview(item)
                if (mode == WatchPartyPickMode.Stage) viewModel.stage(item) else viewModel.suggest(item)
            },
            onOpenSeries = onOpenDetail,
            onDismiss = { pickerMode = null },
        )
    }

    if (confirmEnd) {
        WatchPartyEndDialog(
            onConfirm = {
                confirmEnd = false
                endNow()
            },
            onDismiss = { confirmEnd = false },
        )
    }
    if (confirmHostLeave) {
        WatchPartyHostLeaveDialog(
            onConfirm = {
                confirmHostLeave = false
                leaveNow()
            },
            onDismiss = { confirmHostLeave = false },
        )
    }
}
