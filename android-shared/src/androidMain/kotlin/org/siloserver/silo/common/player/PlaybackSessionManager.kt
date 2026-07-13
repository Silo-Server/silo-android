package org.siloserver.silo.common.player

import android.os.SystemClock
import android.util.Log
import org.siloserver.silo.model.playback.ClientCodecCapabilities
import org.siloserver.silo.model.playback.ClientPlaybackContext
import org.siloserver.silo.model.playback.PlayMethod
import org.siloserver.silo.model.playback.PlaybackDelivery
import org.siloserver.silo.model.playback.PlaybackEngineKind
import org.siloserver.silo.model.playback.PlaybackRouteFamily
import org.siloserver.silo.model.playback.PlaybackStreamRequest
import org.siloserver.silo.model.playback.PlaybackSessionResponse
import org.siloserver.silo.model.playback.PlaybackTimeline
import org.siloserver.silo.model.playback.TranscodeStartRequest
import org.siloserver.silo.model.playback.TranscodeStartResponse
import org.siloserver.silo.model.playback.PlaybackStartRequestV3
import org.siloserver.silo.model.playback.PlaybackV3Validation
import org.siloserver.silo.model.playback.SubtitleFidelityPreference
import org.siloserver.silo.model.playback.planAttemptKey
import org.siloserver.silo.model.playback.validateForMedia3
import org.siloserver.silo.model.playback.PlaybackFailureV3
import org.siloserver.silo.model.playback.PlaybackReplanRequestV3
import org.siloserver.silo.model.playback.PlaybackRouteEventV3
import org.siloserver.silo.model.playback.SelectedPlaybackTracksV3
import org.siloserver.silo.model.playback.PlaybackTrackIdentityV3
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.network.TokenManager
import org.siloserver.silo.repository.PlaybackRepository
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.siloserver.silo.common.player.audio.PassthroughSuppressionRegistry

/**
 * Manages the playback session lifecycle: creation, progress reporting,
 * audio track switching, transcoding, and teardown.
 *
 * Wraps [PlaybackRepository] and adds token/server-URL resolution via [TokenManager].
 */
