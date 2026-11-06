package org.siloserver.silo.android.ui.screens.watchparty

import android.content.Context
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import org.siloserver.silo.android.ui.components.SiloConfirmDialog
import org.siloserver.silo.common.R as CommonR
import org.siloserver.silo.common.i18n.getStringWithNames
import org.siloserver.silo.model.feature.WatchPartyExposure
import org.siloserver.silo.model.watchtogether.AddSuggestionRequest
import org.siloserver.silo.model.watchtogether.MemberRole
import org.siloserver.silo.model.watchtogether.RoomPhase
import org.siloserver.silo.model.watchtogether.RoomSelectionMode
import org.siloserver.silo.model.watchtogether.RoomSnapshot
import org.siloserver.silo.model.watchtogether.SetSelectionRequest
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.repository.WatchTogetherRepository
import org.siloserver.silo.viewmodel.WatchPartyItem
import org.siloserver.silo.watchtogether.WatchPartyAvailability
import org.siloserver.silo.watchtogether.WatchPartyAvailabilityRepository
import org.siloserver.silo.watchtogether.newWatchPartyId
import org.siloserver.silo.watchtogether.watchPartyEligibility
import org.siloserver.silo.watchtogether.watchPartyErrorMessage
import org.siloserver.silo.watchtogether.watchPartyHostingOffered

/** The detail page's one Watch Party overflow action. */
data class DetailPartyAction(val label: String, val onClick: () -> Unit)

/**
 * A toast from [WatchPartyDetailViewModel], resolved where it is shown: a
 * catalog message (about [title] when given), or the shared module's text for a failed
 * [result] with the catalog message as its fallback.
 */
class DetailPartyMessage internal constructor(
    @StringRes private val message: Int,
    private val title: String? = null,
    private val result: ApiResult<*>? = null,
) {
    fun text(context: Context): String {
        val text = if (title == null) context.getString(message) else context.getStringWithNames(message, title)
        return result?.let { watchPartyErrorMessage(it, text) } ?: text
    }
}

/**
 * The detail page's party operations (D9): a guest suggests, a host in the
 * lobby stages, and a host whose party is playing switches everyone to the
 * item through the selection operation. A suggestion whose outcome is unknown
 * keeps its id, so tapping again for the same title replays it rather than
 * adding a second one.
 */
