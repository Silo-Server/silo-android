package org.siloserver.silo.android.ui.screens.player

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.siloserver.silo.common.player.PlaybackSessionManager
import org.siloserver.silo.common.player.PlaybackTrackSelectionWriteCoordinator
import org.siloserver.silo.common.player.StagedVideoReplan
import org.siloserver.silo.common.player.VideoSessionStartV3
import org.siloserver.silo.model.catalog.AudioTrack
import org.siloserver.silo.model.playback.CommittedSubtitle
import org.siloserver.silo.model.playback.PlaybackSubtitleModeV3
import org.siloserver.silo.model.playback.PlayerSubtitleInfo
import org.siloserver.silo.model.playback.SelectSubtitle
import org.siloserver.silo.model.playback.StagedSubtitleCandidate
import org.siloserver.silo.model.playback.StagedSubtitleFailed
import org.siloserver.silo.model.playback.StagedSubtitleValidated
import org.siloserver.silo.model.playback.SubtitleContentReset
import org.siloserver.silo.model.playback.SubtitleIdentity
import org.siloserver.silo.model.playback.SubtitleTransitionEvent
import org.siloserver.silo.model.playback.SubtitleTransitionState
import org.siloserver.silo.model.playback.UpdateAudioPreference
import org.siloserver.silo.model.playback.rebaseDownloadedSubtitleUrl
import org.siloserver.silo.model.playback.reduceSubtitleTransition
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.repository.port.PlaybackWriteScope

internal data class MobileSubtitlePlaybackContext(
    val contentId: String,
    val mediaFileId: Int,
    val versionId: String,
    val sessionId: String?,
    val positionSeconds: Double,
    val audioTrackIndex: Int?,
    val qualityPreference: String?,
    val subtitleTracks: List<PlayerSubtitleInfo>,
    val audioTracks: List<AudioTrack> = emptyList(),
    val writeScope: PlaybackWriteScope? = null,
)

internal data class MobileSubtitleStageRequest(
    val generation: Long,
    val contentId: String,
    val mediaFileId: Int,
    val versionId: String,
    val sessionId: String,
    val positionSeconds: Double,
    val audioTrackIndex: Int?,
    val qualityPreference: String?,
    val subtitleTrackIndex: Int,
)

internal data class MobileStagedSubtitleCandidate(
    val id: String,
    val sessionId: String,
    val selectedAudioIndex: Int?,
    val selectedSubtitleIndex: Int?,
    val subtitleMode: PlaybackSubtitleModeV3,
    val hasSidecar: Boolean,
    val subtitleTracks: List<PlayerSubtitleInfo>,
    internal val managerHandle: StagedVideoReplan? = null,
)

internal data class MobileSubtitleCommittedPlayback(
    val sessionId: String,
    val subtitleTracks: List<PlayerSubtitleInfo>,
    val ready: VideoSessionStartV3.Ready? = null,
)

internal interface MobileSubtitleStagedReplanPort {
    suspend fun stage(request: MobileSubtitleStageRequest): ApiResult<MobileStagedSubtitleCandidate>

    suspend fun commit(
        candidate: MobileStagedSubtitleCandidate,
    ): ApiResult<MobileSubtitleCommittedPlayback>

    suspend fun discard(candidate: MobileStagedSubtitleCandidate)

    suspend fun abandonCommitted(playback: MobileSubtitleCommittedPlayback)
}

internal interface MobileSubtitlePersistencePort {
    suspend fun persist(
        committed: CommittedSubtitle,
        context: MobileSubtitlePlaybackContext,
    ): Boolean
}

/**
 * Process-lifetime owner for bounded final preference writes. Keeping this
 * scope outside each adapter prevents one unbounded SupervisorJob per player.
 */
private object MobileSubtitleDurablePersistenceOwner {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
}

internal data class MobileSubtitleTransactionSnapshot(
    val transition: SubtitleTransitionState,
    val pendingIdentity: SubtitleIdentity? = transition.pending?.identity,
    val localMountIdentity: SubtitleIdentity? = null,
    val failureMessage: String? = null,
) {
    val committedIdentity: SubtitleIdentity
        get() = transition.committed.identity

    val subtitleApplying: Boolean
        get() = pendingIdentity != null
}

internal data class MobileSubtitleRefreshOwner(
    val contentGeneration: Long,
    val contentId: String,
    val mediaFileId: Int,
    val versionId: String,
    val sessionId: String?,
    val refreshGeneration: Long,
    val subtitleIntentGeneration: Long,
)

internal enum class MobileSubtitleAdoptionResult {
    Adopted,
    Superseded,
}

internal class MobileSubtitlePlaybackAdoption internal constructor(
    val playback: MobileSubtitleCommittedPlayback,
    val committed: CommittedSubtitle,
    private val currentOwner: () -> Boolean,
    private val currentPendingIdentity: () -> SubtitleIdentity?,
) {
    fun isCurrent(): Boolean = currentOwner()

    fun pendingIdentity(): SubtitleIdentity? =
        currentPendingIdentity().takeIf { isCurrent() }
}

/**
 * Mobile execution adapter for the shared subtitle reducer.
 *
 * One conflated worker serializes staged server requests. A newer intent does
 * not cancel an in-flight HTTP request; its eventual candidate is discarded
 * and only the newest queued intent is staged from the still-committed session.
 */
