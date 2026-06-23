package com.continuum.app.common.player

import android.util.Log
import com.continuum.app.model.playback.ChangeAudioResponse
import com.continuum.app.model.playback.ClientCodecCapabilities
import com.continuum.app.model.playback.ClientPlaybackContext
import com.continuum.app.model.playback.PlayMethod
import com.continuum.app.model.playback.PlaybackDelivery
import com.continuum.app.model.playback.PlaybackEngineKind
import com.continuum.app.model.playback.PlaybackRouteFamily
import com.continuum.app.model.playback.PlaybackStreamRequest
import com.continuum.app.model.playback.PlaybackSessionResponse
import com.continuum.app.model.playback.PlaybackRouteEventRequest
import com.continuum.app.model.playback.PlaybackTimeline
import com.continuum.app.model.playback.TranscodeStartRequest
import com.continuum.app.model.playback.TranscodeStartResponse
import com.continuum.app.network.ApiResult
import com.continuum.app.network.TokenManager
import com.continuum.app.repository.PlaybackRepository

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
    ): ApiResult<PlaybackSessionResponse> {
        Log.i(
            TAG,
            "startSession fileId=$fileId profileId=$profileId " +
                "video=${capabilities.codecsVideo} audio=${capabilities.codecsAudio} " +
                "containers=${capabilities.containers} max=${capabilities.maxResolution} " +
                "hdr=${capabilities.hdr} hdrDetails=${capabilities.hdrDetails} " +
                "passthrough=${capabilities.audioPassthrough} " +
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
    open suspend fun stopSession(sessionId: String): ApiResult<Unit> =
        playbackRepository.stopPlayback(sessionId)

    /**
     * Requests transcoding with specific parameters.
     * Used when switching quality mid-playback or when the server chose transcode
     * and the encoding needs to be started explicitly.
     */
    suspend fun startTranscode(request: TranscodeStartRequest): ApiResult<TranscodeStartResponse> =
        playbackRepository.startTranscode(request)

    /**
     * Switches the audio track mid-stream.
     * May trigger a new transcode if the server needs to re-mux.
     *
     * [position] is the current playback position in seconds. For TRANSCODE
     * sessions the server uses it as the re-seek point; omitting it would cause
     * the transcode to restart from 0.
     */
    suspend fun changeAudio(
        sessionId: String,
        audioTrackIndex: Int,
        position: Double? = null,
    ): ApiResult<ChangeAudioResponse> =
        playbackRepository.changeAudio(sessionId, audioTrackIndex, position)

    suspend fun reportRouteEvent(
        sessionId: String,
        request: PlaybackRouteEventRequest,
    ): ApiResult<Unit> =
        playbackRepository.reportRouteEvent(sessionId, request)

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
            subtitleBurnIn = false,
        )
        return when (val r = playbackRepository.startTranscode(request)) {
            is ApiResult.Success -> {
                val tc = r.data
                ApiResult.Success(
                    session.copy(
                        sessionId = tc.sessionId,
                        playMethod = if (isRemux) {
                            com.continuum.app.model.playback.PlayMethod.REMUX
                        } else {
                            com.continuum.app.model.playback.PlayMethod.TRANSCODE
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
                                        com.continuum.app.model.playback.PlayMethod.REMUX
                                    } else {
                                        com.continuum.app.model.playback.PlayMethod.TRANSCODE
                                    },
                                ),
                                timeline = PlaybackTimeline(
                                    playerStartSeconds = tc.playerStartSeconds,
                                    streamOriginSeconds = tc.streamOriginSeconds,
                                    timelineOffsetSeconds = tc.timelineOffsetSeconds,
                                    canSeekAnywhere = tc.canSeekAnywhere,
                                ),
                                degradationWarnings = plan.degradationWarnings +
                                    com.continuum.app.model.playback.PlaybackDegradationWarning(
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
}
