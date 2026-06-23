package com.continuum.app.common.player

import android.media.MediaCodecInfo.CodecProfileLevel
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import com.continuum.app.model.playback.HdrCapabilities

/**
 * Probes the device's `MediaCodec` decoder list to determine which video
 * codecs and HDR profiles can be direct-played. Modeled on jellyfin-androidtv's
 * `MediaCodecCapabilitiesTest.kt`.
 *
 * Dolby Vision Profile 7 (dual-layer BL+EL) needs two concurrent HEVC decoder
 * instances. We approximate via [android.media.MediaCodecInfo.CodecCapabilities.getMaxSupportedInstances]:
 * the codec must advertise ≥2 instances or the device almost certainly cannot
 * decode P7 content. Outside NVIDIA Shield this gate generally fails.
 */
object MediaCodecCapabilitiesProbe {

    data class ProbeResult(
        val videoCodecs: Set<String>,
        val hdr: HdrCapabilities,
        val maxResolution: String,
        val supportsDvProfile7: Boolean,
    )

    // Decoder/HDR support is static for the process lifetime; the MediaCodecList
    // enumeration is expensive and was being run up to 4× per playback start
    // (detect() runs it twice and is itself called twice). Probe once, cache.
    @Volatile
    private var cached: ProbeResult? = null

    fun probe(): ProbeResult =
        cached ?: synchronized(this) { cached ?: computeProbe().also { cached = it } }

    private fun computeProbe(): ProbeResult {
        val list = MediaCodecList(MediaCodecList.REGULAR_CODECS)
        // Track the largest supported height PER codec rather than a single
        // global max. Otherwise one beefy decoder (e.g. H.264 at 4K) lets us
        // claim `maxResolution=2160p` for every other codec name we collect,
        // including low-ceiling ones (e.g. emulator's software HEVC stopping
        // at 1080p). The server's flat `codecs_video` + `max_resolution`
        // protocol has no way to express per-codec ceilings, so we filter
        // under-spec'd codecs out of the payload instead of lying.
        val videoMaxHeights = mutableMapOf<String, Int>()
        var hdr10 = false
        var hdr10p = false
        var hlg = false
        val dv = sortedSetOf<Int>()
        var dvP7MultiInstance = false
        var hevcHdrCapable = false

        for (info in list.codecInfos) {
            if (info.isEncoder) continue
            for (type in info.supportedTypes) {
                val caps = runCatching { info.getCapabilitiesForType(type) }.getOrNull() ?: continue
                val height = caps.videoCapabilities?.supportedHeights?.upper ?: 0

                fun track(name: String) {
                    videoMaxHeights.merge(name, height) { a, b -> maxOf(a, b) }
                }

                when {
                    type.equals(MediaFormat.MIMETYPE_VIDEO_HEVC, ignoreCase = true) -> {
                        track("hevc")
                        for (pl in caps.profileLevels) when (pl.profile) {
                            CodecProfileLevel.HEVCProfileMain10HDR10 -> {
                                hdr10 = true
                                hevcHdrCapable = true
                            }
                            CodecProfileLevel.HEVCProfileMain10HDR10Plus -> {
                                hdr10 = true
                                hdr10p = true
                                hevcHdrCapable = true
                            }
                        }
                    }
                    type.equals(MediaFormat.MIMETYPE_VIDEO_DOLBY_VISION, ignoreCase = true) -> {
                        track("dolby_vision")
                        val multiInstance = runCatching { caps.maxSupportedInstances >= 2 }
                            .getOrDefault(false)
                        for (pl in caps.profileLevels) {
                            when (pl.profile) {
                                CodecProfileLevel.DolbyVisionProfileDvheStn -> dv += 5
                                CodecProfileLevel.DolbyVisionProfileDvheDtr,
                                CodecProfileLevel.DolbyVisionProfileDvheDth,
                                CodecProfileLevel.DolbyVisionProfileDvheDtb -> {
                                    dv += 7
                                    if (multiInstance) dvP7MultiInstance = true
                                }
                                CodecProfileLevel.DolbyVisionProfileDvheSt -> dv += 8
                            }
                        }
                    }
                    type.equals(MediaFormat.MIMETYPE_VIDEO_AV1, ignoreCase = true) -> {
                        track("av1")
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            for (pl in caps.profileLevels) when (pl.profile) {
                                CodecProfileLevel.AV1ProfileMain10HDR10 -> hdr10 = true
                                CodecProfileLevel.AV1ProfileMain10HDR10Plus -> {
                                    hdr10 = true
                                    hdr10p = true
                                }
                            }
                        }
                    }
                    type.equals(MediaFormat.MIMETYPE_VIDEO_AVC, ignoreCase = true) -> track("h264")
                    type.equals(MediaFormat.MIMETYPE_VIDEO_VP9, ignoreCase = true) -> track("vp9")
                }
            }
        }

