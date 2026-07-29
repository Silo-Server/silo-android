package org.siloserver.silo.tv.ui.screens.watchtogether

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Glow
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import org.siloserver.silo.model.watchtogether.GuestControlPolicy
import org.siloserver.silo.model.watchtogether.MemberRole
import org.siloserver.silo.model.watchtogether.RoomPhase
import org.siloserver.silo.model.watchtogether.RoomSelectionMode
import org.siloserver.silo.model.watchtogether.RoomSnapshot
import org.siloserver.silo.model.watchtogether.Suggestion
import org.siloserver.silo.tv.ui.navigation.TvRoute
import org.siloserver.silo.tv.ui.screens.auth.QrCodePanel
import org.siloserver.silo.tv.ui.screens.player.TvDialogActionRow
import org.siloserver.silo.tv.ui.screens.player.TvDialogCyclerRow
import org.siloserver.silo.tv.ui.theme.DarkBackground
import org.siloserver.silo.tv.ui.theme.FocusedContainer
import org.siloserver.silo.tv.ui.theme.FocusedContent
import org.siloserver.silo.tv.ui.theme.SiloBlue
import org.siloserver.silo.watchtogether.isVoteRoom
import org.siloserver.silo.watchtogether.roomVoteWinner
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

/**
 * Pure: should the lobby hand off to the synced player? Only once the room is
 * actually [RoomPhase.Playing] AND a (non-blank) selection has landed — mirrors
 * the phone lobby's `lobbyPlayerDestinationOrNull` semantics (enum compare +
 * isNullOrBlank, NOT a wire-string compare).
 */
fun shouldEnterSyncedPlayer(s: RoomSnapshot?): Boolean {
    if (s == null || s.phase != RoomPhase.Playing || s.selectedContentId.isNullOrBlank()) return false
    if (s.selfRole == MemberRole.Host && s.memberCount <= 1) return false
    return true
}

