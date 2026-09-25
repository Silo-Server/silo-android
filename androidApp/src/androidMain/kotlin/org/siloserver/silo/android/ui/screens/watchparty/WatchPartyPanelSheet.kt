package org.siloserver.silo.android.ui.screens.watchparty

import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import org.siloserver.silo.model.watchtogether.GuestControlPolicy
import org.siloserver.silo.model.watchtogether.MemberRole
import org.siloserver.silo.model.watchtogether.RoomSnapshot
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.repository.WatchTogetherRepository
import org.siloserver.silo.watchtogether.WatchPartyAvailability
import org.siloserver.silo.watchtogether.WatchPartyAvailabilityRepository
import org.siloserver.silo.watchtogether.WatchPartyEligibility
import org.siloserver.silo.watchtogether.watchPartyErrorMessage

/**
 * The party panel over a retained party player (Back opens it). It is the
 * room itself, as Apple's `WatchPartyRoomPanel` reopens the lobby over the
 * player: "Now watching · together", the seats with playback states, the
 * code pill and "···" options, and Return to playback as the primary action.
 * Closing it only closes the panel; the player keeps playing underneath.
 */
@Composable
internal fun WatchPartyPanelSheet(
    room: RoomSnapshot?,
    eligibility: WatchPartyEligibility,
    inviteUrl: String?,
    onReturnToLobby: () -> Unit,
    onEndForEveryone: () -> Unit,
    onLeave: () -> Unit,
    onDismiss: () -> Unit,
) {
    val repository: WatchTogetherRepository = koinInject()
    val availabilityRepository: WatchPartyAvailabilityRepository = koinInject()
    val availability by availabilityRepository.availability.collectAsState()
    val suggestions by repository.suggestions.collectAsState()
    val personalVotesKnown by repository.personalVotesKnown.collectAsState()
    val connection by repository.connectionState.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val isHost = room?.selfRole == MemberRole.Host
    var inviteOpen by rememberSaveable { mutableStateOf(false) }
    var confirmEnd by rememberSaveable { mutableStateOf(false) }
    var confirmHostLeave by rememberSaveable { mutableStateOf(false) }

    if (room == null) {
        LaunchedEffect(Unit) { onDismiss() }
        return
    }

    val staged = rememberWatchPartyStagedTitle(room, suggestions)
    val reconnectSince = connection.disconnectedAtMs.takeIf { !connection.writable }
    val showReconnect = rememberWatchPartyReconnectNotice(
        disconnected = !connection.writable,
        since = reconnectSince,
    )

    val actions = object : WatchPartyRoomActions {
        override fun invite() {
            inviteOpen = true
        }

        override fun returnToPlayback() = onDismiss()

        override fun setPolicy(policy: GuestControlPolicy) {
            if (room.guestControlPolicy == policy) return
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
        }

        override fun returnEveryoneToLobby() = onReturnToLobby()

        override fun leave() {
            if (isHost) confirmHostLeave = true else onLeave()
        }

        override fun end() {
            confirmEnd = true
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        WatchPartyRoomContent(
            room = room,
            staged = staged,
            suggestions = suggestions,
            personalVotesKnown = personalVotesKnown,
            features = (availability as? WatchPartyAvailability.Available)?.features,
            eligibility = eligibility,
            connected = connection.writable,
            showReconnect = showReconnect,
            firstConnect = reconnectSince == null || reconnectSince == 0L,
            actions = actions,
            onBack = null,
        )
    }

    if (inviteOpen) {
        WatchPartyInviteSheet(code = room.code, inviteUrl = inviteUrl, onDismiss = { inviteOpen = false })
    }
    if (confirmEnd) {
        WatchPartyEndDialog(
            onConfirm = {
                confirmEnd = false
                onEndForEveryone()
            },
            onDismiss = { confirmEnd = false },
        )
    }
    if (confirmHostLeave) {
        WatchPartyHostLeaveDialog(
            onConfirm = {
                confirmHostLeave = false
                onLeave()
            },
            onDismiss = { confirmHostLeave = false },
        )
    }
}
