package org.siloserver.silo.tv.ui.screens.watchparty

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import kotlinx.coroutines.delay
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf
import org.siloserver.silo.common.ui.components.rememberProfileServerUrl
import org.siloserver.silo.model.watchtogether.GuestControlPolicy
import org.siloserver.silo.model.watchtogether.ItemMemberState
import org.siloserver.silo.model.watchtogether.MemberRole
import org.siloserver.silo.model.watchtogether.RoomSelectionMode
import org.siloserver.silo.model.watchtogether.RoomSnapshot
import org.siloserver.silo.model.watchtogether.Suggestion
import org.siloserver.silo.repository.WatchTogetherRepository
import org.siloserver.silo.tv.ui.components.TvDialogOption
import org.siloserver.silo.tv.ui.components.TvOptionDialog
import org.siloserver.silo.tv.ui.components.TvPoster
import org.siloserver.silo.tv.ui.focus.TvControlState
import org.siloserver.silo.tv.ui.focus.TvRestoreFocusOnModalDismiss
import org.siloserver.silo.tv.ui.focus.requestFocusUntilObserved
import org.siloserver.silo.viewmodel.WatchPartyItem
import org.siloserver.silo.viewmodel.WatchPartyLobbyViewModel
import org.siloserver.silo.watchtogether.WatchPartyDestination
import org.siloserver.silo.watchtogether.WatchPartyEligibility
import org.siloserver.silo.watchtogether.canRemoveSuggestion
import org.siloserver.silo.watchtogether.roomVoteWinner
import org.siloserver.silo.watchtogether.watchPartyInviteUrl

/**
 * The Watch Party lobby: the staged item (or the empty state), members with
 * the host badge and lobby Ready, Start for the host and Ready for guests,
 * the host's options, Leave, the invitation, and suggestions.
 *
 * Only the room's phase opens the player ([WatchPartyLobbyViewModel.destination]).
 * Every control is always composed for its role and gated transiently, and
 * rows are keyed by suggestion id, so live snapshots and vote reordering never
 * move or drop focus. If a focused row disappears (removed or promoted), focus
 * goes to the nearest remaining row, else the primary action.
 */
