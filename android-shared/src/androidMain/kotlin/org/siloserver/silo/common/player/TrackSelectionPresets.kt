package org.siloserver.silo.common.player

import android.content.Context
import android.os.Build
import androidx.media3.common.MimeTypes
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import org.siloserver.silo.model.playback.AudioPassthroughCapabilities
import org.siloserver.silo.model.playback.HdrCapabilities

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

    /**
     * TV: prioritize passthrough and let Media3 use the platform's native
     * audio-output and hardware A/V-sync paths where the device supports them.
     *
     * Google TV Streamer has a confirmed tunneled-startup stall, so only that
     * device family keeps tunneling disabled. Disabling tunneling globally made
     * Shield-class devices use Media3's software-timed PCM path even though the
     * hardware A/V-sync path is available.
     *
     * [ffmpegAvailable] defaults to probing the runtime classpath via
     * [FfmpegAudioSupport.isAvailable]; tests override it directly. When
     * true, the preferred-MIME list widens to include FFmpeg-reachable
     * audio codecs (TrueHD, DTS-HD, etc.) regardless of passthrough
     * support — the platform renderer still wins selection on
     * passthrough-capable routes because its `supportsFormat` score beats
     * FFmpeg's. TV route planning no longer advertises extension-only decode,
     * so FFmpeg remains here for forced-original and runtime recovery only.
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

        preferredAudioLanguage?.takeIf { it.isNotBlank() }
            ?.let { builder.setPreferredAudioLanguage(it) }

        return builder.build()
    }

    /**
     * Phone: offload-friendly, tunneling off, downmix when the sink can't
     * carry the channel count.
     *
     * [ffmpegAvailable] behaves as in [buildTvParameters] — widens the
     * preferred-MIME list to include FFmpeg-reachable codecs when the
     * extension is on the classpath.
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

        preferredAudioLanguage?.takeIf { it.isNotBlank() }
            ?.let { builder.setPreferredAudioLanguage(it) }

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
    ): List<String> {
        // Union of MIMEs the sink can passthrough + MIMEs FFmpeg can decode
        // locally. Announcing a codec neither route can reach just burns a
        // failed selection attempt, so we filter down to the union.
        val reachable = reachableAudioMimes(caps, ffmpegAvailable)
        return TV_DESIRED_ORDER.filter { it in reachable }
    }

    internal fun buildPhoneAudioMimePreferences(
        caps: AudioPassthroughCapabilities,
        spatializerOn: Boolean,
        ffmpegAvailable: Boolean,
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
        val reachable = reachableAudioMimes(caps, ffmpegAvailable)
        val desired = buildList {
            if (spatializerOn) {
                add(MimeTypes.AUDIO_E_AC3_JOC)
                add(MimeTypes.AUDIO_AC4)
            }
            add(MimeTypes.AUDIO_E_AC3)
            add(MimeTypes.AUDIO_TRUEHD)
            add(MimeTypes.AUDIO_DTS_HD)
            add(MimeTypes.AUDIO_DTS)
            add(MimeTypes.AUDIO_AC3)
            add(MimeTypes.AUDIO_AAC)
        }
        return desired.distinct().filter { it in reachable }
    }

    /**
     * MIMEs playable on this device — union of passthrough-reachable codecs
     * and, when the FFmpeg audio extension is on the classpath, the
     * FFmpeg-decodable codecs. AAC is always included (every Android
     * device has an AAC decoder).
     */
    private fun reachableAudioMimes(
        caps: AudioPassthroughCapabilities,
        ffmpegAvailable: Boolean,
    ): Set<String> {
        val result = mutableSetOf(MimeTypes.AUDIO_AAC)
        caps.passthroughCodecs.forEach { code ->
            passthroughCodeToMime(code)?.let(result::add)
        }
        if (ffmpegAvailable) result += FfmpegAudioSupport.mimeTypes
        return result
    }

    private fun passthroughCodeToMime(code: String): String? = when (code) {
        "eac3_joc" -> MimeTypes.AUDIO_E_AC3_JOC
        "truehd"   -> MimeTypes.AUDIO_TRUEHD
        "ac4"      -> MimeTypes.AUDIO_AC4
        "eac3"     -> MimeTypes.AUDIO_E_AC3
        "dts_hd"   -> MimeTypes.AUDIO_DTS_HD
        "dts"      -> MimeTypes.AUDIO_DTS
        "ac3"      -> MimeTypes.AUDIO_AC3
        else       -> null
    }

    /**
     * TV preferred-MIME order, highest-quality first. AAC sits last as the
     * ever-present fallback — filtering against [reachableAudioMimes]
     * guarantees it's still in the output because AAC is always reachable.
     */
    private val TV_DESIRED_ORDER = listOf(
        MimeTypes.AUDIO_E_AC3_JOC,
        MimeTypes.AUDIO_TRUEHD,
        MimeTypes.AUDIO_AC4,
        MimeTypes.AUDIO_E_AC3,
        MimeTypes.AUDIO_DTS_HD,
        MimeTypes.AUDIO_DTS,
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
