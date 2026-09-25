package org.siloserver.silo.android.ui.screens.watchparty

import android.content.Intent
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import org.siloserver.silo.android.ui.components.SiloConfirmDialog
import org.siloserver.silo.model.watchtogether.MemberRole
import org.siloserver.silo.model.watchtogether.RoomMember
import org.siloserver.silo.model.watchtogether.RoomPhase
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

/** A plain status banner for the hub and lobby. */
@Composable
internal fun WatchPartyBanner(
    text: String,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(12.dp),
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            action?.invoke()
        }
    }
}

/**
 * The room code with Share and Copy. A host shares the server's invitation
 * link (with the code); a guest has no link and shares the code alone.
 */
@Composable
internal fun WatchPartyInviteSection(
    code: String,
    inviteUrl: String?,
    modifier: Modifier = Modifier,
) {
    if (code.isBlank()) return
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Invite", style = MaterialTheme.typography.titleSmall)
        Text(
            text = code.chunked(4).joinToString(" "),
            style = MaterialTheme.typography.headlineSmall,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(vertical = 4.dp),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = {
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, watchPartyShareText(code, inviteUrl))
                    }
                    context.startActivity(Intent.createChooser(send, "Share Watch Party invitation"))
                },
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Icon(Icons.Outlined.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Share")
            }
            OutlinedButton(
                onClick = {
                    clipboard.setText(AnnotatedString(inviteUrl ?: code))
                    // Android 13+ confirms clipboard writes itself.
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                        Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
                    }
                },
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Icon(Icons.Outlined.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(if (inviteUrl != null) "Copy link" else "Copy code")
            }
        }
    }
}

/** One member: name, host badge, and ready or playback status. */
@Composable
internal fun WatchPartyMemberRow(
    member: RoomMember,
    phase: RoomPhase,
    modifier: Modifier = Modifier,
) {
    val status = watchPartyMemberStatus(member, phase)
    val name = watchPartyMemberName(member) + if (member.isSelf) " (you)" else ""
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .padding(vertical = 4.dp),
    ) {
        Text(
            text = name,
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = if (member.connected) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.weight(1f, fill = false),
        )
        if (member.isHost) {
            Spacer(Modifier.width(8.dp))
            Surface(
                color = MaterialTheme.colorScheme.secondaryContainer,
                shape = RoundedCornerShape(6.dp),
            ) {
                Text(
                    text = "Host",
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        }
        Spacer(Modifier.weight(1f))
        if (status == "Ready") {
            Icon(
                Icons.Filled.CheckCircle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(6.dp))
        }
        if (status != null) {
            Text(
                text = status,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Confirms ending the party for everyone (host). */
@Composable
internal fun WatchPartyEndDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    SiloConfirmDialog(
        title = "End the party for everyone?",
        body = "Everyone leaves the Watch Party, and it can't be rejoined.",
        confirmLabel = "End party",
        onConfirm = onConfirm,
        onDismiss = onDismiss,
    )
}

/** Explains a host's Leave before it happens: the room outlives them for two minutes. */
@Composable
internal fun WatchPartyHostLeaveDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    SiloConfirmDialog(
        title = "Leave the party?",
        body = "You're the host. The party ends for everyone two minutes after you leave, unless you rejoin.",
        confirmLabel = "Leave",
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
        title = "Leave the Watch Party to play this?",
        body = if (guard.pendingFromHost) {
            "You're the host. The party ends for everyone two minutes after you leave, unless you rejoin."
        } else {
            "You'll leave the party and play this on your own."
        },
        confirmLabel = "Leave and play",
        onConfirm = guard::leaveAndRun,
        onDismiss = { guard.pending = null },
    )
}