/**
 * Watch Together lobby (TV). Shows member count / role / selection mode, the
 * host block (invite code shown LARGE + selection-mode/policy cyclers + per-row
 * promote/remove, gated on the server's per-recipient management capability),
 * and the suggestion list (focusable rows; Select toggles vote/unvote). Auto-
 * navigates everyone into the synced player once a selection lands and the room
 * starts playing.
 *
 * Host pre-selection comes from item-detail (the entry dialog) and there is no
 * reusable TV title-picker, so this screen is vote/promote/pick-only: there is
 * no "add a suggestion" affordance on TV (it would require building a picker).
 *
 * [onNavigateToPlayer] is called with a fully-built player route (computed from
 * the snapshot, mirroring the phone lobby); [onBack] backs out of the lobby.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun TvWatchTogetherLobbyScreen(
    roomId: String,
    onNavigateToPlayer: (route: String) -> Unit,
    onBack: () -> Unit,
    viewModel: TvWatchTogetherLobbyViewModel = koinViewModel(
        key = "wt-lobby-$roomId",
        parameters = { parametersOf(roomId) },
    ),
) {
    val room by viewModel.room.collectAsState()
    val suggestions by viewModel.suggestions.collectAsState()
    val closedReason by viewModel.roomClosedReason.collectAsState()
    val context = LocalContext.current

    // Role drives only the cosmetic header label; mutating controls gate on
    // the server's per-recipient management capability so a demoted/
    // grace-period host (selfRole still "host" but management revoked) doesn't
    // see dead buttons.
    val snapshot = room
    val isHostLabel = snapshot?.selfRole == MemberRole.Host
    val canManage = snapshot?.selfCanManageRoom == true
    val isVoteRoom = snapshot.isVoteRoom()

    // Auto-hand-off into the synced player once the room is playing + selected.
    LaunchedEffect(
        room?.phase,
        room?.selectedContentId,
        room?.selectedFileId,
        room?.memberCount,
        room?.selfRole,
    ) {
        val snapshot = room ?: return@LaunchedEffect
        if (shouldEnterSyncedPlayer(snapshot)) {
            onNavigateToPlayer(
                TvRoute.Player(
                    contentId = snapshot.selectedContentId!!,
                    fileId = snapshot.selectedFileId,
                    roomId = snapshot.roomId,
                ).route,
            )
        }
    }

    // Server already closed the room (host left / explicit close) — just tear
    // down locally and back out; do NOT re-issue closeRoom.
    LaunchedEffect(closedReason) {
        if (closedReason != null) {
            viewModel.leave()
            onBack()
        }
    }

    LaunchedEffect(viewModel) {
        viewModel.errors.collect { message ->
            if (message.isNotBlank()) {
                Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            }
        }
    }

    // Browsing away retains the process-scoped room connection. Leaving is an
    // explicit action below; hosts close the room for everyone via "Close room".
    BackHandler(enabled = true) {
        onBack()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(start = 56.dp, end = 56.dp, top = 40.dp, bottom = 48.dp),
            horizontalArrangement = Arrangement.spacedBy(48.dp),
        ) {
        Column(
            modifier = Modifier.weight(0.42f),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            // --- Header --------------------------------------------------------
            Text(
                text = "WATCH TOGETHER",
                style = MaterialTheme.typography.headlineMedium.copy(
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 4.sp,
                ),
                color = Color.White,
            )
            if (snapshot != null) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "${snapshot.memberCount} in room · ${if (isHostLabel) "Host" else "Guest"}",
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White.copy(alpha = 0.86f),
                    )
                    Text(
                        text = "Mode: ${selectionModeLabel(snapshot.selectionMode)}",
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White.copy(alpha = 0.66f),
                    )
                    if (!snapshot.hostConnected) {
                        Text(
                            text = "Host disconnected",
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.SemiBold,
                            ),
                            color = Color(0xFFFFB4A9),
                        )
                    }
                }
            }

            if (snapshot == null) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = "Connecting…",
                        style = MaterialTheme.typography.titleLarge,
                        color = Color.White.copy(alpha = 0.7f),
                    )
                }
            } else {
                // --- Host block (gated on manage capability) -------------------
                if (canManage) {
                    Text(
                        text = "JOIN CODE",
                        style = MaterialTheme.typography.labelLarge.copy(letterSpacing = 2.sp),
                        color = Color.White.copy(alpha = 0.56f),
                    )
                    Text(
                        text = snapshot.code,
                        style = MaterialTheme.typography.displaySmall.copy(
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 12.sp,
                        ),
                        color = Color.White,
                    )
                    // Scannable invite: a guest can point their phone camera at
                    // the QR to read the join code instead of typing it. (TVs
                    // have no share targets, so a QR is the parity equivalent of
                    // the phone's share-code action.) Nested Column so the parent
                    // 20dp spacing doesn't balloon around the QR + caption.
                    if (snapshot.code.isNotBlank()) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            QrCodePanel(content = snapshot.code, size = 140.dp)
                            Text(
                                text = "Scan to get the code",
                                style = MaterialTheme.typography.labelMedium,
                                color = Color.White.copy(alpha = 0.5f),
                            )
                        }
                    }
                }
            }
        }

        Column(
            modifier = Modifier
                .weight(0.58f)
                .verticalScroll(rememberScrollState())
                .padding(bottom = 48.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            if (snapshot != null) {
                TvDialogActionRow(
                    title = "Browse titles",
                    onClick = onBack,
                )
                TvDialogActionRow(
                    title = "Leave room",
                    onClick = {
                        viewModel.leave()
                        onBack()
                    },
                )
                if (canManage) {
                    // Selection mode is fixed at room creation — shown read-only.
                    TvDialogCyclerRow(
                        title = "Selection mode",
                        value = selectionModeLabel(snapshot.selectionMode),
                        onPrevious = {},
                        onNext = {},
                        enabled = false,
                    )
                    TvDialogCyclerRow(
                        title = "Guest controls",
                        value = policyLabel(snapshot.guestControlPolicy),
                        onPrevious = { viewModel.updatePolicy(nextPolicy(snapshot.guestControlPolicy).wire) },
                        onNext = { viewModel.updatePolicy(nextPolicy(snapshot.guestControlPolicy).wire) },
                    )
                    // Explicit host close — mirrors mobile's "Close" action.
                    // Stays on screen; the room_closed broadcast then drives the
                    // back-out via the closedReason effect above.
                    CloseRoomButton(onClick = viewModel::closeRoom)
                }

                val winner = roomVoteWinner(suggestions)
                if (isVoteRoom && canManage) {
                    TvDialogActionRow(
                        title = winner?.let { "Start winner: ${it.title}" }
                            ?: "Start winner — no votes yet",
                        enabled = winner != null,
                        onClick = { winner?.let { viewModel.promote(it.id) } },
                    )
                }

                Text(
                    text = "SUGGESTIONS",
                    style = MaterialTheme.typography.labelLarge.copy(letterSpacing = 2.sp),
                    color = Color.White.copy(alpha = 0.56f),
                )
                if (suggestions.isEmpty()) {
                    Text(
                        text = "No suggestions yet.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = Color.White.copy(alpha = 0.5f),
                    )
                } else {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        suggestions.forEach { s ->
                            key(s.id) {
                                SuggestionRow(
                                    suggestion = s,
                                    canManage = canManage,
                                    isWinning = isVoteRoom && winner?.id == s.id,
                                    onVote = {
                                        if (s.votedByMe) viewModel.unvote(s.id) else viewModel.vote(s.id)
                                    },
                                    onPromote = { viewModel.promote(s.id) },
                                    onRemove = { viewModel.removeSuggestion(s.id) },
                                )
                            }
                        }
                    }
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

private fun policyLabel(policy: GuestControlPolicy): String = when (policy) {
    GuestControlPolicy.HostOnly -> "Host only"
    GuestControlPolicy.GuestPlayPause -> "Guests play/pause"
    GuestControlPolicy.Unknown -> "—"
}

/** Cycle between the two real policies; Unknown maps forward to HostOnly. */
private fun nextPolicy(policy: GuestControlPolicy): GuestControlPolicy = when (policy) {
    GuestControlPolicy.HostOnly -> GuestControlPolicy.GuestPlayPause
    GuestControlPolicy.GuestPlayPause -> GuestControlPolicy.HostOnly
    GuestControlPolicy.Unknown -> GuestControlPolicy.HostOnly
}

