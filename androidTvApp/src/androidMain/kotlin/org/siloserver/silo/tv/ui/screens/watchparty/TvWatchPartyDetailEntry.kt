package org.siloserver.silo.tv.ui.screens.watchparty

import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf
import org.siloserver.silo.model.feature.WatchPartyExposure
import org.siloserver.silo.model.watchtogether.MemberRole
import org.siloserver.silo.model.watchtogether.RoomPhase
import org.siloserver.silo.model.watchtogether.RoomSelectionMode
import org.siloserver.silo.model.watchtogether.SetSelectionRequest
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.repository.WatchTogetherRepository
import org.siloserver.silo.viewmodel.WatchPartyHubViewModel
import org.siloserver.silo.viewmodel.WatchPartyItem
import org.siloserver.silo.viewmodel.WatchPartyLobbyViewModel
import org.siloserver.silo.watchtogether.RoomSession
import org.siloserver.silo.watchtogether.WatchPartyAvailability
import org.siloserver.silo.watchtogether.WatchPartyAvailabilityRepository
import org.siloserver.silo.watchtogether.WatchPartyDestination
import org.siloserver.silo.watchtogether.watchPartyErrorMessage

/** The detail page's Watch Party row in its More menu. */
internal data class TvWatchPartyDetailOption(
    val title: String,
    val subtitle: String,
    val onSelect: () -> Unit,
)

/** What the viewer asked the detail page to do with the party. */
internal sealed interface TvDetailPartyRequest {
    val item: WatchPartyItem

    /** Not engaged: host a Host Picks party with this item staged. */
    data class Host(override val item: WatchPartyItem) : TvDetailPartyRequest

    /** Host in a Host Picks lobby: stage this item. */
    data class Stage(val roomId: String, override val item: WatchPartyItem) : TvDetailPartyRequest

    /** Any member: suggest this item. */
    data class Suggest(val roomId: String, override val item: WatchPartyItem) : TvDetailPartyRequest

    /** Host while the party plays: confirm, then switch everyone to this item. */
    data class Select(val roomId: String, override val item: WatchPartyItem) : TvDetailPartyRequest
}

/** Holds the detail page's pending party request; the dialogs and effects run it. */
@Stable
internal class TvWatchPartyDetailEntry internal constructor() {
    internal var request by mutableStateOf<TvDetailPartyRequest?>(null)
    internal var selectConfirmed by mutableStateOf(false)
}

@Composable
internal fun rememberTvWatchPartyDetailEntry(): TvWatchPartyDetailEntry = remember { TvWatchPartyDetailEntry() }

/**
 * The Watch Party row for a detail page showing [item] (null when the page
 * is not a movie, an episode, or a series whose next-up episode is known).
 * Not engaged: "Watch Party" hosts with the item staged. Engaged: a Host
 * Picks host in the lobby adds it ("Add to party"), a host while playing
 * switches everyone to it after a confirmation, and everyone else suggests it.
 */
@Composable
internal fun rememberTvWatchPartyDetailOption(
    entry: TvWatchPartyDetailEntry,
    item: WatchPartyItem?,
    exposure: WatchPartyExposure = koinInject(),
    repository: WatchTogetherRepository = koinInject(),
): TvWatchPartyDetailOption? {
    val enabled by exposure.enabled.collectAsState()
    val room by repository.roomSnapshot.collectAsState()
    if (!enabled || item == null) return null
    val current = room
    if (current == null) {
        return TvWatchPartyDetailOption(
            title = "Watch Party",
            subtitle = "Host a party with this title",
            onSelect = { entry.request = TvDetailPartyRequest.Host(item) },
        )
    }
    val host = current.selfRole == MemberRole.Host && current.selfCanManageRoom
    val knownMode = current.selectionMode == RoomSelectionMode.HostPick || current.selectionMode == RoomSelectionMode.Vote
    return when {
        host && current.phase == RoomPhase.Lobby && current.selectionMode == RoomSelectionMode.HostPick ->
            TvWatchPartyDetailOption(
                title = "Add to party",
                subtitle = "Make this the party's next title",
                onSelect = { entry.request = TvDetailPartyRequest.Stage(current.roomId, item) },
            )
        host && current.phase == RoomPhase.Playing -> TvWatchPartyDetailOption(
            title = "Play for everyone",
            subtitle = "Switch the party to this title",
            onSelect = {
                entry.selectConfirmed = false
                entry.request = TvDetailPartyRequest.Select(current.roomId, item)
            },
        )
        current.selfRole != MemberRole.Unknown && knownMode &&
            (current.phase == RoomPhase.Lobby || current.phase == RoomPhase.Playing) ->
            TvWatchPartyDetailOption(
                title = "Suggest to Party",
                subtitle = "Add it to the party's suggestions",
                onSelect = { entry.request = TvDetailPartyRequest.Suggest(current.roomId, item) },
            )
        else -> null
    }
}