internal class MobileSubtitleTransactionAdapter(
    private val scope: CoroutineScope,
    private val stagedPort: MobileSubtitleStagedReplanPort,
    private val persistencePort: MobileSubtitlePersistencePort,
    private val durablePersistenceScope: CoroutineScope =
        MobileSubtitleDurablePersistenceOwner.scope,
    private val persistenceCoordinator: PlaybackTrackSelectionWriteCoordinator =
        PlaybackTrackSelectionWriteCoordinator.Process,
    private val onSnapshotChanged: (MobileSubtitleTransactionSnapshot) -> Unit = {},
    private val onCommittedPlayback: suspend (
        MobileSubtitlePlaybackAdoption,
    ) -> MobileSubtitleAdoptionResult = { MobileSubtitleAdoptionResult.Adopted },
    private val onCommittedPlaybackFailure: suspend (String) -> Unit = {},
) {
    private data class PendingLocalSelection(
        val generation: Long,
        val identity: SubtitleIdentity,
        val proposedState: SubtitleTransitionState,
        val context: MobileSubtitlePlaybackContext,
        val mountedBeforeAdoption: Boolean = false,
    )

    private data class PendingLocalRestore(
        val generation: Long,
        val identity: SubtitleIdentity,
        val persistence: PersistenceRequest? = null,
    )

    private data class PersistenceRequest(
        val ticket: PlaybackTrackSelectionWriteCoordinator.Ticket,
        val committed: CommittedSubtitle,
        val context: MobileSubtitlePlaybackContext,
        val completion: CompletableDeferred<Boolean>? = null,
    )

    private val stagedRequests = Channel<org.siloserver.silo.model.playback.PendingSubtitle>(
        capacity = Channel.CONFLATED,
    )
    private val persistenceRequests = Channel<PersistenceRequest>(capacity = Channel.UNLIMITED)

    private var transition = SubtitleTransitionState.committed(SubtitleIdentity.Off)
    private var context: MobileSubtitlePlaybackContext? = null
    private var contentGeneration = 0L
    private var refreshGeneration = 0L
    private var subtitleIntentGeneration = 0L
    private var failureMessage: String? = null
    private var pendingLocalSelection: PendingLocalSelection? = null
    private var pendingLocalRestore: PendingLocalRestore? = null
    private var localMountGeneration = 0L
    private var localMountTimeout: Job? = null
    private var lastSettledLocalMountMissSnapshotKey: String? = null
    private val queuedMutations = mutableListOf<SubtitleTransitionEvent>()
    private var commitInFlight = false
    private var resetDuringCommit = false
    private var adoptionGeneration = 0L

    val snapshot: MobileSubtitleTransactionSnapshot
        get() {
            val queuedIdentity = queuedPreviewState()?.pending?.identity
            val localIdentity = pendingLocalSelection?.identity ?: pendingLocalRestore?.identity
            return MobileSubtitleTransactionSnapshot(
                transition = transition,
                pendingIdentity = queuedIdentity ?: localIdentity ?: transition.pending?.identity,
                localMountIdentity = if (queuedIdentity == null) localIdentity else null,
                failureMessage = failureMessage,
            )
        }

    val hasActiveTransaction: Boolean
        get() = commitInFlight ||
            transition.pending != null ||
            pendingLocalSelection != null ||
            pendingLocalRestore != null ||
            queuedMutations.isNotEmpty()

    init {
        scope.launch {
            for (pending in stagedRequests) {
                processStagedRequest(pending)
            }
        }
        scope.launch {
            var shutdownCause: CancellationException? = null
            try {
                for (request in persistenceRequests) {
                    try {
                        val success = writePersistenceRequest(request)
                        if (!success && request.completion == null) {
                            persistenceCoordinator.abandon(request.ticket)
                        }
                        request.completion?.complete(success)
                    } catch (cancellation: CancellationException) {
                        if (request.completion == null) {
                            persistenceCoordinator.abandon(request.ticket)
                        }
                        request.completion?.completeExceptionally(cancellation)
                        throw cancellation
                    }
                }
            } catch (cancellation: CancellationException) {
                shutdownCause = cancellation
                throw cancellation
            } finally {
                val cause = shutdownCause
                    ?: CancellationException("Subtitle persistence worker stopped.")
                persistenceRequests.close(cause)
                while (true) {
                    val queued = persistenceRequests.tryReceive().getOrNull() ?: break
                    if (queued.completion == null) {
                        persistenceCoordinator.abandon(queued.ticket)
                    }
                    queued.completion?.completeExceptionally(cause)
                }
            }
        }
    }

    fun resetContent(
        context: MobileSubtitlePlaybackContext,
        committedIdentity: SubtitleIdentity,
    ) {
        if (commitInFlight) {
            resetDuringCommit = true
            queuedMutations.clear()
        }
        adoptionGeneration += 1
        contentGeneration += 1
        refreshGeneration += 1
        subtitleIntentGeneration += 1
        this.context = context
        invalidateLocalMount()
        transition = reduceSubtitleTransition(
            transition,
            SubtitleContentReset(committedIdentity),
        ).state.copy(
            committed = CommittedSubtitle(
                identity = committedIdentity,
                audioTrackIndex = context.audioTrackIndex,
                qualityPreference = context.qualityPreference,
            ),
        )
        failureMessage = null
        publish()
    }

    fun replaceSession(sessionId: String, subtitleTracks: List<PlayerSubtitleInfo>? = null) {
        val current = context ?: return
        if (commitInFlight) {
            resetDuringCommit = true
            queuedMutations.clear()
        }
        adoptionGeneration += 1
        refreshGeneration += 1
        subtitleIntentGeneration += 1
        invalidateLocalMount()
        context = current.copy(
            sessionId = sessionId,
            subtitleTracks = subtitleTracks ?: current.subtitleTracks,
        )
        transition = reduceSubtitleTransition(
            transition,
            SubtitleContentReset(transition.committed.identity),
        ).state.copy(committed = transition.committed)
        failureMessage = null
        publish()
    }

    fun updatePlaybackContext(updated: MobileSubtitlePlaybackContext) {
        val current = context
        if (current == null ||
            current.contentId != updated.contentId ||
            current.mediaFileId != updated.mediaFileId ||
            current.versionId != updated.versionId
        ) {
            resetContent(updated, transition.committed.identity)
            return
        }
        if (current.sessionId != updated.sessionId) {
            val nextSessionId = updated.sessionId
            if (nextSessionId == null) {
                if (commitInFlight) {
                    resetDuringCommit = true
                    queuedMutations.clear()
                }
                adoptionGeneration += 1
                contentGeneration += 1
                refreshGeneration += 1
                subtitleIntentGeneration += 1
                context = updated
                invalidateLocalMount()
                transition = reduceSubtitleTransition(
                    transition,
                    SubtitleContentReset(transition.committed.identity),
                ).state.copy(committed = transition.committed)
                failureMessage = null
                publish()
            } else {
                replaceSession(nextSessionId, updated.subtitleTracks)
                context = updated
            }
            return
        }
        context = updated
    }

    fun select(identity: SubtitleIdentity) {
        mutate(SelectSubtitle(identity), explicit = true)
    }

    fun selectAudio(audioTrackIndex: Int?) {
        mutate(UpdateAudioPreference(audioTrackIndex), explicit = true)
    }

    fun invalidate() {
        adoptionGeneration += 1
        contentGeneration += 1
        refreshGeneration += 1
        subtitleIntentGeneration += 1
        invalidateLocalMount()
        queuedMutations.clear()
        if (commitInFlight) resetDuringCommit = true
        transition = reduceSubtitleTransition(
            transition,
            SubtitleContentReset(transition.committed.identity),
        ).state.copy(committed = transition.committed)
        failureMessage = null
        publish()
    }

    fun persistCommittedSelection() {
        context?.let { persist(transition.committed, it) }
    }

    suspend fun persistCommittedSelectionAndFlush(): Boolean {
        val request = capturePersistenceRequest(
            completion = CompletableDeferred(),
        ) ?: return false
        val primarySucceeded = try {
            withTimeoutOrNull(PRIMARY_PERSISTENCE_TIMEOUT_MS) {
                persistenceRequests.send(request)
                requireNotNull(request.completion).await()
            } ?: false
        } catch (_: Exception) {
            false
        }
        if (primarySucceeded) return true
        val durableSucceeded = awaitBoundedDurablePersistence(request)
        if (!durableSucceeded) persistenceCoordinator.abandon(request.ticket)
        return durableSucceeded
    }

    fun requestDurableFinalPersistence() {
        val request = capturePersistenceRequest() ?: return
        durablePersistenceScope.launch {
            val success = withTimeoutOrNull(DURABLE_PERSISTENCE_TIMEOUT_MS) {
                runCatching { writePersistenceRequest(request) }.getOrDefault(false)
            } ?: false
            if (!success) persistenceCoordinator.abandon(request.ticket)
        }
    }

    fun restoreCommittedLocalMount() {
        val identity = transition.committed.identity
        if (context?.sessionId != null && identity.requiresLocalMountConfirmation()) {
            beginLocalRestore(identity)
        }
    }

    fun beginRefresh(): MobileSubtitleRefreshOwner {
        refreshGeneration += 1
        val current = requireNotNull(context) {
            "Subtitle refresh cannot start before playback context is installed."
        }
        return MobileSubtitleRefreshOwner(
            contentGeneration = contentGeneration,
            contentId = current.contentId,
            mediaFileId = current.mediaFileId,
            versionId = current.versionId,
            sessionId = current.sessionId,
            refreshGeneration = refreshGeneration,
            subtitleIntentGeneration = subtitleIntentGeneration,
        )
    }

    fun ownsRefresh(owner: MobileSubtitleRefreshOwner): Boolean {
        val current = context ?: return false
        return owner.contentGeneration == contentGeneration &&
            owner.contentId == current.contentId &&
            owner.mediaFileId == current.mediaFileId &&
            owner.versionId == current.versionId &&
            owner.sessionId == current.sessionId &&
            owner.refreshGeneration == refreshGeneration &&
            owner.subtitleIntentGeneration == subtitleIntentGeneration
    }

    fun selectFromRefresh(
        owner: MobileSubtitleRefreshOwner,
        identity: SubtitleIdentity,
    ): Boolean {
        if (!ownsRefresh(owner)) return false
        mutate(SelectSubtitle(identity), explicit = false)
        return true
    }

    fun reportMountedSelection(
        identity: SubtitleIdentity,
        selected: Boolean,
        snapshotKey: String?,
        settled: Boolean = false,
    ) {
        val pendingSelection = pendingLocalSelection?.takeIf { it.identity == identity }
        if (pendingSelection != null) {
            if (selected) {
                if (pendingSelection.proposedState.pending != null) {
                    localMountTimeout?.cancel()
                    localMountTimeout = null
                    pendingLocalSelection = pendingSelection.copy(mountedBeforeAdoption = true)
                    failureMessage = null
                    publish()
                } else {
                    transition = pendingSelection.proposedState
                    invalidateLocalMount()
                    failureMessage = null
                    publish()
                    persist(transition.committed, pendingSelection.context)
                }
            } else if (isStableLocalMountMiss(snapshotKey, settled)) {
                failLocalMount(pendingSelection.generation)
            }
            return
        }

        val pendingRestore = pendingLocalRestore?.takeIf { it.identity == identity } ?: return
        if (selected) {
            val persistence = pendingRestore.persistence
            invalidateLocalMount()
            failureMessage = null
            publish()
            persistence?.let { persist(it.committed, it.context) }
        } else if (isStableLocalMountMiss(snapshotKey, settled)) {
            failLocalMount(pendingRestore.generation)
        }
    }

    private fun isStableLocalMountMiss(snapshotKey: String?, settled: Boolean): Boolean {
        if (!settled || snapshotKey.isNullOrBlank()) return false
        val stable = snapshotKey == lastSettledLocalMountMissSnapshotKey
        lastSettledLocalMountMissSnapshotKey = snapshotKey
        return stable
    }

    private fun mutate(event: SubtitleTransitionEvent, explicit: Boolean) {
        if (explicit) refreshGeneration += 1
        subtitleIntentGeneration += 1
        failureMessage = null

        if (commitInFlight) {
            queuedMutations += event
            publish()
            return
        }

        val localSelection = pendingLocalSelection
        if (localSelection != null && event !is SelectSubtitle) {
            applyMutationToPendingLocalSelection(localSelection, event)
            return
        }

        when (event) {
            is SelectSubtitle -> applySelection(event.identity)
            else -> applyPreferenceMutation(event)
        }
    }

    private fun applyMutationToPendingLocalSelection(
        pendingSelection: PendingLocalSelection,
        event: SubtitleTransitionEvent,
    ) {
        val updated = reduceSubtitleTransition(pendingSelection.proposedState, event).state
        pendingLocalSelection = pendingSelection.copy(proposedState = updated)
        transition = transition.copy(
            pending = updated.pending,
            nextGeneration = updated.nextGeneration,
        )
        publish()
        transition.pending?.let(stagedRequests::trySend)
    }

    private fun applyPreferenceMutation(event: SubtitleTransitionEvent) {
        val updated = reduceSubtitleTransition(transition, event)
        val current = context
        if (current?.sessionId == null) {
            val pending = updated.state.pending
            val committedState = if (pending == null) {
                updated.state
            } else {
                reduceSubtitleTransition(
                    updated.state,
                    StagedSubtitleValidated(
                        generation = pending.generation,
                        candidate = StagedSubtitleCandidate("mobile-preplay"),
                    ),
                ).state
            }
            transition = committedState
            invalidateLocalMount()
            publish()
            current?.let { persist(committedState.committed, it) }
            return
        }

        invalidateLocalMount()
        transition = updated.state
        publish()
        transition.pending?.let(stagedRequests::trySend)
    }

    private fun applySelection(identity: SubtitleIdentity) {
        val selected = reduceSubtitleTransition(transition, SelectSubtitle(identity))
        val current = context
        val commitsSynchronously = current?.sessionId == null

        if (commitsSynchronously) {
            val committedState = if (selected.state.pending == null) {
                selected.state
            } else {
                reduceSubtitleTransition(
                    selected.state,
                    StagedSubtitleValidated(
                        generation = selected.state.pending!!.generation,
                        candidate = StagedSubtitleCandidate("mobile-local"),
                    ),
                ).state
            }
            transition = committedState
            invalidateLocalMount()
            publish()
            current?.let { persist(committedState.committed, it) }
            return
        }

        if (
            identity.isClientOwnedSubtitle() &&
            selected.state.pending != null
        ) {
            invalidateLocalMount()
            transition = selected.state
            publish()
            stagedRequests.trySend(requireNotNull(transition.pending))
            return
        }

        if (identity.requiresLocalMountConfirmation()) {
            val proposedState = if (selected.state.pending == null) {
                selected.state
            } else {
                reduceSubtitleTransition(
                    selected.state,
                    StagedSubtitleValidated(
                        generation = selected.state.pending!!.generation,
                        candidate = StagedSubtitleCandidate("mobile-local"),
                    ),
                ).state
            }
            beginLocalSelection(
                identity = identity,
                proposedState = proposedState,
                selectionContext = requireNotNull(current),
            )
            return
        }

        invalidateLocalMount()
        transition = selected.state
        publish()
        transition.pending?.let(stagedRequests::trySend)
    }

    private suspend fun processStagedRequest(
        requested: org.siloserver.silo.model.playback.PendingSubtitle,
    ) {
        val requestContext = context ?: return
        val requestSessionId = requestContext.sessionId ?: return
        if (transition.pending?.generation != requested.generation) return

        val request = MobileSubtitleStageRequest(
            generation = requested.generation,
            contentId = requestContext.contentId,
            mediaFileId = requestContext.mediaFileId,
            versionId = requestContext.versionId,
            sessionId = requestSessionId,
            positionSeconds = requestContext.positionSeconds,
            audioTrackIndex = requested.audioTrackIndex,
            qualityPreference = requested.qualityPreference,
            subtitleTrackIndex = requested.identity.serverTrackIndex(),
        )
        val staged = try {
            stagedPort.stage(request)
        } catch (cancellation: CancellationException) {
            if (!currentCoroutineContext().isActive) throw cancellation
            ApiResult.NetworkError(cancellation)
        } catch (error: Exception) {
            ApiResult.NetworkError(error)
        }

        when (staged) {
            is ApiResult.Success -> processCandidate(requested, request, staged.data)
            is ApiResult.Error -> fail(requested.generation, staged.message)
            is ApiResult.NetworkError -> fail(
                requested.generation,
                staged.exception.message ?: "Subtitle selection failed.",
            )
        }
    }

    private suspend fun processCandidate(
        requested: org.siloserver.silo.model.playback.PendingSubtitle,
        request: MobileSubtitleStageRequest,
        candidate: MobileStagedSubtitleCandidate,
    ) {
        val validationFailure = candidate.validationFailure(
            requested = requested,
            expectedSubtitleIndex = request.subtitleTrackIndex,
        )
        if (validationFailure != null) {
            discardCandidateBestEffort(candidate)
            fail(requested.generation, validationFailure)
            return
        }

        val validated = reduceSubtitleTransition(
            transition,
            StagedSubtitleValidated(
                generation = requested.generation,
                candidate = StagedSubtitleCandidate(candidate.id),
            ),
        )
        if (validated.state == transition) {
            discardCandidateBestEffort(candidate)
            return
        }

        commitInFlight = true
        val commitResult = try {
            stagedPort.commit(candidate)
        } catch (cancellation: CancellationException) {
            commitInFlight = false
            if (!currentCoroutineContext().isActive) throw cancellation
            ApiResult.NetworkError(cancellation)
        } catch (error: Exception) {
            ApiResult.NetworkError(error)
        }

        when (val committed = commitResult) {
            is ApiResult.Success -> {
                if (resetDuringCommit) {
                    abandonCommittedPlayback(committed.data)
                    finishSupersededAdoption()
                    return
                }

                val adoptionContext = context ?: run {
                    abandonCommittedPlayback(committed.data)
                    commitInFlight = false
                    resetDuringCommit = false
                    return
                }
                val playback = committed.data.withRebasedDownloads(adoptionContext)
                val ownerGeneration = adoptionGeneration
                val adoption = MobileSubtitlePlaybackAdoption(
                    playback = playback,
                    committed = validated.state.committed,
                    currentOwner = {
                        ownerGeneration == adoptionGeneration &&
                            !resetDuringCommit
                    },
                    currentPendingIdentity = {
                        queuedPreviewState()?.pending?.identity
                    },
                )
                val adoptionOutcome = withContext(NonCancellable) {
                    try {
                        if (!adoption.isCurrent()) {
                            AdoptionOutcome.Superseded
                        } else {
                            when (onCommittedPlayback(adoption)) {
                                MobileSubtitleAdoptionResult.Adopted ->
                                    if (adoption.isCurrent()) AdoptionOutcome.Adopted
                                    else AdoptionOutcome.Superseded
                                MobileSubtitleAdoptionResult.Superseded ->
                                    AdoptionOutcome.Superseded
                            }
                        }
                    } catch (error: Exception) {
                        AdoptionOutcome.Failed(error)
                    }
                }
                when (adoptionOutcome) {
                    AdoptionOutcome.Adopted -> finishSuccessfulAdoption(
                        validatedState = validated.state,
                        playback = playback,
                        adoptionContext = adoptionContext,
                    )
                    AdoptionOutcome.Superseded -> {
                        abandonCommittedPlayback(playback)
                        finishSupersededAdoption()
                    }
                    is AdoptionOutcome.Failed -> {
                        abandonCommittedPlayback(playback)
                        commitInFlight = false
                        val message = "Subtitle playback adoption failed."
                        finishFailedCommit(requested.generation, message)
                        withContext(NonCancellable) {
                            try {
                                onCommittedPlaybackFailure(
                                    adoptionOutcome.error.message ?: message,
                                )
                            } catch (_: Exception) {
                                // Recovery notification is best effort; the worker
                                // must remain alive after a committed-session fault.
                            }
                        }
                    }
                }
            }
            is ApiResult.Error -> {
                commitInFlight = false
                finishFailedCommit(
                    generation = requested.generation,
                    message = committed.message,
                )
            }
            is ApiResult.NetworkError -> {
                commitInFlight = false
                finishFailedCommit(
                    generation = requested.generation,
                    message = committed.exception.message ?: "Subtitle selection failed.",
                )
            }
        }
    }

    private suspend fun finishSuccessfulAdoption(
        validatedState: SubtitleTransitionState,
        playback: MobileSubtitleCommittedPlayback,
        adoptionContext: MobileSubtitlePlaybackContext,
    ) {
        transition = validatedState
        val liveContext = context
            ?.takeIf {
                it.contentId == adoptionContext.contentId &&
                    it.mediaFileId == adoptionContext.mediaFileId &&
                    it.versionId == adoptionContext.versionId
            }
            ?: adoptionContext
        context = liveContext.copy(
            sessionId = playback.sessionId,
            subtitleTracks = playback.subtitleTracks,
            audioTrackIndex = transition.committed.audioTrackIndex,
            qualityPreference = transition.committed.qualityPreference,
        )
        refreshGeneration += 1
        failureMessage = null
        commitInFlight = false
        resetDuringCommit = false
        if (queuedMutations.isEmpty()) {
            if (transition.committed.identity.requiresLocalMountConfirmation()) {
                beginLocalRestore(
                    identity = transition.committed.identity,
                    persistence = newPersistenceRequest(
                        committed = transition.committed,
                        context = requireNotNull(context),
                    ),
                )
            } else {
                publish()
                persist(transition.committed, requireNotNull(context))
            }
        } else {
            applyQueuedMutations()
        }
    }

    private fun finishSupersededAdoption() {
        commitInFlight = false
        resetDuringCommit = false
        applyQueuedMutations()
    }

    private suspend fun abandonCommittedPlayback(playback: MobileSubtitleCommittedPlayback) {
        withContext(NonCancellable) {
            try {
                stagedPort.abandonCommitted(playback)
            } catch (_: Exception) {
                // The manager owns authoritative cleanup; a cleanup transport
                // failure must not kill the serialized transaction worker.
            }
        }
    }

    private suspend fun discardCandidateBestEffort(candidate: MobileStagedSubtitleCandidate) {
        withContext(NonCancellable) {
            try {
                stagedPort.discard(candidate)
            } catch (_: Throwable) {
                // Discard is cleanup after the reducer has already rejected
                // this candidate. Its transport failure must not skip the
                // owned rollback or terminate the serialized worker.
            }
        }
    }

    private sealed interface AdoptionOutcome {
        data object Adopted : AdoptionOutcome
        data object Superseded : AdoptionOutcome
        data class Failed(val error: Exception) : AdoptionOutcome
    }

    private fun finishFailedCommit(generation: Long, message: String) {
        resetDuringCommit = false
        if (queuedMutations.isEmpty()) {
            fail(generation, message)
            return
        }

        transition = reduceSubtitleTransition(
            transition,
            StagedSubtitleFailed(
                generation = generation,
                message = message,
            ),
        ).state
        failureMessage = null
        applyQueuedMutations()
    }

    private fun queuedPreviewState(): SubtitleTransitionState? {
        if (queuedMutations.isEmpty()) return null
        return queuedMutations.fold(transition) { state, event ->
            reduceSubtitleTransition(state, event).state
        }
    }

    private fun applyQueuedMutations() {
        if (queuedMutations.isEmpty()) return
        val events = queuedMutations.toList()
        queuedMutations.clear()
        val finalState = events.fold(transition) { state, event ->
            reduceSubtitleTransition(state, event).state
        }
        val finalIdentity = finalState.pending?.identity ?: finalState.committed.identity
        if (finalState.pending == null && finalIdentity.requiresLocalMountConfirmation()) {
            beginLocalSelection(
                identity = finalIdentity,
                proposedState = finalState,
                selectionContext = requireNotNull(context),
            )
            return
        }
        invalidateLocalMount()
        transition = finalState
        publish()
        transition.pending?.let(stagedRequests::trySend)
    }

    private fun fail(generation: Long, message: String) {
        val failedLocalOwner = pendingLocalSelection?.takeIf { owner ->
            owner.proposedState.pending?.generation == generation
        }
        val failed = reduceSubtitleTransition(
            transition,
            StagedSubtitleFailed(
                generation = generation,
                message = message,
            ),
        )
        if (failed.state == transition && failed.effects.isEmpty()) return
        if (failedLocalOwner != null) {
            invalidateLocalMount()
        }
        transition = failed.state
        failureMessage = message
        val priorIdentity = transition.committed.identity
        if (
            failedLocalOwner?.mountedBeforeAdoption == true &&
            priorIdentity.requiresLocalMountConfirmation() &&
            context?.sessionId != null
        ) {
            beginLocalRestore(priorIdentity)
        } else {
            publish()
        }
    }

    private fun failLocalMount(generation: Long) {
        val ownedSelection = pendingLocalSelection?.generation == generation
        val ownedRestore = pendingLocalRestore?.generation == generation
        if (!ownedSelection && !ownedRestore) return
        invalidateLocalMount()
        failureMessage = "The selected subtitle could not be mounted."
        publish()
    }

    private fun beginLocalSelection(
        identity: SubtitleIdentity,
        proposedState: SubtitleTransitionState,
        selectionContext: MobileSubtitlePlaybackContext,
    ) {
        transition = transition.copy(
            pending = proposedState.pending,
            nextGeneration = proposedState.nextGeneration,
        )
        invalidateLocalMount()
        val generation = localMountGeneration
        pendingLocalSelection = PendingLocalSelection(
            generation = generation,
            identity = identity,
            proposedState = proposedState,
            context = selectionContext,
        )
        scheduleLocalMountTimeout(generation)
        publish()
        transition.pending?.let(stagedRequests::trySend)
    }

    private fun beginLocalRestore(
        identity: SubtitleIdentity,
        persistence: PersistenceRequest? = null,
    ) {
        invalidateLocalMount()
        val generation = localMountGeneration
        pendingLocalRestore = PendingLocalRestore(
            generation = generation,
            identity = identity,
            persistence = persistence,
        )
        scheduleLocalMountTimeout(generation)
        publish()
    }

    private fun scheduleLocalMountTimeout(generation: Long) {
        localMountTimeout = scope.launch {
            delay(LOCAL_MOUNT_TIMEOUT_MS)
            failLocalMount(generation)
        }
    }

    private fun invalidateLocalMount() {
        localMountGeneration += 1
        pendingLocalSelection = null
        pendingLocalRestore = null
        lastSettledLocalMountMissSnapshotKey = null
        localMountTimeout?.cancel()
        localMountTimeout = null
    }

    private fun persist(
        committed: CommittedSubtitle,
        committedContext: MobileSubtitlePlaybackContext,
    ) {
        newPersistenceRequest(
            committed = committed,
            context = committedContext,
        )?.let { request ->
            if (persistenceRequests.trySend(request).isFailure) {
                persistenceCoordinator.abandon(request.ticket)
            }
        }
    }

    private fun capturePersistenceRequest(
        completion: CompletableDeferred<Boolean>? = null,
    ): PersistenceRequest? {
        val committedContext = context ?: return null
        return newPersistenceRequest(
            committed = transition.committed,
            context = committedContext,
            completion = completion,
        )
    }

    private fun newPersistenceRequest(
        committed: CommittedSubtitle,
        context: MobileSubtitlePlaybackContext,
        completion: CompletableDeferred<Boolean>? = null,
    ): PersistenceRequest? {
        val writeScope = context.writeScope ?: return null
        return PersistenceRequest(
            ticket = persistenceCoordinator.capture(
                scope = writeScope,
                contentId = context.contentId,
                fileId = context.mediaFileId,
            ),
            committed = committed,
            context = context,
            completion = completion,
        )
    }

    private suspend fun writePersistenceRequest(request: PersistenceRequest): Boolean =
        persistenceCoordinator.write(request.ticket) {
            repeat(PERSISTENCE_ATTEMPTS) {
                try {
                    if (persistencePort.persist(request.committed, request.context)) {
                        return@write true
                    }
                } catch (cancellation: CancellationException) {
                    if (!currentCoroutineContext().isActive) throw cancellation
                } catch (_: Exception) {
                    // The bounded loop owns retry and containment.
                }
            }
            false
        }

    private suspend fun awaitBoundedDurablePersistence(
        request: PersistenceRequest,
    ): Boolean {
        val completion = CompletableDeferred<Boolean>()
        val job = durablePersistenceScope.launch {
            val success = withTimeoutOrNull(DURABLE_PERSISTENCE_TIMEOUT_MS) {
                runCatching { writePersistenceRequest(request) }.getOrDefault(false)
            } ?: false
            completion.complete(success)
        }
        job.invokeOnCompletion { cause ->
            if (cause != null) completion.complete(false)
        }
        return withContext(NonCancellable) {
            withTimeoutOrNull(DURABLE_PERSISTENCE_TIMEOUT_MS) {
                completion.await()
            } ?: false
        }
    }

    private fun publish() {
        onSnapshotChanged(snapshot)
    }

    private companion object {
        const val LOCAL_MOUNT_TIMEOUT_MS = 5_000L
        const val PERSISTENCE_ATTEMPTS = 2
        const val PRIMARY_PERSISTENCE_TIMEOUT_MS = 5_000L
        const val DURABLE_PERSISTENCE_TIMEOUT_MS = 5_000L
    }
}

