package org.siloserver.silo.tv.ui.screens.watchparty

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.tv.material3.Text
import kotlinx.coroutines.delay
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf
import org.siloserver.silo.common.ui.components.rememberProfileServerUrl
import org.siloserver.silo.model.watchtogether.ItemMemberState
import org.siloserver.silo.model.watchtogether.MemberRole
import org.siloserver.silo.model.watchtogether.RoomSelectionMode
import org.siloserver.silo.model.watchtogether.RoomSnapshot
import org.siloserver.silo.model.watchtogether.Suggestion
import org.siloserver.silo.repository.WatchTogetherRepository
import org.siloserver.silo.tv.ui.focus.TvControlState
import org.siloserver.silo.tv.ui.focus.TvRestoreFocusOnModalDismiss
import org.siloserver.silo.tv.ui.focus.requestFocusUntilObserved
import org.siloserver.silo.tv.ui.theme.SiloOnSurface
import org.siloserver.silo.tv.ui.theme.SiloSecondaryText
import org.siloserver.silo.viewmodel.WatchPartyItem
import org.siloserver.silo.viewmodel.WatchPartyLobbyViewModel
import org.siloserver.silo.watchtogether.WatchPartyDestination
import org.siloserver.silo.watchtogether.WatchPartyFeatures
import org.siloserver.silo.watchtogether.canRemoveSuggestion
import org.siloserver.silo.watchtogether.roomVoteWinner
import org.siloserver.silo.watchtogether.watchPartyInviteUrl

