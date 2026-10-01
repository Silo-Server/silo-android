package org.siloserver.silo.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlin.time.Duration
import kotlin.time.TimeSource
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.siloserver.silo.model.feature.RequestsFeatureStore
import org.siloserver.silo.model.request.AdminRequestAction
import org.siloserver.silo.model.request.CreateMediaRequest
import org.siloserver.silo.model.request.MediaRequest
import org.siloserver.silo.model.request.RequestActionHold
import org.siloserver.silo.model.request.RequestActionCopy
import org.siloserver.silo.model.request.RequestAttention
import org.siloserver.silo.model.request.RequestDisplayState
import org.siloserver.silo.model.request.RequestMediaDetail
import org.siloserver.silo.model.request.RequestMediaResult
import org.siloserver.silo.model.request.RequestMediaType
import org.siloserver.silo.model.request.RequestMutationFailure
import org.siloserver.silo.model.request.RequestOutcome
import org.siloserver.silo.model.request.RequestProgress
import org.siloserver.silo.model.request.RequestStatus
import org.siloserver.silo.model.request.RequestUnconfirmedToken
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.network.errorMessage
import org.siloserver.silo.repository.RequestDetailCache
import org.siloserver.silo.repository.RequestsRepository

/**
 * The single primary action on a request detail page, computed from server
 * state on every read so the button never disagrees with the server. The UI
 * renders exactly one button whose label and action vary by case, so TV focus
 * stays put as it morphs.
 */
sealed interface RequestPrimaryAction {
    data object Loading : RequestPrimaryAction
    /** The one-tap request. */
    data object Request : RequestPrimaryAction
    /** Submit in flight: same button, relabeled. */
    data object Submitting : RequestPrimaryAction
    /** A request exists (or the title is blocked); not interactive. */
    data class Status(val state: RequestDisplayState) : RequestPrimaryAction
    /** Already in the library: the button opens the item. */
    data class OpenInLibrary(val contentId: String) : RequestPrimaryAction

    val isInteractive: Boolean get() = this == Request || this is OpenInLibrary
}

