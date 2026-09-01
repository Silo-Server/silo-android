package org.siloserver.silo.common.player

import android.content.Context
import android.os.Build
import androidx.media3.common.MimeTypes
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import org.siloserver.silo.common.player.video.canonicalAudioCodecFamily
import org.siloserver.silo.model.catalog.AudioTrack
import org.siloserver.silo.model.playback.AudioPassthroughCapabilities
import org.siloserver.silo.model.playback.ClientCodecCapabilities
import org.siloserver.silo.model.playback.HdrCapabilities
import org.siloserver.silo.playback.canonicalSubtitleLanguage

/**
 * Client-side track-selection presets. Without these, the default selector
 * tends to pick the first track it sees — on an Atmos receiver with a 2ch AAC
 * track and an E-AC-3 JOC track in the same MKV, the default order lets AAC
 * win. These presets push passthrough-eligible codecs first when the sink
 * actually supports them.
 *
 * `passthroughCodecs` from [AudioPassthroughCapabilities] uses the Silo
 * server's canonical short codes (`eac3_joc`, `truehd`, `dts_hd`, etc.) — we
 * reuse the same strings here so the snapshot from [AudioCapabilityManager]
 * can drive the filter without a second mapping layer.
 *
 * The presets produce [DefaultTrackSelector.Parameters] so tunneling and the
 * channel-constraint knob (both specific to `DefaultTrackSelector`) are
 * expressible; the ExoPlayer built by [SiloPlayerFactory] always uses a
 * `DefaultTrackSelector`.
 */
@UnstableApi
object TrackSelectionPresets {
    fun effectivePreferredAudioLanguage(
        settingsLanguage: String?,
        profileLanguage: String?,
    ): String? = settingsLanguage?.trim()?.takeIf { it.isNotEmpty() }
        ?: profileLanguage?.trim()?.takeIf { it.isNotEmpty() }

    /**
     * TV: prioritize passthrough and let Media3 use the platform's native
     * audio-output and hardware A/V-sync paths where the device supports them.
     *
     * Google TV Streamer has a confirmed tunneled-startup stall, so only that
     * device family keeps tunneling disabled. Disabling tunneling globally made
     * Shield-class devices use Media3's software-timed PCM path even though the
     * hardware A/V-sync path is available.
     *
     * [ffmpegAvailable] defaults to probing the runtime JNI extension via
     * [FfmpegAudioSupport.isAvailable]; tests override it directly. When
     * true, the preferred-MIME list widens to include FFmpeg-reachable
     * audio codecs (TrueHD, DTS-HD, etc.) regardless of passthrough
     * support — the platform renderer still wins selection on
     * passthrough-capable routes because its `supportsFormat` score beats
     * FFmpeg's. If FFmpeg is selected, Media3 automatically uses its
     * non-tunneled PCM path while leaving hardware video decode intact.
     *
     * Deliberately NO preferred TEXT language. On TV the subtitle transaction
     * adapter is the single owner of subtitle selection: it resolves a typed
     * `SubtitleIdentity` and mounts it through `SubtitleManager`. A
     * preferred-text hint here made `DefaultTrackSelector` a second, silent
     * authority that enabled a text track on its own — playback obeyed the
     * selector while the HUD reported the adapter's committed identity, so the
     * two disagreed (subtitles on screen, "Off" in the HUD). The app decides;
     * ExoPlayer executes. Text-track enablement is left untouched here so
     * re-applying presets on a capability change cannot disturb a mounted
     * subtitle either.
     */
    fun buildTvParameters(
        context: Context,
        base: TrackSelectionParameters,
        audioCaps: AudioPassthroughCapabilities,
        displayHdr: HdrCapabilities,
        preferredAudioLanguage: String?,
        allowHdr: Boolean = true,
        ffmpegAvailable: Boolean = FfmpegAudioSupport.isAvailable(),
    ): DefaultTrackSelector.Parameters {
        val audioMimes = buildTvAudioMimePreferences(audioCaps, ffmpegAvailable)
        val videoMimes = buildTvVideoMimePreferences(displayHdr, allowHdr)
        val tunnelingEnabled = TvPlaybackOutputPolicy.shouldEnableTunneling(
            manufacturer = Build.MANUFACTURER,
            model = Build.MODEL,
            device = Build.DEVICE,
        )

        val builder = base.toDefaultBuilder(context)
            .setTunnelingEnabled(tunnelingEnabled)
            .setAudioOffloadPreferences(
                TrackSelectionParameters.AudioOffloadPreferences.Builder()
                    .setAudioOffloadMode(
                        TrackSelectionParameters.AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_ENABLED,
                    )
                    .build(),
            )
            .setViewportSizeToPhysicalDisplaySize(context, /* viewportOrientationMayChange = */ true)
            .setPreferredVideoMimeTypes(*videoMimes.toTypedArray())
            .setPreferredAudioMimeTypes(*audioMimes.toTypedArray())

        preferredAudioLanguages(preferredAudioLanguage).takeIf { it.isNotEmpty() }
            ?.let { builder.setPreferredAudioLanguages(*it.toTypedArray()) }

        return builder.build()
    }

