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

    /** The plan promised a Profile 8 base-layer route but the track is not Profile 8. */
    data class DvBaseLayerMetadataMismatch(val profile: Int, val baseRange: String) : Playability()

    /** The plan promised a base range the active output no longer supports. */
    data class DvBaseLayerOutputMismatch(val profile: Int, val baseRange: String) : Playability()

    /** The plan promised a base-layer route but the renderer opened a decoder that cannot produce it. */
    data class DvBaseLayerDecoderUnavailable(val decoderName: String, val baseRange: String) : Playability()
    data class UnsupportedAudioCodec(val mimeType: String) : Playability()
    data class UnsupportedChannelCount(val codec: String, val channels: Int) : Playability()
    data class StartupStalled(
        val bufferedAheadMs: Long,
        val stalledForMs: Long,
        val classification: String = "transport_stall",
    ) : Playability()
}

fun Playability.failureDiagnostics(): Map<String, String> = when (this) {
    is Playability.StartupStalled -> mapOf(
        "buffered_ahead_ms" to bufferedAheadMs.toString(),
        "stalled_for_ms" to stalledForMs.toString(),
    )
    is Playability.DvBaseLayerMetadataMismatch -> mapOf(
        "dolby_vision_profile" to profile.toString(),
        "promised_base_range" to baseRange,
    )
    is Playability.DvBaseLayerOutputMismatch -> mapOf(
        "dolby_vision_profile" to profile.toString(),
        "promised_base_range" to baseRange,
    )
    is Playability.DvBaseLayerDecoderUnavailable -> mapOf(
        "decoder_name" to decoderName,
        "promised_base_range" to baseRange,
    )
    else -> emptyMap()
}

/** Server-facing failure classification for a typed v3 replan. */
fun Playability.failureClassification(): String = when (this) {
    is Playability.UnsupportedDvProfile -> "unsupported_dolby_vision_profile"
    is Playability.DvBaseLayerMetadataMismatch -> "dv8_base_layer_metadata_mismatch"
    is Playability.DvBaseLayerOutputMismatch -> "dv8_base_layer_output_mismatch"
    is Playability.DvBaseLayerDecoderUnavailable -> "dv8_base_layer_decoder_unavailable"
    is Playability.UnsupportedAudioCodec -> "unsupported_audio_encoding"
    is Playability.UnsupportedChannelCount -> "unsupported_audio_layout"
    is Playability.StartupStalled -> classification
    Playability.Supported -> "none"
}
