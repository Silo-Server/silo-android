package org.siloserver.silo.common.player

import org.siloserver.silo.model.playback.PlayMethod
import org.siloserver.silo.model.playback.PlaybackDelivery
import org.siloserver.silo.model.playback.PlaybackEngineKind
import org.siloserver.silo.model.playback.PlaybackExecutionPlan
import org.siloserver.silo.model.playback.PlaybackPlanV3
import org.siloserver.silo.model.playback.PlaybackRouteFamily
import org.siloserver.silo.model.playback.PlaybackSessionResponse
import org.siloserver.silo.model.playback.PlaybackSourceMetadata
import org.siloserver.silo.model.playback.PlaybackStreamRequest
import org.siloserver.silo.model.playback.PlaybackSubtitleModeV3
import org.siloserver.silo.model.playback.PlaybackTimeline
import org.siloserver.silo.model.playback.PlayerSubtitleInfo
import org.siloserver.silo.model.playback.SelectedPlaybackTracks

sealed interface VideoSessionStartV3 {
    data class Ready(
        val session: PlaybackSessionResponse,
        val plan: PlaybackPlanV3,
        val playbackAttemptId: String,
        val planAttemptId: String,
        val planAttemptKey: String,
    ) : VideoSessionStartV3

    data class Terminal(
        val reason: String,
        val message: String,
        val retryable: Boolean,
    ) : VideoSessionStartV3

    data object ServerUpgradeRequired : VideoSessionStartV3
}

internal fun PlaybackPlanV3.toSessionResponse(
    sessionId: String,
    profileId: String,
    mediaFileId: Int,
): PlaybackSessionResponse {
    val playMethod = when (delivery) {
        PlaybackDelivery.ORIGINAL_HTTP -> PlayMethod.DIRECT
        PlaybackDelivery.SERVER_REMUX_PROGRESSIVE,
        PlaybackDelivery.SERVER_REMUX_HLS,
        -> PlayMethod.REMUX
        PlaybackDelivery.SERVER_TRANSCODE_HLS -> PlayMethod.TRANSCODE
        PlaybackDelivery.CLIENT_LOCAL_NORMALIZATION -> PlayMethod.REMUX
    }
    val subtitles = subtitle.artifact?.takeIf {
        subtitle.mode == PlaybackSubtitleModeV3.CONVERT || subtitle.mode == PlaybackSubtitleModeV3.RENDER
    }?.let { artifact ->
        listOf(
            PlayerSubtitleInfo(
                index = selectedTracks.subtitle?.index ?: 0,
                codec = artifact.format,
                label = "Server subtitle",
                source = "server_artifact",
                url = artifact.url,
            ),
        )
    }
    val routeFamily = when (delivery) {
        PlaybackDelivery.ORIGINAL_HTTP -> PlaybackRouteFamily.PLATFORM_NATIVE
        PlaybackDelivery.SERVER_REMUX_PROGRESSIVE,
        PlaybackDelivery.SERVER_REMUX_HLS,
        PlaybackDelivery.SERVER_TRANSCODE_HLS,
        -> PlaybackRouteFamily.SERVER_ADAPTIVE
        PlaybackDelivery.CLIENT_LOCAL_NORMALIZATION -> PlaybackRouteFamily.CLIENT_NORMALIZED
    }
    val legacyPlan = PlaybackExecutionPlan(
        planId = planId,
        protocolVersion = protocolVersion,
        delivery = delivery,
        engine = engine,
        routeFamily = routeFamily,
        stream = PlaybackStreamRequest(
            url = stream.url,
            streamType = if (stream.protocol.name == "HLS") "hls" else "progressive",
            playMethod = playMethod,
        ),
        timeline = PlaybackTimeline(
            playerStartSeconds = timeline.playerStartSeconds,
            streamOriginSeconds = timeline.streamOriginSeconds,
            timelineOffsetSeconds = timeline.timelineOffsetSeconds,
            canSeekAnywhere = timeline.canSeekAnywhere,
        ),
        selectedTracks = SelectedPlaybackTracks(
            audioIndex = selectedTracks.audio?.index,
            subtitleIndex = selectedTracks.subtitle?.index,
        ),
        source = PlaybackSourceMetadata(
            mediaFileId = mediaFileId,
            container = stream.container,
            videoCodec = effectiveRecipe.videoCodec,
            audioCodec = effectiveRecipe.audioCodec,
            resolution = effectiveRecipe.height?.let { "${it}p" },
            hdrFormat = effectiveRecipe.dynamicRange,
            subtitleCodec = subtitle.artifact?.format,
        ),
        claims = claims,
        degradationWarnings = degradationWarnings,
        decisionTrace = listOf(decisionReason),
    )
    return PlaybackSessionResponse(
        sessionId = sessionId,
        userId = 0,
        profileId = profileId,
        mediaFileId = mediaFileId,
        playMethod = playMethod,
        position = timeline.playerStartSeconds,
        streamUrl = stream.url,
        audioTrackIndex = selectedTracks.audio?.index ?: 0,
        subtitleUrls = subtitles,
        playbackPlan = legacyPlan,
    )
}