data class RequestDetailUiState(
    val detail: RequestMediaDetail? = null,
    /** False while the page shows a cached or provisional first frame. */
    val hasFreshDetail: Boolean = false,
    /** The signed-in user's own record for this title: stage timestamps, targets, and the id to cancel. */
    val record: MediaRequest? = null,
    /** A pending or failed request for this title the signed-in admin can decide on. */
    val moderationRecord: MediaRequest? = null,
    /** Opened from an approval queue: the page describes that exact request. */
    val openedForModeration: Boolean = false,
    val isLoading: Boolean = false,
    val error: String? = null,
    val isSubmitting: Boolean = false,
    /** A create was sent but its outcome is unknown; the action stays held until a read shows a request, or the hold lapses. */
    val isSubmissionUnconfirmed: Boolean = false,
    val isCancelling: Boolean = false,
    /** A cancel was sent without a usable answer; Cancel stays hidden until the request changes, or the hold lapses. */
    val isCancelUnconfirmed: Boolean = false,
    val isModerating: Boolean = false,
    /** A decision was sent without a usable answer; the buttons stay hidden until it settles. */
    val isModerationUnconfirmed: Boolean = false,
    /** Inline message near the action for a failed create, cancel, or decision. */
    val actionErrorMessage: String? = null,
    /** Bumped when the server accepts a new request, for the success haptic. */
    val submittedCount: Int = 0,
    /** Bumped on every accepted admin decision, for the success haptic. */
    val moderatedCount: Int = 0,
) {
    val primaryAction: RequestPrimaryAction
        get() {
            val detail = detail ?: return RequestPrimaryAction.Loading
            // The same state as the title's card: a title in the library opens
            // only when no active request says otherwise.
            val state = RequestDisplayState.of(detail.availability, detail.request)
            state?.libraryItemToOpen(detail.libraryContentId)?.let { return RequestPrimaryAction.OpenInLibrary(it) }
            if (isSubmitting) return RequestPrimaryAction.Submitting
            if (isSubmissionUnconfirmed) {
                return RequestPrimaryAction.Status(RequestDisplayState.Unavailable(RequestUnconfirmedToken))
            }
            val moderation = moderationRecord
            if (openedForModeration && moderation != null) {
                return RequestPrimaryAction.Status(RequestDisplayState.of(moderation))
            }
            if (state != null) return RequestPrimaryAction.Status(state)
            // The title annotation drops a request whose download finished but
            // hasn't reached the library, and would offer a duplicate request;
            // the user's own record still knows it's on the way.
            activeRecordState?.let { return RequestPrimaryAction.Status(it) }
            // A failed request no longer blocks a new one, so the server calls
            // the title requestable. An admin who can retry it does that instead.
            val ended = endedRequest
            if (ended != null && isRetryableByAdmin) return RequestPrimaryAction.Status(RequestDisplayState.of(ended))
            return RequestPrimaryAction.Request
        }

    /**
     * The request for this title that ended without landing (failed or
     * declined), when that's the latest word on it: the user's own, or the one
     * an admin is deciding on.
     */
    val endedRequest: MediaRequest?
        get() {
            val order = if (openedForModeration) listOf(moderationRecord, currentOwnRecord) else listOf(currentOwnRecord, moderationRecord)
            return order.filterNotNull().firstOrNull { RequestDisplayState.of(it) is RequestDisplayState.NeedsAttention }
        }

    /**
     * Stage-track state for a request that exists: the user's record when they
     * have one, an admin's view of someone else's, otherwise the title
     * annotation. Null for a title nobody has requested.
     */
    val progress: RequestProgress?
        get() {
            val own = currentOwnRecord
            val moderation = moderationRecord
            if (moderation != null && (openedForModeration || own == null)) return RequestProgress.of(moderation)
            if (own != null && (activeRecordState != null || endedRequest?.id == own.id || detail?.request?.requestId == own.id)) {
                return RequestProgress.of(own)
            }
            val detail = detail ?: return null
            return RequestProgress.of(detail.availability, detail.request)
                ?.takeUnless { it.display is RequestDisplayState.Unavailable }
        }

    /** The request whose steps and timestamps the page shows. */
    val displayedRecord: MediaRequest?
        get() = if (openedForModeration && moderationRecord != null) moderationRecord else record ?: moderationRecord

    /** Cancel is the requester's action, offered while the request is still pending. */
    val canCancel: Boolean
        get() {
            val record = record ?: return false
            if (openedForModeration || isCancelling || isCancelUnconfirmed) return false
            return RequestDisplayState.of(record).isCancelable
        }

    /** The decisions the admin can take on [moderationRecord]. */
    val moderationActions: List<AdminRequestAction>
        get() {
            val moderation = moderationRecord ?: return emptyList()
            if (isModerating || isModerationUnconfirmed) return emptyList()
            return when (val state = RequestDisplayState.of(moderation)) {
                RequestDisplayState.Pending -> listOf(AdminRequestAction.Approve, AdminRequestAction.Decline)
                is RequestDisplayState.NeedsAttention ->
                    if (state.attention == RequestAttention.Failed) listOf(AdminRequestAction.Retry) else emptyList()
                else -> emptyList()
            }
        }

    /** Recommendations this client can route (movies and series). */
    val recommendations: List<RequestMediaResult>
        get() = detail?.recommendations.orEmpty().filter {
            it.mediaType == RequestMediaType.Movie || it.mediaType == RequestMediaType.Series
        }

    /** Where the title stands, in the slot a library title uses for its eyebrow. */
    val eyebrow: String?
        get() {
            if (moderationRecord != null && (openedForModeration || record == null)) return "Requested by someone on this server"
            val display = progress?.display ?: return "Not in your library"
            return if (display == RequestDisplayState.InLibrary) null else "Requested"
        }

    /** The primary action's label. */
    val primaryActionTitle: String
        get() = when (val action = primaryAction) {
            RequestPrimaryAction.Loading -> ""
            RequestPrimaryAction.Request -> if (endedRequest == null) "Request" else "Request Again"
            RequestPrimaryAction.Submitting -> "Requesting…"
            is RequestPrimaryAction.OpenInLibrary -> "Open in Library"
            is RequestPrimaryAction.Status -> progress
                ?.takeIf { action.state == it.display && action.state != RequestDisplayState.Pending }
                ?.longLabel
                ?: action.state.detailTitle
        }

    /** The hint under a plain Request button. */
    val showsRequestHint: Boolean
        get() = actionErrorMessage == null && primaryAction == RequestPrimaryAction.Request && endedRequest == null

    /** Whether the status card belongs on the page. */
    val showsStatus: Boolean
        get() = progress?.let { it.display != RequestDisplayState.InLibrary } == true

    /** The admin can retry a failed request, independent of an action in flight. */
    private val isRetryableByAdmin: Boolean
        get() = moderationRecord?.let {
            (RequestDisplayState.of(it) as? RequestDisplayState.NeedsAttention)?.attention == RequestAttention.Failed
        } == true

    /**
     * The user's record while it's still the latest word on the title. A failed
     * or declined one stops counting once the title is in the library or
     * another request for it is under way.
     */
    private val currentOwnRecord: MediaRequest?
        get() {
            val record = record ?: return null
            val detail = detail
            if (record.outcome == RequestOutcome.Active || detail == null) return record
            if (RequestDisplayState.of(detail.availability, detail.request) == RequestDisplayState.InLibrary) return null
            val current = detail.request.requestId
            if (current != null && current != record.id) return null
            return record
        }

    private val activeRecordState: RequestDisplayState?
        get() {
            val record = record?.takeIf { it.outcome == RequestOutcome.Active } ?: return null
            return RequestDisplayState.of(record).takeIf {
                it == RequestDisplayState.Pending || it == RequestDisplayState.OnTheWay
            }
        }
}

