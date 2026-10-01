package org.siloserver.silo.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.siloserver.silo.model.request.AdminRequestAction
import org.siloserver.silo.model.request.MediaRequest
import org.siloserver.silo.model.request.ModerationHold
import org.siloserver.silo.model.request.RequestActionCopy
import org.siloserver.silo.model.request.RequestDisplayState
import org.siloserver.silo.model.request.RequestMutationFailure
import org.siloserver.silo.model.request.RequestOutcome
import org.siloserver.silo.model.request.RequestRowActionPhase
import org.siloserver.silo.model.request.RequestStatus
import org.siloserver.silo.model.request.requestInstantKey
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.network.errorMessage
import org.siloserver.silo.repository.RequestsRepository

data class RequestApprovalsUiState(
    /** Everyone's requests waiting on a decision, oldest first. */
    val awaitingApproval: List<MediaRequest> = emptyList(),
    /** Failed requests that can be retried, most recently updated first. */
    val failed: List<MediaRequest> = emptyList(),
    val hasLoaded: Boolean = false,
    val isRefreshing: Boolean = false,
    val error: String? = null,
    /** Inline message for an action whose result is unknown; cleared when it settles. */
    val actionErrorMessage: String? = null,
    /** Per-row animation state for actions in flight or just finished. */
    val phases: Map<String, RequestRowActionPhase> = emptyMap(),
    /** Rows whose decision was sent without a usable answer; they offer nothing until it settles. */
    val heldIds: Set<String> = emptySet(),
    /** A failed action's reason, shown on its own row. */
    val rowErrors: Map<String, String> = emptyMap(),
    /** Per-row count of outright failures; the row's button shakes when it changes. */
    val failureCounts: Map<String, Int> = emptyMap(),
    /** Bumped on every accepted action, for the success haptic. */
    val completedActions: Int = 0,
    /** Bumped on every outright failure, for the error haptic. */
    val failedActions: Int = 0,
    /** The newest outright failure's message, for surfaces without per-row error lines (TV). */
    val lastActionError: String? = null,
) {
    val isEmpty: Boolean get() = hasLoaded && awaitingApproval.isEmpty() && failed.isEmpty()

    /** Whether a row may offer its decision: not while its own action runs or is held. */
    fun canAct(request: MediaRequest): Boolean = request.id !in phases && request.id !in heldIds
}

/**
 * The admin approval queue: everyone's requests waiting on a decision, and
 * failed ones that can be retried. Only reachable when
 * [org.siloserver.silo.model.feature.RequestsFeatureStore.canModerate] is true.
 */