/**
 * Runs the detail page's party request: its confirmation, the room or hub
 * operation, and the navigation that follows. [onNavigate] receives the
 * party screen to show, or null for the hub.
 */
@Composable
internal fun TvWatchPartyDetailEffects(
    entry: TvWatchPartyDetailEntry,
    onNavigate: (WatchPartyDestination?) -> Unit,
) {
    when (val request = entry.request) {
        null -> Unit
        is TvDetailPartyRequest.Host -> DetailHostRunner(entry, request, onNavigate)
        is TvDetailPartyRequest.Stage -> DetailStageRunner(entry, request, onNavigate)
        is TvDetailPartyRequest.Suggest -> DetailSuggestRunner(entry, request)
        is TvDetailPartyRequest.Select -> if (!entry.selectConfirmed) {
            TvWatchPartyConfirmDialog(
                title = "Play this for everyone?",
                message = "The party switches to ${request.item.title} now.",
                confirmLabel = "Play for everyone",
                focusConfirm = true,
                onConfirm = { entry.selectConfirmed = true },
                onDismiss = { entry.request = null },
            )
        } else {
            DetailSelectRunner(entry, request, onNavigate)
        }
    }
}

@Composable
private fun DetailHostRunner(
    entry: TvWatchPartyDetailEntry,
    request: TvDetailPartyRequest.Host,
    onNavigate: (WatchPartyDestination?) -> Unit,
) {
    val context = LocalContext.current
    val availability: WatchPartyAvailabilityRepository = koinInject()
    val hub: WatchPartyHubViewModel = koinViewModel(key = "tv-detail-watch-party-hub")
    val state by hub.uiState.collectAsState()
    val latestNavigate by rememberUpdatedState(onNavigate)
    var started by remember(request) { mutableStateOf(false) }

    LaunchedEffect(request, state.availability) {
        if (started) return@LaunchedEffect
        when (state.availability ?: availability.availability.value) {
            null -> Unit // The hub's first probe is still running.
            is WatchPartyAvailability.Available -> {
                started = true
                TvWatchPartyPreviews.put(request.item)
                hub.clearError()
                hub.hostWithItem(request.item)
            }
            // Unsupported, not allowed, or a failed probe: the hub explains and offers Retry.
            else -> {
                entry.request = null
                latestNavigate(null)
            }
        }
    }
    LaunchedEffect(state.destination) {
        val destination = state.destination ?: return@LaunchedEffect
        hub.consumeDestination()
        entry.request = null
        latestNavigate(destination)
    }
    LaunchedEffect(state.error) {
        val error = state.error ?: return@LaunchedEffect
        if (!started) return@LaunchedEffect
        Toast.makeText(context, error, Toast.LENGTH_LONG).show()
        hub.clearError()
        // A staging failure after the room exists still lands in the lobby
        // (the destination above); anything else ends this request.
        if (state.destination == null) entry.request = null
    }
}

@Composable
private fun DetailStageRunner(
    entry: TvWatchPartyDetailEntry,
    request: TvDetailPartyRequest.Stage,
    onNavigate: (WatchPartyDestination?) -> Unit,
) {
    val context = LocalContext.current
    val repository: WatchTogetherRepository = koinInject()
    val lobby: WatchPartyLobbyViewModel = koinViewModel(
        key = "tv-detail-watch-party-lobby-${request.roomId}",
        parameters = { parametersOf(request.roomId) },
    )
    val latestNavigate by rememberUpdatedState(onNavigate)
    LaunchedEffect(request) {
        TvWatchPartyPreviews.put(request.item)
        lobby.stage(request.item)
        val staged = withTimeoutOrNull(REQUEST_TIMEOUT_MS) {
            repository.roomSnapshot.first { room ->
                room == null || room.roomId != request.roomId || room.selectedContentId == request.item.contentId
            }
        }
        entry.request = null
        if (staged != null && staged.roomId == request.roomId && staged.selectedContentId == request.item.contentId) {
            Toast.makeText(context, "Added ${request.item.title} to the party.", Toast.LENGTH_SHORT).show()
            latestNavigate(WatchPartyDestination.Lobby(request.roomId))
        }
    }
    LaunchedEffect(lobby) {
        lobby.messages.collect { message ->
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            entry.request = null
        }
    }
}