    /**
     * Phone: offload-friendly, tunneling off, downmix when the sink can't
     * carry the channel count.
     *
     * [ffmpegAvailable] behaves as in [buildTvParameters] — widens the
     * preferred-MIME list to include FFmpeg-reachable codecs when the
     * native extension is available for the current ABI.
     */
    fun buildPhoneParameters(
        context: Context,
        base: TrackSelectionParameters,
        audioCaps: AudioPassthroughCapabilities,
        spatializerOn: Boolean,
        preferredAudioLanguage: String?,
        preferredTextLanguage: String?,
        ffmpegAvailable: Boolean = FfmpegAudioSupport.isAvailable(),
    ): DefaultTrackSelector.Parameters {
        val audioMimes = buildPhoneAudioMimePreferences(audioCaps, spatializerOn, ffmpegAvailable)

        val offloadMode =
            TrackSelectionParameters.AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_ENABLED

        val builder = base.toDefaultBuilder(context)
            .setTunnelingEnabled(false)
            .setConstrainAudioChannelCountToDeviceCapabilities(true)
            .setAudioOffloadPreferences(
                TrackSelectionParameters.AudioOffloadPreferences.Builder()
                    .setAudioOffloadMode(offloadMode)
                    .build(),
            )
            .setPreferredAudioMimeTypes(*audioMimes.toTypedArray())

        explicitPreferredAudioLanguages(preferredAudioLanguage).takeIf { it.isNotEmpty() }
            ?.let { builder.setPreferredAudioLanguages(*it.toTypedArray()) }

        preferredTextLanguage?.takeIf { it.isNotBlank() }
            ?.let { builder.setPreferredTextLanguage(it) }

        return builder.build()
    }

    /**
     * Preferred video-MIME list for TV. DV is added only when the display
     * advertises DV profiles AND the user's [allowHdr] preference is on. When
     * [allowHdr] is false the DV MIME is dropped so the selector picks
     * H.265/H.264 over a DV variant on multi-track content (A.3d-hdr).
     *
     * Honest constraint: this is preference-driven track selection only —
     * Media3 has no surface-level SDR forcing equivalent to AVPlayer's
     * `setHDREnabled`. For single-track HDR-only files there is no SDR
     * alternative and the toggle becomes a per-file no-op.
     *
     * `internal` so [TrackSelectionPresetsFfmpegTest] can exercise the
     * helper directly without Robolectric.
     */
    internal fun buildTvVideoMimePreferences(
        displayHdr: HdrCapabilities,
        allowHdr: Boolean = true,
    ): List<String> {
        val mimes = mutableListOf<String>()
        if (allowHdr && displayHdr.dolbyVisionProfiles.isNotEmpty()) {
            mimes += MimeTypes.VIDEO_DOLBY_VISION
        }
        mimes += MimeTypes.VIDEO_H265
        mimes += MimeTypes.VIDEO_H264
        return mimes
    }

    // Marked internal for TrackSelectionPresetsFfmpegTest — the preset builders
    // themselves need a Context to build DefaultTrackSelector.Parameters, which
    // a plain JVM unit test can't provide without Robolectric. The MIME-
    // preference helpers are pure and carry all the FFmpeg-aware logic, so we
    // test them directly.
    internal fun buildTvAudioMimePreferences(
        caps: AudioPassthroughCapabilities,
        ffmpegAvailable: Boolean,
        ffmpegMimeTypes: Set<String> = if (ffmpegAvailable) {
            FfmpegAudioSupport.supportedMimeTypes()
        } else {
            emptySet()
        },
    ): List<String> {
        // Union of MIMEs the sink can passthrough + MIMEs FFmpeg can decode
        // locally. Announcing a codec neither route can reach just burns a
        // failed selection attempt, so we filter down to the union.
        val reachable = reachableAudioMimes(caps, ffmpegMimeTypes)
        return TV_DESIRED_ORDER.filter { it in reachable }
    }