@Composable
fun TvWatchPartyLobbyScreen(
    roomId: String,
    /** Opened because the host stopped playback. */
    hostStopped: Boolean,
    onOpenPlayer: (WatchPartyDestination.Player) -> Unit,
    onOpenDetail: (contentId: String) -> Unit,
    onEnded: () -> Unit,
    onLeft: () -> Unit,
    onBack: () -> Unit,
    viewModel: WatchPartyLobbyViewModel = koinViewModel(
        key = "tv-watch-party-lobby-$roomId",
        parameters = { parametersOf(roomId) },
    ),
    repository: WatchTogetherRepository = koinInject(),
) {
    val state by viewModel.state.collectAsState()
    val destination by viewModel.destination.collectAsState()
    val context = LocalContext.current
    val latestOnOpenPlayer by rememberUpdatedState(onOpenPlayer)
    val latestOnEnded by rememberUpdatedState(onEnded)

    LaunchedEffect(destination) {
        (destination as? WatchPartyDestination.Player)?.let { latestOnOpenPlayer(it) }
    }
    LaunchedEffect(state.ended) {
        val ended = state.ended ?: return@LaunchedEffect
        if (ended.roomId == roomId) latestOnEnded()
    }
    // The membership can also vanish without an ended record (another screen
    // left, the identity changed); the hub then offers what is possible.
    var hadRoom by remember { mutableStateOf(false) }
    val hasRoom = state.room?.roomId == roomId
    LaunchedEffect(hasRoom) {
        if (hasRoom) {
            hadRoom = true
        } else if (hadRoom && state.ended == null) {
            latestOnEnded()
        }
    }
    LaunchedEffect(viewModel) {
        viewModel.messages.collect { message ->
            if (message.isNotBlank()) Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }

    var pickerOpen by rememberSaveable { mutableStateOf(false) }
    var optionsOpen by rememberSaveable { mutableStateOf(false) }
    var confirmEnd by rememberSaveable { mutableStateOf(false) }
    val modalOpen = pickerOpen || optionsOpen || confirmEnd

    BackHandler(enabled = !modalOpen) { onBack() }

    val room = state.room?.takeIf { it.roomId == roomId }
    val eligibility = state.eligibility
    // Role comes from the snapshot, so the layout is right before the
    // capability probe (which eligibility also needs) has answered.
    val isHost = room?.let { it.selfRole == MemberRole.Host && it.selfCanManageRoom } == true
    val hostPick = room?.selectionMode == RoomSelectionMode.HostPick
    // Which controls exist depends on role and room only, never on a request
    // in flight: busy gating is transient, so no focused control disappears.
    val stagesPick = isHost && hostPick && state.features?.stagedSelection == true
    val canAdd = room != null && room.selfRole != MemberRole.Unknown &&
        (room.selectionMode == RoomSelectionMode.HostPick || room.selectionMode == RoomSelectionMode.Vote)
    // Start and lobby Ready apply to a staged pick, which only Host Picks has;
    // a voting room starts when the host plays a suggestion.
    val showStart = isHost && hostPick
    val showReady = !isHost && hostPick && state.features?.lobbyReady == true
    val primaryRow = when {
        room == null -> LobbyPrimary.Back
        showStart -> LobbyPrimary.Start
        showReady -> LobbyPrimary.Ready
        canAdd -> LobbyPrimary.Add
        else -> LobbyPrimary.Leave
    }
    val showPromote = isHost && room?.selectionMode == RoomSelectionMode.Vote && state.features?.voteHostOverride == true

    val primaryFocus = remember { FocusRequester() }
    val addFocus = remember { FocusRequester() }
    val optionsFocus = remember { FocusRequester() }
    var addFocused by remember { mutableStateOf(false) }
    var optionsFocused by remember { mutableStateOf(false) }
    val rowFocus = remember { mutableMapOf<String, FocusRequester>() }
    var lobbyHasFocus by remember { mutableStateOf(false) }
    var lastFocusedSuggestion by remember { mutableStateOf<Int?>(null) }

    val suggestions = state.suggestions
    val actionableIds = suggestions.filter { suggestionHasActions(it, room, isHost, hostPick) }.map { it.id }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val latestActionableIds by rememberUpdatedState(actionableIds)
    val latestSuggestionIds by rememberUpdatedState(suggestions.map { it.id })

    // One focus owner for first entry and for recovery: on entry, and
    // whenever focus leaves the lobby without a modal taking it (a focused
    // suggestion was removed, promoted, or reordered away), land on the
    // nearest suggestion that still has actions, else the primary action.
    LaunchedEffect(lobbyHasFocus, modalOpen, room != null) {
        if (lobbyHasFocus || modalOpen) return@LaunchedEffect
        delay(LOBBY_FOCUS_RESCUE_DELAY_MS)
        if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return@LaunchedEffect
        val target = lastFocusedSuggestion
            ?.let { index -> nearestActionable(latestSuggestionIds, latestActionableIds, index) }
            ?.let { id -> rowFocus[id] }
            ?: primaryFocus
        requestFocusUntilObserved(
            maxAttempts = 20,
            awaitAttempt = { delay(60) },
            requestFocus = target::requestFocus,
            isFocused = { lobbyHasFocus },
        )
    }

    val serverUrl = rememberProfileServerUrl()
    val inviteUrl = room?.takeIf { isHost }?.let { watchPartyInviteUrl(serverUrl, it.invitePath) }
    val connection by repository.connectionState.collectAsState()
    val reconnect = rememberTvWatchPartyReconnectNotice(connection = connection, inRoom = room != null)
    var showStopped by remember { mutableStateOf(hostStopped) }
    LaunchedEffect(showStopped) {
        if (showStopped) {
            delay(HOST_STOPPED_NOTICE_MS)
            showStopped = false
        }
    }
    val staged = rememberTvStagedPreview(
        contentId = room?.selectedContentId,
        fileId = room?.selectedFileId,
        libraryId = room?.selectedLibraryId,
        suggestions = suggestions,
    )
    val stagedId = room?.selectedContentId?.takeIf { it.isNotBlank() }
    val memberState by produceState<ItemMemberState?>(initialValue = null, stagedId) {
        value = null
        stagedId?.let { value = viewModel.memberState(it) }
    }

    Row(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .onFocusChanged { lobbyHasFocus = it.hasFocus }
            .padding(start = 48.dp, end = 48.dp, top = 32.dp),
        horizontalArrangement = Arrangement.spacedBy(36.dp),
    ) {
        // ---- Left: what's on, and what you can do --------------------------
        Column(
            modifier = Modifier
                .weight(0.44f)
                .fillMaxHeight()
                .onFocusChanged { if (it.hasFocus) lastFocusedSuggestion = null }
                .verticalScroll(rememberScrollState())
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            TvPartySectionLabel("Watch Party")
            if (room != null) {
                Text(
                    text = "${tvWatchPartyModeLabel(room.selectionMode)} · Play and pause: " +
                        tvWatchPartyPolicyLabel(room.guestControlPolicy),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.72f),
                )
            }
            StagedCard(
                room = room,
                staged = staged,
                hostPick = hostPick,
                isHost = isHost,
                memberState = memberState,
            )
            if (reconnect != null) TvPartyNotice(title = reconnect)
            if (room != null && !isHost && !room.hostConnected) {
                TvPartyNotice(title = "The host disconnected", detail = TV_WATCH_PARTY_HOST_AWAY_NOTE)
            }
            if (showStopped && !isHost) TvPartyNotice(title = "The host stopped playback")

            if (room == null) {
                Text(
                    text = "Connecting to the party…",
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White.copy(alpha = 0.72f),
                )
                TvPartyActionRow(title = "Back", onClick = onBack, modifier = Modifier.focusRequester(primaryFocus))
                return@Column
            }

            if (showStart) {
                val readiness = state.readiness
                TvPartyActionRow(
                    title = "Start",
                    subtitle = when {
                        stagedId == null -> "Add a title first."
                        readiness.guests > 0 -> "${readiness.ready} of ${readiness.guests} guests ready"
                        else -> "You can start on your own."
                    },
                    onClick = viewModel::start,
                    state = TvControlState.transient(eligibility.canStart),
                    modifier = Modifier.focusRequester(primaryFocus),
                )
            }
            if (showReady) {
                val ready = room.selfMember?.lobbyReady == true
                TvPartyActionRow(
                    title = if (ready) "You're ready" else "I'm ready",
                    subtitle = when {
                        stagedId == null -> "Available once the host picks a title."
                        ready -> "Select to undo."
                        else -> "Lets the host know you're set."
                    },
                    onClick = { viewModel.setLobbyReady(!ready) },
                    state = TvControlState.transient(eligibility.canLobbyReady),
                    modifier = Modifier.focusRequester(primaryFocus),
                )
            }
            if (canAdd) {
                TvPartyActionRow(
                    title = if (stagesPick) "Add a title" else "Suggest a title",
                    onClick = { pickerOpen = true },
                    state = TvControlState.transient(eligibility.canStage || eligibility.canSuggest),
                    modifier = Modifier
                        .then(if (primaryRow == LobbyPrimary.Add) Modifier.focusRequester(primaryFocus) else Modifier)
                        .focusRequester(addFocus)
                        .onFocusChanged { addFocused = it.isFocused },
                )
            }
            if (isHost) {
                TvPartyActionRow(
                    title = "Party options",
                    subtitle = "Mode, who can play and pause, end the party",
                    onClick = { optionsOpen = true },
                    modifier = Modifier
                        .focusRequester(optionsFocus)
                        .onFocusChanged { optionsFocused = it.isFocused },
                )
            }
            TvPartyActionRow(
                title = "Leave party",
                subtitle = if (isHost) TV_WATCH_PARTY_HOST_LEAVE_NOTE else null,
                onClick = {
                    viewModel.leave()
                    onLeft()
                },
                modifier = if (primaryRow == LobbyPrimary.Leave) Modifier.focusRequester(primaryFocus) else Modifier,
            )
        }

        // ---- Right: invitation, members, suggestions -----------------------
        Column(
            modifier = Modifier
                .weight(0.56f)
                .fillMaxHeight()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (room == null) return@Column
            TvPartyInvitation(code = room.code, inviteUrl = inviteUrl)

            val readiness = state.readiness
            TvPartySectionLabel(
                "In this party (${room.members.size})" +
                    if (readiness.guests > 0) " · ${readiness.ready} of ${readiness.guests} ready" else "",
            )
            if (room.members.none { it.isHost }) {
                MemberPlaceholder("Host · connecting…")
            }
            room.members.sortedByDescending { it.isHost }.forEach { member ->
                TvPartyMemberRow(member = member, room = room)
            }

            TvPartySectionLabel(
                if (room.selectionMode == RoomSelectionMode.Vote) "Suggestions · vote for what's next" else "Suggestions",
                modifier = Modifier.padding(top = 8.dp),
            )
            state.suggestionRetry?.let { draft ->
                SuggestionRetryRow(
                    title = draft.title,
                    onRetry = viewModel::retrySuggestion,
                    onDismiss = viewModel::dismissSuggestionRetry,
                )
            }
            if (suggestions.isEmpty()) {
                Text(
                    text = if (canAdd) "No suggestions yet. Suggest a title to get things going." else "No suggestions yet.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = Color.White.copy(alpha = 0.56f),
                )
            }
            val winner = if (room.selectionMode == RoomSelectionMode.Vote) roomVoteWinner(suggestions) else null
            suggestions.forEachIndexed { index, suggestion ->
                key(suggestion.id) {
                    SuggestionRow(
                        suggestion = suggestion,
                        room = room,
                        eligibility = eligibility,
                        isHost = isHost,
                        personalVotesKnown = state.personalVotesKnown,
                        hostPick = hostPick,
                        showPromote = showPromote,
                        leading = winner?.id == suggestion.id,
                        firstChipFocus = rowFocus.getOrPut(suggestion.id) { FocusRequester() },
                        onFocused = { lastFocusedSuggestion = index },
                        onVote = { viewModel.vote(suggestion) },
                        onPromote = { viewModel.promote(suggestion) },
                        onQueue = {
                            TvWatchPartyPreviews.put(
                                WatchPartyItem(
                                    contentId = suggestion.contentId,
                                    contentType = suggestion.contentType,
                                    title = suggestion.title,
                                    subtitle = suggestion.subtitle.ifBlank { null },
                                    posterUrl = suggestion.posterUrl.ifBlank { null },
                                ),
                            )
                            viewModel.queue(suggestion)
                        },
                        onRemove = { viewModel.remove(suggestion) },
                    )
                }
            }
        }
    }

    if (pickerOpen) {
        TvWatchPartyPicker(
            stages = stagesPick,
            onPick = { item ->
                pickerOpen = false
                TvWatchPartyPreviews.put(item)
                if (stagesPick) viewModel.stage(item) else viewModel.suggest(item)
            },
            onOpenSeries = { contentId ->
                pickerOpen = false
                onOpenDetail(contentId)
            },
            onDismiss = { pickerOpen = false },
        )
    }
    TvRestoreFocusOnModalDismiss(visible = pickerOpen, opener = addFocus, isOpenerFocused = { addFocused })

    if (optionsOpen && room != null) {
        val otherMode = if (room.selectionMode == RoomSelectionMode.Vote) RoomSelectionMode.HostPick else RoomSelectionMode.Vote
        val otherPolicy = if (room.guestControlPolicy == GuestControlPolicy.GuestPlayPause) {
            GuestControlPolicy.HostOnly
        } else {
            GuestControlPolicy.GuestPlayPause
        }
        TvOptionDialog(
            title = "Party options",
            options = listOf(
                TvDialogOption(
                    key = "mode",
                    title = "Mode: ${tvWatchPartyModeLabel(room.selectionMode)}",
                    subtitle = "Switch to ${tvWatchPartyModeLabel(otherMode)}",
                    enabled = eligibility.canSwitchMode,
                    onClick = {
                        optionsOpen = false
                        viewModel.setMode(otherMode)
                    },
                ),
                TvDialogOption(
                    key = "policy",
                    title = "Play and pause: ${tvWatchPartyPolicyLabel(room.guestControlPolicy)}",
                    subtitle = "Change to ${tvWatchPartyPolicyLabel(otherPolicy)}",
                    enabled = eligibility.canSetPolicy,
                    onClick = {
                        optionsOpen = false
                        viewModel.setGuestPolicy(otherPolicy)
                    },
                ),
                TvDialogOption(
                    key = "end",
                    title = "End party for everyone",
                    enabled = eligibility.canEnd,
                    onClick = {
                        optionsOpen = false
                        confirmEnd = true
                    },
                ),
            ),
            onDismiss = { optionsOpen = false },
        )
    }
    if (confirmEnd) {
        TvWatchPartyConfirmDialog(
            title = "End the party for everyone?",
            message = "Everyone leaves the party.",
            confirmLabel = "End party",
            destructive = true,
            onConfirm = {
                confirmEnd = false
                viewModel.endForEveryone()
                onLeft()
            },
            onDismiss = { confirmEnd = false },
        )
    }
    TvRestoreFocusOnModalDismiss(
        visible = optionsOpen || confirmEnd,
        opener = optionsFocus,
        isOpenerFocused = { optionsFocused },
    )
}