class RequestDetailViewModel(
    private val repository: RequestsRepository,
    private val mediaType: String,
    private val tmdbId: Int,
    private val featureStore: RequestsFeatureStore? = null,
    private val holdLifetime: Duration = RequestActionHold.Lifetime,
    private val timeSource: TimeSource = TimeSource.Monotonic,
) : ViewModel() {

    private val cache = repository.cache
    private val key = RequestDetailCache.Key(mediaType, tmdbId)

    private val _uiState: MutableStateFlow<RequestDetailUiState>
    val uiState: StateFlow<RequestDetailUiState>

    /** The one request this page decides on, once chosen, so a refresh never moves the buttons to another requester. */
    private var selectedModerationId: String?
    private var moderationHold: RequestActionHold? = null
    private var cancelHold: RequestActionHold? = null
    /** The lapse timer of an unconfirmed create; null when none is held. */
    private var submissionHold: Job? = null
    private var reloadJob: Job? = null

    init {
        // First frame from what the app already knows: the finished page when
        // this title was read before, the tapped card or record otherwise, and
        // the status from the user's own records. load() refreshes all of it.
        // Admin records seed the page only for a user who can still act on them.
        val moderates = featureStore?.canModerate?.value == true
        val pinned = cache.pinnedModerationRecord(key)?.takeIf { moderates }
        val moderation = pinned ?: cache.moderationRecord(key)?.takeIf { moderates }
        val detail = cache.firstFrameDetail(key)
        _uiState = MutableStateFlow(
            RequestDetailUiState(
                detail = detail,
                record = cache.ownRecord(key),
                moderationRecord = moderation,
                openedForModeration = pinned != null,
                isLoading = detail == null,
            ),
        )
        uiState = _uiState.asStateFlow()
        selectedModerationId = moderation?.id
        // The pin is for this one opening; the next visit is an ordinary page.
        pinned?.let(cache::unpinModeration)
        load()
        viewModelScope.launch {
            // Another surface mutated this title (a cancel from My Requests while
            // this page sits in the back stack).
            repository.lastUpdate.drop(1).filterNotNull().collect { event ->
                val record = event.record
                val state = _uiState.value
                if (record.mediaType != mediaType || record.tmdbId != tmdbId) return@collect
                // This page's own action re-reads when it finishes.
                if (state.isSubmitting || state.isCancelling || state.isModerating) return@collect
                reloadJob?.cancel()
                reloadJob = viewModelScope.launch { fetch() }
            }
        }
    }

    fun load() {
        viewModelScope.launch { fetch() }
    }

    /**
     * Reads the title, then the user's and the admin's records for it. [ownMaxAge]
     * lets a recent read of the user's requests stand in; settling an uncertain
     * action passes zero so it sees the server's latest.
     */
    private suspend fun fetch(ownMaxAge: Duration = RequestsRepository.MineFreshness) {
        _uiState.update { it.copy(isLoading = it.detail == null, error = null) }
        when (val fresh = repository.detail(mediaType, tmdbId)) {
            is ApiResult.Success -> {
                _uiState.update { it.copy(detail = fresh.data, hasFreshDetail = true) }
                // Supporting reads run side by side after the title; each keeps
                // its previous value on failure, so a slow list never blocks the page.
                val moderationLookup = viewModelScope.async { loadModerationRecord() }
                val own = loadOwnRecords(ownMaxAge)
                val moderation = moderationLookup.await()
                _uiState.update { state ->
                    var next = state
                    if (own != null) {
                        next = next.copy(record = RequestDetailCache.currentRecord(own))
                        // A timed-out cancel can still land after this read;
                        // only a changed or missing request settles it.
                        cancelHold?.let { hold ->
                            if (hold.isSettled(own.firstOrNull { it.id == hold.requestId })) {
                                cancelHold = null
                                next = next.releasingCancelHold()
                            }
                        }
                    }
                    if (moderation != null) {
                        next = next.copy(moderationRecord = moderation.record)
                        // An unchanged request doesn't show what a lost call did.
                        if (moderationHold?.isSettled(moderation.record) == true) {
                            moderationHold = null
                            next = next.releasingModerationHold()
                        }
                    }
                    // A create still being processed reads as no request at all:
                    // the hold ends only when the page no longer offers Request.
                    val released = next.releasingSubmissionHold()
                    if (next.isSubmissionUnconfirmed && released.primaryAction != RequestPrimaryAction.Request) {
                        submissionHold?.cancel()
                        submissionHold = null
                        next = released
                    }
                    next.copy(isLoading = false)
                }
            }
            is ApiResult.Error, is ApiResult.NetworkError -> _uiState.update {
                it.copy(
                    isLoading = false,
                    error = fresh.errorMessage("Failed to load request details").takeIf { _ -> it.detail == null },
                )
            }
        }
    }

    /**
     * The user's requests for this title, from a read of their list no older
     * than [maxAge]; null when the read fails, so the page keeps what it has.
     * The list has no per-title filter, so a recent read stands in for a new one.
     */
    private suspend fun loadOwnRecords(maxAge: Duration): List<MediaRequest>? {
        if (repository.ensureMine(maxAge) !is ApiResult.Success) return null
        return repository.mine.value.filter { it.mediaType == mediaType && it.tmdbId == tmdbId }
    }

    private class ModerationLookup(val record: MediaRequest?)

    /** Null when the reads fail (the page keeps what it has); a null record means nothing awaits a decision. */
    private suspend fun loadModerationRecord(): ModerationLookup? {
        if (featureStore?.canModerate?.value != true) return ModerationLookup(null)
        // Filtered to this title on the server: one short page each.
        val pendingRead = viewModelScope.async {
            repository.adminRequests(status = RequestStatus.Pending, outcome = RequestOutcome.Active, mediaType = mediaType, tmdbId = tmdbId)
        }
        val failedRead = repository.adminRequests(outcome = RequestOutcome.Failed, mediaType = mediaType, tmdbId = tmdbId)
        val pending = pendingRead.await()
        if (pending !is ApiResult.Success || failedRead !is ApiResult.Success) return null
        val matches = (pending.data + failedRead.data).filter { it.mediaType == mediaType && it.tmdbId == tmdbId }
        // Several users can have failed requests for one title: stay on the
        // exact request the page opened with. Once it's decided, offer nothing
        // rather than another requester's request.
        selectedModerationId?.let { selected -> return ModerationLookup(matches.firstOrNull { it.id == selected }) }
        selectedModerationId = matches.firstOrNull()?.id
        return ModerationLookup(matches.firstOrNull())
    }

    /** One tap, no confirmation dialog: the button is the confirmation. */
    fun submitRequest() {
        val state = _uiState.value
        val detail = state.detail ?: return
        if (state.isSubmitting || state.primaryAction != RequestPrimaryAction.Request) return
        _uiState.update { it.copy(isSubmitting = true, actionErrorMessage = null) }
        viewModelScope.launch {
            val result = repository.create(detail.toCreateMediaRequest())
            when {
                result is ApiResult.Success -> {
                    _uiState.update { it.copy(submittedCount = it.submittedCount + 1) }
                    // Re-read so the page reflects the server's state, not a local
                    // guess. Still submitting meanwhile, so this page's own
                    // broadcast doesn't trigger a second read.
                    fetch()
                    _uiState.update { it.copy(isSubmitting = false) }
                }
                RequestMutationFailure.isUncertain(result) -> {
                    // Never resend: hold the action until a read shows the
                    // request, or the hold lapses.
                    holdSubmission()
                    _uiState.update {
                        it.copy(isSubmitting = false, isSubmissionUnconfirmed = true, actionErrorMessage = RequestActionCopy.UnconfirmedSubmit)
                    }
                    fetch(ownMaxAge = Duration.ZERO)
                }
                else -> _uiState.update {
                    it.copy(isSubmitting = false, actionErrorMessage = RequestActionCopy.failure(result, "Failed to submit request"))
                }
            }
        }
    }

    fun cancel() {
        val state = _uiState.value
        val record = state.record ?: return
        if (!state.canCancel) return
        _uiState.update { it.copy(isCancelling = true, actionErrorMessage = null) }
        viewModelScope.launch {
            val result = repository.cancel(record.id)
            when {
                result is ApiResult.Success -> {
                    fetch()
                    _uiState.update { it.copy(isCancelling = false) }
                }
                RequestMutationFailure.isUncertain(result) -> {
                    // Never resend: hold Cancel until the request changes, or the hold lapses.
                    holdCancel(record)
                    _uiState.update {
                        it.copy(isCancelling = false, isCancelUnconfirmed = true, actionErrorMessage = RequestActionCopy.UnconfirmedCancel)
                    }
                    fetch(ownMaxAge = Duration.ZERO)
                }
                else -> _uiState.update {
                    it.copy(isCancelling = false, actionErrorMessage = RequestActionCopy.failure(result, "Couldn't cancel the request"))
                }
            }
        }
    }

    fun moderate(action: AdminRequestAction) {
        val state = _uiState.value
        val moderation = state.moderationRecord ?: return
        if (state.isModerating) return
        _uiState.update { it.copy(isModerating = true, actionErrorMessage = null) }
        viewModelScope.launch {
            val result = repository.adminAction(moderation.id, action)
            when {
                result is ApiResult.Success -> {
                    // Still moderating through the re-read, so the decision
                    // buttons can't come back for the request just decided.
                    _uiState.update { it.copy(moderatedCount = it.moderatedCount + 1) }
                    fetch()
                    _uiState.update { it.copy(isModerating = false) }
                }
                RequestMutationFailure.isUncertain(result) -> {
                    // Never resend: hide the decision until a read shows it landed.
                    holdModeration(moderation)
                    _uiState.update {
                        it.copy(isModerating = false, isModerationUnconfirmed = true, actionErrorMessage = RequestActionCopy.UnconfirmedModeration)
                    }
                    fetch(ownMaxAge = Duration.ZERO)
                }
                else -> _uiState.update {
                    it.copy(isModerating = false, actionErrorMessage = RequestActionCopy.failure(result, "Something went wrong"))
                }
            }
        }
    }

    /** Ends the hold when its lifetime runs out even if no read settles it; a newer hold keeps its own clock. */
    private fun holdModeration(request: MediaRequest) {
        val hold = RequestActionHold(request, timeSource, holdLifetime)
        moderationHold = hold
        viewModelScope.launch {
            delay(holdLifetime)
            if (moderationHold !== hold) return@launch
            moderationHold = null
            _uiState.update { it.releasingModerationHold() }
            fetch(ownMaxAge = Duration.ZERO)
        }
    }

    private fun holdCancel(request: MediaRequest) {
        val hold = RequestActionHold(request, timeSource, holdLifetime)
        cancelHold = hold
        viewModelScope.launch {
            delay(holdLifetime)
            if (cancelHold !== hold) return@launch
            cancelHold = null
            _uiState.update { it.releasingCancelHold() }
            fetch(ownMaxAge = Duration.ZERO)
        }
    }

    private fun holdSubmission() {
        submissionHold?.cancel()
        submissionHold = viewModelScope.launch {
            delay(holdLifetime)
            submissionHold = null
            _uiState.update { it.releasingSubmissionHold() }
            fetch(ownMaxAge = Duration.ZERO)
        }
    }

    private fun RequestDetailUiState.releasingModerationHold(): RequestDetailUiState = copy(
        isModerationUnconfirmed = false,
        actionErrorMessage = actionErrorMessage.takeUnless { it == RequestActionCopy.UnconfirmedModeration },
    )

    private fun RequestDetailUiState.releasingCancelHold(): RequestDetailUiState = copy(
        isCancelUnconfirmed = false,
        actionErrorMessage = actionErrorMessage.takeUnless { it == RequestActionCopy.UnconfirmedCancel },
    )

    private fun RequestDetailUiState.releasingSubmissionHold(): RequestDetailUiState = copy(
        isSubmissionUnconfirmed = false,
        actionErrorMessage = actionErrorMessage.takeUnless { it == RequestActionCopy.UnconfirmedSubmit },
    )

    private fun RequestMediaDetail.toCreateMediaRequest(): CreateMediaRequest = CreateMediaRequest(
        mediaType = mediaType,
        tmdbId = tmdbId,
        tvdbId = tvdbId,
        imdbId = imdbId,
        title = title,
        year = year,
        overview = overview,
        posterPath = posterPath,
        backdropPath = backdropPath,
    )
}