    internal fun buildPhoneAudioMimePreferences(
        caps: AudioPassthroughCapabilities,
        spatializerOn: Boolean,
        ffmpegAvailable: Boolean,
        ffmpegMimeTypes: Set<String> = if (ffmpegAvailable) {
            FfmpegAudioSupport.supportedMimeTypes()
        } else {
            emptySet()
        },
    ): List<String> {
        // Same union-and-filter pattern as TV, but phone ordering biases
        // toward renderer-decoded codecs since phones almost never do
        // passthrough. With FFmpeg on the classpath the "renderer-decoded"
        // set widens from { AAC, (platform AC-3 / E-AC-3 only on some
        // devices) } to { AAC, AC-3, E-AC-3, E-AC-3 JOC, TrueHD, DTS,
        // DTS-HD } — the whole point of this PR.
        //
        // Spatialized playback (Android 12+) gets a boost for Atmos-bearing
        // streams. JOC metadata decoded via FFmpeg produces PCM — the
        // spatializer can still render it positionally even without native
        // Atmos passthrough.
        val reachable = reachableAudioMimes(caps, ffmpegMimeTypes)
        val desired = buildList {
            if (spatializerOn) {
                add(MimeTypes.AUDIO_E_AC3_JOC)
                add(MimeTypes.AUDIO_AC4)
            }
            add(MimeTypes.AUDIO_E_AC3)
            add(MimeTypes.AUDIO_TRUEHD)
            add(MimeTypes.AUDIO_DTS_HD)
            add(MimeTypes.AUDIO_DTS)
            add(MimeTypes.AUDIO_DTS_EXPRESS)
            add(MimeTypes.AUDIO_ALAC)
            add(MimeTypes.AUDIO_AC3)
            add(MimeTypes.AUDIO_AAC)
        }
        return desired.distinct().filter { it in reachable }
    }

    /**
     * Media3 language preference order. The viewer's explicit language wins;
     * English is the deterministic fallback instead of whatever track happens
     * to be first in the container. ISO-639 aliases are deduplicated, so
     * `eng` and `en` do not become two artificial tiers.
     */
    internal fun preferredAudioLanguages(preferred: String?): List<String> {
        val result = mutableListOf<String>()
        val seen = mutableSetOf<String>()
        listOfNotNull(preferred?.trim()?.takeIf { it.isNotEmpty() }, "en").forEach { language ->
            val canonical = canonicalSubtitleLanguage(language) ?: language.lowercase()
            if (seen.add(canonical)) result += language
        }
        return result
    }

    /**
     * Phone keeps the container/server default when no preference is set. Once
     * the viewer explicitly chooses a language, English remains its fallback.
     * TV intentionally calls [preferredAudioLanguages] directly so its Auto
     * policy is deterministic even without an explicit setting.
     */
    internal fun explicitPreferredAudioLanguages(preferred: String?): List<String> =
        preferred?.trim()?.takeIf { it.isNotEmpty() }
            ?.let(::preferredAudioLanguages)
            .orEmpty()

    /**
     * Resolve the catalog audio ordinal before the server creates a playback
     * plan. Explicit per-play choices are handled by the caller; this is the
     * automatic policy used when no override exists.
     *
     * Selection is language-first (preferred, then English), then quality. A
     * track only enters the first pass when the current device/output can
     * decode it or carry it as passthrough at that channel count. If the file
     * has no directly compatible track, a second pass still chooses the best
     * source track so the server can adapt it rather than failing playback.
     */
    fun selectBestCompatibleAudioTrackOrdinal(
        tracks: List<AudioTrack>,
        preferredAudioLanguage: String?,
        capabilities: ClientCodecCapabilities,
    ): Int? {
        if (tracks.isEmpty()) return null
        val candidates = tracks.mapIndexed(::RankedAudioTrack)
        val languageTiers = preferredAudioLanguages(preferredAudioLanguage)
            .mapNotNull(::canonicalSubtitleLanguage)
        languageTiers.forEach { language ->
            val tier = candidates.filter {
                canonicalSubtitleLanguage(it.track.language) == language
            }
            if (tier.isNotEmpty()) {
                return bestCompatibleOrAdaptableAudioCandidate(tier, capabilities)?.ordinal
            }
        }

        val mainMixes = withoutCommentaryWhenMainMixExists(candidates)
        val directlyCompatible = mainMixes.filter {
            isDirectlyCompatibleAudioTrack(it.track, capabilities)
        }
        return bestAudioCandidate(directlyCompatible.filter { it.track.isDefault })?.ordinal
            ?: bestAudioCandidate(directlyCompatible)?.ordinal
            ?: bestAudioCandidate(mainMixes.filter { it.track.isDefault })?.ordinal
            ?: bestAudioCandidate(mainMixes)?.ordinal
    }