/**
 * The Watch Party lobby, laid out as the tvOS lobby ([TvWatchPartyRoomView]):
 * the staged film full-bleed, the hero, the ballot or suggestions, seats, and
 * the one foregrounded action. Settings, End, and Leave sit behind "···"; the
 * code pill and the Invite seat open the invitation page.
 *
 * Only the room's phase opens the player ([WatchPartyLobbyViewModel.destination]).
 * Every control is composed from role and room alone and gated transiently,
 * and cards are keyed by suggestion id, so live snapshots and vote reordering
 * never move or drop focus. If a focused card disappears (removed, promoted),
 * focus goes to the nearest card that still has an action, else the primary
 * action. Modals hand focus back to whatever opened them.
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

    /** "select" stages a pick, "suggest" adds a suggestion; null is closed. */
    var pickerPurpose by rememberSaveable { mutableStateOf<String?>(null) }
    var optionsOpen by rememberSaveable { mutableStateOf(false) }
    var inviteOpen by rememberSaveable { mutableStateOf(false) }
    var confirmEnd by rememberSaveable { mutableStateOf(false) }
    var confirmLeave by rememberSaveable { mutableStateOf(false) }
    var menuSuggestionId by remember { mutableStateOf<String?>(null) }
    val modalOpen = pickerPurpose != null || optionsOpen || inviteOpen || confirmEnd || confirmLeave ||
        menuSuggestionId != null

    BackHandler(enabled = !modalOpen) { onBack() }

    val room = state.room?.takeIf { it.roomId == roomId }
    val eligibility = state.eligibility
    val suggestions = state.suggestions
    // Role comes from the snapshot, so the layout is right before the
    // capability probe (which eligibility also needs) has answered.
    val isHost = room?.let { it.selfRole == MemberRole.Host && it.selfCanManageRoom } == true

    val focus = rememberTvPartyFocus()
    var lobbyHasFocus by remember { mutableStateOf(false) }
    var lastFocusedSuggestion by remember { mutableStateOf<Int?>(null) }
    val actionableIds = if (room == null) {
        emptyList()
    } else {
        suggestions.filter { tvPartySuggestionFocusable(room, it) }.map { it.id }
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val latestActionableIds by rememberUpdatedState(actionableIds)
    val latestSuggestionIds by rememberUpdatedState(suggestions.map { it.id })
    val anchorKey = room?.let { tvPartyAnchorKey(it, state.features, suggestions) } ?: PARTY_KEY_BACK
    val latestAnchorKey by rememberUpdatedState(anchorKey)

    // Remember which card had focus, so a card that vanishes under focus
    // hands it to its neighbour rather than the page.
    val focusedKey = focus.focusedKey
    LaunchedEffect(focusedKey) {
        val key = focusedKey ?: return@LaunchedEffect
        lastFocusedSuggestion = if (key.startsWith("s:")) {
            latestSuggestionIds.indexOf(key.removePrefix("s:")).takeIf { it >= 0 }
        } else {
            null
        }
    }

    // One focus owner for first entry and for recovery: on entry, and
    // whenever focus leaves the lobby without a modal taking it (a focused
    // suggestion was removed, promoted, or reordered away; a button's action
    // swapped it for another), land on the nearest suggestion that still has
    // actions, else the anchor action.
    LaunchedEffect(lobbyHasFocus, modalOpen, room != null) {
        if (lobbyHasFocus || modalOpen) return@LaunchedEffect
        delay(LOBBY_FOCUS_RESCUE_DELAY_MS)
        if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return@LaunchedEffect
        val target = lastFocusedSuggestion
            ?.let { index -> nearestActionable(latestSuggestionIds, latestActionableIds, index) }
            ?.let { id -> focus.requester(tvPartySuggestionKey(id)) }
            ?: focus.requester(latestAnchorKey)
        requestFocusUntilObserved(
            maxAttempts = 20,
            awaitAttempt = { delay(60) },
            requestFocus = target::requestFocus,
            isFocused = { lobbyHasFocus },
        )
    }

    // Entry focus goes to the anchor (the primary action). The anchor can
    // change a moment after entry (the capability probe answers, a pick
    // lands); while the viewer still sits on the old one, focus follows.
    var initialFocusClaimed by rememberSaveable { mutableStateOf(false) }
    var previousAnchor by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(room != null, anchorKey, modalOpen) {
        if (room == null || modalOpen) return@LaunchedEffect
        val previous = previousAnchor
        previousAnchor = anchorKey
        val follow = previous != null && previous != anchorKey && focus.isFocused(previous)
        if (initialFocusClaimed && !follow) return@LaunchedEffect
        val target = focus.requester(anchorKey)
        requestFocusUntilObserved(
            maxAttempts = 20,
            awaitAttempt = { delay(60) },
            requestFocus = target::requestFocus,
            isFocused = { focus.isFocused(latestAnchorKey) },
        )
        initialFocusClaimed = true
    }

    // Modals return focus to the control that opened them.
    var opener by remember { mutableStateOf<String?>(null) }
    fun openModal(open: () -> Unit) {
        opener = focus.focusedKey
        open()
    }
    TvRestoreFocusOnModalDismiss(
        visible = modalOpen,
        opener = opener?.let(focus::requester),
        isOpenerFocused = { focus.isFocused(opener) },
    )

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

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onFocusChanged { lobbyHasFocus = it.hasFocus },
    ) {
        if (room == null) {
            ConnectingRoom(focus = focus, onBack = onBack)
        } else {
            TvWatchPartyRoomView(
                room = room,
                suggestions = suggestions,
                features = state.features,
                eligibility = eligibility,
                personalVotesKnown = state.personalVotesKnown,
                connection = connection,
                staged = staged,
                focus = focus,
                notice = reconnect ?: "The host stopped playback".takeIf { showStopped && !isHost },
                memberStateLine = memberStateLine(room, memberState),
                footer = state.suggestionRetry?.let { draft ->
                    {
                        SuggestionRetryRow(
                            title = draft.title,
                            onRetry = viewModel::retrySuggestion,
                            onDismiss = viewModel::dismissSuggestionRetry,
                        )
                    }
                },
                actions = TvPartyRoomActions(
                    onInvite = { openModal { inviteOpen = true } },
                    onOptions = { openModal { optionsOpen = true } },
                    onChooseTitle = { openModal { pickerPurpose = PICK_SELECT } },
                    onSuggest = { openModal { pickerPurpose = PICK_SUGGEST } },
                    onStart = {
                        if (room.selectionMode == RoomSelectionMode.Vote) {
                            roomVoteWinner(suggestions)?.let(viewModel::promote)
                        } else {
                            viewModel.start()
                        }
                    },
                    onReady = viewModel::setLobbyReady,
                    onVote = viewModel::vote,
                    onQueue = { suggestion -> queue(viewModel, suggestion) },
                    onSuggestionMenu = { suggestion ->
                        if (suggestionMenuRows(room, suggestion, state.features).isNotEmpty()) {
                            openModal { menuSuggestionId = suggestion.id }
                        }
                    },
                ),
            )
        }
    }

    val purpose = pickerPurpose
    if (purpose != null && room != null) {
        // Host-pick hosts stage their pick; everyone else suggests.
        val stages = purpose == PICK_SELECT && state.features?.stagedSelection == true
        TvWatchPartyPicker(
            stages = stages,
            members = room.members,
            memberState = if (state.features?.memberState == true) viewModel::memberState else null,
            onPick = { item ->
                pickerPurpose = null
                TvWatchPartyPreviews.put(item)
                if (stages) viewModel.stage(item) else viewModel.suggest(item)
            },
            onOpenSeries = { contentId ->
                pickerPurpose = null
                onOpenDetail(contentId)
            },
            onDismiss = { pickerPurpose = null },
        )
    }

    if (optionsOpen && room != null) {
        TvWatchPartyOptionsOverlay(
            room = room,
            features = state.features,
            eligibility = eligibility,
            onSetMode = viewModel::setMode,
            onSetPolicy = viewModel::setGuestPolicy,
            // The lobby is never playing; the player's panel owns this row.
            onStopPlayback = { optionsOpen = false },
            onEnd = {
                optionsOpen = false
                confirmEnd = true
            },
            onLeave = {
                optionsOpen = false
                if (isHost) {
                    confirmLeave = true
                } else {
                    viewModel.leave()
                    onLeft()
                }
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

    val menuSuggestion = suggestions.firstOrNull { it.id == menuSuggestionId }
    LaunchedEffect(menuSuggestionId, menuSuggestion == null) {
        // The suggestion went away (removed, promoted) while its menu was open.
        if (menuSuggestionId != null && menuSuggestion == null) menuSuggestionId = null
    }
    if (menuSuggestion != null && room != null) {
        TvPartyMenuOverlay(
            title = menuSuggestion.title,
            onDismiss = { menuSuggestionId = null },
            width = 380.dp,
        ) {
            suggestionMenuRows(room, menuSuggestion, state.features).forEach { row ->
                TvPartyOptionRow(
                    title = row.title,
                    destructive = row == SuggestionMenuRow.Remove,
                    state = TvControlState.transient(!eligibility.busy),
                    onClick = {
                        menuSuggestionId = null
                        when (row) {
                            SuggestionMenuRow.StartThisOne -> viewModel.promote(menuSuggestion)
                            SuggestionMenuRow.Queue -> queue(viewModel, menuSuggestion)
                            SuggestionMenuRow.Remove -> viewModel.remove(menuSuggestion)
                        }
                    },
                )
            }
        }
    }

    if (confirmEnd) {
        TvWatchPartyConfirmDialog(
            title = "End this party for everyone?",
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
    if (confirmLeave) {
        TvWatchPartyConfirmDialog(
            title = "Leave the party?",
            message = TV_WATCH_PARTY_HOST_LEAVE_NOTE,
            confirmLabel = "Leave party",
            destructive = true,
            onConfirm = {
                confirmLeave = false
                viewModel.leave()
                onLeft()
            },
            onDismiss = { confirmLeave = false },
        )
    }
}

private const val PICK_SELECT = "select"
private const val PICK_SUGGEST = "suggest"
internal const val PARTY_KEY_BACK = "back"

private fun queue(viewModel: WatchPartyLobbyViewModel, suggestion: Suggestion) {
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
}

private enum class SuggestionMenuRow(val title: String) {
    StartThisOne("Start this one"),
    Queue("Queue"),
    Remove("Remove suggestion"),
}

/** A suggestion card's context actions for this member. */
private fun suggestionMenuRows(room: RoomSnapshot, suggestion: Suggestion, features: WatchPartyFeatures?): List<SuggestionMenuRow> {
    val manages = room.selfRole == MemberRole.Host && room.selfCanManageRoom
    return buildList {
        if (manages && room.selectionMode == RoomSelectionMode.Vote && features?.voteHostOverride == true) {
            add(SuggestionMenuRow.StartThisOne)
        }
        // Queueing stages the pick, which only servers with staged selection accept.
        if (manages && room.selectionMode == RoomSelectionMode.HostPick && features?.stagedSelection == true &&
            suggestion.contentId != room.selectedContentId
        ) {
            add(SuggestionMenuRow.Queue)
        }
        if (canRemoveSuggestion(room, suggestion)) add(SuggestionMenuRow.Remove)
    }
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

/** Before the first snapshot: say so, and keep a focus owner so Back and Select work. */
@Composable
private fun ConnectingRoom(focus: TvPartyFocus, onBack: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize()) {
        TvPartyBackdrop(url = null)
        Column(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .padding(horizontal = TvPartyMetrics.pageInsetX),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            TvPartyEyebrow("Watch Party")
            Text(text = "Connecting to the party…", fontSize = 30.sp, fontWeight = FontWeight.Bold, color = SiloOnSurface)
            TvPartyButton(
                label = "Back",
                kind = TvPartyButtonKind.Secondary,
                onClick = onBack,
                modifier = Modifier.partyFocus(focus, PARTY_KEY_BACK),
            )
        }
    }
}

@Composable
private fun SuggestionRetryRow(title: String, onRetry: () -> Unit, onDismiss: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = "Couldn't confirm your suggestion of $title.",
            fontSize = TvPartyMetrics.caption,
            color = SiloSecondaryText,
            modifier = Modifier.weight(1f, fill = false),
        )
        TvPartyButton(label = "Try again", kind = TvPartyButtonKind.Secondary, onClick = onRetry, height = 32.dp, fontSize = 14.sp)
        TvPartyButton(label = "Dismiss", kind = TvPartyButtonKind.Secondary, onClick = onDismiss, height = 32.dp, fontSize = 14.sp)
    }
}

private const val LOBBY_FOCUS_RESCUE_DELAY_MS = 150L
private const val HOST_STOPPED_NOTICE_MS = 6_000L