open class PlaybackSessionManager(
    private val playbackRepository: PlaybackRepository,
    private val tokenManager: TokenManager,
) {
    private data class ActiveVideoAttempt(
        val fileId: Int,
        val profileId: String,
        val capabilities: ClientCodecCapabilities,
        val context: ClientPlaybackContext,
        val playbackAttemptId: String,
        val qualityPreference: String,
        val sessionId: String,
        val plan: org.siloserver.silo.model.playback.PlaybackPlanV3,
        val planAttemptId: String,
        val planAttemptKey: String,
        val localMutations: List<String>,
        val attemptedPlanKeys: List<String>,
        val attemptCount: Int,
        val startedAtElapsedRealtimeMs: Long,
        val firstFrameReported: Boolean,
    )

    private val videoAttemptMutex = Mutex()
    private val telemetryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var activeVideoAttempt: ActiveVideoAttempt? = null

    suspend fun startVideoSessionV3(
        fileId: Int,
        profileId: String,
        capabilities: ClientCodecCapabilities,
        clientPlaybackContext: ClientPlaybackContext,
        audioTrackIndex: Int?,
        subtitleTrackIndex: Int?,
        qualityPreference: String?,
        startPosition: Double?,
        subtitleFidelityPreference: SubtitleFidelityPreference = SubtitleFidelityPreference.PRESERVE,
    ): ApiResult<VideoSessionStartV3> {
        val playbackAttemptId = UUID.randomUUID().toString()
        val request = PlaybackStartRequestV3(
            fileId = fileId,
            profileId = profileId,
            playbackAttemptId = playbackAttemptId,
            qualityPreference = qualityPreference?.lowercase() ?: "auto",
            subtitleFidelityPreference = subtitleFidelityPreference,
            startPosition = startPosition,
            audioTrackId = audioTrackIndex?.let { stableTrackId(fileId, "audio", it) },
            audioTrackIndex = audioTrackIndex,
            subtitleTrackId = subtitleTrackIndex?.takeIf { it >= 0 }
                ?.let { stableTrackId(fileId, "subtitle", it) },
            subtitleTrackIndex = subtitleTrackIndex,
            outputRouteGeneration = clientPlaybackContext.output.outputRouteGeneration,
            capabilities = capabilities,
            clientPlaybackContext = clientPlaybackContext,
        )
        return when (val result = playbackRepository.startPlaybackV3(request)) {
            is ApiResult.Success -> when (val validated = result.data.validateForMedia3()) {
                is PlaybackV3Validation.Playable -> {
                    val planAttemptId = UUID.randomUUID().toString()
                    val planAttemptKey = validated.plan.planAttemptKey(request.outputRouteGeneration)
                    activeVideoAttempt = ActiveVideoAttempt(
                        fileId = fileId,
                        profileId = profileId,
                        capabilities = capabilities,
                        context = clientPlaybackContext,
                        playbackAttemptId = playbackAttemptId,
                        qualityPreference = request.qualityPreference,
                        sessionId = validated.sessionId,
                        plan = validated.plan,
                        planAttemptId = planAttemptId,
                        planAttemptKey = planAttemptKey,
                        localMutations = emptyList(),
                        attemptedPlanKeys = listOf(planAttemptKey),
                        attemptCount = 1,
                        startedAtElapsedRealtimeMs = SystemClock.elapsedRealtime(),
                        firstFrameReported = false,
                    )
                    PassthroughSuppressionRegistry.beginAttempt(planAttemptKey)
                    reportActiveVideoEvent("plan_selected")
                    ApiResult.Success(
                        VideoSessionStartV3.Ready(
                            session = validated.plan.toSessionResponse(validated.sessionId, profileId, fileId),
                            plan = validated.plan,
                            playbackAttemptId = playbackAttemptId,
                            planAttemptId = planAttemptId,
                            planAttemptKey = planAttemptKey,
                        ),
                    )
                }
                is PlaybackV3Validation.Terminal -> {
                    activeVideoAttempt = null
                    emitRouteEvent(
                        PlaybackRouteEventV3(
                            playbackAttemptId = playbackAttemptId,
                            sessionId = result.data.sessionId,
                            event = "terminal",
                            fallbackReason = validated.reason,
                            outputRouteGeneration = request.outputRouteGeneration,
                        ),
                    )
                    (result.data.playbackPlan?.sessionId ?: result.data.sessionId)
                        ?.let { playbackRepository.stopPlayback(it) }
                    ApiResult.Success(
                        VideoSessionStartV3.Terminal(validated.reason, validated.message, validated.retryable),
                    )
                }
                is PlaybackV3Validation.Incompatible -> {
                    activeVideoAttempt = null
                    validated.allocatedSessionId?.let { playbackRepository.stopPlayback(it) }
                    ApiResult.Success(VideoSessionStartV3.ServerUpgradeRequired)
                }
                is PlaybackV3Validation.ReplanRequired -> {
                    // Decode stale engine enums, but never execute them. Preserve
                    // the allocated session and give the v3 planner exactly one
                    // opportunity to replace the route with a Media3 plan.
                    val planAttemptId = UUID.randomUUID().toString()
                    val key = validated.plan.planAttemptKey(request.outputRouteGeneration)
                    activeVideoAttempt = ActiveVideoAttempt(
                        fileId = fileId,
                        profileId = profileId,
                        capabilities = capabilities,
                        context = clientPlaybackContext,
                        playbackAttemptId = playbackAttemptId,
                        qualityPreference = request.qualityPreference,
                        sessionId = validated.sessionId,
                        plan = validated.plan,
                        planAttemptId = planAttemptId,
                        planAttemptKey = key,
                        localMutations = emptyList(),
                        attemptedPlanKeys = listOf(key),
                        attemptCount = 1,
                        startedAtElapsedRealtimeMs = SystemClock.elapsedRealtime(),
                        firstFrameReported = false,
                    )
                    PassthroughSuppressionRegistry.beginAttempt(key)
                    replanActiveVideoSession(
                        classification = validated.reason,
                        message = "The server returned a legacy player route.",
                        positionSeconds = startPosition ?: 0.0,
                        audioTrackIndex = audioTrackIndex,
                        subtitleTrackIndex = subtitleTrackIndex,
                    )
                }
            }
            is ApiResult.Error -> result
            is ApiResult.NetworkError -> result
        }
    }

    suspend fun replanActiveVideoSession(
        classification: String,
        message: String? = null,
        positionSeconds: Double,
        audioTrackIndex: Int?,
        subtitleTrackIndex: Int?,
        decoderName: String? = null,
        diagnostics: Map<String, String> = emptyMap(),
        qualityPreference: String? = null,
        capabilities: ClientCodecCapabilities? = null,
        clientPlaybackContext: ClientPlaybackContext? = null,
    ): ApiResult<VideoSessionStartV3> = videoAttemptMutex.withLock {
        val active = activeVideoAttempt ?: return@withLock ApiResult.Error(
            code = 409,
            error = "playback_attempt_not_active",
            message = "No protocol-v3 playback attempt is active.",
        )
        val currentCapabilities = capabilities ?: active.capabilities
        val currentContext = clientPlaybackContext ?: active.context
        val failedKey = active.planAttemptKey
        val attemptedKeys = (active.attemptedPlanKeys + failedKey).distinct()
        val invalidation = classification in setOf(
            "audio_track_changed",
            "subtitle_track_changed",
            "quality_changed",
            "output_route_changed",
        )
        emitRouteEvent(
            PlaybackRouteEventV3(
                playbackAttemptId = active.playbackAttemptId,
                sessionId = active.sessionId,
                planId = active.plan.planId,
                planAttemptId = active.planAttemptId,
                planAttemptKey = failedKey,
                event = if (invalidation) "plan_invalidated" else "plan_failed",
                failureClassification = classification,
                outputRouteGeneration = currentContext.output.outputRouteGeneration,
                diagnostics = diagnostics + mapOfNotNull("decoder_name" to decoderName),
            ),
        )
        val request = PlaybackReplanRequestV3(
            playbackAttemptId = active.playbackAttemptId,
            replanRequestId = UUID.randomUUID().toString(),
            failedPlanId = active.plan.planId,
            planAttemptId = active.planAttemptId,
            planAttemptKey = failedKey,
            attemptedPlanKeys = attemptedKeys,
            attemptCount = active.attemptCount,
            qualityPreference = qualityPreference?.lowercase() ?: active.qualityPreference,
            positionSeconds = positionSeconds,
            outputRouteGeneration = currentContext.output.outputRouteGeneration,
            selectedTracks = SelectedPlaybackTracksV3(
                audio = selectedTrackIdentity(active, "audio", audioTrackIndex, active.plan.selectedTracks.audio),
                subtitle = subtitleTrackIndex?.takeIf { it >= 0 }
                    ?.let { selectedTrackIdentity(active, "subtitle", it, active.plan.selectedTracks.subtitle) },
            ),
            failure = PlaybackFailureV3(classification, message, decoderName),
            capabilities = currentCapabilities,
            clientPlaybackContext = currentContext,
        )
        when (val result = playbackRepository.replanPlaybackV3(active.sessionId, request)) {
            is ApiResult.Success -> when (val validated = result.data.validateForMedia3()) {
                is PlaybackV3Validation.Playable -> {
                    val nextKey = validated.plan.planAttemptKey(currentContext.output.outputRouteGeneration)
                    if (nextKey in attemptedKeys) {
                        if (validated.sessionId != active.sessionId) {
                            playbackRepository.stopPlayback(validated.sessionId)
                        }
                        activeVideoAttempt = null
                        return@withLock ApiResult.Success(
                            VideoSessionStartV3.Terminal(
                                "replan_loop_detected",
                                "The server returned a playback plan that already failed on this output route.",
                                false,
                            ),
                        )
                    }
                    val nextAttemptId = UUID.randomUUID().toString()
                    val next = active.copy(
                        sessionId = validated.sessionId,
                        plan = validated.plan,
                        planAttemptId = nextAttemptId,
                        planAttemptKey = nextKey,
                        localMutations = emptyList(),
                        attemptedPlanKeys = attemptedKeys + nextKey,
                        attemptCount = active.attemptCount + 1,
                        qualityPreference = request.qualityPreference,
                        capabilities = currentCapabilities,
                        context = currentContext,
                        startedAtElapsedRealtimeMs = SystemClock.elapsedRealtime(),
                        firstFrameReported = false,
                    )
                    activeVideoAttempt = next
                    PassthroughSuppressionRegistry.beginAttempt(nextKey)
                    if (validated.sessionId != active.sessionId) playbackRepository.stopPlayback(active.sessionId)
                    emitRouteEvent(
                        PlaybackRouteEventV3(
                            playbackAttemptId = active.playbackAttemptId,
                            sessionId = validated.sessionId,
                            planId = validated.plan.planId,
                            planAttemptId = nextAttemptId,
                            planAttemptKey = nextKey,
                            event = "plan_selected",
                            fallbackReason = classification,
                            outputRouteGeneration = currentContext.output.outputRouteGeneration,
                        ),
                    )
                    ApiResult.Success(
                        VideoSessionStartV3.Ready(
                            session = validated.plan.toSessionResponse(validated.sessionId, active.profileId, active.fileId),
                            plan = validated.plan,
                            playbackAttemptId = active.playbackAttemptId,
                            planAttemptId = nextAttemptId,
                            planAttemptKey = nextKey,
                        ),
                    )
                }
                is PlaybackV3Validation.Terminal -> {
                    reportActiveVideoEvent(
                        event = "terminal",
                        diagnostics = mapOf("reason" to validated.reason),
                    )
                    activeVideoAttempt = null
                    listOfNotNull(active.sessionId, result.data.sessionId).distinct()
                        .forEach { playbackRepository.stopPlayback(it) }
                    ApiResult.Success(VideoSessionStartV3.Terminal(validated.reason, validated.message, validated.retryable))
                }
                is PlaybackV3Validation.Incompatible -> {
                    activeVideoAttempt = null
                    validated.allocatedSessionId?.let { playbackRepository.stopPlayback(it) }
                    ApiResult.Success(VideoSessionStartV3.ServerUpgradeRequired)
                }
                is PlaybackV3Validation.ReplanRequired -> {
                    activeVideoAttempt = null
                    listOf(active.sessionId, validated.sessionId).distinct()
                        .forEach { playbackRepository.stopPlayback(it) }
                    ApiResult.Success(
                        VideoSessionStartV3.Terminal(
                            "unsupported_legacy_engine",
                            "The server could not provide a Media3 playback route.",
                            false,
                        ),
                    )
                }
            }
            is ApiResult.Error -> result
            is ApiResult.NetworkError -> result
        }
    }

    private fun mapOfNotNull(vararg values: Pair<String, String?>): Map<String, String> =
        values.mapNotNull { (key, value) -> value?.let { key to it } }.toMap()

    private fun emitRouteEvent(event: PlaybackRouteEventV3) {
        telemetryScope.launch {
            playbackRepository.reportRouteEventV3(event)
        }
    }

    @Synchronized
    fun reportActiveVideoEvent(
        event: String,
        diagnostics: Map<String, String> = emptyMap(),
    ) {
        val active = activeVideoAttempt ?: return
        emitRouteEvent(
            PlaybackRouteEventV3(
                playbackAttemptId = active.playbackAttemptId,
                sessionId = active.sessionId,
                planId = active.plan.planId,
                planAttemptId = active.planAttemptId,
                planAttemptKey = active.planAttemptKey,
                event = event,
                outputRouteGeneration = active.context.output.outputRouteGeneration,
                diagnostics = diagnostics,
            ),
        )
    }

    @Synchronized
    fun reportFirstVideoFrame(stats: PlayerStatsSnapshot) {
        val active = activeVideoAttempt ?: return
        if (active.firstFrameReported) return
        activeVideoAttempt = active.copy(firstFrameReported = true)
        val firstFrameMs = SystemClock.elapsedRealtime() - active.startedAtElapsedRealtimeMs
        emitRouteEvent(
            PlaybackRouteEventV3(
                playbackAttemptId = active.playbackAttemptId,
                sessionId = active.sessionId,
                planId = active.plan.planId,
                planAttemptId = active.planAttemptId,
                planAttemptKey = active.planAttemptKey,
                event = "first_frame",
                outputRouteGeneration = active.context.output.outputRouteGeneration,
                diagnostics = stats.firstFrameDiagnostics(firstFrameMs),
            ),
        )
    }

    private fun stableTrackId(fileId: Int, kind: String, index: Int): String =
        "file:$fileId:$kind:$index"

    private fun selectedTrackIdentity(
        active: ActiveVideoAttempt,
        kind: String,
        index: Int?,
        selected: PlaybackTrackIdentityV3?,
    ): PlaybackTrackIdentityV3? {
        if (index == null) return null
        return selected?.takeIf { it.index == index }
            ?: PlaybackTrackIdentityV3(stableTrackId(active.fileId, kind, index), index)
    }

    @Synchronized
    fun trySingleLocalPcmRetry(mime: String, channels: Int): Boolean {
        val active = activeVideoAttempt ?: return false
        val mutation = "pcm:${mime.lowercase()}:${channels.coerceAtLeast(0)}"
        if (active.localMutations.any { it.startsWith("pcm:") }) return false
        val mutations = active.localMutations + mutation
        val key = active.plan.planAttemptKey(active.context.output.outputRouteGeneration, mutations)
        activeVideoAttempt = active.copy(
            planAttemptKey = key,
            localMutations = mutations,
            attemptedPlanKeys = (active.attemptedPlanKeys + key).distinct(),
        )
        PassthroughSuppressionRegistry.beginAttempt(key)
        return PassthroughSuppressionRegistry.suppressForSinglePcmRetry(mime, channels)
    }

    @Synchronized
    fun recordTransportReopen(): Boolean {
        val active = activeVideoAttempt ?: return false
        val mutation = "transport_reopen"
        if (mutation in active.localMutations) return false
        val mutations = active.localMutations + mutation
        val key = active.plan.planAttemptKey(active.context.output.outputRouteGeneration, mutations)
        activeVideoAttempt = active.copy(
            planAttemptKey = key,
            localMutations = mutations,
            attemptedPlanKeys = (active.attemptedPlanKeys + key).distinct(),
        )
        PassthroughSuppressionRegistry.beginAttempt(key)
        return true
    }

    /**
     * Starts a new playback session for the given file.
     * The server decides the play method (direct, remux, transcode).
     */
    open suspend fun startSession(
        fileId: Int,
        profileId: String,
        capabilities: ClientCodecCapabilities,
        audioTrackIndex: Int? = null,
        qualityPreference: String? = null,
        startPosition: Double? = null,
        disableProgressPersistence: Boolean = false,
    ): ApiResult<PlaybackSessionResponse> = startSessionInternal(
        fileId = fileId,
        profileId = profileId,
        capabilities = capabilities,
        audioTrackIndex = audioTrackIndex,
        subtitleTrackIndex = null,
        qualityPreference = qualityPreference,
        startPosition = startPosition,
        clientPlaybackContext = null,
        preserveDirectAudioSelection = false,
        playMethod = null,
        disableProgressPersistence = disableProgressPersistence,
    )

    suspend fun startSessionV2(
        fileId: Int,
        profileId: String,
        capabilities: ClientCodecCapabilities,
        audioTrackIndex: Int? = null,
        subtitleTrackIndex: Int? = null,
        qualityPreference: String? = null,
        startPosition: Double? = null,
        clientPlaybackContext: ClientPlaybackContext? = null,
        preserveDirectAudioSelection: Boolean = false,
        playMethod: PlayMethod? = null,
    ): ApiResult<PlaybackSessionResponse> = startSessionInternal(
        fileId = fileId,
        profileId = profileId,
        capabilities = capabilities,
        audioTrackIndex = audioTrackIndex,
        subtitleTrackIndex = subtitleTrackIndex,
        qualityPreference = qualityPreference,
        startPosition = startPosition,
        clientPlaybackContext = clientPlaybackContext,
        preserveDirectAudioSelection = preserveDirectAudioSelection,
        playMethod = playMethod,
        disableProgressPersistence = false,
    )

    private suspend fun startSessionInternal(
        fileId: Int,
        profileId: String,
        capabilities: ClientCodecCapabilities,
        audioTrackIndex: Int?,
        subtitleTrackIndex: Int?,
        qualityPreference: String?,
        startPosition: Double?,
        clientPlaybackContext: ClientPlaybackContext?,
        preserveDirectAudioSelection: Boolean,
        playMethod: PlayMethod?,
        disableProgressPersistence: Boolean,
    ): ApiResult<PlaybackSessionResponse> {
        Log.i(
            TAG,
            "startSession fileId=$fileId profileId=$profileId " +
                "video=${capabilities.codecsVideo} audio=${capabilities.codecsAudio} " +
                "containers=${capabilities.containers} max=${capabilities.maxResolution} " +
                "hdr=${capabilities.hdr} hdrDetails=${capabilities.hdrDetails} " +
                "passthrough=${capabilities.audioPassthrough} " +
                "qualityPreference=$qualityPreference " +
                "preserveDirectAudioSelection=$preserveDirectAudioSelection " +
                "requestedPlayMethod=$playMethod",
        )
        val result = playbackRepository.startPlayback(
            fileId = fileId,
            profileId = profileId,
            audioTrackIndex = audioTrackIndex,
            subtitleTrackIndex = subtitleTrackIndex,
            qualityPreference = qualityPreference,
            startPosition = startPosition,
            capabilities = capabilities,
            clientPlaybackContext = clientPlaybackContext,
            preserveDirectAudioSelection = preserveDirectAudioSelection,
            playMethod = playMethod,
            disableProgressPersistence = disableProgressPersistence,
        )
        when (result) {
            is ApiResult.Success -> Log.i(
                TAG,
                "startSession -> playMethod=${result.data.playMethod} " +
                    "playbackInfo=${result.data.playbackInfo} " +
                    "plan=${result.data.playbackPlan?.planId}:${result.data.playbackPlan?.engine}",
            )
            is ApiResult.Error -> Log.w(TAG, "startSession error: ${result.code} ${result.message}")
            is ApiResult.NetworkError -> Log.w(TAG, "startSession network error: ${result.exception}")
        }
        return result
    }

    companion object {
        private const val TAG = "PlaybackSessionMgr"
    }

    /**
     * Reports the current playback position to the server.
     * Called periodically (every ~10 seconds) during active playback.
     */
    open suspend fun reportProgress(
        sessionId: String,
        position: Double,
        isPaused: Boolean,
    ): ApiResult<Unit> =
        playbackRepository.updateProgress(sessionId, position, isPaused)

    /**
     * Stops an active playback session.
     * Must be called when exiting the player or when playback completes.
     */
    open suspend fun stopSession(sessionId: String): ApiResult<Unit> {
        if (activeVideoAttempt?.sessionId == sessionId) {
            reportActiveVideoEvent("stopped")
            activeVideoAttempt = null
        }
        return playbackRepository.stopPlayback(sessionId)
    }

    /**
     * Requests transcoding with specific parameters.
     * Used when switching quality mid-playback or when the server chose transcode
     * and the encoding needs to be started explicitly.
     */
    suspend fun startTranscode(request: TranscodeStartRequest): ApiResult<TranscodeStartResponse> =
        playbackRepository.startTranscode(request)

    /** Returns the current access token for stream authentication. */
    suspend fun getAccessToken(): String? = tokenManager.getAccessToken()

    /** Returns the server base URL for resolving relative stream URLs. */
    suspend fun getServerUrl(): String = tokenManager.getServerUrl()

    enum class TranscodeMode { REMUX, FULL }

    /**
     * Issue a `TranscodeStartRequest` for a fallback path — either because the
     * server chose REMUX / TRANSCODE up front (`handleSessionStarted`) or
     * because client-side preflight determined direct play was impossible
     * ([PlaybackPreflightListener] in PR 8). Folds the resulting HLS URL back
     * into a [PlaybackSessionResponse] so both VMs can treat the result like
     * any other session start.
     *
     * Does **not** stop the caller's current session — ViewModels handle that
     * alongside their state cleanup, which is the point they also tear down
     * progress reporting.
     */
    suspend fun startTranscodeFallback(
        session: PlaybackSessionResponse,
        seekSeconds: Double,
        resolution: String,
        mode: TranscodeMode,
        audioTrackIndex: Int? = null,
        subtitleTrackIndex: Int? = null,
    ): ApiResult<PlaybackSessionResponse> {
        val isRemux = mode == TranscodeMode.REMUX
        val request = TranscodeStartRequest(
            sessionId = session.sessionId,
            seekSeconds = seekSeconds,
            targetResolution = if (isRemux) "" else resolution,
            targetCodecVideo = if (isRemux) "copy" else "h264",
            // REMUX copies audio to preserve passthrough codecs
            // (EAC3/TrueHD/DTS). Forcing AAC clobbers the play-method
            // decision.
            targetCodecAudio = if (isRemux) "copy" else "aac",
            targetBitrateKbps = if (isRemux) 0 else 8000,
            segmentDuration = 2,
            audioTrackIndex = audioTrackIndex,
            subtitleTrackIndex = subtitleTrackIndex,
            subtitleBurnIn = shouldBurnStyledSubtitle(
                isRemux = isRemux,
                subtitleTrackIndex = subtitleTrackIndex,
                subtitleCodec = session.playbackPlan?.source?.subtitleCodec,
            ),
        )
        Log.i(
            TAG,
            "startTranscodeFallback session=${session.sessionId} mode=$mode seekSeconds=$seekSeconds " +
                "targetResolution=${request.targetResolution} " +
                "targetCodecVideo=${request.targetCodecVideo} " +
                "targetCodecAudio=${request.targetCodecAudio} " +
                "targetBitrateKbps=${request.targetBitrateKbps} " +
                "audioTrackIndex=$audioTrackIndex subtitleTrackIndex=$subtitleTrackIndex",
        )
        return when (val r = playbackRepository.startTranscode(request)) {
            is ApiResult.Success -> {
                val tc = r.data
                ApiResult.Success(
                    session.copy(
                        sessionId = tc.sessionId,
                        playMethod = if (isRemux) {
                            org.siloserver.silo.model.playback.PlayMethod.REMUX
                        } else {
                            org.siloserver.silo.model.playback.PlayMethod.TRANSCODE
                        },
                        streamUrl = tc.manifestUrl,
                        durationSeconds = tc.durationSeconds ?: session.durationSeconds,
                        position = tc.playerStartSeconds,
                        playbackPlan = session.playbackPlan?.let { plan ->
                            plan.copy(
                                delivery = if (isRemux) {
                                    PlaybackDelivery.SERVER_REMUX_HLS
                                } else {
                                    PlaybackDelivery.SERVER_TRANSCODE_HLS
                                },
                                engine = PlaybackEngineKind.MEDIA3_HLS,
                                routeFamily = PlaybackRouteFamily.SERVER_ADAPTIVE,
                                stream = PlaybackStreamRequest(
                                    url = tc.manifestUrl,
                                    streamType = "hls",
                                    playMethod = if (isRemux) {
                                        org.siloserver.silo.model.playback.PlayMethod.REMUX
                                    } else {
                                        org.siloserver.silo.model.playback.PlayMethod.TRANSCODE
                                    },
                                ),
                                timeline = PlaybackTimeline(
                                    playerStartSeconds = tc.playerStartSeconds,
                                    streamOriginSeconds = tc.streamOriginSeconds,
                                    timelineOffsetSeconds = tc.timelineOffsetSeconds,
                                    canSeekAnywhere = tc.canSeekAnywhere,
                                ),
                                degradationWarnings = plan.degradationWarnings +
                                    org.siloserver.silo.model.playback.PlaybackDegradationWarning(
                                        code = if (isRemux) {
                                            "server_remux_fallback"
                                        } else {
                                            "server_transcode_fallback"
                                        },
                                        message = if (isRemux) {
                                            "Playback fell back to server remux."
                                        } else {
                                            "Playback fell back to server transcode."
                                        },
                                    ),
                            )
                        },
                    ),
                )
            }
            is ApiResult.Error -> r
            is ApiResult.NetworkError -> r
        }
    }

    suspend fun startTranscodeFallbackRecoveringMissingSession(
        session: PlaybackSessionResponse,
        seekSeconds: Double,
        resolution: String,
        mode: TranscodeMode,
        audioTrackIndex: Int? = null,
        subtitleTrackIndex: Int? = null,
        renewSession: suspend () -> ApiResult<PlaybackSessionResponse>,
    ): ApiResult<PlaybackSessionResponse> {
        val first = startTranscodeFallback(
            session = session,
            seekSeconds = seekSeconds,
            resolution = resolution,
            mode = mode,
            audioTrackIndex = audioTrackIndex,
            subtitleTrackIndex = subtitleTrackIndex,
        )
        if (!first.isPlaybackSessionMissingError()) return first

        Log.w(TAG, "Fallback session missing; renewing playback session before retry")
        return when (val renewed = renewSession()) {
            is ApiResult.Success -> {
                val retry = startTranscodeFallback(
                    session = renewed.data,
                    seekSeconds = seekSeconds,
                    resolution = resolution,
                    mode = mode,
                    audioTrackIndex = audioTrackIndex,
                    subtitleTrackIndex = subtitleTrackIndex,
                )
                if (retry !is ApiResult.Success) {
                    stopSession(renewed.data.sessionId)
                }
                retry
            }
            is ApiResult.Error -> renewed
            is ApiResult.NetworkError -> renewed
        }
    }
}

internal fun ApiResult<*>.isPlaybackSessionMissingError(): Boolean {
    val error = this as? ApiResult.Error ?: return false
    return error.code == 404 &&
        (error.error == "playback_session_not_found" || error.message == "Playback session not found")
}

/**
 * A styled subtitle selected for a full server transcode is burned in. Remux
 * has no video encode surface, and plain text stays client-rendered.
 */
internal fun shouldBurnStyledSubtitle(
    isRemux: Boolean,
    subtitleTrackIndex: Int?,
    subtitleCodec: String?,
): Boolean =
    !isRemux &&
        subtitleTrackIndex != null &&
        subtitleCodec?.trim()?.lowercase() in setOf("ass", "ssa")