/** The action that owns focus on entry and when focus is lost. */
private enum class LobbyPrimary { Back, Start, Ready, Add, Leave }

private fun suggestionHasActions(
    suggestion: Suggestion,
    room: RoomSnapshot?,
    isHost: Boolean,
    hostPick: Boolean,
): Boolean {
    if (room == null) return false
    if (!hostPick) return true
    return (isHost && suggestion.contentId != room.selectedContentId) || canRemoveSuggestion(room, suggestion)
}

/** The actionable suggestion nearest [index] in the current list, preferring the one now in its place. */
private fun nearestActionable(ids: List<String>, actionable: List<String>, index: Int): String? {
    if (actionable.isEmpty()) return null
    val set = actionable.toSet()
    for (offset in 0..ids.size) {
        ids.getOrNull(index + offset)?.takeIf { it in set }?.let { return it }
        ids.getOrNull(index - offset)?.takeIf { it in set }?.let { return it }
    }
    return null
}

@Composable
private fun StagedCard(
    room: RoomSnapshot?,
    staged: TvStagedPreview?,
    hostPick: Boolean,
    isHost: Boolean,
    memberState: ItemMemberState?,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.White.copy(alpha = 0.05f), RoundedCornerShape(16.dp))
            .padding(14.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (staged != null) {
            TvPoster(
                imageUrl = staged.posterUrl,
                contentDescription = null,
                modifier = Modifier.size(width = 72.dp, height = 108.dp),
            )
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                TvPartySectionLabel(if (hostPick) "Up next" else "Selected")
                Text(
                    text = staged.title,
                    style = MaterialTheme.typography.titleLarge,
                    color = Color.White,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                listOfNotNull(staged.subtitle, staged.edition).joinToString(" · ").takeIf { it.isNotBlank() }?.let {
                    Text(text = it, style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.72f))
                }
                memberStateLine(room, memberState)?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 14.sp, lineHeight = 18.sp),
                        color = Color.White.copy(alpha = 0.64f),
                    )
                }
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = "Nothing picked yet",
                    style = MaterialTheme.typography.titleLarge,
                    color = Color.White,
                )
                Text(
                    text = when {
                        room == null -> "Waiting for the party…"
                        !hostPick -> "Suggest titles and vote. The host starts the one everyone wants."
                        isHost -> "Add a title, then start when everyone's ready."
                        else -> "The host picks what everyone watches. You can suggest titles."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.72f),
                )
            }
        }
    }
}