    /**
     * Compatibility is ranked only *inside* a language tier. An available
     * preferred-language source that needs server adaptation must not lose to
     * directly playable English, and a compatible commentary track must not
     * beat an adaptable main mix in that same language.
     */
    private fun bestCompatibleOrAdaptableAudioCandidate(
        candidates: List<RankedAudioTrack>,
        capabilities: ClientCodecCapabilities,
    ): RankedAudioTrack? {
        val mainMixes = withoutCommentaryWhenMainMixExists(candidates)
        val directlyCompatible = mainMixes.filter {
            isDirectlyCompatibleAudioTrack(it.track, capabilities)
        }
        return bestAudioCandidate(directlyCompatible) ?: bestAudioCandidate(mainMixes)
    }

    private fun withoutCommentaryWhenMainMixExists(
        candidates: List<RankedAudioTrack>,
    ): List<RankedAudioTrack> = candidates.filterNot { looksLikeCommentary(it.track.title) }
        .ifEmpty { candidates }

    private fun bestAudioCandidate(candidates: List<RankedAudioTrack>): RankedAudioTrack? {
        if (candidates.isEmpty()) return null
        val mainMixes = candidates.filterNot { looksLikeCommentary(it.track.title) }
            .ifEmpty { candidates }
        return mainMixes.maxWithOrNull(
            compareBy<RankedAudioTrack> { audioQualityRank(it.track.codec) }
                .thenBy { it.track.channels ?: 0 }
                .thenBy { it.track.bitrate ?: 0 }
                .thenBy { if (it.track.isDefault) 1 else 0 }
                // Stable tie-break: the earlier catalog ordinal wins.
                .thenBy { -it.ordinal },
        )
    }

    private fun isDirectlyCompatibleAudioTrack(
        track: AudioTrack,
        capabilities: ClientCodecCapabilities,
    ): Boolean {
        val codec = audioPreferenceCodec(track.codec) ?: return false
        if (codec == "aac") return true
        val decodeCodecs = capabilities.codecsAudio
            .mapNotNull(::audioPreferenceCodec)
            .toSet()
        if (codec in decodeCodecs) return true

        val passthrough = capabilities.audioPassthrough ?: return false
        val channelCount = track.channels ?: 0
        val passthroughCandidates = when (codec) {
            "eac3_joc" -> listOf("eac3_joc", "eac3")
            "dts_hd" -> listOf("dts_hd", "dts")
            else -> listOf(codec)
        }
        return passthroughCandidates.any { candidate ->
            sinkCanPassthrough(candidate, channelCount, passthrough)
        }
    }

    private fun audioPreferenceCodec(raw: String?): String? {
        val token = raw
            ?.substringAfterLast('/')
            ?.lowercase()
            ?.filter { it.isLetterOrDigit() }
            ?.takeIf { it.isNotBlank() }
            ?: return null
        return when {
            token.contains("truehd") || token == "mlp" -> "truehd"
            token.contains("eac3joc") || token.contains("ec3joc") -> "eac3_joc"
            token.contains("dtshd") || token.contains("dtsma") || token.contains("dtshra") -> "dts_hd"
            token.startsWith("ec3") || token == "eac3" || token == "ddp" -> "eac3"
            token.startsWith("ac3") -> "ac3"
            token.startsWith("ac4") -> "ac4"
            else -> canonicalAudioCodecFamily(raw)
        }
    }

