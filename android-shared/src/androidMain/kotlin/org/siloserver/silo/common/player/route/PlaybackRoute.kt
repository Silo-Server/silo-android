package org.siloserver.silo.common.player.route

/**
 * What kind of playback engine + media source combination is in use.
 * Mirrors Apple's tvOS route taxonomy (see `/opt/silo-apple/docs/tvos-player/05-route-capability-matrix.md`).
 *
 * Today's Android player decides MIME-driven via DefaultMediaSourceFactory.
 * This enum is observational — it labels what's actually running so the HUD
 * Stats pane can surface it, and provides a vocabulary for a future
 * client-side route selector.
 */
enum class PlaybackRoute(val displayName: String) {
    /** ProgressiveMediaSource + RenderersFactory with FFmpeg audio extension (`EXTENSION_RENDERER_MODE_PREFER`). */
    SiloPlayer("SiloPlayer"),

    /** ProgressiveMediaSource + platform-only renderers (`EXTENSION_RENDERER_MODE_OFF`). Narrower codec breadth. */
    NativeDirect("Native Direct"),

    /** HlsMediaSource. Used when the server delivers HLS or transcodes to it. */
    Hls("HLS"),
}