/** "Seen by Ana · Part way: Ben" for the staged item, from member state (omitted members are unknown, not unplayed). */
private fun memberStateLine(room: RoomSnapshot?, state: ItemMemberState?): String? {
    if (room == null || state == null) return null
    fun nameOf(userId: String, profileId: String): String? = room.members
        .firstOrNull { it.userId == userId && it.profileId == profileId }
        ?.let(::tvWatchPartyMemberName)
    val seen = state.members.filter { it.state == "watched" }.mapNotNull { nameOf(it.userId, it.profileId) }
    val partWay = state.members
        .filter { it.state != "watched" && (it.positionSeconds ?: 0.0) > 0.0 }
        .mapNotNull { nameOf(it.userId, it.profileId) }
    val parts = buildList {
        if (seen.isNotEmpty()) add("Seen by ${tvWatchPartyNameList(seen)}")
        if (partWay.isNotEmpty()) add("Part way: ${tvWatchPartyNameList(partWay)}")
    }
    return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
}

@Composable
private fun MemberPlaceholder(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium.copy(fontSize = 16.sp),
        color = Color.White.copy(alpha = 0.5f),
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.White.copy(alpha = 0.03f), RoundedCornerShape(10.dp))
            .padding(horizontal = 14.dp, vertical = 8.dp),
    )
}