@Composable
private fun DetailSuggestRunner(entry: TvWatchPartyDetailEntry, request: TvDetailPartyRequest.Suggest) {
    val context = LocalContext.current
    val repository: WatchTogetherRepository = koinInject()
    val lobby: WatchPartyLobbyViewModel = koinViewModel(
        key = "tv-detail-watch-party-lobby-${request.roomId}",
        parameters = { parametersOf(request.roomId) },
    )
    LaunchedEffect(request) {
        val before = repository.suggestions.value.count { it.contentId == request.item.contentId }
        lobby.suggest(request.item)
        val added = withTimeoutOrNull(REQUEST_TIMEOUT_MS) {
            repository.suggestions.first { list -> list.count { it.contentId == request.item.contentId } > before }
        }
        entry.request = null
        if (added != null) {
            Toast.makeText(context, "Suggested ${request.item.title} to the party.", Toast.LENGTH_SHORT).show()
        }
    }
    LaunchedEffect(lobby) {
        lobby.messages.collect { message ->
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            entry.request = null
        }
    }
}

@Composable
private fun DetailSelectRunner(
    entry: TvWatchPartyDetailEntry,
    request: TvDetailPartyRequest.Select,
    onNavigate: (WatchPartyDestination?) -> Unit,
) {
    val context = LocalContext.current
    val repository: WatchTogetherRepository = koinInject()
    val latestNavigate by rememberUpdatedState(onNavigate)
    LaunchedEffect(request) {
        val result = repository.setSelection(
            SetSelectionRequest(
                contentId = request.item.contentId,
                fileId = request.item.fileId,
                libraryId = request.item.libraryId,
            ),
        )
        entry.request = null
        entry.selectConfirmed = false
        if (result is ApiResult.Success) {
            latestNavigate(WatchPartyDestination.Player(request.roomId))
        } else {
            Toast.makeText(
                context,
                watchPartyErrorMessage(result, "Couldn't switch the party to ${request.item.title}."),
                Toast.LENGTH_LONG,
            ).show()
        }
    }
}

/**
 * D5: while this device is in a Watch Party, starting other playback first
 * asks the viewer to leave the party. Leaving is explicit, never hidden.
 */
@Stable
internal class TvWatchPartyPlayGuard internal constructor(
    private val engaged: () -> Boolean,
    private val leave: () -> Unit,
) {
    internal var pending by mutableStateOf<(() -> Unit)?>(null)
        private set

    /** Run [play] now, or ask first when engaged. */
    fun requestPlay(play: () -> Unit) {
        if (engaged()) pending = play else play()
    }

    internal fun confirm() {
        val play = pending ?: return
        pending = null
        leave()
        play()
    }

    internal fun cancel() {
        pending = null
    }
}

@Composable
internal fun rememberTvWatchPartyPlayGuard(
    repository: WatchTogetherRepository = koinInject(),
    roomSession: RoomSession = koinInject(),
): TvWatchPartyPlayGuard = remember(repository, roomSession) {
    TvWatchPartyPlayGuard(
        engaged = { repository.roomSnapshot.value != null },
        leave = { roomSession.depart(closeRoom = false) },
    )
}

@Composable
internal fun TvWatchPartyPlayGuardDialog(guard: TvWatchPartyPlayGuard) {
    if (guard.pending == null) return
    TvWatchPartyConfirmDialog(
        title = "Leave the Watch Party to play this?",
        message = "You're in a Watch Party. Leaving it stops the party on this TV.",
        confirmLabel = "Leave and play",
        onConfirm = guard::confirm,
        onDismiss = guard::cancel,
    )
}

private const val REQUEST_TIMEOUT_MS = 15_000L