internal class PlaybackSessionManagerMobileSubtitleStagedReplanPort(
    private val manager: PlaybackSessionManager,
) : MobileSubtitleStagedReplanPort {
    override suspend fun stage(
        request: MobileSubtitleStageRequest,
    ): ApiResult<MobileStagedSubtitleCandidate> = when (
        val result = manager.stageActiveVideoSessionReplan(
            classification = "subtitle_track_changed",
            message = "Applying subtitle selection.",
            positionSeconds = request.positionSeconds,
            audioTrackIndex = request.audioTrackIndex,
            subtitleTrackIndex = request.subtitleTrackIndex,
            qualityPreference = request.qualityPreference,
        )
    ) {
        is ApiResult.Success -> {
            val handle = result.data
            val ready = handle.candidate
            ApiResult.Success(
                MobileStagedSubtitleCandidate(
                    id = handle.candidateSessionId,
                    sessionId = handle.candidateSessionId,
                    selectedAudioIndex = ready.plan.selectedTracks.audio?.index,
                    selectedSubtitleIndex = ready.plan.selectedTracks.subtitle?.index,
                    subtitleMode = ready.plan.subtitle.mode,
                    hasSidecar = ready.plan.subtitle.artifact?.url?.isNotBlank() == true,
                    subtitleTracks = ready.session.subtitleUrls.orEmpty(),
                    managerHandle = handle,
                ),
            )
        }
        is ApiResult.Error -> result
        is ApiResult.NetworkError -> result
    }

    override suspend fun commit(
        candidate: MobileStagedSubtitleCandidate,
    ): ApiResult<MobileSubtitleCommittedPlayback> {
        val handle = candidate.managerHandle ?: return ApiResult.Error(
            code = 409,
            error = "missing_staged_subtitle_handle",
            message = "The staged subtitle candidate no longer has a commit handle.",
        )
        return when (val result = manager.commitStagedVideoReplan(handle)) {
            is ApiResult.Success -> ApiResult.Success(
                MobileSubtitleCommittedPlayback(
                    sessionId = result.data.session.sessionId,
                    subtitleTracks = result.data.session.subtitleUrls.orEmpty(),
                    ready = result.data,
                ),
            )
            is ApiResult.Error -> result
            is ApiResult.NetworkError -> result
        }
    }

    override suspend fun discard(candidate: MobileStagedSubtitleCandidate) {
        candidate.managerHandle?.let { manager.discardStagedVideoReplan(it) }
    }

    override suspend fun abandonCommitted(playback: MobileSubtitleCommittedPlayback) {
        // Mobile replans publish immediately. If a later adapter step abandons
        // that committed session, disown it as well as stopping it so future
        // recovery cannot keep addressing the torn-down active attempt.
        manager.abandonActiveVideoSession(playback.sessionId)
    }
}

