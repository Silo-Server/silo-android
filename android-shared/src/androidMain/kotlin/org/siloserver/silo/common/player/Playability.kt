package org.siloserver.silo.common.player

/**
 * Outcome of a client-side playability check on the selected [androidx.media3.common.Tracks].
 *
 * Unsupported cases fall back to a transcoded stream. The distinct variants
 * exist so the UI can surface a specific reason — "DV Profile 7" is actionable
 * feedback ("your device doesn't support this DV flavor"), "lossless audio"
 * is actionable feedback ("your receiver can't passthrough TrueHD"); a single
 * "not supported" banner would obscure both.
 */
sealed class Playability {
    object Supported : Playability()
    data class UnsupportedDvProfile(val profile: Int) : Playability()
    data class UnsupportedAudioCodec(val mimeType: String) : Playability()
    data class UnsupportedChannelCount(val codec: String, val channels: Int) : Playability()
    data class StartupStalled(
        val bufferedAheadMs: Long,
        val stalledForMs: Long,
        val classification: String = "transport_stall",
    ) : Playability()
}
