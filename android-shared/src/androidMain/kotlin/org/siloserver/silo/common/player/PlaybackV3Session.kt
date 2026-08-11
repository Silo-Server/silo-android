package org.siloserver.silo.common.player

import org.siloserver.silo.model.playback.PlayMethod
import org.siloserver.silo.model.playback.PlaybackDelivery
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
import org.siloserver.silo.model.playback.SUBTITLE_DELIVERY_BURN_IN_ONLY
import org.siloserver.silo.model.playback.SUBTITLE_DELIVERY_SIDECAR
import org.siloserver.silo.model.playback.resolvedSelectedSubtitleIndex
import org.siloserver.silo.playback.isBitmapSubtitleCodecFamily

sealed interface VideoSessionStartV3 {
    data class Ready(
        val session: PlaybackSessionResponse,
        val plan: PlaybackPlanV3,
        val playbackAttemptId: String,
        val planAttemptId: String,
        val planAttemptKey: String,
        /** Exact evidence snapshot used to negotiate this plan. */
        val capabilities: org.siloserver.silo.model.playback.ClientCodecCapabilities,
        /** Exact output/delivery context used to negotiate this plan. */
        val clientPlaybackContext: org.siloserver.silo.model.playback.ClientPlaybackContext,
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
    val selectedSubtitleIndex = resolvedSelectedSubtitleIndex()
    val playMethod = when (delivery) {
        PlaybackDelivery.ORIGINAL_HTTP -> PlayMethod.DIRECT
        PlaybackDelivery.SERVER_REMUX_PROGRESSIVE,
        PlaybackDelivery.SERVER_REMUX_HLS,
        -> PlayMethod.REMUX
        PlaybackDelivery.SERVER_TRANSCODE_HLS -> PlayMethod.TRANSCODE
        PlaybackDelivery.CLIENT_LOCAL_NORMALIZATION -> PlayMethod.REMUX
    }
    val selectedSubtitle = subtitle.artifact?.takeIf {
        subtitle.mode == PlaybackSubtitleModeV3.CONVERT || subtitle.mode == PlaybackSubtitleModeV3.RENDER
    }?.let { artifact ->
        val artifactIndex = selectedSubtitleIndex ?: return@let null
        // A bitmap RENDER artifact describes the subtitle stream already
        // embedded in ORIGINAL_HTTP media. It is not a WebVTT sidecar: trying
        // to mount its descriptive `/subtitles/{index}.vtt` URL makes the
        // server reject image-based PGS/VobSub/DVB data and prevents Media3
        // from selecting the decoder-backed embedded track.
        val rendersEmbeddedBitmap =
            subtitle.mode == PlaybackSubtitleModeV3.RENDER &&
                delivery == PlaybackDelivery.ORIGINAL_HTTP &&
                isBitmapSubtitleCodecFamily(artifact.format)
        listOf(
            PlayerSubtitleInfo(
                index = artifactIndex,
                codec = artifact.format,
                label = if (rendersEmbeddedBitmap) null else "Server subtitle",
                source = if (rendersEmbeddedBitmap) "embedded" else "server_artifact",
                url = if (rendersEmbeddedBitmap) "" else artifact.url,
            ),
        )
    }
    // Neutral v3 publishes the complete subtitle inventory on every plan,
    // including plans with subtitles off. Project it into native phone/TV UI
    // state so both menus retain every authoritative ordinal. The player mount
    // separately filters this inventory to the artifact selected by the active
    // plan; inventory URLs are choices, not a preload list. During a burn-in
    // plan the URLs are still deliberately blanked so no caller can mount a
    // sidecar over captions already baked into the video.
    val inventorySubtitles = subtitle.inventory.asSequence()
        .filter { it.combinedIndex >= 0 && it.trackId.isNotBlank() }
        .filter {
            it.delivery == SUBTITLE_DELIVERY_SIDECAR ||
                it.delivery == SUBTITLE_DELIVERY_BURN_IN_ONLY
        }
        .map { item ->
            PlayerSubtitleInfo(
                index = item.combinedIndex,
                language = item.language,
                codec = item.codec,
                label = item.label,
                source = item.source,
                forced = item.forced,
                url = if (
                    subtitle.mode != PlaybackSubtitleModeV3.BURN_IN &&
                    item.delivery == SUBTITLE_DELIVERY_SIDECAR
                ) item.url.orEmpty() else "",
                catalogLabel = item.label,
                catalogSource = item.source,
                isDefault = item.isDefault,
                serverTrackId = item.trackId,
                serverDelivery = item.delivery.takeIf {
                    it == SUBTITLE_DELIVERY_SIDECAR ||
                        it == SUBTITLE_DELIVERY_BURN_IN_ONLY
                },
            )
        }
        .distinctBy(PlayerSubtitleInfo::index)
        .sortedBy(PlayerSubtitleInfo::index)
        .toList()
    val plannedSubtitles = inventorySubtitles
    val plannedIndexes = plannedSubtitles.mapTo(mutableSetOf(), PlayerSubtitleInfo::index)
    val subtitles = (plannedSubtitles + selectedSubtitle.orEmpty().filterNot { it.index in plannedIndexes })
        .takeIf(List<PlayerSubtitleInfo>::isNotEmpty)
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
            subtitleIndex = selectedSubtitleIndex,
        ),
        source = PlaybackSourceMetadata(
            mediaFileId = effectiveFileId,
            container = stream.container,
            videoCodec = effectiveRecipe.videoCodec,
            audioCodec = effectiveRecipe.audioCodec,
            resolution = effectiveRecipe.height?.let { "${it}p" },
            hdrFormat = effectiveRecipe.dynamicRange,
            colorRange = source.colorRange,
            subtitleCodec = subtitle.artifact?.format,
            letterboxTopFraction = source.letterboxTopFraction,
            letterboxBottomFraction = source.letterboxBottomFraction,
        ),
        claims = claims,
        transformations = transformations,
        appliedQuirks = appliedQuirks,
        runtimeCorrections = runtimeCorrections,
        availableQualities = availableQualities,
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