private fun SubtitleIdentity.serverTrackIndex(): Int = when (this) {
    SubtitleIdentity.Off -> -1
    is SubtitleIdentity.ServerSidecar -> serverIndex
    is SubtitleIdentity.ServerBurnIn -> serverIndex
    is SubtitleIdentity.Embedded -> serverIndex
    is SubtitleIdentity.Downloaded,
    is SubtitleIdentity.LocalMedia3,
    -> -1
}

private fun SubtitleIdentity.requiresLocalMountConfirmation(): Boolean =
    this is SubtitleIdentity.LocalMedia3 ||
        this is SubtitleIdentity.Downloaded ||
        this is SubtitleIdentity.Embedded

private fun SubtitleIdentity.isClientOwnedSubtitle(): Boolean =
    this is SubtitleIdentity.LocalMedia3 || this is SubtitleIdentity.Downloaded

private fun MobileStagedSubtitleCandidate.validationFailure(
    requested: org.siloserver.silo.model.playback.PendingSubtitle,
    expectedSubtitleIndex: Int,
): String? {
    if (requested.audioPreferenceSpecified &&
        selectedAudioIndex != requested.audioTrackIndex
    ) {
        return "The candidate did not select the requested audio track."
    }
    return when (requested.identity) {
        is SubtitleIdentity.Embedded,
        is SubtitleIdentity.Downloaded,
        is SubtitleIdentity.LocalMedia3,
        -> when {
            (selectedSubtitleIndex ?: -1) != expectedSubtitleIndex ->
                "The candidate did not preserve the mounted subtitle."
            expectedSubtitleIndex < 0 && subtitleMode != PlaybackSubtitleModeV3.OFF ->
                "The candidate did not keep server subtitles off for the client-mounted subtitle."
            subtitleMode == PlaybackSubtitleModeV3.BURN_IN ->
                "The candidate unexpectedly burned in the mounted subtitle."
            else -> null
        }
        else -> validationFailure(requested.identity)
    }
}

