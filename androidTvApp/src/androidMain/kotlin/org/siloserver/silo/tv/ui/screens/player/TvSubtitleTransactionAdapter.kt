package org.siloserver.silo.tv.ui.screens.player

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
import org.siloserver.silo.common.player.PlaybackSessionLifecycle
import org.siloserver.silo.common.player.PlaybackTrackSelectionWriteCoordinator
import org.siloserver.silo.common.player.StagedVideoReplan
import org.siloserver.silo.common.player.VideoSessionStartV3
import org.siloserver.silo.common.player.SubDiag
import org.siloserver.silo.model.catalog.AudioTrack
import org.siloserver.silo.model.playback.ClientCodecCapabilities
import org.siloserver.silo.model.playback.ClientPlaybackContext
import org.siloserver.silo.model.playback.CommittedSubtitle
import org.siloserver.silo.model.playback.PlaybackSubtitleModeV3
import org.siloserver.silo.model.playback.PendingSubtitle
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
import org.siloserver.silo.model.playback.UpdateQualityPreference
import org.siloserver.silo.model.playback.isLocalDownloadedSubtitle
import org.siloserver.silo.model.playback.rebaseDownloadedSubtitleUrl
import org.siloserver.silo.model.playback.reduceSubtitleTransition
import org.siloserver.silo.model.playback.resolvedSelectedSubtitleIndex
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.playback.downloadedSubtitleArtifactTrackId
import org.siloserver.silo.playback.subtitleLabelIndicatesHearingImpaired
import org.siloserver.silo.repository.port.PlaybackWriteScope

internal data class TvSubtitlePlaybackContext(
    val contentId: String,
    val mediaFileId: Int,
    val versionId: String,
    val sessionId: String?,
    val positionSeconds: Double,
    val audioTrackIndex: Int?,
    val qualityPreference: String?,
    val subtitleTracks: List<PlayerSubtitleInfo>,
    val audioTracks: List<AudioTrack> = emptyList(),
    val outputRouteGeneration: Long = 0L,
    val capabilities: ClientCodecCapabilities = ClientCodecCapabilities(),
    val clientPlaybackContext: ClientPlaybackContext = ClientPlaybackContext(
        formFactor = "tv",
        appVersion = "unknown",
    ),
    val writeScope: PlaybackWriteScope? = null,
)

private fun TvSubtitlePlaybackContext.withLatestPlanningEvidence(
    latest: TvSubtitlePlaybackContext,
): TvSubtitlePlaybackContext = copy(
    positionSeconds = latest.positionSeconds,
    outputRouteGeneration = latest.outputRouteGeneration,
    capabilities = latest.capabilities,
    clientPlaybackContext = latest.clientPlaybackContext,
)

private fun replayValidatedIntent(
    rollbackState: SubtitleTransitionState,
    requested: PendingSubtitle,
): SubtitleTransitionState = rollbackState.copy(
    pending = requested.copy(generation = rollbackState.nextGeneration),
    nextGeneration = rollbackState.nextGeneration + 1,
)

internal data class TvSubtitleStageRequest(
    val generation: Long,
    val contentId: String,
    val mediaFileId: Int,
    val versionId: String,
    val sessionId: String,
    val positionSeconds: Double,
    val audioTrackIndex: Int?,
    val qualityPreference: String?,
    val subtitleTrackIndex: Int,
    val outputRouteGeneration: Long = 0L,
    val classification: String = "subtitle_track_changed",
    val capabilities: ClientCodecCapabilities,
    val clientPlaybackContext: ClientPlaybackContext,
)

internal data class TvSubtitleManagerStageInput(
    val capabilities: ClientCodecCapabilities,
    val clientPlaybackContext: ClientPlaybackContext,
    val outputRouteGeneration: Long,
)

internal fun TvSubtitleStageRequest.toManagerStageInput(): ApiResult<TvSubtitleManagerStageInput> {
    // The contract's output context token is opaque to the server; this client
    // mints it from the route generation it is tracking here, so an equality
    // check against the stringified generation is the same staleness test the
    // server performs.
    if (clientPlaybackContext.output.outputContextId != outputRouteGeneration.toString()) {
        return ApiResult.Error(
            code = 409,
            error = "stale_output_route_context",
            message = "The playback context does not match the requested output route.",
        )
    }
    return ApiResult.Success(
        TvSubtitleManagerStageInput(
            capabilities = capabilities,
            clientPlaybackContext = clientPlaybackContext,
            outputRouteGeneration = outputRouteGeneration,
        ),
    )
}

internal data class TvStagedSubtitleCandidate(
    val id: String,
    val sessionId: String,
    val selectedAudioIndex: Int?,
    val selectedSubtitleIndex: Int?,
    val subtitleMode: PlaybackSubtitleModeV3,
    val hasSidecar: Boolean,
    val subtitleTracks: List<PlayerSubtitleInfo>,
    val effectiveMediaFileId: Int? = null,
    val selectedSubtitleIdentity: SubtitleIdentity? = null,
    val qualityPreference: String? = null,
    val outputRouteGeneration: Long = 0L,
    internal val managerHandle: StagedVideoReplan? = null,
)

internal data class TvSubtitleCommittedPlayback(
    val sessionId: String,
    val subtitleTracks: List<PlayerSubtitleInfo>,
    val ready: VideoSessionStartV3.Ready? = null,
    val effectiveMediaFileId: Int? = null,
    val outputRouteGeneration: Long = 0L,
)

internal interface TvSubtitleStagedReplanPort {
    suspend fun stage(request: TvSubtitleStageRequest): ApiResult<TvStagedSubtitleCandidate>

    suspend fun commit(
        candidate: TvStagedSubtitleCandidate,
    ): ApiResult<TvSubtitleCommittedPlayback>

    suspend fun discard(candidate: TvStagedSubtitleCandidate)

    suspend fun confirmCommitted(playback: TvSubtitleCommittedPlayback) = Unit

    suspend fun abandonCommitted(playback: TvSubtitleCommittedPlayback)
}

internal interface TvSubtitlePersistencePort {
    suspend fun persist(
        committed: CommittedSubtitle,
        context: TvSubtitlePlaybackContext,
    ): Boolean
}

/**
 * Process-lifetime owner for bounded final preference writes. Keeping this
 * scope outside each adapter prevents one unbounded SupervisorJob per player.
 */
private object TvSubtitleDurablePersistenceOwner {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
}

/** Serial process owner for publication settlement; survives ViewModel scope cancellation. */
internal object TvSubtitleSettlementOwner {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
}

internal class TvSubtitleDurablePersistenceReservation internal constructor(
    internal val ticket: PlaybackTrackSelectionWriteCoordinator.Ticket,
    internal val context: TvSubtitlePlaybackContext,
)

internal data class TvSubtitleTransactionSnapshot(
    val transition: SubtitleTransitionState,
    val pendingIdentity: SubtitleIdentity? = transition.pending?.identity,
    val localMountIdentity: SubtitleIdentity? = null,
    val failureMessage: String? = null,
    val committedOutputRouteGeneration: Long = 0L,
    val desiredOutputRouteGeneration: Long = committedOutputRouteGeneration,
    val subtitleTracks: List<PlayerSubtitleInfo> = emptyList(),
    val subtitleRefreshNonce: Long = 0L,
) {
    val committedIdentity: SubtitleIdentity
        get() = transition.committed.identity

    val subtitleApplying: Boolean
        get() = pendingIdentity != null
}

internal data class TvSubtitleRefreshOwner(
    val contentGeneration: Long,
    val contentId: String,
    val mediaFileId: Int,
    val versionId: String,
    val sessionId: String?,
    val refreshGeneration: Long,
    val subtitleIntentGeneration: Long,
    val source: TvSubtitleRefreshSource = TvSubtitleRefreshSource.Realtime,
)

internal enum class TvSubtitleRefreshSource {
    Download,
    AiCompletion,
    Realtime,
}

internal enum class TvSubtitleAdoptionResult {
    Adopted,
    Superseded,
}

internal class TvSubtitlePlaybackAdoption internal constructor(
    val playback: TvSubtitleCommittedPlayback,
    val committed: CommittedSubtitle,
    val requestedSourcePositionSeconds: Double,
    private val currentOwner: () -> Boolean,
    private val currentPendingIdentity: () -> SubtitleIdentity?,
) {
    fun isCurrent(): Boolean = currentOwner()

    fun pendingIdentity(): SubtitleIdentity? =
        currentPendingIdentity().takeIf { isCurrent() }
}

/**
 * Tv execution adapter for the shared subtitle reducer.
 *
 * One conflated worker serializes staged server requests. A newer intent does
 * not cancel an in-flight HTTP request; its eventual candidate is discarded
 * and only the newest queued intent is staged from the still-committed session.
 */
