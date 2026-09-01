package org.siloserver.silo.common.player

import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.decoder.ffmpeg.FfmpegLibrary
import org.siloserver.silo.common.BuildConfig

/**
 * Shared probe + constants for the Media3 FFmpeg audio decoder extension.
 *
 * The `media3-decoder-ffmpeg-1.11.0.aar` is checked in under
 * `android-shared/libs/` (see `scripts/build-ffmpeg-aar.sh` for provenance).
 * We never ship build flavors that omit the AAR, but consumers of this
 * helper must *still* gate runtime behavior on [isAvailable] rather than
 * assuming the extension is present — that guards against a missing native
 * library, an unsupported ABI, future flavor work (e.g., a small-APK
 * variant), and mirrors the reflection
 * [androidx.media3.exoplayer.DefaultRenderersFactory] uses internally to
 * register extension renderers. Class presence alone is not enough:
 * [FfmpegLibrary.isAvailable] actually loads `libffmpegJNI.so`, while
 * [FfmpegLibrary.supportsFormat] verifies that the packaged FFmpeg build
 * contains the decoder for one MIME type. Capability advertisement uses both
 * checks so it never promises a decoder the installed APK cannot execute.
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
@UnstableApi
object FfmpegAudioSupport {
    private data class CodecBinding(
        val shortCode: String,
        val mimeTypes: Set<String>,
    )

    private val codecBindings = listOf(
        CodecBinding("ac3", setOf(MimeTypes.AUDIO_AC3)),
        CodecBinding("eac3", setOf(MimeTypes.AUDIO_E_AC3)),
        CodecBinding("eac3_joc", setOf(MimeTypes.AUDIO_E_AC3_JOC)),
        CodecBinding("truehd", setOf(MimeTypes.AUDIO_TRUEHD)),
        CodecBinding("dts", setOf(MimeTypes.AUDIO_DTS, MimeTypes.AUDIO_DTS_EXPRESS)),
        CodecBinding("dts_hd", setOf(MimeTypes.AUDIO_DTS_HD)),
        CodecBinding("alac", setOf(MimeTypes.AUDIO_ALAC)),
    )

    /**
     * Short codec codes expected in the pinned FFmpeg build. Both phone and TV
     * capability planning may advertise the runtime-supported subset: Media3
     * automatically declines tunneling when the selected FFmpeg renderer emits
     * PCM, while passthrough-capable routes continue to prefer the platform
     * renderer.
     *
     * Mapping to FFmpeg decoders:
     *   ac3       → ac3
     *   eac3      → eac3 (also handles E-AC-3 JOC — shared decoder)
     *   eac3_joc  → eac3 (JOC metadata is an extension of E-AC-3)
     *   truehd    → mlp + truehd decoders together
     *   dts       → dca (also handles DTS Express/LBR)
     *   dts_hd    → dca (same decoder — advertised as separate short code
     *               for DTS-HD HRA + MA source metadata)
     *   alac      → alac (Media3 1.11 can now extract it from Matroska)
     */
    val codecShortCodes: List<String> = codecBindings.map(CodecBinding::shortCode)

    /**
     * Media3 MIME types the FFmpeg decoder can reach. Used by
     * [TrackSelectionPresets] to widen the preferred-MIME list beyond what
     * the passthrough sink supports — the platform renderer still wins on
     * passthrough-capable routes because its `supportsFormat` score beats
     * FFmpeg's; this set just tells the selector *which tracks to consider*.
     */
    val mimeTypes: Set<String> = codecBindings.flatMapTo(linkedSetOf(), CodecBinding::mimeTypes)

    private const val RENDERER_CLASS =
        "androidx.media3.decoder.ffmpeg.FfmpegAudioRenderer"

    /**
     * Classpath-only diagnostic. This proves the Java renderer was packaged,
     * not that its native library can load on this device.
     */
    fun isRendererOnClasspath(): Boolean = runCatching {
        Class.forName(RENDERER_CLASS)
    }.isSuccess

    /** True when the renderer is enabled and its JNI library loads for this ABI. */
    fun isAvailable(): Boolean = runCatching {
        BuildConfig.FFMPEG_AUDIO_ENABLED &&
            isRendererOnClasspath() &&
            FfmpegLibrary.isAvailable()
    }.getOrDefault(false)

    /**
     * True only when the native library loads and the pinned build contains a
     * decoder for [mimeType]. This is the same probe the renderer performs in
     * `supportsFormatInternal`, so planning and playback cannot drift.
     */
    fun supportsMimeType(mimeType: String): Boolean =
        mimeType in mimeTypes && isAvailable() && runCatching {
            FfmpegLibrary.supportsFormat(mimeType)
        }.getOrDefault(false)

    /** Runtime-supported server codec identifiers, preserving canonical order. */
    internal fun supportedCodecShortCodes(
        supportsMimeType: (String) -> Boolean = ::supportsMimeType,
    ): List<String> = codecBindings
        .filter { binding -> binding.mimeTypes.any(supportsMimeType) }
        .map(CodecBinding::shortCode)

    /** Runtime-supported Media3 MIME types. */
    internal fun supportedMimeTypes(
        supportsMimeType: (String) -> Boolean = ::supportsMimeType,
    ): Set<String> = codecBindings
        .flatMap(CodecBinding::mimeTypes)
        .filterTo(linkedSetOf(), supportsMimeType)
}
