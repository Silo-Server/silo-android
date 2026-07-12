package org.siloserver.silo.common.player.route

/**
 * Static capability table mapping each [PlaybackRoute] to its feature set.
 * Single source of truth for "can route X do feature Y."
 *
 * Built from Apple's `tvos-player/05-route-capability-matrix.md` adapted
 * to Android's Media3 reality. Differences from Apple's matrix are
 * documented per row.
 */
object RouteCapabilityMatrix {

    private val map: Map<PlaybackRoute, RouteCapability> = mapOf(
        // SiloPlayer: ProgressiveMediaSource + FFmpeg audio extension.
        // Same capabilities as NativeDirect; differs only in codec breadth
        // (FFmpeg unlocks TrueHD/EAC-3 JOC/AC-4 software-decoded).
        PlaybackRoute.SiloPlayer to RouteCapability(
            buffersReported = true,
            videoGravityToggle = true,
            hdrToggle = true,
            audioDelaySupported = true,
            subtitleDelaySupported = true,
            subtitleStyling = SubtitleStyling.Full,
            supportsTunneling = true,
            supportsPassthrough = true,
        ),
        // NativeDirect: ProgressiveMediaSource + platform decoders only.
        PlaybackRoute.NativeDirect to RouteCapability(
            buffersReported = true,
            videoGravityToggle = true,
            hdrToggle = true,
            audioDelaySupported = true,
            subtitleDelaySupported = true,
            subtitleStyling = SubtitleStyling.Full,
            supportsTunneling = true,
            supportsPassthrough = true,
        ),
        // Hls: HlsMediaSource. Server-baked HDR (can't toggle off);
        // tunneling generally not supported by HLS pipeline; passthrough
        // is segment-level so depends on the segment's codec.
        PlaybackRoute.Hls to RouteCapability(
            buffersReported = true,
            videoGravityToggle = true,
            hdrToggle = false,
            audioDelaySupported = true,
            subtitleDelaySupported = true,
            subtitleStyling = SubtitleStyling.SystemOnly,
            supportsTunneling = false,
            supportsPassthrough = true,  // best-effort; depends on segment
        ),
    )

    /** Returns the capability flags for [route]. */
    fun get(route: PlaybackRoute): RouteCapability = map.getValue(route)

    /** Sanity check: every [PlaybackRoute] enum value has a matrix entry. */
    fun isExhaustive(): Boolean = PlaybackRoute.entries.all { it in map }
}