internal class TvSubtitleTransactionAdapter(
    private val scope: CoroutineScope,
    private val stagedPort: TvSubtitleStagedReplanPort,
    private val persistencePort: TvSubtitlePersistencePort,
    private val durablePersistenceScope: CoroutineScope =
        TvSubtitleDurablePersistenceOwner.scope,
    private val persistenceCoordinator: PlaybackTrackSelectionWriteCoordinator =
        PlaybackTrackSelectionWriteCoordinator.Process,
    private val settlementScope: CoroutineScope = scope,
    private val onSnapshotChanged: (TvSubtitleTransactionSnapshot) -> Unit = {},
    private val onCommittedPlayback: suspend (
        TvSubtitlePlaybackAdoption,
    ) -> TvSubtitleAdoptionResult = { TvSubtitleAdoptionResult.Adopted },
    private val onCommittedPlaybackConfirmed: suspend (
        TvSubtitleCommittedPlayback,
    ) -> Boolean = { true },
    private val onCommittedPlaybackRollback: suspend (
        TvSubtitleCommittedPlayback,
        Boolean,
    ) -> Boolean = { _, _ -> true },
    private val onCommittedPlaybackFailure: suspend (String) -> Unit = {},
    /**
     * Whether the player currently exposes any text track to mount against.
     * The mount deadline must not run while the answer is "none": a freshly
     * replanned stream reports READY seconds before publishing its tracks.
     */
    private val hasMountableTracks: () -> Boolean = { true },
    /**
     * Whether THIS identity can be satisfied by a text track the player already
     * exposes. Catalog-only rows (embedded tracks on a remuxed or transcoded
     * route) carry a blank URL and never become Media3 tracks, so committing
     * them locally could only ever end at the mount deadline and roll back.
     * Answering false sends the pick down the staged-replan path instead, which
     * is what asks the server to materialise the artifact.
     */
    private val isLocallyMountable: (SubtitleIdentity) -> Boolean = { true },
) {
    private data class PendingLocalSelection(
        val generation: Long,
        val identity: SubtitleIdentity,
        val proposedState: SubtitleTransitionState,
        val context: TvSubtitlePlaybackContext,
        val rollbackState: SubtitleTransitionState,
        val rollbackContext: TvSubtitlePlaybackContext,
        val rollbackOutputRouteGeneration: Long,
        val replayStateAfterRollback: SubtitleTransitionState? = null,
        val committedPlayback: TvSubtitleCommittedPlayback? = null,
        val persistOnSuccess: Boolean = true,
        val mountedBeforeAdoption: Boolean = false,
        val rollbackIncludesLifecycle: Boolean = true,
        val exposesMountBoundary: Boolean = true,
    )

    private enum class PublicationSettlementPhase {
        AwaitingCommit,
        Confirming,
        RollingBack,
        Compensating,
    }

    private data class PublicationSettlement(
        var ownerGeneration: Long,
        val completion: CompletableDeferred<Boolean>,
        var phase: PublicationSettlementPhase,
        /**
         * Identity whose mount was confirmed while this settlement was open.
         *
         * A mount reported during a settlement used to be dropped on the floor,
         * so a settlement could go on rolling back a publication whose mount had
         * in fact succeeded — the proof arrived and was thrown away.
         */
        var mountedIdentity: SubtitleIdentity? = null,
    )

    private data class PendingLocalRestore(
        val generation: Long,
        val identity: SubtitleIdentity,
        val persistence: PersistenceRequest? = null,
    )

    private data class PendingFreshRestore(
        val generation: Long,
        val identity: SubtitleIdentity,
        val priorState: SubtitleTransitionState,
    )

    private data class PersistenceRequest(
        val ticket: PlaybackTrackSelectionWriteCoordinator.Ticket,
        val committed: CommittedSubtitle,
        val context: TvSubtitlePlaybackContext,
        val completion: CompletableDeferred<Boolean>? = null,
    )

    private val stagedRequests = Channel<org.siloserver.silo.model.playback.PendingSubtitle>(
        capacity = Channel.CONFLATED,
    )
    private val persistenceRequests = Channel<PersistenceRequest>(capacity = Channel.UNLIMITED)

    private var transition = SubtitleTransitionState.committed(SubtitleIdentity.Off)
    private var context: TvSubtitlePlaybackContext? = null
    private var contentGeneration = 0L
    private var refreshGeneration = 0L
    private var subtitleIntentGeneration = 0L
    private var failureMessage: String? = null
    private var pendingLocalSelection: PendingLocalSelection? = null
    private var pendingLocalRestore: PendingLocalRestore? = null
    private var pendingFreshRestore: PendingFreshRestore? = null
    private var localMountGeneration = 0L
    private var localMountTimeout: Job? = null
    private val queuedMutations = mutableListOf<SubtitleTransitionEvent>()
    private var commitInFlight = false
    private var resetDuringCommit = false
    private var adoptionGeneration = 0L
    private var committedOutputRouteGeneration = 0L
    private var desiredOutputRouteGeneration = 0L
    private var subtitleRefreshNonce = 0L
    private var compensatingGeneration: Long? = null
    private var publicationSettlement: PublicationSettlement? = null
    private val settlementInFlight: Boolean
        get() = publicationSettlement != null
    private val settlementCompletion: CompletableDeferred<Boolean>?
        get() = publicationSettlement?.completion
    private var discardQueuedAfterSettlement = false
    private var resetAfterSettlement: Pair<TvSubtitlePlaybackContext, SubtitleIdentity>? = null

    /** A fresh-preference restore deferred because a publication was unsettled. */
    private data class DeferredFreshRestore(
        val identity: SubtitleIdentity,
        val migrationRequired: Boolean,
        // The content it was captured for. The TV load path calls resetContent
        // and restoreFreshPreference one line apart for the SAME new content,
        // so "a reset is pending" is not evidence the restore is stale — only a
        // reset to DIFFERENT content is.
        val contentId: String,
        val mediaFileId: Int,
    )

    private var pendingFreshRestoreAfterSettlement: DeferredFreshRestore? = null

    val snapshot: TvSubtitleTransactionSnapshot
        get() {
            val queuedIdentity = queuedPreviewState()?.pending?.identity
            val publicationIdentity = pendingLocalSelection?.identity
            val localIdentity = pendingLocalSelection
                ?.takeIf { it.exposesMountBoundary }
                ?.identity
                ?: pendingLocalRestore?.identity
            return TvSubtitleTransactionSnapshot(
                transition = transition,
                pendingIdentity = queuedIdentity
                    ?: publicationIdentity
                    ?: localIdentity
                    ?: transition.pending?.identity,
                localMountIdentity = if (queuedIdentity == null) localIdentity else null,
                failureMessage = failureMessage,
                committedOutputRouteGeneration = committedOutputRouteGeneration,
                desiredOutputRouteGeneration = desiredOutputRouteGeneration,
                subtitleTracks = context?.subtitleTracks.orEmpty(),
                subtitleRefreshNonce = subtitleRefreshNonce,
            )
        }

    val hasActiveTransaction: Boolean
        get() = settlementInFlight ||
            commitInFlight ||
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
        context: TvSubtitlePlaybackContext,
        committedIdentity: SubtitleIdentity,
    ) {
        SubDiag.trace("ADAPTER resetContent session=${context.sessionId} committed=$committedIdentity")
        val unpublished = pendingLocalSelection
            ?.takeIf { it.committedPlayback != null }
        if (commitInFlight && unpublished == null) {
            resetAfterSettlement = context to committedIdentity
            discardQueuedAfterSettlement = true
            queuedMutations.clear()
            resetDuringCommit = true
            beginAwaitingCommitSettlement()
            return
        }
        if (unpublished != null || settlementInFlight) {
            resetAfterSettlement = context to committedIdentity
            discardQueuedAfterSettlement = true
            queuedMutations.clear()
            // resetContent is installing NEW content and its own UI, and the
            // TV rollback path has already restored the predecessor UI before
            // calling here. Restoring again would re-apply the predecessor's
            // state over the incoming content and re-arm its identity.
            if (unpublished != null) requestSupersessionSettlement(unpublished, restoreUi = false)
            return
        }
        resetContentNow(context, committedIdentity)
    }

    private fun resetContentNow(
        context: TvSubtitlePlaybackContext,
        committedIdentity: SubtitleIdentity,
    ) {
        resetAfterSettlement = null
        discardQueuedAfterSettlement = false
        if (commitInFlight) {
            resetDuringCommit = true
            queuedMutations.clear()
        }
        adoptionGeneration += 1
        contentGeneration += 1
        refreshGeneration += 1
        subtitleIntentGeneration += 1
        this.context = context
        committedOutputRouteGeneration = context.outputRouteGeneration
        desiredOutputRouteGeneration = context.outputRouteGeneration
        subtitleRefreshNonce = 0L
        invalidateLocalMount()
        pendingFreshRestore = null
        compensatingGeneration = null
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
        pendingFreshRestore = null
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

    fun updatePlaybackContext(updated: TvSubtitlePlaybackContext) {
        val current = context
        // Content identity is the item and its file. versionId is
        // "<fileId>:<planId>", so its stable half is already covered by
        // mediaFileId and the rest is the plan — which every replan changes by
        // design. Treating a new plan as new content made the subtitle
        // transaction destroy itself: its own replan produced a new planId, this
        // check called it a content change, and resetContent tore down the
        // in-flight selection microseconds after its mount had matched.
        if (current == null ||
            current.contentId != updated.contentId ||
            current.mediaFileId != updated.mediaFileId
        ) {
            resetContent(updated, transition.committed.identity)
            return
        }
        if (current.sessionId != updated.sessionId) {
            if (commitInFlight) {
                resetContent(updated, transition.committed.identity)
                return
            }
            val publicationOwner = pendingLocalSelection
                ?.takeIf { it.committedPlayback != null }
            if (publicationOwner != null || publicationSettlement != null) {
                val resetIdentity = when (publicationSettlement?.phase) {
                    PublicationSettlementPhase.Confirming ->
                        publicationOwner?.proposedState?.committed?.identity
                    PublicationSettlementPhase.AwaitingCommit,
                    PublicationSettlementPhase.RollingBack,
                    PublicationSettlementPhase.Compensating,
                    null,
                    -> publicationOwner?.rollbackState?.committed?.identity
                } ?: transition.committed.identity
                resetAfterSettlement = updated to resetIdentity
                discardQueuedAfterSettlement = true
                queuedMutations.clear()
                if (publicationSettlement == null && publicationOwner != null) {
                    // Same as resetContent: this branch is adopting a new
                    // session and installs its own UI.
                    requestSupersessionSettlement(publicationOwner, restoreUi = false)
                }
                return
            }
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
        pendingLocalSelection = pendingLocalSelection?.let { pending ->
            pending.copy(
                rollbackContext = pending.rollbackContext.withLatestPlanningEvidence(updated),
            )
        }
        context = updated
    }

    fun select(identity: SubtitleIdentity) {
        SubDiag.log("ADAPTER select $identity")
        mutate(SelectSubtitle(identity), explicit = true)
    }

    /**
     * Applies an APP-DERIVED automatic selection — the launch-time language /
     * mode / forced heuristics — through the same commit path as [select], so
     * the adapter stays the single owner of subtitle selection and the HUD's
     * committed identity always describes what is actually mounted.
     *
     * Not [select] for two reasons: an automatic pick must not cancel an
     * in-flight subtitle refresh (only an explicit intent bumps the refresh
     * generation), and it is not the viewer choosing, so the caller keeps it
     * out of the durable per-item preference (see
     * `TvPlayerViewModel.autoSelectedSubtitleIdentity`).
     *
     * A no-op when the identity is already committed and nothing is in flight:
     * re-selecting what is already on would arm a pointless remount.
     */
    fun selectAuto(identity: SubtitleIdentity) {
        if (identity == transition.committed.identity && !hasActiveTransaction) {
            SubDiag.log("ADAPTER selectAuto NOOP $identity")
            return
        }
        SubDiag.log("ADAPTER selectAuto $identity")
        mutate(SelectSubtitle(identity), explicit = false)
    }

    /**
     * Restores a saved fresh-load preference without declaring it committed
     * before both the server replan and the player backend have accepted it.
     */
    fun restoreFreshPreference(
        identity: SubtitleIdentity,
        migrationRequired: Boolean = false,
    ) {
        val current = context ?: return
        val priorState = transition
        if (identity == transition.committed.identity) {
            if (identity is SubtitleIdentity.ServerBurnIn) {
                if (migrationRequired) persist(transition.committed, current)
            } else {
                // mutate() refuses to touch local state while a publication is
                // unsettled; this branch reached beginLocalSelection directly,
                // which nulls pendingLocalSelection via invalidateLocalMount. If
                // that owner still held an unsettled committedPlayback the
                // server publication was orphaned — never confirmed, never
                // rolled back — and the in-flight settlement lost its owner.
                // The TV load path calls this on the line after resetContent,
                // i.e. exactly while such a settlement is open.
                val unpublished = pendingLocalSelection
                    ?.takeIf { it.committedPlayback != null }
                if (unpublished != null || settlementInFlight) {
                    pendingFreshRestoreAfterSettlement = DeferredFreshRestore(
                        identity = identity,
                        migrationRequired = migrationRequired,
                        contentId = current.contentId,
                        mediaFileId = current.mediaFileId,
                    )
                    if (unpublished != null) {
                        requestSupersessionSettlement(unpublished, restoreUi = false)
                    }
                    return
                }
                beginLocalSelection(
                    identity = identity,
                    proposedState = transition,
                    selectionContext = current,
                )
            }
            return
        }

        mutate(SelectSubtitle(identity), explicit = false)
        val generation = transition.pending?.generation
        if (generation != null) {
            pendingFreshRestore = PendingFreshRestore(
                generation = generation,
                identity = identity,
                priorState = priorState,
            )
        }
    }

    fun selectAudio(audioTrackIndex: Int?) {
        mutate(UpdateAudioPreference(audioTrackIndex), explicit = true)
    }

    fun selectQuality(qualityPreference: String?) {
        mutate(UpdateQualityPreference(qualityPreference), explicit = true)
    }

    fun updateOutputRouteGeneration(generation: Long) {
        if (generation == desiredOutputRouteGeneration) return
        desiredOutputRouteGeneration = generation
        val identity = snapshot.pendingIdentity ?: transition.committed.identity
        mutate(SelectSubtitle(identity), explicit = true)
    }

    fun invalidate() {
        val unpublished = pendingLocalSelection
            ?.takeIf { it.committedPlayback != null }
        if (commitInFlight && unpublished == null) {
            discardQueuedAfterSettlement = true
            queuedMutations.clear()
            resetDuringCommit = true
            beginAwaitingCommitSettlement()
            return
        }
        if (unpublished != null || settlementInFlight) {
            discardQueuedAfterSettlement = true
            queuedMutations.clear()
            if (unpublished != null) requestSupersessionSettlement(unpublished, restoreUi = true)
            return
        }
        invalidateNow()
    }

    suspend fun invalidateAndSettle(restoreUi: Boolean = true): Boolean {
        discardQueuedAfterSettlement = true
        queuedMutations.clear()
        val unpublished = pendingLocalSelection
            ?.takeIf { it.committedPlayback != null }
        if (commitInFlight && unpublished == null) {
            resetDuringCommit = true
            return beginAwaitingCommitSettlement().completion.await()
        }
        val completion = when {
            unpublished != null -> requestSupersessionSettlement(unpublished, restoreUi)
            settlementInFlight -> settlementCompletion
            else -> null
        }
        if (completion == null) {
            invalidateNow()
            return true
        }
        return completion.await()
    }

    suspend fun invalidateAndAwaitSettlement(): Boolean =
        invalidateAndSettle(restoreUi = true)

    fun invalidateAndSettleAsync(
        restoreUi: Boolean = false,
        afterSettlement: suspend () -> Unit,
    ) {
        settlementScope.launch {
            runCatching { invalidateAndSettle(restoreUi) }
            runCatching { afterSettlement() }
        }
    }

    private fun invalidateNow() {
        resetAfterSettlement = null
        discardQueuedAfterSettlement = false
        adoptionGeneration += 1
        contentGeneration += 1
        refreshGeneration += 1
        subtitleIntentGeneration += 1
        desiredOutputRouteGeneration = committedOutputRouteGeneration
        invalidateLocalMount()
        pendingFreshRestore = null
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
        val reservation = reserveDurableFinalPersistence() ?: return
        requestDurableFinalPersistence(reservation)
    }

    fun reserveDurableFinalPersistence(): TvSubtitleDurablePersistenceReservation? {
        val committedContext = context ?: return null
        val writeScope = committedContext.writeScope ?: return null
        return TvSubtitleDurablePersistenceReservation(
            ticket = persistenceCoordinator.capture(
                scope = writeScope,
                contentId = committedContext.contentId,
                fileId = committedContext.mediaFileId,
            ),
            context = committedContext,
        )
    }

    fun requestDurableFinalPersistence(
        reservation: TvSubtitleDurablePersistenceReservation,
    ) {
        val request = PersistenceRequest(
            ticket = reservation.ticket,
            committed = transition.committed,
            context = reservation.context,
        )
        durablePersistenceScope.launch {
            val success = withTimeoutOrNull(DURABLE_PERSISTENCE_TIMEOUT_MS) {
                runCatching { writePersistenceRequest(request) }.getOrDefault(false)
            } ?: false
            if (!success) persistenceCoordinator.abandon(request.ticket)
        }
    }

    fun restoreCommittedLocalMount() {
        val identity = transition.committed.identity
        if (
            context?.sessionId != null &&
            identity.requiresLocalMountConfirmation() &&
            isLocallyMountable(identity)
        ) {
            beginLocalRestore(identity)
        }
    }

    fun beginRefresh(
        source: TvSubtitleRefreshSource = TvSubtitleRefreshSource.Realtime,
    ): TvSubtitleRefreshOwner {
        refreshGeneration += 1
        val current = requireNotNull(context) {
            "Subtitle refresh cannot start before playback context is installed."
        }
        return TvSubtitleRefreshOwner(
            contentGeneration = contentGeneration,
            contentId = current.contentId,
            mediaFileId = current.mediaFileId,
            versionId = current.versionId,
            sessionId = current.sessionId,
            refreshGeneration = refreshGeneration,
            subtitleIntentGeneration = subtitleIntentGeneration,
            source = source,
        )
    }

    fun ownsRefresh(owner: TvSubtitleRefreshOwner): Boolean {
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
        owner: TvSubtitleRefreshOwner,
        identity: SubtitleIdentity,
    ): Boolean {
        if (!ownsRefresh(owner)) return false
        mutate(SelectSubtitle(identity), explicit = false)
        return true
    }

    fun applyRefresh(
        owner: TvSubtitleRefreshOwner,
        subtitleTracks: List<PlayerSubtitleInfo>,
        autoSelectDownloadId: Int?,
    ): Boolean {
        if (!ownsRefresh(owner)) return false
        val current = context ?: return false
        // Callers provide the complete authoritative list. Never strip and
        // rebuild downloaded rows: V3 owns their ordinals, track IDs, delivery
        // modes, and session-scoped URLs.
        context = current.copy(subtitleTracks = subtitleTracks)
        subtitleRefreshNonce += 1
        publish()

        val selectedRow = autoSelectDownloadId
            ?.let { id -> subtitleTracks.filter { it.downloadId == id }.singleOrNull() }
        if (selectedRow != null) {
            mutate(SelectSubtitle(tvSubtitleIdentity(selectedRow)), explicit = false)
        }
        return true
    }

    fun completeRefreshFailure(owner: TvSubtitleRefreshOwner, message: String): Boolean {
        if (!ownsRefresh(owner)) return false
        refreshGeneration += 1
        return true
    }

    fun cancelRefresh(owner: TvSubtitleRefreshOwner) {
        if (ownsRefresh(owner)) refreshGeneration += 1
    }

    fun reportMountedSelection(
        identity: SubtitleIdentity,
        selected: Boolean,
        snapshotKey: String?,
        settled: Boolean = false,
    ) {
        SubDiag.log("REPORT mounted=$identity selected=$selected settled=$settled pendingLocal=${pendingLocalSelection?.identity}")
        if (settlementInFlight) {
            // Record it rather than discarding it: a settlement that is
            // rolling back a publication whose mount has actually succeeded is
            // deciding against the evidence.
            if (selected) publicationSettlement?.mountedIdentity = identity
            return
        }
        val pendingSelection = pendingLocalSelection
            ?.takeIf { it.identity == identity && it.exposesMountBoundary }
        if (pendingSelection != null) {
            if (selected) {
                if (pendingSelection.proposedState.pending != null) {
                    localMountTimeout?.cancel()
                    localMountTimeout = null
                    pendingLocalSelection = pendingSelection.copy(mountedBeforeAdoption = true)
                    failureMessage = null
                    publish()
                } else if (pendingSelection.committedPlayback != null) {
                    confirmMountedCommittedSelection(pendingSelection)
                } else {
                    transition = pendingSelection.proposedState
                    invalidateLocalMount()
                    failureMessage = null
                    publish()
                    if (pendingSelection.persistOnSuccess) {
                        persist(transition.committed, pendingSelection.context)
                    }
                }
            } else if (settled && !snapshotKey.isNullOrBlank()) {
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
        } else if (settled && !snapshotKey.isNullOrBlank()) {
            failLocalMount(pendingRestore.generation)
        }
    }

    private fun mutate(event: SubtitleTransitionEvent, explicit: Boolean) {
        if (explicit) refreshGeneration += 1
        subtitleIntentGeneration += 1
        failureMessage = null
        if (explicit && settlementInFlight && resetAfterSettlement == null) {
            discardQueuedAfterSettlement = false
        }

        val unpublished = pendingLocalSelection
            ?.takeIf { it.committedPlayback != null }
        if (unpublished != null || settlementInFlight) {
            queuedMutations += event
            if (unpublished != null) {
                requestSupersessionSettlement(unpublished, restoreUi = true)
            }
            publish()
            return
        }

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
                        candidate = StagedSubtitleCandidate("tv-preplay"),
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

    /**
     * Commits a locally-mountable identity — an embedded (muxed) track the
     * stream already carries — without a server round trip.
     *
     * Replanning to reach a track that is already in the stream tears it down
     * and restarts it: black flash, rebuffer, re-seek, for the same picture. A
     * pending is force-validated against the synthetic `tv-local` candidate so
     * the state machine reaches committed with no server involved.
     *
     * Returns false — meaning "you must replan after all" — when the pending
     * also carries an audio, quality or output-route preference. Those can only
     * be applied by the server, and short-circuiting it would drop them
     * silently. Both callers must respect that: this is shared precisely
     * because the two paths had drifted, and a subtitle press replayed out of
     * `queuedMutations` was replanning where a direct press did not.
     */
    private fun commitLocallyMountableSelection(
        identity: SubtitleIdentity,
        state: SubtitleTransitionState,
    ): Boolean {
        val selectionContext = context ?: return false
        if (!isLocallyMountable(identity)) {
            SubDiag.log("commitLocal SKIP not mountable id=$identity")
            return false
        }
        val pending = state.pending
        if (
            pending != null &&
            (
                pending.audioPreferenceSpecified ||
                    pending.qualityPreferenceSpecified ||
                    desiredOutputRouteGeneration != committedOutputRouteGeneration
                )
        ) {
            return false
        }
        val proposedState = if (pending == null) {
            state
        } else {
            reduceSubtitleTransition(
                state,
                StagedSubtitleValidated(
                    generation = pending.generation,
                    candidate = StagedSubtitleCandidate("tv-local"),
                ),
            ).state
        }
        beginLocalSelection(
            identity = identity,
            proposedState = proposedState,
            selectionContext = selectionContext,
        )
        return true
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
                        candidate = StagedSubtitleCandidate("tv-local"),
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

        if (
            identity.requiresLocalMountConfirmation() &&
            commitLocallyMountableSelection(identity, selected.state)
        ) {
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

        val request = TvSubtitleStageRequest(
            generation = requested.generation,
            contentId = requestContext.contentId,
            mediaFileId = requestContext.mediaFileId,
            versionId = requestContext.versionId,
            sessionId = requestSessionId,
            positionSeconds = requestContext.positionSeconds,
            audioTrackIndex = requested.audioTrackIndex,
            qualityPreference = requested.qualityPreference,
            subtitleTrackIndex = requested.identity.serverTrackIndex(),
            outputRouteGeneration = desiredOutputRouteGeneration,
            capabilities = requestContext.capabilities,
            clientPlaybackContext = requestContext.clientPlaybackContext,
            classification = when {
                desiredOutputRouteGeneration != committedOutputRouteGeneration ->
                    "output_route_changed"
                requested.qualityPreference != transition.committed.qualityPreference ->
                    "quality_changed"
                requested.audioTrackIndex != transition.committed.audioTrackIndex ->
                    "audio_track_changed"
                else -> "subtitle_track_changed"
            },
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
            is ApiResult.Success -> processCandidate(
                requested = requested,
                stagingContext = requestContext,
                request = request,
                candidate = staged.data,
            )
            is ApiResult.Error -> fail(requested.generation, staged.message)
            is ApiResult.NetworkError -> fail(
                requested.generation,
                staged.exception.message ?: "Subtitle selection failed.",
            )
        }
    }

    private suspend fun processCandidate(
        requested: org.siloserver.silo.model.playback.PendingSubtitle,
        stagingContext: TvSubtitlePlaybackContext,
        request: TvSubtitleStageRequest,
        candidate: TvStagedSubtitleCandidate,
    ) {
        val validationFailure = candidate.validationFailure(
            requested = requested,
            requestedMediaFileId = request.mediaFileId,
            expectedSubtitleIndex = request.subtitleTrackIndex,
            expectedOutputRouteGeneration = request.outputRouteGeneration,
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
        val validatedState = candidate.authoritativeValidatedState(
            requested = requested,
            requestedMediaFileId = request.mediaFileId,
            validated = validated.state,
        )

        commitInFlight = true
        val commitResult = withContext(NonCancellable) {
            try {
                stagedPort.commit(candidate)
            } catch (cancellation: CancellationException) {
                ApiResult.NetworkError(cancellation)
            } catch (error: Exception) {
                ApiResult.NetworkError(error)
            }
        }

        when (val committed = commitResult) {
            is ApiResult.Success -> {
                if (resetDuringCommit) {
                    val owner = installCommittedPublicationOwner(
                        requested = requested,
                        validatedState = validatedState,
                        playback = committed.data,
                        adoptionContext = stagingContext,
                        rollbackIncludesLifecycle = false,
                        exposesMountBoundary = false,
                        persistOnSuccess = false,
                    )
                    val settlement = publicationSettlement
                        ?: beginPublicationSettlement(
                            owner = owner,
                            phase = PublicationSettlementPhase.RollingBack,
                        )
                    settlement.ownerGeneration = owner.generation
                    settlement.phase = PublicationSettlementPhase.RollingBack
                    val rolledBack = abandonCommittedPlayback(committed.data)
                    if (!rolledBack) {
                        failureMessage = "Playback publication could not be rolled back."
                        publish()
                        finishSettlement(settlement, false)
                        return
                    }
                    restoreRollbackOwner(owner)
                    failureMessage = null
                    drainSettledPublication(
                        owner = owner,
                        confirmed = false,
                        compensating = false,
                    )
                    finishSettlement(settlement, true)
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
                val adoption = TvSubtitlePlaybackAdoption(
                    playback = playback,
                    committed = validatedState.committed,
                    requestedSourcePositionSeconds = adoptionContext.positionSeconds,
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
                                TvSubtitleAdoptionResult.Adopted ->
                                    if (adoption.isCurrent()) AdoptionOutcome.Adopted
                                    else AdoptionOutcome.Superseded
                                TvSubtitleAdoptionResult.Superseded ->
                                    AdoptionOutcome.Superseded
                            }
                        }
                    } catch (error: Exception) {
                        AdoptionOutcome.Failed(error)
                    }
                }
                when (adoptionOutcome) {
                    AdoptionOutcome.Adopted -> finishSuccessfulAdoption(
                        requested = requested,
                        requestedGeneration = requested.generation,
                        validatedState = validatedState,
                        playback = playback,
                        adoptionContext = adoptionContext,
                    )
                    AdoptionOutcome.Superseded -> {
                        if (rollbackCommittedPlayback(playback, restoreUi = false)) {
                            finishSupersededAdoption()
                        } else {
                            retainFailedAdoptionPublication(
                                requested = requested,
                                validatedState = validatedState,
                                playback = playback,
                                adoptionContext = adoptionContext,
                            )
                        }
                    }
                    is AdoptionOutcome.Failed -> {
                        val message = "Subtitle playback adoption failed."
                        if (rollbackCommittedPlayback(playback, restoreUi = false)) {
                            commitInFlight = false
                            finishFailedCommit(requested.generation, message)
                        } else {
                            retainFailedAdoptionPublication(
                                requested = requested,
                                validatedState = validatedState,
                                playback = playback,
                                adoptionContext = adoptionContext,
                            )
                        }
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
                if (finishAwaitingCommitWithoutPublication()) return
                finishFailedCommit(
                    generation = requested.generation,
                    message = committed.message,
                )
            }
            is ApiResult.NetworkError -> {
                commitInFlight = false
                if (finishAwaitingCommitWithoutPublication()) return
                finishFailedCommit(
                    generation = requested.generation,
                    message = committed.exception.message ?: "Subtitle selection failed.",
                )
            }
        }
    }

    private fun retainFailedAdoptionPublication(
        requested: PendingSubtitle,
        validatedState: SubtitleTransitionState,
        playback: TvSubtitleCommittedPlayback,
        adoptionContext: TvSubtitlePlaybackContext,
    ) {
        val owner = installCommittedPublicationOwner(
            requested = requested,
            validatedState = validatedState,
            playback = playback,
            adoptionContext = adoptionContext,
            rollbackIncludesLifecycle = true,
            exposesMountBoundary = true,
            persistOnSuccess = true,
        )
        publicationSettlement
            ?.takeIf { it.phase == PublicationSettlementPhase.AwaitingCommit }
            ?.let { settlement ->
                settlement.ownerGeneration = owner.generation
                settlement.phase = PublicationSettlementPhase.RollingBack
                finishSettlement(settlement, false)
            }
        failureMessage = "Playback publication could not be rolled back."
        publish()
    }

    private fun installCommittedPublicationOwner(
        requested: PendingSubtitle,
        validatedState: SubtitleTransitionState,
        playback: TvSubtitleCommittedPlayback,
        adoptionContext: TvSubtitlePlaybackContext,
        rollbackIncludesLifecycle: Boolean,
        exposesMountBoundary: Boolean,
        persistOnSuccess: Boolean,
    ): PendingLocalSelection {
        val rollbackOutputRouteGeneration = committedOutputRouteGeneration
        val priorState = transition.copy(pending = null)
        val liveContext = context
            ?.takeIf {
                it.contentId == adoptionContext.contentId &&
                    it.mediaFileId == adoptionContext.mediaFileId &&
                    it.versionId == adoptionContext.versionId
            }
            ?: adoptionContext
        val rollbackContext = adoptionContext.withLatestPlanningEvidence(liveContext)
        context = liveContext.copy(
            mediaFileId = playback.effectiveMediaFileId ?: liveContext.mediaFileId,
            versionId = playback.effectiveMediaFileId
                ?.let { "adapted:$it" }
                ?: liveContext.versionId,
            sessionId = playback.sessionId,
            subtitleTracks = playback.subtitleTracks,
            audioTrackIndex = validatedState.committed.audioTrackIndex,
            qualityPreference = validatedState.committed.qualityPreference,
        )
        transition = priorState
        committedOutputRouteGeneration = playback.outputRouteGeneration
        commitInFlight = false
        resetDuringCommit = false
        invalidateLocalMount()
        val owner = PendingLocalSelection(
            generation = localMountGeneration,
            identity = validatedState.committed.identity,
            proposedState = validatedState,
            context = requireNotNull(context),
            rollbackState = priorState,
            rollbackContext = rollbackContext,
            rollbackOutputRouteGeneration = rollbackOutputRouteGeneration,
            replayStateAfterRollback = replayValidatedIntent(
                rollbackState = priorState,
                requested = requested,
            ),
            committedPlayback = playback,
            persistOnSuccess = persistOnSuccess,
            rollbackIncludesLifecycle = rollbackIncludesLifecycle,
            exposesMountBoundary = exposesMountBoundary,
        )
        pendingLocalSelection = owner
        return owner
    }

    private suspend fun finishSuccessfulAdoption(
        requested: PendingSubtitle,
        requestedGeneration: Long,
        validatedState: SubtitleTransitionState,
        playback: TvSubtitleCommittedPlayback,
        adoptionContext: TvSubtitlePlaybackContext,
    ) {
        val rollbackOutputRouteGeneration = committedOutputRouteGeneration
        val freshRestore = pendingFreshRestore
            ?.takeIf { it.generation < validatedState.nextGeneration }
            ?.takeIf { it.identity == validatedState.committed.identity }
        val priorState = freshRestore?.priorState ?: transition.copy(pending = null)
        transition = if (validatedState.committed.identity.requiresPlayerBoundaryConfirmation()) {
            priorState
        } else {
            validatedState
        }
        committedOutputRouteGeneration = playback.outputRouteGeneration
        if (queuedMutations.isEmpty()) {
            desiredOutputRouteGeneration = playback.outputRouteGeneration
        }
        val liveContext = context
            ?.takeIf {
                it.contentId == adoptionContext.contentId &&
                    it.mediaFileId == adoptionContext.mediaFileId &&
                    it.versionId == adoptionContext.versionId
            }
            ?: adoptionContext
        val rollbackContext = adoptionContext.withLatestPlanningEvidence(liveContext)
        context = liveContext.copy(
            mediaFileId = playback.effectiveMediaFileId ?: liveContext.mediaFileId,
            versionId = playback.effectiveMediaFileId
                ?.let { "adapted:$it" }
                ?: liveContext.versionId,
            sessionId = playback.sessionId,
            subtitleTracks = playback.subtitleTracks,
            audioTrackIndex = validatedState.committed.audioTrackIndex,
            qualityPreference = validatedState.committed.qualityPreference,
        )
        refreshGeneration += 1
        failureMessage = null
        commitInFlight = false
        resetDuringCommit = false
        if (queuedMutations.isEmpty()) {
            if (validatedState.committed.identity.requiresPlayerBoundaryConfirmation()) {
                pendingFreshRestore = null
                beginLocalSelection(
                    identity = validatedState.committed.identity,
                    proposedState = validatedState,
                    selectionContext = requireNotNull(context),
                    committedPlayback = playback,
                    persistOnSuccess = compensatingGeneration != requestedGeneration,
                    rollbackState = priorState,
                    rollbackContext = rollbackContext,
                    rollbackOutputRouteGeneration = rollbackOutputRouteGeneration,
                    replayStateAfterRollback = replayValidatedIntent(
                        rollbackState = priorState,
                        requested = requested,
                    ),
                )
            } else {
                pendingFreshRestore = null
                transition = priorState
                invalidateLocalMount()
                val owner = PendingLocalSelection(
                    generation = localMountGeneration,
                    identity = validatedState.committed.identity,
                    proposedState = validatedState,
                    context = requireNotNull(context),
                    committedPlayback = playback,
                    persistOnSuccess = compensatingGeneration != requestedGeneration,
                    rollbackState = priorState,
                    rollbackContext = rollbackContext,
                    rollbackOutputRouteGeneration = rollbackOutputRouteGeneration,
                    replayStateAfterRollback = replayValidatedIntent(
                        rollbackState = priorState,
                        requested = requested,
                    ),
                )
                pendingLocalSelection = owner
                publish()
                confirmMountedCommittedSelection(owner)
            }
        } else {
            pendingFreshRestore = null
            transition = priorState
            invalidateLocalMount()
            val owner = PendingLocalSelection(
                generation = localMountGeneration,
                identity = validatedState.committed.identity,
                proposedState = validatedState,
                context = requireNotNull(context),
                rollbackState = priorState,
                rollbackContext = rollbackContext,
                rollbackOutputRouteGeneration = rollbackOutputRouteGeneration,
                replayStateAfterRollback = replayValidatedIntent(
                    rollbackState = priorState,
                    requested = requested,
                ),
                committedPlayback = playback,
                persistOnSuccess = false,
            )
            pendingLocalSelection = owner
            publish()
            requestSupersessionSettlement(owner, restoreUi = true)
        }
    }

    private fun finishSupersededAdoption() {
        commitInFlight = false
        resetDuringCommit = false
        if (finishAwaitingCommitWithoutPublication()) return
        applyQueuedMutations()
    }

    private fun finishAwaitingCommitWithoutPublication(): Boolean {
        val settlement = publicationSettlement
            ?.takeIf { it.phase == PublicationSettlementPhase.AwaitingCommit }
            ?: return false
        resetDuringCommit = false
        val reset = resetAfterSettlement
        resetAfterSettlement = null
        val discard = discardQueuedAfterSettlement
        discardQueuedAfterSettlement = false
        when {
            reset != null -> {
                queuedMutations.clear()
                resetContentNow(reset.first, reset.second)
            }
            discard -> {
                queuedMutations.clear()
                invalidateNow()
            }
            queuedMutations.isNotEmpty() -> applyQueuedMutations()
            else -> publish()
        }
        finishSettlement(settlement, true)
        return true
    }

    private suspend fun abandonCommittedPlayback(
        playback: TvSubtitleCommittedPlayback,
    ): Boolean = withContext(NonCancellable) {
            try {
                stagedPort.abandonCommitted(playback)
                true
            } catch (_: Exception) {
                // The manager owns authoritative cleanup; a cleanup transport
                // failure must not kill the serialized transaction worker.
                false
            }
    }


    /**
     * Re-points a committed Embedded identity at the artifact the server just
     * materialised for it.
     *
     * A catalog-only embedded row is picked as `Embedded(n)`, but committing it
     * makes the server produce a sidecar, and the row then comes back as a
     * server artifact. Leaving the committed identity as `Embedded(n)` makes it
     * match no row afterwards: the picker finds nothing checked and falls back
     * to marking "Off" while that subtitle is plainly on screen, and a restore
     * later has to re-derive the same thing. Adopt the row's own identity once
     * the artifact exists.
     */
    private fun SubtitleTransitionState.reconcileMaterialisedIdentity(
        playback: TvSubtitleCommittedPlayback,
    ): SubtitleTransitionState {
        val committedIdentity = committed.identity as? SubtitleIdentity.Embedded ?: return this
        val row = playback.subtitleTracks
            .firstOrNull { it.index == committedIdentity.serverIndex && it.url.isNotBlank() }
            ?: return this
        val materialised = tvSubtitleIdentity(row)
        if (materialised == committedIdentity) return this
        SubDiag.log("RECONCILE committed $committedIdentity -> $materialised")
        return copy(committed = committed.copy(identity = materialised))
    }

    private fun confirmMountedCommittedSelection(owner: PendingLocalSelection) {
        if (publicationSettlement != null) return
        val settlement = beginPublicationSettlement(
            owner = owner,
            phase = PublicationSettlementPhase.Confirming,
        )
        settlementScope.launch {
            val playback = owner.committedPlayback ?: return@launch
            val confirmed = confirmCommittedPlayback(playback)
            val currentOwner = currentSettlementOwner(settlement)
            if (currentOwner == null) {
                drainOrphanedSettlement(settlement)
                finishSettlement(settlement, false)
                return@launch
            }
            if (confirmed) {
                transition = currentOwner.proposedState.reconcileMaterialisedIdentity(playback)
                committedOutputRouteGeneration = playback.outputRouteGeneration
                invalidateLocalMount()
                failureMessage = null
                drainSettledPublication(
                    owner = currentOwner,
                    confirmed = true,
                    compensating = false,
                )
                finishSettlement(settlement, true)
                return@launch
            }

            // If the mount this settlement is compensating for actually
            // succeeded while the settlement was open, there is nothing to
            // compensate: rolling back would tear down a subtitle the user can
            // see, and re-arm the predecessor identity over it.
            if (settlement.mountedIdentity == owner.identity) {
                SubDiag.log("SETTLE skip compensation, mount confirmed ${owner.identity}")
                // Adopt it like a confirm. Returning here left the HUD stuck on
                // "Applying", the choice unpersisted, and — worse —
                // resetAfterSettlement undrained, so a later unrelated
                // settlement picked it up and reset to dead content. Everything
                // the confirmed branch does EXCEPT committedOutputRouteGeneration,
                // which must follow an actual server confirm rather than a
                // local mount observation.
                val settledOwner = currentSettlementOwner(settlement)
                if (settledOwner == null) {
                    drainOrphanedSettlement(settlement)
                    finishSettlement(settlement, true)
                    return@launch
                }
                transition = settledOwner.proposedState
                invalidateLocalMount()
                failureMessage = null
                drainSettledPublication(
                    owner = settledOwner,
                    confirmed = true,
                    compensating = false,
                )
                finishSettlement(settlement, true)
                return@launch
            }
            settlement.phase = PublicationSettlementPhase.Compensating
            if (!rollbackCommittedPlayback(playback, restoreUi = true)) {
                failureMessage = "Playback publication could not be rolled back."
                publish()
                finishSettlement(settlement, false)
                return@launch
            }
            val compensatedOwner = currentSettlementOwner(settlement)
            if (compensatedOwner == null) {
                drainOrphanedSettlement(settlement)
                finishSettlement(settlement, false)
                return@launch
            }
            restoreRollbackOwner(compensatedOwner)
            failureMessage = "The selected subtitle could not be mounted."
            drainSettledPublication(
                owner = compensatedOwner,
                confirmed = false,
                compensating = true,
            )
            finishSettlement(settlement, true)
        }
    }

    private fun requestSupersessionSettlement(
        owner: PendingLocalSelection,
        restoreUi: Boolean,
    ): CompletableDeferred<Boolean> {
        publicationSettlement?.let { return it.completion }
        val settlement = beginPublicationSettlement(
            owner = owner,
            phase = PublicationSettlementPhase.RollingBack,
        )
        settlementScope.launch {
            val settled = owner.committedPlayback?.let { playback ->
                if (owner.rollbackIncludesLifecycle) {
                    rollbackCommittedPlayback(playback, restoreUi)
                } else {
                    abandonCommittedPlayback(playback)
                }
            } ?: true
            val currentOwner = currentSettlementOwner(settlement)
            if (currentOwner == null) {
                drainOrphanedSettlement(settlement)
                finishSettlement(settlement, settled)
                return@launch
            }
            if (!settled) {
                failureMessage = "Playback publication could not be rolled back."
                publish()
                finishSettlement(settlement, false)
                return@launch
            }

            restoreRollbackOwner(currentOwner)
            failureMessage = null
            drainSettledPublication(
                owner = currentOwner,
                confirmed = false,
                compensating = false,
            )
            finishSettlement(settlement, true)
        }
        return settlement.completion
    }

    private fun requestCompensationSettlement(
        owner: PendingLocalSelection,
    ): CompletableDeferred<Boolean> {
        publicationSettlement?.let { return it.completion }
        val settlement = beginPublicationSettlement(
            owner = owner,
            phase = PublicationSettlementPhase.Compensating,
        )
        settlementScope.launch {
            val playback = owner.committedPlayback
            val rolledBack = playback == null ||
                rollbackCommittedPlayback(playback, restoreUi = true)
            val currentOwner = currentSettlementOwner(settlement)
            if (currentOwner == null) {
                drainOrphanedSettlement(settlement)
                finishSettlement(settlement, rolledBack)
                return@launch
            }
            if (!rolledBack) {
                failureMessage = "Playback publication could not be rolled back."
                publish()
                finishSettlement(settlement, false)
                return@launch
            }
            restoreRollbackOwner(currentOwner)
            failureMessage = "The selected subtitle could not be mounted."
            drainSettledPublication(
                owner = currentOwner,
                confirmed = false,
                compensating = true,
            )
            finishSettlement(settlement, true)
        }
        return settlement.completion
    }

    private fun beginPublicationSettlement(
        owner: PendingLocalSelection,
        phase: PublicationSettlementPhase,
    ): PublicationSettlement {
        localMountTimeout?.cancel()
        localMountTimeout = null
        return PublicationSettlement(
            ownerGeneration = owner.generation,
            completion = CompletableDeferred(),
            phase = phase,
        ).also { publicationSettlement = it }
    }

    private fun beginAwaitingCommitSettlement(): PublicationSettlement {
        publicationSettlement?.let { return it }
        return PublicationSettlement(
            ownerGeneration = transition.pending?.generation ?: -1L,
            completion = CompletableDeferred(),
            phase = PublicationSettlementPhase.AwaitingCommit,
        ).also { publicationSettlement = it }
    }

    private fun currentSettlementOwner(
        settlement: PublicationSettlement,
    ): PendingLocalSelection? {
        if (publicationSettlement !== settlement) return null
        return pendingLocalSelection
            ?.takeIf { it.generation == settlement.ownerGeneration }
    }

    private fun restoreRollbackOwner(owner: PendingLocalSelection) {
        context = owner.rollbackContext
        transition = owner.rollbackState
        committedOutputRouteGeneration = owner.rollbackOutputRouteGeneration
        desiredOutputRouteGeneration = owner.rollbackContext.outputRouteGeneration
        invalidateLocalMount()
    }


    /**
     * Handles a settlement whose owner has been replaced or invalidated.
     *
     * These paths used to `finishSettlement` and return, leaving
     * `resetAfterSettlement` / `discardQueuedAfterSettlement` / `queuedMutations`
     * undrained. The deferred reset then leaked and was picked up by an
     * unrelated later settlement, which reset to a dead content generation while
     * `context` still pointed at the abandoned session — so the next staged
     * replan targeted it.
     *
     * When no owner remains at all, the deferred work is ours to run. When a
     * NEWER owner exists it has already rewritten `transition`/`context`, so we
     * only clear the bookkeeping and must not write state back over it.
     */
    private fun drainOrphanedSettlement(settlement: PublicationSettlement) {
        val supersededByNewerOwner = pendingLocalSelection != null
        val reset = resetAfterSettlement
        val deferredRestore = pendingFreshRestoreAfterSettlement
        val discardQueued = discardQueuedAfterSettlement
        resetAfterSettlement = null
        pendingFreshRestoreAfterSettlement = null
        discardQueuedAfterSettlement = false

        if (supersededByNewerOwner) {
            // The newer owner owns the state; drop only what we were holding.
            if (discardQueued) queuedMutations.clear()
            return
        }
        queuedMutations.clear()
        when {
            reset != null -> resetContentNow(reset.first, reset.second)
            discardQueued -> invalidateNow()
        }
        // After the reset, not instead of it: the load path resets and restores
        // for the SAME content one line apart, so dropping the restore whenever
        // a reset was pending silently ignored the user's saved preference.
        deferredRestore?.let(::applyDeferredFreshRestore)
    }

    /**
     * Applies a deferred restore without re-entering the admission guard.
     *
     * The drain runs while the settlement it is completing is still installed,
     * so routing back through restoreFreshPreference would simply defer again —
     * and nothing would drain it a second time.
     */
    private fun applyDeferredFreshRestore(deferred: DeferredFreshRestore) {
        val current = context ?: return
        if (current.contentId != deferred.contentId ||
            current.mediaFileId != deferred.mediaFileId
        ) {
            return
        }
        if (deferred.identity != transition.committed.identity) {
            restoreFreshPreference(deferred.identity, deferred.migrationRequired)
            return
        }
        if (deferred.identity is SubtitleIdentity.ServerBurnIn) {
            if (deferred.migrationRequired) persist(transition.committed, current)
            return
        }
        beginLocalSelection(
            identity = deferred.identity,
            proposedState = transition,
            selectionContext = current,
        )
    }

    private fun drainSettledPublication(
        owner: PendingLocalSelection,
        confirmed: Boolean,
        compensating: Boolean,
    ) {
        val reset = resetAfterSettlement
        resetAfterSettlement = null
        val discardQueued = discardQueuedAfterSettlement
        discardQueuedAfterSettlement = false
        // A restore deferred by restoreFreshPreference runs once the settlement
        // it was blocked on is done. It is applied AFTER the reset below, and
        // only when it belongs to the content that reset installed.
        val deferredRestore = pendingFreshRestoreAfterSettlement
        pendingFreshRestoreAfterSettlement = null
        when {
            reset != null -> {
                queuedMutations.clear()
                resetContentNow(reset.first, reset.second)
            }
            discardQueued -> {
                queuedMutations.clear()
                invalidateNow()
            }
            queuedMutations.isNotEmpty() -> {
                if (confirmed && owner.persistOnSuccess) {
                    persist(transition.committed, owner.context)
                }
                if (!confirmed) {
                    transition = owner.replayStateAfterRollback ?: transition
                }
                applyQueuedMutations()
            }
            confirmed -> {
                publish()
                if (owner.persistOnSuccess) {
                    persist(transition.committed, owner.context)
                } else {
                    compensatingGeneration = null
                }
            }
            compensating -> stageCompensatingRestore(owner)
            else -> publish()
        }
        deferredRestore?.let(::applyDeferredFreshRestore)
    }

    private fun stageCompensatingRestore(owner: PendingLocalSelection) {
        val priorState = owner.rollbackState
        val priorIdentity = priorState.committed.identity
        if (priorIdentity.isClientOwnedSubtitle() && isLocallyMountable(priorIdentity)) {
            transition = priorState
            beginLocalRestore(priorIdentity)
            return
        }
        val restore = reduceSubtitleTransition(
            owner.proposedState,
            SelectSubtitle(priorIdentity),
        ).state.copy(committed = priorState.committed)
        val pending = restore.pending
        if (pending == null) {
            transition = priorState
            publish()
            return
        }
        transition = restore
        compensatingGeneration = pending.generation
        publish()
        stagedRequests.trySend(pending)
    }

    private fun finishSettlement(
        settlement: PublicationSettlement,
        success: Boolean,
    ) {
        if (publicationSettlement === settlement) {
            publicationSettlement = null
        }
        settlement.completion.complete(success)
    }

    private suspend fun confirmCommittedPlayback(
        playback: TvSubtitleCommittedPlayback,
    ): Boolean = withContext(NonCancellable) {
        try {
            stagedPort.confirmCommitted(playback)
            onCommittedPlaybackConfirmed(playback)
        } catch (_: Exception) {
            false
        }
    }

    private suspend fun rollbackCommittedPlayback(
        playback: TvSubtitleCommittedPlayback,
        restoreUi: Boolean,
    ): Boolean = withContext(NonCancellable) {
        val managerRolledBack = try {
            stagedPort.abandonCommitted(playback)
            true
        } catch (_: Exception) {
            false
        }
        if (!managerRolledBack) return@withContext false
        val playbackRolledBack = try {
            onCommittedPlaybackRollback(playback, restoreUi)
        } catch (_: Exception) {
            false
        }
        playbackRolledBack
    }

    private suspend fun discardCandidateBestEffort(candidate: TvStagedSubtitleCandidate) {
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
        if (
            finalIdentity.requiresLocalMountConfirmation() &&
            commitLocallyMountableSelection(finalIdentity, finalState)
        ) {
            return
        }
        invalidateLocalMount()
        transition = finalState
        publish()
        transition.pending?.let(stagedRequests::trySend)
    }

    private fun fail(generation: Long, message: String) {
        if (pendingFreshRestore?.generation == generation) {
            pendingFreshRestore = null
        }
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
            isLocallyMountable(priorIdentity) &&
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
        val selection = pendingLocalSelection
        if (ownedSelection && selection?.committedPlayback != null) {
            requestCompensationSettlement(selection)
            return
        }
        invalidateLocalMount()
        failureMessage = "The selected subtitle could not be mounted."
        publish()
    }

    private fun beginLocalSelection(
        identity: SubtitleIdentity,
        proposedState: SubtitleTransitionState,
        selectionContext: TvSubtitlePlaybackContext,
        committedPlayback: TvSubtitleCommittedPlayback? = null,
        persistOnSuccess: Boolean = true,
        rollbackState: SubtitleTransitionState = transition.copy(pending = null),
        rollbackContext: TvSubtitlePlaybackContext = selectionContext,
        rollbackOutputRouteGeneration: Long = committedOutputRouteGeneration,
        replayStateAfterRollback: SubtitleTransitionState? = null,
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
            committedPlayback = committedPlayback,
            persistOnSuccess = persistOnSuccess,
            rollbackState = rollbackState,
            rollbackContext = rollbackContext,
            rollbackOutputRouteGeneration = rollbackOutputRouteGeneration,
            replayStateAfterRollback = replayStateAfterRollback,
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
        localMountTimeout?.cancel()
        localMountTimeout = scope.launch {
            var waited = 0L
            while (true) {
                delay(LOCAL_MOUNT_TIMEOUT_MS)
                waited += LOCAL_MOUNT_TIMEOUT_MS
                // Only count against the mount once there is something to
                // mount; the onTracksChanged callback may not arrive inside the
                // window at all, so ask rather than wait to be told.
                if (hasMountableTracks() || waited >= MAX_LOCAL_MOUNT_WAIT_MS) break
            }
            SubDiag.log("mountDeadline FAIL gen=$generation")
            failLocalMount(generation)
        }
    }

    private fun invalidateLocalMount() {
        localMountGeneration += 1
        pendingLocalSelection = null
        pendingLocalRestore = null
        localMountTimeout?.cancel()
        localMountTimeout = null
    }

    private fun persist(
        committed: CommittedSubtitle,
        committedContext: TvSubtitlePlaybackContext,
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
        context: TvSubtitlePlaybackContext,
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
        SubDiag.log("SNAPSHOT applying=${snapshot.subtitleApplying} committed=${snapshot.committedIdentity} pending=${snapshot.pendingIdentity} localMount=${snapshot.localMountIdentity} failure=${snapshot.failureMessage}")
        onSnapshotChanged(snapshot)
    }

    private companion object {
        const val LOCAL_MOUNT_TIMEOUT_MS = 5_000L
        const val MAX_LOCAL_MOUNT_WAIT_MS = 30_000L
        const val PERSISTENCE_ATTEMPTS = 2
        const val PRIMARY_PERSISTENCE_TIMEOUT_MS = 5_000L
        const val DURABLE_PERSISTENCE_TIMEOUT_MS = 5_000L
    }
}

internal class PlaybackSessionManagerTvSubtitleStagedReplanPort(
    private val manager: PlaybackSessionManager,
    private val lifecycle: PlaybackSessionLifecycle,
) : TvSubtitleStagedReplanPort {
    override suspend fun stage(request: TvSubtitleStageRequest): ApiResult<TvStagedSubtitleCandidate> {
        val input = when (val mapped = request.toManagerStageInput()) {
            is ApiResult.Success -> mapped.data
            is ApiResult.Error -> return mapped
            is ApiResult.NetworkError -> return mapped
        }
        return when (
            val result = manager.stageActiveVideoSessionReplan(
                classification = request.classification,
                message = when (request.classification) {
                    "quality_changed" -> "Applying playback quality."
                    "output_route_changed" -> "Applying output route."
                    "audio_track_changed" -> "Applying audio selection."
                    else -> "Applying subtitle selection."
                },
                positionSeconds = request.positionSeconds,
                audioTrackIndex = request.audioTrackIndex,
                subtitleTrackIndex = request.subtitleTrackIndex,
                qualityPreference = request.qualityPreference,
                capabilities = input.capabilities,
                clientPlaybackContext = input.clientPlaybackContext,
            )
        ) {
            is ApiResult.Success -> {
                val handle = result.data
                val ready = handle.candidate
                ApiResult.Success(
                    TvStagedSubtitleCandidate(
                        id = handle.candidateSessionId,
                        sessionId = handle.candidateSessionId,
                        selectedAudioIndex = ready.plan.selectedTracks.audio?.index,
                        selectedSubtitleIndex = ready.plan.resolvedSelectedSubtitleIndex(),
                        subtitleMode = ready.plan.subtitle.mode,
                        hasSidecar = ready.plan.subtitle.artifact?.url?.isNotBlank() == true,
                        subtitleTracks = ready.session.subtitleUrls.orEmpty(),
                        effectiveMediaFileId = ready.session.mediaFileId.takeIf { it > 0 }
                            ?: ready.plan.effectiveMediaFileId
                            ?: request.mediaFileId,
                        selectedSubtitleIdentity = ready.selectedTvSubtitleIdentity(),
                        qualityPreference = request.qualityPreference,
                        // This is the local monotonic route generation captured
                        // by the stage request. The server's output_context_id
                        // is an opaque equality token and must never be parsed
                        // or used as a local counter.
                        outputRouteGeneration = input.outputRouteGeneration,
                        managerHandle = handle,
                    ),
                )
            }
            is ApiResult.Error -> result
            is ApiResult.NetworkError -> result
        }
    }

    override suspend fun commit(
        candidate: TvStagedSubtitleCandidate,
    ): ApiResult<TvSubtitleCommittedPlayback> {
        val handle = candidate.managerHandle ?: return ApiResult.Error(
            code = 409,
            error = "missing_staged_subtitle_handle",
            message = "The staged subtitle candidate no longer has a commit handle.",
        )
        return when (
            val result = manager.commitStagedVideoReplan(
                staged = handle,
                deferPublication = true,
            )
        ) {
            is ApiResult.Success -> ApiResult.Success(
                TvSubtitleCommittedPlayback(
                    sessionId = result.data.session.sessionId,
                    subtitleTracks = result.data.session.subtitleUrls.orEmpty(),
                    ready = result.data,
                    effectiveMediaFileId = result.data.session.mediaFileId.takeIf { it > 0 }
                        ?: result.data.plan.effectiveMediaFileId,
                    outputRouteGeneration = candidate.outputRouteGeneration,
                ),
            )
            is ApiResult.Error -> result
            is ApiResult.NetworkError -> result
        }
    }

    override suspend fun discard(candidate: TvStagedSubtitleCandidate) {
        candidate.managerHandle?.let { manager.discardStagedVideoReplan(it) }
    }

    override suspend fun confirmCommitted(playback: TvSubtitleCommittedPlayback) {
        check(
            lifecycle.settlePendingPublicationIfCurrent(
                sessionId = playback.sessionId,
                confirm = true,
                settleManager = {
                    manager.confirmVideoSessionPublication(playback.sessionId)
                },
            ),
        ) {
            "The staged subtitle session was no longer awaiting publication."
        }
    }

    override suspend fun abandonCommitted(playback: TvSubtitleCommittedPlayback) {
        val jointlyRolledBack = lifecycle.settlePendingPublicationIfCurrent(
            sessionId = playback.sessionId,
            confirm = false,
            settleManager = {
                manager.rollbackUnpublishedVideoSession(playback.sessionId)
            },
        )
        check(
            jointlyRolledBack ||
                manager.rollbackUnpublishedVideoSession(playback.sessionId),
        ) {
            "The staged subtitle session was no longer awaiting rollback."
        }
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
    this is SubtitleIdentity.ServerSidecar ||
        this is SubtitleIdentity.LocalMedia3 ||
        this is SubtitleIdentity.Downloaded ||
        this is SubtitleIdentity.Embedded

private fun SubtitleIdentity.requiresPlayerBoundaryConfirmation(): Boolean =
    this !is SubtitleIdentity.ServerBurnIn

private fun SubtitleIdentity.isClientOwnedSubtitle(): Boolean =
    this is SubtitleIdentity.LocalMedia3 || this is SubtitleIdentity.Downloaded

private fun TvStagedSubtitleCandidate.validationFailure(
    requested: org.siloserver.silo.model.playback.PendingSubtitle,
    requestedMediaFileId: Int,
    expectedSubtitleIndex: Int,
    expectedOutputRouteGeneration: Long,
): String? {
    if (outputRouteGeneration != expectedOutputRouteGeneration) {
        return "The candidate was planned for a stale output route."
    }
    if (requested.qualityPreferenceSpecified &&
        qualityPreference != requested.qualityPreference
    ) {
        return "The candidate did not preserve the requested quality."
    }
    val sameFile = effectiveMediaFileId == null || effectiveMediaFileId == requestedMediaFileId
    if (sameFile && requested.audioPreferenceSpecified &&
        selectedAudioIndex != requested.audioTrackIndex
    ) {
        return "The candidate did not select the requested audio track."
    }
    if (!sameFile) {
        val returnedIdentity = selectedSubtitleIdentity
            ?: return "The adapted candidate omitted its selected subtitle identity."
        return validationFailure(returnedIdentity)
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

private fun TvStagedSubtitleCandidate.authoritativeValidatedState(
    requested: org.siloserver.silo.model.playback.PendingSubtitle,
    requestedMediaFileId: Int,
    validated: SubtitleTransitionState,
): SubtitleTransitionState {
    val sameFile = effectiveMediaFileId == null || effectiveMediaFileId == requestedMediaFileId
    val committedIdentity = if (requested.identity.isClientOwnedSubtitle()) {
        validated.committed.identity
    } else {
        selectedSubtitleIdentity ?: validated.committed.identity
    }
    return validated.copy(
        committed = validated.committed.copy(
            identity = committedIdentity,
            audioTrackIndex = if (sameFile) {
                validated.committed.audioTrackIndex
            } else {
                selectedAudioIndex ?: validated.committed.audioTrackIndex
            },
        ),
    )
}

private fun VideoSessionStartV3.Ready.selectedTvSubtitleIdentity(): SubtitleIdentity? {
    val selected = plan.selectedTracks.subtitle ?: return SubtitleIdentity.Off
    return session.subtitleUrls.orEmpty()
        .singleOrNull { row ->
            row.serverTrackId == selected.id &&
                (selected.index == null || row.index == selected.index)
        }
        ?.let(::tvSubtitleIdentity)
}

private fun PlayerSubtitleInfo.isDownloadedTvRow(): Boolean =
    isLocalDownloadedSubtitle()

private fun PlayerSubtitleInfo.toDownloadedTvIdentity(): SubtitleIdentity.Downloaded {
    val id = requireNotNull(downloadId)
    return SubtitleIdentity.Downloaded(
        downloadId = id,
        media = org.siloserver.silo.model.playback.SubtitleMediaIdentity(
            trackId = downloadedSubtitleArtifactTrackId(id),
            label = label,
            language = language,
            codecFamily = codec,
            forced = forced ?: false,
            hearingImpaired = label
                ?.takeIf(::subtitleLabelIndicatesHearingImpaired)
                ?.let { true }
                ?: false,
        ),
    )
}

private fun TvStagedSubtitleCandidate.validationFailure(
    identity: SubtitleIdentity,
): String? = when (identity) {
    SubtitleIdentity.Off -> if (
        (selectedSubtitleIndex ?: -1) == -1 &&
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

private fun TvSubtitleCommittedPlayback.withRebasedDownloads(
    oldContext: TvSubtitlePlaybackContext,
): TvSubtitleCommittedPlayback {
    val downloadedPredicate: (PlayerSubtitleInfo) -> Boolean =
        PlayerSubtitleInfo::isLocalDownloadedSubtitle
    val downloaded = if (effectiveMediaFileId == null || effectiveMediaFileId == oldContext.mediaFileId) {
        oldContext.subtitleTracks
            .filter(downloadedPredicate)
            .map { track ->
                track.copy(url = rebaseDownloadedSubtitleUrl(track.url, sessionId))
            }
    } else {
        emptyList()
    }
    val oldByIndex = oldContext.subtitleTracks
        .filterNot(downloadedPredicate)
        .associateBy(PlayerSubtitleInfo::index)
    val authoritative = subtitleTracks
        .filterNot(downloadedPredicate)
        .distinctBy(PlayerSubtitleInfo::index)
        .map { candidate ->
            val old = oldByIndex[candidate.index] ?: return@map candidate
            candidate.copy(
                language = candidate.language ?: old.language,
                codec = candidate.codec ?: old.codec,
                label = candidate.label ?: old.label,
                forced = candidate.forced ?: old.forced,
                catalogLabel = old.catalogLabel ?: candidate.catalogLabel,
                catalogSource = old.catalogSource ?: candidate.catalogSource,
                isDefault = old.isDefault ?: candidate.isDefault,
            )
        }
    val authoritativeIndexes = authoritative.mapTo(mutableSetOf(), PlayerSubtitleInfo::index)
    return copy(
        subtitleTracks = authoritative + downloaded.filterNot { it.index in authoritativeIndexes },
    )
}
