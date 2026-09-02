package org.siloserver.silo.common.player

import androidx.annotation.OptIn
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi

@OptIn(UnstableApi::class)
fun mountVideoMedia(
    player: Player,
    playerFactory: SiloPlayerFactory,
    spec: VideoPlayerMediaSpec,
    startPositionMs: Long = spec.startPositionMs,
    playWhenReady: Boolean = true,
) {
    val mediaItem = playerFactory.buildMediaItem(
        contentId = spec.contentId,
        streamUrl = spec.streamUrl,
        playMethod = spec.playMethod,
        delivery = spec.delivery,
        serverUrl = spec.serverUrl,
        container = spec.container,
        subtitles = spec.subtitles,
        title = spec.title,
        subtitle = spec.subtitle,
        artworkUrl = spec.artworkUrl,
        durationMs = spec.durationMs,
        timelineOffsetSeconds = spec.timelineOffsetSeconds,
        requestHeaders = spec.requestHeaders,
        expectedDynamicRange = spec.expectedDynamicRange,
        expectedColorRange = spec.expectedColorRange,
        transformations = spec.transformations,
        runtimeCorrections = spec.runtimeCorrections,
        activeClaims = spec.activeClaims,
    )
    player.setMediaItem(mediaItem, startPositionMs.coerceAtLeast(0L))
    player.prepare()
    player.playWhenReady = playWhenReady
}

@OptIn(UnstableApi::class)
fun refreshMountedVideoMedia(
    player: Player,
    playerFactory: SiloPlayerFactory,
    spec: VideoPlayerMediaSpec,
) {
    val resumePositionMs = player.currentPosition.coerceAtLeast(0L)
    val wasPlaying = player.playWhenReady
    val mediaItem = playerFactory.buildMediaItem(
        contentId = spec.contentId,
        streamUrl = spec.streamUrl,
        playMethod = spec.playMethod,
        delivery = spec.delivery,
        serverUrl = spec.serverUrl,
        container = spec.container,
        subtitles = spec.subtitles,
        title = spec.title,
        subtitle = spec.subtitle,
        artworkUrl = spec.artworkUrl,
        durationMs = spec.durationMs,
        timelineOffsetSeconds = spec.timelineOffsetSeconds,
        requestHeaders = spec.requestHeaders,
        expectedDynamicRange = spec.expectedDynamicRange,
        expectedColorRange = spec.expectedColorRange,
        transformations = spec.transformations,
        runtimeCorrections = spec.runtimeCorrections,
        activeClaims = spec.activeClaims,
    )
    player.setMediaItem(mediaItem, resumePositionMs)
    player.prepare()
    player.playWhenReady = wasPlaying
}
