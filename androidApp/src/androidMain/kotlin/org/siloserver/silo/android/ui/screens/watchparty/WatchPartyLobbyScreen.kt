package org.siloserver.silo.android.ui.screens.watchparty

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material.icons.outlined.ThumbUp
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf
import org.siloserver.silo.android.ui.components.SiloTopBar
import org.siloserver.silo.common.ui.components.ThumbhashImage
import org.siloserver.silo.model.catalog.ItemDetail
import org.siloserver.silo.model.watchtogether.GuestControlPolicy
import org.siloserver.silo.model.watchtogether.MemberRole
import org.siloserver.silo.model.watchtogether.RoomSelectionMode
import org.siloserver.silo.model.watchtogether.RoomSnapshot
import org.siloserver.silo.model.watchtogether.Suggestion
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.network.ServerRegistry
import org.siloserver.silo.repository.CatalogRepository
import org.siloserver.silo.repository.WatchTogetherRepository
import org.siloserver.silo.viewmodel.WatchPartyConnectionStatus
import org.siloserver.silo.viewmodel.WatchPartyItem
import org.siloserver.silo.viewmodel.WatchPartyLobbyViewModel
import org.siloserver.silo.watchtogether.WatchPartyDestination
import org.siloserver.silo.watchtogether.canRemoveSuggestion
import org.siloserver.silo.watchtogether.roomVoteWinner
import org.siloserver.silo.watchtogether.watchPartyInviteUrl

