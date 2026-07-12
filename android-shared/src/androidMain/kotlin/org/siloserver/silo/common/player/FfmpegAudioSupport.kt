package org.siloserver.silo.common.player

import androidx.media3.common.MimeTypes

/**
 * Shared probe + constants for the Media3 FFmpeg audio decoder extension.
 *
 * The `media3-decoder-ffmpeg-1.10.1.aar` is checked in under
 * `android-shared/libs/` (see `scripts/build-ffmpeg-aar.sh` for provenance).
 * We never ship build flavors that omit the AAR, but consumers of this
 * helper must *still* gate runtime behavior on [isAvailable] rather than
 * assuming the extension is present — that guards against future flavor
 * work (e.g., a small-APK variant) and mirrors the reflection
 * [androidx.media3.exoplayer.DefaultRenderersFactory] uses internally to
 * register extension renderers. Using the same probe here guarantees the
 * capability detector, track-selection presets, and the renderer factory
 * never disagree about whether FFmpeg is available.
 *
 * [codecShortCodes] and [mimeTypes] must stay consistent with:
 *   - `scripts/build-ffmpeg-aar.sh` `ENABLED_DECODERS` (what the AAR can
 *     actually decode)
 *   - `docs/plans/ffmpeg-audio-extension-plan.md` codec table
 *   - Server-side codec identifiers in `Silo/internal/playback/`
 *
 * Changing this list without updating the AAR build script will silently
 * over-report the client's capabilities and cause the server to pick
 * DIRECT for streams the client then can't actually decode.
 */
object FfmpegAudioSupport {
    /**
     * Short codec codes emitted into [org.siloserver.silo.model.playback.ClientCodecCapabilities.codecsAudio]
     * so the server knows the client can handle these audio codecs locally.
     *
     * Mapping to FFmpeg decoders:
     *   ac3       → ac3
     *   eac3      → eac3 (also handles E-AC-3 JOC — shared decoder)
     *   eac3_joc  → eac3 (JOC metadata is an extension of E-AC-3)
     *   truehd    → mlp + truehd decoders together
     *   dts       → dca (also handles DTS-HD core + HRA + MA)
     *   dts_hd    → dca (same decoder — advertised as separate short code
     *               so the server can match DTS-HD-specific streams)
     */
    val codecShortCodes: List<String> = listOf(
        "ac3",
        "eac3",
        "eac3_joc",
        "truehd",
        "dts",
        "dts_hd",
    )

    /**
     * Media3 MIME types the FFmpeg decoder can reach. Used by
     * [TrackSelectionPresets] to widen the preferred-MIME list beyond what
     * the passthrough sink supports — the platform renderer still wins on
     * passthrough-capable routes because its `supportsFormat` score beats
     * FFmpeg's; this set just tells the selector *which tracks to consider*.
     */
    val mimeTypes: Set<String> = setOf(
        MimeTypes.AUDIO_AC3,
        MimeTypes.AUDIO_E_AC3,
        MimeTypes.AUDIO_E_AC3_JOC,
        MimeTypes.AUDIO_TRUEHD,
        MimeTypes.AUDIO_DTS,
        MimeTypes.AUDIO_DTS_HD,
    )

    private const val RENDERER_CLASS =
        "androidx.media3.decoder.ffmpeg.FfmpegAudioRenderer"

    /**
     * True when the Media3 FFmpeg audio decoder extension is reachable on
     * the runtime classpath. The result is `false` only if the AAR was
     * intentionally excluded (future flavor work) or the dependency was
     * accidentally dropped; `FfmpegClasspathTest` guards against the
     * latter.
     *
     * Not cached — `Class.forName` is effectively a hashmap lookup on the
     * classloader's class table, and callers hit this at most once per
     * playback session. Caching would only complicate test overrides.
     */
    fun isAvailable(): Boolean = runCatching {
        Class.forName(RENDERER_CLASS)
    }.isSuccess
}
