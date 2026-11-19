package org.siloserver.silo.android.ui.screens.watchparty

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import org.siloserver.silo.android.ui.components.SiloConfirmDialog
import org.siloserver.silo.common.R as CommonR
import org.siloserver.silo.model.watchtogether.MemberRole
import org.siloserver.silo.repository.WatchTogetherRepository
import org.siloserver.silo.watchtogether.RoomSession

/** A reconnect shorter than this is routine (socket rotation) and stays silent. */
internal const val WATCH_PARTY_RECONNECT_NOTICE_DELAY_MS = 2_000L

/**
 * True once [disconnected] has held for two seconds; false again the moment
 * it clears. [since] identifies one disconnection, so a new one restarts the
 * wait.
 */
@Composable
internal fun rememberWatchPartyReconnectNotice(disconnected: Boolean, since: Long?): Boolean {
    var show by remember { mutableStateOf(false) }
    LaunchedEffect(disconnected, since) {
        show = false
        if (disconnected) {
            delay(WATCH_PARTY_RECONNECT_NOTICE_DELAY_MS)
            show = true
        }
    }
    return show
}

/** Confirms ending the party for everyone (host). */
@Composable
internal fun WatchPartyEndDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    SiloConfirmDialog(
        title = stringResource(CommonR.string.watchParty_end_titleEveryone),
        body = stringResource(CommonR.string.watchParty_end_body),
        confirmLabel = stringResource(CommonR.string.watchParty_end_button),
        onConfirm = onConfirm,
        onDismiss = onDismiss,
    )
}

/** Explains a host's Leave before it happens: the room outlives them for two minutes. */
@Composable
internal fun WatchPartyHostLeaveDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    SiloConfirmDialog(
        title = stringResource(CommonR.string.watchParty_leave_title),
        body = stringResource(CommonR.string.watchParty_leave_hostBody),
        confirmLabel = stringResource(CommonR.string.watchParty_leave_confirm),
        onConfirm = onConfirm,
        onDismiss = onDismiss,
    )
}

/**
 * The D5 guard: while this device is in a Watch Party, starting anything
 * else on the shared player first asks the user to leave the party.
 */
@Stable
class WatchPartySoloGuard internal constructor(
    private val repository: WatchTogetherRepository,
    private val roomSession: RoomSession,
    private val scope: CoroutineScope,
) {
    internal var pending by mutableStateOf<(() -> Unit)?>(null)
    internal var pendingFromHost by mutableStateOf(false)

    /** Runs [start] now, or after the user agrees to leave the party. */
    fun run(start: () -> Unit) {
        val room = repository.roomSnapshot.value
        if (room == null) {
            start()
        } else {
            pendingFromHost = room.selfRole == MemberRole.Host
            pending = start
        }
    }

    internal fun leaveAndRun() {
        val start = pending ?: return
        pending = null
        scope.launch {
            roomSession.depart().join()
            start()
        }
    }
}

@Composable
fun rememberWatchPartySoloGuard(): WatchPartySoloGuard {
    val repository: WatchTogetherRepository = koinInject()
    val roomSession: RoomSession = koinInject()
    val scope = rememberCoroutineScope()
    return remember(repository, roomSession, scope) { WatchPartySoloGuard(repository, roomSession, scope) }
}

@Composable
fun WatchPartySoloGuardDialog(guard: WatchPartySoloGuard) {
    if (guard.pending == null) return
    SiloConfirmDialog(
        title = stringResource(CommonR.string.watchParty_soloGuard_title),
        body = if (guard.pendingFromHost) {
            stringResource(CommonR.string.watchParty_leave_hostBody)
        } else {
            stringResource(CommonR.string.watchParty_soloGuard_guestBody)
        },
        confirmLabel = stringResource(CommonR.string.watchParty_soloGuard_confirm),
        onConfirm = guard::leaveAndRun,
        onDismiss = { guard.pending = null },
    )
}