/**
 * Focusable suggestion row — reuses the `Surface` + [MutableInteractionSource]
 * focus idiom from `TvSubtitleResultRow`. Select toggles vote/unvote; a host
 * (manage-capable) row exposes Promote + Remove sub-actions.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun SuggestionRow(
    suggestion: Suggestion,
    canManage: Boolean,
    isWinning: Boolean,
    onVote: () -> Unit,
    onPromote: () -> Unit,
    onRemove: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val shape = RoundedCornerShape(16.dp)

    Surface(
        onClick = onVote,
        interactionSource = interactionSource,
        shape = ClickableSurfaceDefaults.shape(shape = shape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.White.copy(alpha = 0.04f),
            contentColor = Color.White,
            focusedContainerColor = FocusedContainer,
            focusedContentColor = FocusedContent,
            pressedContainerColor = FocusedContainer,
            pressedContentColor = FocusedContent,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.02f),
        border = ClickableSurfaceDefaults.border(
            focusedBorder = Border(
                border = BorderStroke(2.dp, DarkBackground.copy(alpha = 0.82f)),
                shape = shape,
            ),
        ),
        glow = ClickableSurfaceDefaults.glow(
            focusedGlow = Glow(
                elevationColor = Color.White.copy(alpha = 0.18f),
                elevation = 16.dp,
            ),
        ),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .then(
                if (isFocused) {
                    Modifier.border(2.dp, Color.White.copy(alpha = 0.98f), shape)
                } else {
                    Modifier
                },
            ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (suggestion.posterUrl.isNotBlank()) {
                AsyncImage(
                    model = suggestion.posterUrl,
                    contentDescription = null,
                    modifier = Modifier
                        .size(width = 36.dp, height = 54.dp),
                )
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Text(
                    text = suggestion.title,
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontSize = 17.sp,
                        fontWeight = FontWeight.SemiBold,
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = if (isFocused) FocusedContent else Color.White,
                )
                if (suggestion.subtitle.isNotBlank()) {
                    Text(
                        text = suggestion.subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = if (isFocused) {
                            FocusedContent.copy(alpha = 0.7f)
                        } else {
                            Color.White.copy(alpha = 0.56f)
                        },
                    )
                }
            }
            // Vote glyph: filled when votedByMe, outline otherwise.
            Icon(
                imageVector = if (suggestion.votedByMe) Icons.Filled.Star else Icons.Outlined.StarBorder,
                contentDescription = if (suggestion.votedByMe) "Voted" else "Vote",
                tint = if (isFocused) FocusedContent else Color.White,
            )
            Spacer(Modifier.width(4.dp))
            Text(
                text = suggestion.voteCount.toString(),
                style = MaterialTheme.typography.titleMedium,
                color = if (isFocused) FocusedContent else Color.White,
            )
            if (isWinning) {
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "WINNING",
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp,
                    ),
                    color = if (isFocused) FocusedContent else SiloBlue,
                )
            }
            if (canManage) {
                Spacer(Modifier.width(8.dp))
                RowAction(text = "Pick", onClick = onPromote)
                RowAction(text = "Remove", onClick = onRemove)
            }
        }
    }
}

/** Compact focusable sub-action button used for host Promote/Remove. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun RowAction(text: String, onClick: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val shape = RoundedCornerShape(10.dp)
    Surface(
        onClick = onClick,
        interactionSource = interactionSource,
        shape = ClickableSurfaceDefaults.shape(shape = shape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.White.copy(alpha = 0.10f),
            contentColor = Color.White,
            focusedContainerColor = FocusedContainer,
            focusedContentColor = FocusedContent,
            pressedContainerColor = FocusedContainer,
            pressedContentColor = FocusedContent,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.04f),
        modifier = Modifier.then(
            if (isFocused) Modifier.border(2.dp, Color.White.copy(alpha = 0.98f), shape) else Modifier,
        ),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = if (isFocused) FocusedContent else Color.White,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        )
    }
}

/**
 * Host-only "Close room for everyone" action — the TV equivalent of mobile's
 * "Close" button. Clicking closes the room server-side while staying on the
 * lobby; the resulting room_closed broadcast drives the back-out reactively.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun CloseRoomButton(onClick: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val shape = RoundedCornerShape(12.dp)
    Surface(
        onClick = onClick,
        interactionSource = interactionSource,
        shape = ClickableSurfaceDefaults.shape(shape = shape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color(0xFF7A2620).copy(alpha = 0.42f),
            contentColor = Color(0xFFFFB4A9),
            focusedContainerColor = Color(0xFFB3261E),
            focusedContentColor = Color.White,
            pressedContainerColor = Color(0xFFB3261E),
            pressedContentColor = Color.White,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.02f),
        modifier = Modifier.then(
            if (isFocused) Modifier.border(2.dp, Color.White.copy(alpha = 0.98f), shape) else Modifier,
        ),
    ) {
        Text(
            text = "Close room for everyone",
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp),
        )
    }
}