class WatchPartyDetailViewModel(
    private val repository: WatchTogetherRepository,
    private val handoff: WatchPartyHandoff,
) : ViewModel() {
    private val _messages = MutableSharedFlow<DetailPartyMessage>(extraBufferCapacity = 4, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val messages: SharedFlow<DetailPartyMessage> = _messages.asSharedFlow()
    private var suggestionDraft: AddSuggestionRequest? = null

    fun stage(item: WatchPartyItem) {
        handoff.rememberPreview(item)
        viewModelScope.launch {
            val result = repository.stageSelection(SetSelectionRequest(item.contentId, item.fileId, item.libraryId))
            _messages.tryEmit(
                if (result is ApiResult.Success) {
                    DetailPartyMessage(CommonR.string.watchParty_toast_added, item.title)
                } else {
                    DetailPartyMessage(CommonR.string.watchParty_error_addTitle, item.title, result)
                },
            )
        }
    }

    fun select(item: WatchPartyItem, onSelected: (RoomSnapshot) -> Unit) {
        handoff.rememberPreview(item)
        viewModelScope.launch {
            when (val result = repository.setSelection(SetSelectionRequest(item.contentId, item.fileId, item.libraryId))) {
                is ApiResult.Success -> onSelected(result.data.room)
                else -> _messages.tryEmit(DetailPartyMessage(CommonR.string.watchParty_error_switchTo, item.title, result))
            }
        }
    }

    fun suggest(item: WatchPartyItem) {
        val request = suggestionDraft?.takeIf { it.contentId == item.contentId } ?: AddSuggestionRequest(
            suggestionId = newWatchPartyId(),
            contentId = item.contentId,
            contentType = item.contentType,
            title = item.title,
            subtitle = item.subtitle,
            posterUrl = item.posterUrl,
        )
        // Held before sending, so a second tap while this one is in flight
        // resends the same suggestion id instead of adding a duplicate.
        suggestionDraft = request
        viewModelScope.launch {
            when (val result = repository.addSuggestion(request)) {
                is ApiResult.Success -> {
                    if (suggestionDraft == request) suggestionDraft = null
                    _messages.tryEmit(DetailPartyMessage(CommonR.string.watchParty_toast_suggested, item.title))
                }
                is ApiResult.NetworkError -> {
                    suggestionDraft = request
                    _messages.tryEmit(DetailPartyMessage(CommonR.string.watchParty_error_suggestionUnconfirmed))
                }
                is ApiResult.Error -> {
                    if (suggestionDraft == request) suggestionDraft = request.takeIf { result.error == "invalid_response" }
                    _messages.tryEmit(DetailPartyMessage(CommonR.string.watchParty_error_suggestTitle, item.title, result))
                }
            }
        }
    }
}

/**
 * Builds the detail page's party action for an item. Made by
 * [rememberWatchPartyDetailActions], which also returns the dialogs to compose.
 */
@Stable
class WatchPartyDetailActions internal constructor(
    private val enabled: Boolean,
    private val room: RoomSnapshot?,
    private val canStage: Boolean,
    private val canSuggest: Boolean,
    private val canHost: Boolean,
    private val onHost: (WatchPartyItem) -> Unit,
    private val viewModel: WatchPartyDetailViewModel,
    private val confirmSelect: (WatchPartyItem) -> Unit,
    private val labels: DetailPartyLabels,
) {
    /** The action for [item], or null when Watch Party is off or nothing applies. */
    fun actionFor(item: WatchPartyItem?): DetailPartyAction? {
        if (!enabled || item == null) return null
        val party = room ?: return if (canHost) DetailPartyAction(labels.host) { onHost(item) } else null
        val host = party.selfRole == MemberRole.Host && party.selfCanManageRoom
        return when {
            host && party.phase == RoomPhase.Lobby && canStage ->
                DetailPartyAction(labels.stage) { viewModel.stage(item) }
            // The server refuses a direct selection in a voting room; those suggest instead.
            host && party.phase == RoomPhase.Playing && party.selectionMode == RoomSelectionMode.HostPick ->
                DetailPartyAction(labels.select) { confirmSelect(item) }
            canSuggest -> DetailPartyAction(labels.suggest) { viewModel.suggest(item) }
            else -> null
        }
    }
}

/** The action labels, resolved in composition. */
internal class DetailPartyLabels(val host: String, val stage: String, val select: String, val suggest: String)

@Composable
fun rememberWatchPartyDetailActions(
    onHost: (WatchPartyItem) -> Unit,
    onSelectedWhilePlaying: (RoomSnapshot) -> Unit,
): Pair<WatchPartyDetailActions, @Composable () -> Unit> {
    val exposure: WatchPartyExposure = koinInject()
    val repository: WatchTogetherRepository = koinInject()
    val availability: WatchPartyAvailabilityRepository = koinInject()
    val viewModel: WatchPartyDetailViewModel = koinViewModel()
    val enabled by exposure.enabled.collectAsState()
    val room by repository.roomSnapshot.collectAsState()
    val available by availability.availability.collectAsState()
    val context = LocalContext.current
    var selecting by remember { mutableStateOf<WatchPartyItem?>(null) }

    LaunchedEffect(viewModel) {
        viewModel.messages.collect { Toast.makeText(context, it.text(context), Toast.LENGTH_SHORT).show() }
    }

    // Not gated on an action in flight: the repository runs one at a time,
    // and the menu item should not flicker while another request finishes.
    val eligibility = watchPartyEligibility(
        room = room,
        features = (available as? WatchPartyAvailability.Available)?.features,
        busy = false,
        personalVotesKnown = true,
    )
    val actions = WatchPartyDetailActions(
        enabled = enabled,
        room = room,
        canStage = eligibility.canStage,
        canSuggest = eligibility.canSuggest,
        canHost = watchPartyHostingOffered(available),
        onHost = onHost,
        viewModel = viewModel,
        confirmSelect = { selecting = it },
        labels = DetailPartyLabels(
            host = stringResource(CommonR.string.watchParty_entry_watchParty),
            stage = stringResource(CommonR.string.watchParty_entry_addToParty),
            select = stringResource(CommonR.string.watchParty_confirm_playForEveryone),
            suggest = stringResource(CommonR.string.watchParty_entry_suggest),
        ),
    )
    val dialogs: @Composable () -> Unit = {
        selecting?.let { item ->
            SiloConfirmDialog(
                title = stringResource(CommonR.string.watchParty_select_title),
                body = stringResource(CommonR.string.watchParty_select_body),
                confirmLabel = stringResource(CommonR.string.watchParty_confirm_playForEveryone),
                destructive = false,
                onConfirm = {
                    selecting = null
                    viewModel.select(item, onSelectedWhilePlaying)
                },
                onDismiss = { selecting = null },
            )
        }
    }
    return actions to dialogs
}