    private fun audioQualityRank(raw: String?): Int = when (audioPreferenceCodec(raw)) {
        "truehd" -> 1_000
        "eac3_joc" -> 950
        "eac3" -> 900
        "dts_hd" -> 850
        "flac" -> 825
        "ac4" -> 800
        "dts" -> 700
        "ac3" -> 650
        "aac" -> 500
        "opus" -> 450
        "vorbis" -> 400
        "mp3" -> 300
        "pcm" -> 250
        else -> 0
    }

    private fun looksLikeCommentary(title: String?): Boolean {
        val value = title?.lowercase().orEmpty()
        return listOf("commentary", "description", "descriptive", "director", "isolated score")
            .any(value::contains)
    }

    private data class RankedAudioTrack(
        val ordinal: Int,
        val track: AudioTrack,
    )

    /**
     * MIMEs playable on this device — union of passthrough-reachable codecs
     * and, when the FFmpeg audio extension loads for this ABI, the
     * FFmpeg-decodable codecs. AAC is always included (every Android
     * device has an AAC decoder).
     */
    private fun reachableAudioMimes(
        caps: AudioPassthroughCapabilities,
        ffmpegMimeTypes: Set<String>,
    ): Set<String> {
        val result = mutableSetOf(MimeTypes.AUDIO_AAC)
        caps.passthroughCodecs.forEach { code ->
            result += passthroughCodeToMimes(code)
        }
        result += ffmpegMimeTypes
        return result
    }

    private fun passthroughCodeToMimes(code: String): Set<String> = when (code) {
        "eac3_joc" -> setOf(MimeTypes.AUDIO_E_AC3_JOC)
        "truehd"   -> setOf(MimeTypes.AUDIO_TRUEHD)
        "ac4"      -> setOf(MimeTypes.AUDIO_AC4)
        "eac3"     -> setOf(MimeTypes.AUDIO_E_AC3)
        "dts_hd"   -> setOf(MimeTypes.AUDIO_DTS_HD)
        "dts"      -> setOf(MimeTypes.AUDIO_DTS, MimeTypes.AUDIO_DTS_EXPRESS)
        "ac3"      -> setOf(MimeTypes.AUDIO_AC3)
        else       -> emptySet()
    }

    /**
     * TV preferred-MIME order, highest-quality first. AAC sits last as the
     * ever-present fallback — filtering against [reachableAudioMimes]
     * guarantees it's still in the output because AAC is always reachable.
     */
    private val TV_DESIRED_ORDER = listOf(
        MimeTypes.AUDIO_TRUEHD,
        MimeTypes.AUDIO_E_AC3_JOC,
        MimeTypes.AUDIO_E_AC3,
        MimeTypes.AUDIO_DTS_HD,
        MimeTypes.AUDIO_AC4,
        MimeTypes.AUDIO_DTS,
        MimeTypes.AUDIO_DTS_EXPRESS,
        MimeTypes.AUDIO_ALAC,
        MimeTypes.AUDIO_AC3,
        MimeTypes.AUDIO_AAC,
    )

    private fun TrackSelectionParameters.toDefaultBuilder(
        context: Context,
    ): DefaultTrackSelector.Parameters.Builder {
        // The factory always builds players with a DefaultTrackSelector, so
        // `player.trackSelectionParameters` is a DefaultTrackSelector.Parameters
        // in every call path. The fallback exists only for test doubles or a
        // future refactor that changes the selector type.
        return if (this is DefaultTrackSelector.Parameters) {
            buildUpon()
        } else {
            DefaultTrackSelector.Parameters.Builder(context)
        }
    }
}

/** Device-specific exceptions to Media3's native Android TV output paths. */
internal object TvPlaybackOutputPolicy {
    /** Produces the HDR capability that both the decoder and active output support. */
    fun effectiveHdrCapabilities(
        codec: HdrCapabilities,
        display: HdrCapabilities,
    ): HdrCapabilities = DisplayHdrProbe.intersect(codec, display)

    fun shouldEnableTunneling(
        manufacturer: String?,
        model: String?,
        device: String?,
    ): Boolean {
        val normalizedManufacturer = manufacturer.orEmpty().trim().lowercase()
        val normalizedModel = model.orEmpty().trim().lowercase()
        val normalizedDevice = device.orEmpty().trim().lowercase()
        val isGoogleTvStreamer = normalizedManufacturer == "google" &&
            (normalizedModel.contains("google tv streamer") || normalizedDevice == "mustang")
        return !isGoogleTvStreamer
    }

}