/**
 * The Watch Party lobby: the staged title (or an empty state), members and
 * lobby Ready, Start, the options menu, the invitation, and suggestions. The
 * room's phase alone opens the player.
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
    val catalog: CatalogRepository = koinInject()
    val serverRegistry: ServerRegistry = koinInject()
    val activeServer by serverRegistry.activeEntry.collectAsState()
    val context = LocalContext.current

    val room = state.room?.takeIf { it.roomId == roomId }
    val eligibility = state.eligibility
    val isHost = room?.selfRole == MemberRole.Host
    var leaving by remember { mutableStateOf(false) }
    var seenRoom by remember { mutableStateOf(false) }
    var pickerMode by rememberSaveable { mutableStateOf<WatchPartyPickMode?>(null) }
    var menuOpen by remember { mutableStateOf(false) }
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

    fun leave() {
        leaving = true
        viewModel.leave()
        onLeft()
    }

    fun endForEveryone() {
        leaving = true
        viewModel.endForEveryone()
        onLeft()
    }

    val connection = state.connection
    val reconnecting = connection is WatchPartyConnectionStatus.Reconnecting
    val showReconnect = rememberWatchPartyReconnectNotice(
        disconnected = room != null && reconnecting,
        since = (connection as? WatchPartyConnectionStatus.Reconnecting)?.sinceMs,
    )

    // A title preview from the picker, a detail page, or a suggestion lays the
    // staged card out at once; the catalog read adds the edition.
    val stagedId = room?.selectedContentId?.takeIf { it.isNotBlank() }
    val stagedLibraryId = room?.selectedLibraryId
    val preview = handoff.preview(stagedId)
        ?: state.suggestions.firstOrNull { it.contentId == stagedId }?.toPartyItem()
    var stagedDetail by remember(stagedId) { mutableStateOf<ItemDetail?>(null) }
    LaunchedEffect(stagedId, stagedLibraryId) {
        val id = stagedId ?: return@LaunchedEffect
        stagedDetail = (catalog.getItemDetail(id, stagedLibraryId) as? ApiResult.Success)?.data
    }

    val pickMode = when {
        eligibility.canStage -> WatchPartyPickMode.Stage
        eligibility.canSuggest -> WatchPartyPickMode.Suggest
        else -> null
    }

    Scaffold(
        topBar = {
            SiloTopBar(
                title = "Watch Party",
                onBackClick = onBack,
                actions = {
                    Box {
                        IconButton(onClick = { menuOpen = true }, enabled = room != null) {
                            Icon(Icons.Filled.MoreVert, contentDescription = "Party options")
                        }
                        LobbyOptionsMenu(
                            expanded = menuOpen,
                            room = room,
                            isHost = eligibility.isHost,
                            canSwitchMode = eligibility.canSwitchMode,
                            canSetPolicy = eligibility.canSetPolicy,
                            canEnd = eligibility.canEnd,
                            onDismiss = { menuOpen = false },
                            onSetMode = { mode ->
                                menuOpen = false
                                if (room?.selectionMode != mode) viewModel.setMode(mode)
                            },
                            onSetPolicy = { policy ->
                                menuOpen = false
                                if (room?.guestControlPolicy != policy) viewModel.setGuestPolicy(policy)
                            },
                            onLeave = {
                                menuOpen = false
                                if (isHost) confirmHostLeave = true else leave()
                            },
                            onEnd = {
                                menuOpen = false
                                confirmEnd = true
                            },
                        )
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        ) {
            if (showReconnect) {
                item(key = "reconnect") {
                    WatchPartyBanner(
                        if ((connection as? WatchPartyConnectionStatus.Reconnecting)?.sinceMs == 0L) {
                            "Connecting to the party…"
                        } else {
                            "Reconnecting to the party…"
                        },
                    )
                }
            }
            if (room != null && !room.hostConnected && !isHost) {
                item(key = "host-away") {
                    WatchPartyBanner("The host lost connection. The party ends in two minutes unless the host returns.")
                }
            }
            if (room == null) {
                item(key = "loading") {
                    Text("Connecting to the party…", style = MaterialTheme.typography.bodyLarge)
                }
                return@LazyColumn
            }

            item(key = "mode") {
                Text(
                    listOf(
                        if (room.selectionMode == RoomSelectionMode.Vote) "Voting" else "Host picks",
                        if (room.guestControlPolicy == GuestControlPolicy.GuestPlayPause) {
                            "Guests can play and pause"
                        } else {
                            "Only the host controls playback"
                        },
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            item(key = "staged") {
                if (stagedId != null) {
                    StagedCard(
                        title = preview?.title ?: stagedDetail?.title ?: "Loading title…",
                        subtitle = preview?.subtitle ?: stagedDetail?.episodeSubtitle(),
                        posterUrl = preview?.posterUrl ?: stagedDetail?.posterUrl,
                        edition = stagedDetail?.versions
                            ?.firstOrNull { it.fileId == room.selectedFileId }
                            ?.let(::watchPartyEditionLabel),
                    )
                } else {
                    Text(
                        when {
                            room.selectionMode == RoomSelectionMode.Vote ->
                                "Suggest titles and vote. The host starts the one you pick."
                            isHost -> "Pick something to watch. You can start whenever you're ready, even alone."
                            else -> "Waiting for the host to pick something. You can suggest titles."
                        },
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            }

            item(key = "controls") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (isHost && (room.selectionMode == RoomSelectionMode.HostPick || stagedId != null)) {
                        Button(
                            onClick = viewModel::start,
                            enabled = eligibility.canStart,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                        ) { Text("Start") }
                    }
                    if (eligibility.canLobbyReady) {
                        val ready = room.selfMember?.lobbyReady == true
                        if (ready) {
                            FilledTonalButton(
                                onClick = { viewModel.setLobbyReady(false) },
                                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                            ) {
                                Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("Ready")
                            }
                        } else {
                            Button(
                                onClick = { viewModel.setLobbyReady(true) },
                                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                            ) { Text("I'm ready") }
                        }
                    }
                    if (pickMode != null) {
                        OutlinedButton(
                            onClick = { pickerMode = pickMode },
                            enabled = !eligibility.busy,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                        ) {
                            Text(
                                when {
                                    pickMode == WatchPartyPickMode.Suggest -> "Suggest a title"
                                    stagedId != null -> "Change title"
                                    else -> "Add a title"
                                },
                            )
                        }
                    }
                    val readiness = state.readiness
                    if (readiness.guests > 0 && stagedId != null) {
                        Text(
                            "${readiness.ready} of ${readiness.guests} " +
                                (if (readiness.guests == 1) "guest" else "guests") + " ready",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            item(key = "members-header") {
                Text("People", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
            }
            val members = room.members.sortedByDescending { it.isHost }
            if (members.isEmpty()) {
                item(key = "members-placeholder") {
                    Text(
                        "Connecting…",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                items(members) { member ->
                    WatchPartyMemberRow(member = member, phase = room.phase)
                }
            }

            item(key = "invite") {
                WatchPartyInviteSection(
                    code = room.code,
                    inviteUrl = if (isHost) {
                        activeServer?.url?.let { watchPartyInviteUrl(it, room.invitePath) }
                    } else {
                        null
                    },
                    modifier = Modifier.padding(top = 8.dp),
                )
            }

            item(key = "suggestions-header") {
                Text("Suggestions", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
            }
            state.suggestionRetry?.let { draft ->
                item(key = "suggestion-retry") {
                    WatchPartyBanner(
                        text = "Couldn't confirm your suggestion of ${draft.title}.",
                        action = {
                            TextButton(onClick = viewModel::retrySuggestion) { Text("Try again") }
                            IconButton(onClick = viewModel::dismissSuggestionRetry) {
                                Icon(Icons.Filled.Close, contentDescription = "Dismiss")
                            }
                        },
                    )
                }
            }
            if (state.suggestions.isEmpty()) {
                item(key = "suggestions-empty") {
                    Text(
                        "No suggestions yet.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                val voteRoom = room.selectionMode == RoomSelectionMode.Vote
                val winnerId = if (voteRoom) roomVoteWinner(state.suggestions)?.id else null
                items(state.suggestions, key = { "s-${it.id}" }) { suggestion ->
                    SuggestionRow(
                        suggestion = suggestion,
                        suggester = room.members.firstOrNull {
                            it.userId == suggestion.suggesterUserId && it.profileId == suggestion.suggesterProfileId
                        }?.let(::watchPartyMemberName),
                        voteRoom = voteRoom,
                        leading = suggestion.id == winnerId,
                        upNext = !voteRoom && suggestion.contentId == stagedId,
                        canVote = eligibility.canVote,
                        canRemove = !eligibility.busy && canRemoveSuggestion(room, suggestion),
                        canPromote = voteRoom && eligibility.canPromote,
                        canQueue = !voteRoom && eligibility.canQueue,
                        onVote = { viewModel.vote(suggestion) },
                        onRemove = { viewModel.remove(suggestion) },
                        onPromote = { viewModel.promote(suggestion) },
                        onQueue = {
                            handoff.rememberPreview(suggestion.toPartyItem())
                            viewModel.queue(suggestion)
                        },
                    )
                }
            }
            item(key = "bottom") { Spacer(Modifier.height(24.dp)) }
        }
    }

    pickerMode?.let { mode ->
        WatchPartyPickerSheet(
            mode = mode,
            roomMembers = room?.members.orEmpty(),
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
                endForEveryone()
            },
            onDismiss = { confirmEnd = false },
        )
    }
    if (confirmHostLeave) {
        WatchPartyHostLeaveDialog(
            onConfirm = {
                confirmHostLeave = false
                leave()
            },
            onDismiss = { confirmHostLeave = false },
        )
    }
}

@Composable
private fun LobbyOptionsMenu(
    expanded: Boolean,
    room: RoomSnapshot?,
    isHost: Boolean,
    canSwitchMode: Boolean,
    canSetPolicy: Boolean,
    canEnd: Boolean,
    onDismiss: () -> Unit,
    onSetMode: (RoomSelectionMode) -> Unit,
    onSetPolicy: (GuestControlPolicy) -> Unit,
    onLeave: () -> Unit,
    onEnd: () -> Unit,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        if (isHost && room != null) {
            MenuHeader("Who picks")
            CheckedMenuItem("Host picks", room.selectionMode == RoomSelectionMode.HostPick, canSwitchMode) {
                onSetMode(RoomSelectionMode.HostPick)
            }
            CheckedMenuItem("Voting", room.selectionMode == RoomSelectionMode.Vote, canSwitchMode) {
                onSetMode(RoomSelectionMode.Vote)
            }
            HorizontalDivider()
            MenuHeader("Play and pause")
            CheckedMenuItem("Host only", room.guestControlPolicy == GuestControlPolicy.HostOnly, canSetPolicy) {
                onSetPolicy(GuestControlPolicy.HostOnly)
            }
            CheckedMenuItem(
                "Guests can play and pause",
                room.guestControlPolicy == GuestControlPolicy.GuestPlayPause,
                canSetPolicy,
            ) {
                onSetPolicy(GuestControlPolicy.GuestPlayPause)
            }
            HorizontalDivider()
        }
        DropdownMenuItem(text = { Text("Leave party") }, onClick = onLeave)
        if (isHost) {
            DropdownMenuItem(
                text = { Text("End party for everyone", color = MaterialTheme.colorScheme.error) },
                enabled = canEnd,
                onClick = onEnd,
            )
        }
    }
}

@Composable
private fun MenuHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

@Composable
private fun CheckedMenuItem(label: String, checked: Boolean, enabled: Boolean, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(label) },
        enabled = enabled,
        leadingIcon = {
            if (checked) {
                Icon(Icons.Filled.Check, contentDescription = "Selected")
            } else {
                Spacer(Modifier.size(24.dp))
            }
        },
        onClick = onClick,
    )
}

@Composable
private fun StagedCard(title: String, subtitle: String?, posterUrl: String?, edition: String?) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            ThumbhashImage(
                url = posterUrl,
                thumbhash = null,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.width(72.dp).height(108.dp).clip(RoundedCornerShape(6.dp)),
            )
            Spacer(Modifier.width(16.dp))
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    "Up next",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
                subtitle?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                edition?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun SuggestionRow(
    suggestion: Suggestion,
    suggester: String?,
    voteRoom: Boolean,
    leading: Boolean,
    upNext: Boolean,
    canVote: Boolean,
    canRemove: Boolean,
    canPromote: Boolean,
    canQueue: Boolean,
    onVote: () -> Unit,
    onRemove: () -> Unit,
    onPromote: () -> Unit,
    onQueue: () -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ThumbhashImage(
                url = suggestion.posterUrl.ifBlank { null },
                thumbhash = null,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.width(40.dp).height(60.dp).clip(RoundedCornerShape(4.dp)),
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    suggestion.title,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                val detail = listOfNotNull(
                    suggestion.subtitle.takeIf { it.isNotBlank() },
                    suggester?.let { "Suggested by $it" },
                ).joinToString(" · ")
                if (detail.isNotBlank()) {
                    Text(
                        detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                val badge = when {
                    upNext -> "Up next"
                    leading -> "Leading"
                    else -> null
                }
                badge?.let {
                    Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                }
            }
            if (voteRoom) {
                TextButton(
                    onClick = onVote,
                    enabled = canVote,
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Icon(
                        if (suggestion.votedByMe) Icons.Filled.ThumbUp else Icons.Outlined.ThumbUp,
                        contentDescription = if (suggestion.votedByMe) "Remove your vote" else "Vote",
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(suggestion.voteCount.toString())
                }
            }
        }
        if (canPromote || (canQueue && !upNext) || canRemove) {
            Row(Modifier.padding(start = 52.dp)) {
                if (canPromote) TextButton(onClick = onPromote) { Text("Start now") }
                if (canQueue && !upNext) TextButton(onClick = onQueue) { Text("Queue") }
                if (canRemove) TextButton(onClick = onRemove) { Text("Remove") }
            }
        }
    }
}

private fun Suggestion.toPartyItem() = WatchPartyItem(
    contentId = contentId,
    contentType = contentType,
    title = title,
    subtitle = subtitle.ifBlank { null },
    posterUrl = posterUrl.ifBlank { null },
)

private fun ItemDetail.episodeSubtitle(): String? {
    val series = seriesTitle?.takeIf { it.isNotBlank() } ?: return year.takeIf { it > 0 }?.toString()
    val season = seasonNumber
    val episode = episodeNumber
    return if (season != null && episode != null) "$series · S$season·E$episode" else series
}