private fun MobileStagedSubtitleCandidate.validationFailure(
    identity: SubtitleIdentity,
): String? = when (identity) {
    SubtitleIdentity.Off -> if (
        selectedSubtitleIndex == null &&
        subtitleMode == PlaybackSubtitleModeV3.OFF &&
        !hasSidecar
    ) {
        null
    } else {
        "The candidate did not keep subtitles off."
    }
    is SubtitleIdentity.ServerSidecar -> when {
        selectedSubtitleIndex != identity.serverIndex ->
            "The candidate did not select the requested subtitle."
        subtitleMode != PlaybackSubtitleModeV3.RENDER &&
            subtitleMode != PlaybackSubtitleModeV3.CONVERT ->
            "The candidate did not render the requested sidecar."
        !hasSidecar -> "The candidate omitted the requested subtitle sidecar."
        else -> null
    }
    is SubtitleIdentity.ServerBurnIn -> when {
        selectedSubtitleIndex != identity.serverIndex ->
            "The candidate did not select the requested subtitle."
        subtitleMode != PlaybackSubtitleModeV3.BURN_IN ->
            "The candidate did not burn in the requested subtitle."
        else -> null
    }
    is SubtitleIdentity.Embedded,
    is SubtitleIdentity.Downloaded,
    is SubtitleIdentity.LocalMedia3,
    -> "A local subtitle identity unexpectedly reached staged validation."
}