class RequestApprovalsViewModel(
    private val repository: RequestsRepository,
    private val holdLifetime: Duration = ModerationHold.Lifetime,
    private val timeSource: TimeSource = TimeSource.Monotonic,
    /** Loads on creation; the TV hub loads only once moderation is confirmed. */
    loadOnInit: Boolean = true,
) : ViewModel() {

    private val _uiState = MutableStateFlow(RequestApprovalsUiState())
    val uiState: StateFlow<RequestApprovalsUiState> = _uiState.asStateFlow()

    private val holds = mutableMapOf<String, ModerationHold>()

    init {
        if (loadOnInit) load()
        viewModelScope.launch {
            // A decision made elsewhere (the detail page) takes the request out
            // of whichever queue still lists it.
            repository.lastModeration.drop(1).filterNotNull().collect { applyModeration(it.record) }
        }
    }

    fun load() {
        viewModelScope.launch { fetch() }
    }

    fun refresh() {
        viewModelScope.launch {
            _uiState.update { it.copy(isRefreshing = true) }
            fetch()
            _uiState.update { it.copy(isRefreshing = false) }
        }
    }

    suspend fun fetch(): Boolean {
        _uiState.update { it.copy(error = null) }
        val pending = viewModelScope.async {
            repository.adminRequests(status = RequestStatus.Pending, outcome = RequestOutcome.Active)
        }
        val broken = repository.adminRequests(outcome = RequestOutcome.Failed)
        val waiting = pending.await()
        if (waiting is ApiResult.Success && broken is ApiResult.Success) {
            val awaiting = waiting.data.sortedBy { it.createdAt.requestInstantKey() }
            val failed = broken.data.sortedByDescending { it.updatedAt.requestInstantKey() }
            val listed = awaiting + failed
            repository.cache.storeModerationRecords(listed)
            repository.prefetch(listed)
            // Release the holds this read settles; an unchanged request keeps its hold.
            holds.entries.removeAll { (id, hold) -> hold.isSettled(listed.firstOrNull { it.id == id }) }
            _uiState.update {
                it.copy(
                    awaitingApproval = awaiting,
                    failed = failed,
                    hasLoaded = true,
                    heldIds = holds.keys.toSet(),
                    // A fresh read is the new word on every row.
                    rowErrors = it.rowErrors.filterKeys { id -> id in phasesOrHeld(it) },
                ).clearingUnconfirmedMessageIfSettled()
            }
            return true
        }
        val failure = if (waiting is ApiResult.Success) broken else waiting
        _uiState.update {
            if (it.hasLoaded) it else it.copy(hasLoaded = true, error = failure.errorMessage("Failed to load requests"))
        }
        return false
    }

    fun perform(action: AdminRequestAction, request: MediaRequest) {
        if (!_uiState.value.canAct(request)) return
        _uiState.update {
            it.copy(
                phases = it.phases + (request.id to RequestRowActionPhase.Working(action)),
                rowErrors = it.rowErrors - request.id,
                actionErrorMessage = null,
            )
        }
        viewModelScope.launch {
            val result = repository.adminAction(request.id, action)
            when {
                result is ApiResult.Success -> {
                    // Show the result on the row, then let the row leave.
                    _uiState.update {
                        it.copy(
                            phases = it.phases + (request.id to RequestRowActionPhase.Succeeded(action)),
                            completedActions = it.completedActions + 1,
                        )
                    }
                    delay(ResultHold)
                    _uiState.update {
                        it.copy(
                            awaitingApproval = it.awaitingApproval.filterNot { r -> r.id == request.id },
                            failed = it.failed.filterNot { r -> r.id == request.id },
                            phases = it.phases - request.id,
                        )
                    }
                }
                RequestMutationFailure.isUncertain(result) -> {
                    // Never resend: hold the row until a fresh read shows the result.
                    _uiState.update { it.copy(phases = it.phases - request.id) }
                    hold(request)
                    fetch()
                    if (holds.isNotEmpty()) {
                        _uiState.update { it.copy(actionErrorMessage = RequestActionCopy.UnconfirmedModeration) }
                    }
                }
                else -> {
                    val message = RequestActionCopy.failure(result, "Something went wrong")
                    _uiState.update {
                        it.copy(
                            phases = it.phases - request.id,
                            failureCounts = it.failureCounts + (request.id to ((it.failureCounts[request.id] ?: 0) + 1)),
                            failedActions = it.failedActions + 1,
                            rowErrors = it.rowErrors + (request.id to message),
                            lastActionError = message,
                        )
                    }
                }
            }
        }
    }

    /**
     * Holds the row, and ends the hold when its lifetime runs out even if no
     * read settles it. Only this hold: a newer one for the same request keeps
     * its own clock.
     */
    private fun hold(request: MediaRequest) {
        val hold = ModerationHold(request, timeSource, holdLifetime)
        holds[request.id] = hold
        _uiState.update { it.copy(heldIds = holds.keys.toSet()) }
        viewModelScope.launch {
            delay(holdLifetime)
            if (holds[request.id] !== hold) return@launch
            holds.remove(request.id)
            _uiState.update { it.copy(heldIds = holds.keys.toSet()).clearingUnconfirmedMessageIfSettled() }
            fetch()
        }
    }

    /** Rows whose state the last action still owns; their error lines survive a read. */
    private fun phasesOrHeld(state: RequestApprovalsUiState): Set<String> = state.phases.keys + state.heldIds

    private fun RequestApprovalsUiState.clearingUnconfirmedMessageIfSettled(): RequestApprovalsUiState =
        if (heldIds.isEmpty() && actionErrorMessage == RequestActionCopy.UnconfirmedModeration) copy(actionErrorMessage = null) else this

    private fun applyModeration(record: MediaRequest) {
        // This list's own action removes its row after the result shows.
        if (record.id in _uiState.value.phases) return
        val pendingNow = RequestDisplayState.of(record) == RequestDisplayState.Pending
        _uiState.update {
            it.copy(
                awaitingApproval = it.awaitingApproval.filterNot { r -> r.id == record.id && !pendingNow },
                failed = it.failed.filterNot { r -> r.id == record.id },
            )
        }
    }

    companion object {
        /** How long a finished row shows its result before leaving the list. */
        val ResultHold: Duration = 900.milliseconds
    }
}