        // HLG (BBC's HDR transfer used by broadcast) is implied on any device
        // that decodes Main10 HDR10 — the codec doesn't gate transfers, the
        // panel does. The DisplayHdrProbe gates the actual claim.
        if (hdr10) hlg = true

        // DV P7 strictly requires multi-instance HEVC. Strip the "7" claim if
        // the only DV decoder reported it without enough concurrent instances.
        val dvProfiles = if (dvP7MultiInstance || !dv.contains(7)) dv.toList()
        else dv.filterNot { it == 7 }

        val overallMaxH = videoMaxHeights.values.maxOrNull() ?: 0
        val claimedBucket = resolutionBucket(overallMaxH)
        val claimedFloor = heightFloorForBucket(claimedBucket)

        // Only advertise codecs whose per-codec ceiling reaches the claimed
        // maxResolution — otherwise the server picks DIRECT for a file that
        // the selected codec can't actually decode, and the MediaCodec
        // pipeline crashes at runtime with NO_EXCEEDS_CAPABILITIES. When a
        // codec is dropped, the server falls through to its alternate-file
        // / transcode paths, which is always preferable to a decoder crash.
        val advertisedVideo = videoMaxHeights
            .filterValues { it >= claimedFloor }
            .keys

        // If HEVC dropped out of the advertised set, the HDR claim we picked
        // up from its profileLevels is meaningless (nothing in `advertisedVideo`
        // can decode HDR10 HEVC on this device). Clear it so the server
        // doesn't route HDR content to a decoder we can't actually drive.
        val hdrSurvives = advertisedVideo.any {
            it == "av1" || it == "dolby_vision" || (it == "hevc" && hevcHdrCapable)
        }
        val effectiveHdr10 = hdr10 && hdrSurvives
        val effectiveHdr10p = hdr10p && hdrSurvives
        val effectiveHlg = hlg && hdrSurvives

        // Don't gate DV signaling on `advertisedVideo`. The bucket-filter
        // upstream drops the DV codec when its supported height is below
        // the global claimed bucket — but on phones that pair an 8K HEVC /
        // AV1 decoder with a 4K-cap Dolby Vision decoder (S26 Ultra et al.)
        // that strips legitimate DV Profile 8 support, the server then
        // picks DIRECT for DV files anyway, and the preflight listener
        // bounces every play into a transcode fallback the path can't
        // recover from. `dvProfiles` / `dvP7MultiInstance` already reflect
        // whether the DV decoder was probed at all.
        return ProbeResult(
            videoCodecs = advertisedVideo,
            hdr = HdrCapabilities(
                hdr10 = effectiveHdr10,
                hdr10Plus = effectiveHdr10p,
                hlg = effectiveHlg,
                dolbyVisionProfiles = dvProfiles,
            ),
            maxResolution = claimedBucket,
            supportsDvProfile7 = dvP7MultiInstance,
        )
    }

    /**
     * Map the decoder's maximum supported height (in pixels) to the canonical
     * resolution label the server's playback resolver expects.
     *
     * The server's `access.qualityRank` map in
     * `Continuum/internal/access/quality.go` keys off `480P`/`720P`/`1080P`/
     * `2160P`/`4320P` — case-folded by `CompareQuality` but shape-sensitive.
     * Any unknown string (e.g. `"4k"`, `"8k"`, `"UHD"`) ranks as 0, which
     * makes `resolutionFits(fileRes, maxRes)` fail for EVERY file and
     * unconditionally forces TRANSCODE server-side even for codec-and-
     * container-compatible direct-playable files.
     *
     * `internal` so the companion unit test can exercise every bucket
     * without needing an Android `MediaCodecList`.
     */
    internal fun resolutionBucket(maxHeight: Int): String = when {
        maxHeight >= 4320 -> "4320p"
        maxHeight >= 2160 -> "2160p"
        maxHeight >= 1440 -> "1440p"
        maxHeight >= 1080 -> "1080p"
        maxHeight >= 720 -> "720p"
        else -> "sd"
    }

    /**
     * The minimum vertical pixel count a decoder must support to "qualify"
     * for inclusion at the given advertised bucket. Inverse of
     * [resolutionBucket]: `resolutionBucket(heightFloorForBucket(b)) == b`
     * for every valid bucket string.
     *
     * `internal` for unit testing against [resolutionBucket].
     */
    internal fun heightFloorForBucket(bucket: String): Int = when (bucket) {
        "4320p" -> 4320
        "2160p" -> 2160
        "1440p" -> 1440
        "1080p" -> 1080
        "720p" -> 720
        else -> 0
    }
}