private fun MobileSubtitleCommittedPlayback.withRebasedDownloads(
    oldContext: MobileSubtitlePlaybackContext,
): MobileSubtitleCommittedPlayback {
    val downloadedPredicate: (PlayerSubtitleInfo) -> Boolean = {
        it.downloadId != null || it.source.equals("downloaded", ignoreCase = true)
    }
    val downloaded = oldContext.subtitleTracks
        .filter(downloadedPredicate)
        .map { track ->
            track.copy(url = rebaseDownloadedSubtitleUrl(track.url, sessionId))
        }
    val candidateByIndex = subtitleTracks
        .filterNot(downloadedPredicate)
        .associateBy(PlayerSubtitleInfo::index)
    val retainedCatalog = oldContext.subtitleTracks
        .filterNot(downloadedPredicate)
        .map { old ->
            candidateByIndex[old.index]?.let { candidate ->
                candidate.copy(
                    language = candidate.language ?: old.language,
                    codec = candidate.codec ?: old.codec,
                    label = candidate.label ?: old.label,
                    forced = candidate.forced ?: old.forced,
                    catalogLabel = old.catalogLabel ?: candidate.catalogLabel,
                    catalogSource = old.catalogSource ?: candidate.catalogSource,
                    isDefault = old.isDefault ?: candidate.isDefault,
                )
            } ?: old.copy(url = "")
        }
    val retainedIndexes = retainedCatalog.mapTo(mutableSetOf(), PlayerSubtitleInfo::index)
    val additionalCandidates = subtitleTracks.filterNot(downloadedPredicate)
        .filterNot { it.index in retainedIndexes }
    return copy(
        subtitleTracks = retainedCatalog + additionalCandidates + downloaded,
    )
}
