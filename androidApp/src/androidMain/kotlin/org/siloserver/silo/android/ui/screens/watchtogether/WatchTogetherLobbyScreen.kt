package org.siloserver.silo.android.ui.screens.watchtogether

import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.HowToVote
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import org.siloserver.silo.model.watchtogether.MemberRole
import org.siloserver.silo.model.watchtogether.RoomSelectionMode
import org.siloserver.silo.model.watchtogether.Suggestion
import org.siloserver.silo.watchtogether.isVoteRoom
import org.siloserver.silo.watchtogether.roomVoteWinner
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

/**
 * Watch Together lobby. Shows member count / role / selection mode, the
 * suggestion list (vote / unvote, host promote + remove), an invite-code share
 * affordance, and auto-navigates everyone into the synced player once the host
 * picks/promotes a title (room transitions to [org.siloserver.silo.model.watchtogether.RoomPhase.Playing]
 * with a selection).
 *
 * The "add a suggestion" affordance is deferred — the host pre-selection comes
 * from item-detail; this screen focuses on vote/unvote/promote/pick + invite.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WatchTogetherLobbyScreen(
    roomId: String,
    onNavigateToPlayer: (String) -> Unit,
    onBack: () -> Unit,
    viewModel: WatchTogetherLobbyViewModel = koinViewModel(parameters = { parametersOf(roomId) }),
) {
    val room by viewModel.room.collectAsState()
    val suggestions by viewModel.suggestions.collectAsState()
    val closedReason by viewModel.roomClosedReason.collectAsState()
    val context = LocalContext.current

    // Auto-navigate into the synced player when the room starts playing.
    LaunchedEffect(room?.phase, room?.selectedContentId, room?.memberCount, room?.selfRole) {
        val snapshot = room ?: return@LaunchedEffect
        lobbyPlayerDestinationOrNull(snapshot)?.let { onNavigateToPlayer(it) }
    }

    // Server closed the room (host left / explicit close) — back out.
    LaunchedEffect(closedReason) {
        if (closedReason != null) onBack()
    }

    LaunchedEffect(viewModel) {
        viewModel.errors.collect { message ->
            if (message.isNotBlank()) {
                Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            }
        }
    }

    // Role drives only the cosmetic header label; mutating controls gate on the
    // server's per-recipient management capability so a demoted/grace-period host
    // (selfRole still "host" but management revoked) doesn't see dead buttons.
    val isHostLabel = room?.selfRole == MemberRole.Host
    val canManage = room?.selfCanManageRoom == true

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Watch Together") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back to browse",
                        )
                    }
                },
                actions = {
                    TextButton(
                        onClick = {
                            viewModel.leave()
                            onBack()
                        },
                    ) {
                        Text("Leave room")
                    }
                    if (canManage) {
                        TextButton(onClick = { viewModel.closeRoom() }) { Text("Close") }
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val snapshot = room
            if (snapshot == null) {
                CircularProgressIndicator()
            } else {
                Text(
                    "${snapshot.memberCount} in room · ${if (isHostLabel) "Host" else "Guest"}",
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    "Mode: ${selectionModeLabel(snapshot.selectionMode)}",
                    style = MaterialTheme.typography.bodyMedium,
                )

                // Invite code share row
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Invite code: ${snapshot.code}", style = MaterialTheme.typography.bodyLarge)
                    Spacer(Modifier.width(12.dp))
                    IconButton(onClick = {
                        val send = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(
                                Intent.EXTRA_TEXT,
                                "Join my Watch Together room with code ${snapshot.code}",
                            )
                        }
                        context.startActivity(Intent.createChooser(send, "Share invite code"))
                    }) { Icon(Icons.Filled.Share, contentDescription = "Share invite code") }
                }

                HorizontalDivider()
                val isVoteRoom = room.isVoteRoom()
                val winner = roomVoteWinner(suggestions)
                if (isVoteRoom && canManage) {
                    Button(
                        onClick = { winner?.let { viewModel.promote(it.id) } },
                        enabled = winner != null,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            winner?.let { "Start winner: ${it.title}" }
                                ?: "Start winner — no votes yet",
                        )
                    }
                }
                Text("Suggestions", style = MaterialTheme.typography.titleSmall)
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(suggestions, key = { it.id }) { s ->
                        SuggestionRow(
                            suggestion = s,
                            canManage = canManage,
                            isWinning = isVoteRoom && winner?.id == s.id,
                            onVote = { if (s.votedByMe) viewModel.unvote(s.id) else viewModel.vote(s.id) },
                            onPromote = { viewModel.promote(s.id) },
                            onRemove = { viewModel.removeSuggestion(s.id) },
                        )
                    }
                }
            }
        }
    }
}

private fun selectionModeLabel(mode: RoomSelectionMode): String = when (mode) {
    RoomSelectionMode.HostPick -> "Host pick"
    RoomSelectionMode.Vote -> "Vote"
    RoomSelectionMode.Unknown -> "—"
}

@Composable
private fun SuggestionRow(
    suggestion: Suggestion,
    canManage: Boolean,
    isWinning: Boolean,
    onVote: () -> Unit,
    onPromote: () -> Unit,
    onRemove: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(suggestion.title, style = MaterialTheme.typography.bodyLarge)
                if (isWinning) {
                    Text("WINNING", style = MaterialTheme.typography.labelSmall)
                }
                if (suggestion.subtitle.isNotBlank()) {
                    Text(suggestion.subtitle, style = MaterialTheme.typography.bodySmall)
                }
            }
            TextButton(onClick = onVote) {
                Icon(Icons.Filled.HowToVote, contentDescription = "Vote")
                Spacer(Modifier.width(4.dp))
                Text("${suggestion.voteCount}${if (suggestion.votedByMe) " ✓" else ""}")
            }
            if (canManage) {
                TextButton(onClick = onPromote) { Text("Pick") }
                TextButton(onClick = onRemove) { Text("Remove") }
            }
        }
    }
}