@Composable
private fun SuggestionRetryRow(title: String, onRetry: () -> Unit, onDismiss: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.White.copy(alpha = 0.06f), RoundedCornerShape(12.dp))
            .padding(horizontal = 14.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "Couldn't confirm your suggestion of $title.",
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White,
            modifier = Modifier.weight(1f),
        )
        TvPartyChip(text = "Try again", onClick = onRetry)
        TvPartyChip(text = "Dismiss", onClick = onDismiss)
    }
}

@Composable
private fun SuggestionRow(
    suggestion: Suggestion,
    room: RoomSnapshot,
    eligibility: WatchPartyEligibility,
    isHost: Boolean,
    personalVotesKnown: Boolean,
    hostPick: Boolean,
    showPromote: Boolean,
    leading: Boolean,
    firstChipFocus: FocusRequester,
    onFocused: () -> Unit,
    onVote: () -> Unit,
    onPromote: () -> Unit,
    onQueue: () -> Unit,
    onRemove: () -> Unit,
) {
    val upNext = hostPick && suggestion.contentId == room.selectedContentId
    val canRemove = canRemoveSuggestion(room, suggestion)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.White.copy(alpha = 0.04f), RoundedCornerShape(12.dp))
            .onFocusChanged { if (it.hasFocus) onFocused() }
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TvPoster(
            imageUrl = suggestion.posterUrl.ifBlank { null },
            contentDescription = null,
            modifier = Modifier.size(width = 32.dp, height = 48.dp),
        )
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = suggestion.title,
                style = MaterialTheme.typography.titleMedium.copy(fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val detail = buildList {
                suggestion.subtitle.takeIf { it.isNotBlank() }?.let(::add)
                if (!hostPick) add(if (suggestion.voteCount == 1) "1 vote" else "${suggestion.voteCount} votes")
                if (upNext) add("Up next")
                if (leading) add("Leading")
            }.joinToString(" · ")
            if (detail.isNotBlank()) {
                Text(
                    text = detail,
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 14.sp, lineHeight = 18.sp),
                    color = Color.White.copy(alpha = 0.64f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        var firstAssigned = false
        fun Modifier.firstChip(): Modifier =
            if (!firstAssigned) {
                firstAssigned = true
                this.focusRequester(firstChipFocus)
            } else {
                this
            }
        if (!hostPick) {
            TvPartyChip(
                // Personal votes are unknown until an HTTP read confirms them.
                text = if (!personalVotesKnown) "Vote" else if (suggestion.votedByMe) "Voted" else "Vote",
                selected = personalVotesKnown && suggestion.votedByMe,
                onClick = onVote,
                state = TvControlState.transient(eligibility.canVote),
                modifier = Modifier.firstChip(),
            )
            if (showPromote) {
                TvPartyChip(
                    text = "Play now",
                    onClick = onPromote,
                    state = TvControlState.transient(eligibility.canPromote),
                    modifier = Modifier.firstChip(),
                )
            }
        } else if (isHost && !upNext) {
            TvPartyChip(
                text = "Queue",
                onClick = onQueue,
                state = TvControlState.transient(eligibility.canQueue),
                modifier = Modifier.firstChip(),
            )
        }
        if (canRemove) {
            TvPartyChip(
                text = "Remove",
                onClick = onRemove,
                state = TvControlState.transient(!eligibility.busy),
                modifier = Modifier.firstChip(),
            )
        }
    }
}

private const val LOBBY_FOCUS_RESCUE_DELAY_MS = 150L
private const val HOST_STOPPED_NOTICE_MS = 6_000L
