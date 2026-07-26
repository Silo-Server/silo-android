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
import org.siloserver.silo.model.playback.PlaybackStreamProtocol
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
    val effectiveFileId = effectiveMediaFileId ?: mediaFileId
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
        // A bitmap RENDER artifact describes the subtitle stream already
        // embedded in ORIGINAL_HTTP media. It is not a WebVTT sidecar: trying
        // to mount its descriptive `/subtitles/{index}.vtt` URL makes the
        // server reject image-based PGS/VobSub/DVB data and prevents Media3
        // from selecting the decoder-backed embedded track.
        val rendersEmbeddedBitmap =
            subtitle.mode == PlaybackSubtitleModeV3.RENDER &&
                delivery == PlaybackDelivery.ORIGINAL_HTTP &&
                isBitmapSubtitleCodecOrMime(artifact.format)
        listOf(
            PlayerSubtitleInfo(
                index = selectedTracks.subtitle?.index ?: 0,
                codec = artifact.format,
                label = if (rendersEmbeddedBitmap) null else "Server subtitle",
                source = if (rendersEmbeddedBitmap) "embedded" else "server_artifact",
                url = if (rendersEmbeddedBitmap) "" else artifact.url,
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
            streamType = when (stream.protocol) {
                PlaybackStreamProtocol.HLS -> "hls"
                PlaybackStreamProtocol.HTTP_PROGRESSIVE -> "progressive"
            },
            playMethod = playMethod,
        ),
        timeline = PlaybackTimeline(
            sourceStartSeconds = timeline.sourceStartSeconds,
            playerStartSeconds = timeline.playerStartSeconds,
            streamOriginSeconds = timeline.streamOriginSeconds,
            timelineOffsetSeconds = timeline.timelineOffsetSeconds,
            seekWindowStartSeconds = timeline.seekWindowStartSeconds,
            seekWindowEndSeconds = timeline.seekWindowEndSeconds,
            canSeekAnywhere = timeline.canSeekAnywhere,
            seekRestoration = timeline.seekRestoration,
        ),
        selectedTracks = SelectedPlaybackTracks(
            audioIndex = selectedTracks.audio?.index,
            subtitleIndex = selectedTracks.subtitle?.index,
        ),
        source = PlaybackSourceMetadata(
            mediaFileId = effectiveFileId,
            container = stream.container,
            videoCodec = effectiveRecipe.videoCodec,
            audioCodec = effectiveRecipe.audioCodec,
            resolution = effectiveRecipe.height?.let { "${it}p" },
            hdrFormat = effectiveRecipe.dynamicRange,
            subtitleCodec = subtitle.artifact?.format,
        ),
        claims = claims,
        transformations = transformations,
        appliedQuirks = appliedQuirks,
        runtimeCorrections = runtimeCorrections,
        degradationWarnings = degradationWarnings,
        decisionTrace = listOf(decisionReason),
        requestedMediaFileId = requestedMediaFileId ?: mediaFileId,
        effectiveMediaFileId = effectiveFileId,
    )
    return PlaybackSessionResponse(
        sessionId = sessionId,
        userId = 0,
        profileId = profileId,
        mediaFileId = effectiveFileId,
        playMethod = playMethod,
        // Session/reporting position is always source/movie time. The player
        // mount position lives separately in playbackPlan.timeline.
        position = timeline.sourceStartSeconds,
        streamUrl = stream.url,
        audioTrackIndex = selectedTracks.audio?.index ?: 0,
        // The server's runtime for the effective file, or null when it does
        // not know. Callers must keep null as null rather than substituting
        // the engine's duration: on an HLS copy remux the engine reports the
        // window produced so far, so adopting it shows a feature film as a
        // couple of minutes.
        durationSeconds = source.durationSeconds,
        subtitleUrls = subtitles,
        playbackPlan = legacyPlan,
    )
}
