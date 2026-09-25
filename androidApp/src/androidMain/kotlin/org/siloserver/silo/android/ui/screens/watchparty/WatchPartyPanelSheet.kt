package org.siloserver.silo.android.ui.screens.watchparty

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.siloserver.silo.model.watchtogether.MemberRole
import org.siloserver.silo.model.watchtogether.RoomSnapshot
import org.siloserver.silo.watchtogether.WatchPartyEligibility

/**
 * The party panel over a retained party player (Back opens it): members and
 * their playback status, the host's invitation, and Leave, Return everyone to
 * lobby, and End per D2. Closing it only closes the sheet; the player keeps
 * playing underneath.
 */
@OptIn(ExperimentalMaterial3Api::class)
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
    val isHost = room?.selfRole == MemberRole.Host
    var confirmEnd by rememberSaveable { mutableStateOf(false) }
    var confirmHostLeave by rememberSaveable { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
        ) {
            Text("Watch Party", style = MaterialTheme.typography.titleLarge)
            if (room != null) {
                Column {
                    room.members.sortedByDescending { it.isHost }.forEach { member ->
                        WatchPartyMemberRow(member = member, phase = room.phase)
                    }
                    if (room.members.isEmpty()) {
                        Text(
                            "${room.memberCount} watching",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (isHost) {
                    HorizontalDivider()
                    WatchPartyInviteSection(code = room.code, inviteUrl = inviteUrl)
                }
            }
            HorizontalDivider()
            Button(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) { Text("Back to playback") }
            if (isHost && eligibility.canStop) {
                OutlinedButton(
                    onClick = onReturnToLobby,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                ) { Text("Return everyone to lobby") }
            }
            OutlinedButton(
                onClick = { if (isHost) confirmHostLeave = true else onLeave() },
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) { Text("Leave party") }
            if (isHost && eligibility.canEnd) {
                TextButton(
                    onClick = { confirmEnd = true },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                ) { Text("End party for everyone") }
            }
            Spacer(Modifier.height(24.dp))
        }
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
