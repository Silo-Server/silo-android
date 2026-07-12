package org.siloserver.silo.common.player

import android.media.MediaCodecInfo.CodecProfileLevel
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import org.siloserver.silo.model.playback.HdrCapabilities
import org.siloserver.silo.model.playback.VideoDecodeCapability

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
        val videoDecodeCapabilities: List<VideoDecodeCapability>,
        val hdr: HdrCapabilities,
        val maxResolution: String,
        val supportsDvProfile7: Boolean,
    )

    private data class MutableVideoDecodeCapability(
        val profiles: MutableSet<String> = sortedSetOf(),
        val levels: MutableSet<Int> = sortedSetOf(),
        val bitDepths: MutableSet<Int> = sortedSetOf(),
        var maxWidth: Int = 0,
        var maxHeight: Int = 0,
        var maxFrameRate: Double = 0.0,
        var maxBitrateKbps: Int = 0,
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
        val detailedVideo = mutableMapOf<String, MutableVideoDecodeCapability>()
        var hdr10 = false
        var hdr10p = false
        var hlg = false
        val dv = sortedSetOf<Int>()
        var dvP7DecoderMultiInstance = false
        var hevcMultiInstance = false
        var hevcHdrCapable = false

        for (info in list.codecInfos) {
            if (info.isEncoder) continue
            for (type in info.supportedTypes) {
                val caps = runCatching { info.getCapabilitiesForType(type) }.getOrNull() ?: continue
                val height = caps.videoCapabilities?.supportedHeights?.upper ?: 0

                codecName(type)?.takeIf { isHardwareDecoder(info) }?.let { codec ->
                    val evidence = detailedVideo.getOrPut(codec) { MutableVideoDecodeCapability() }
                    val video = caps.videoCapabilities
                    evidence.maxWidth = maxOf(evidence.maxWidth, video?.supportedWidths?.upper ?: 0)
                    evidence.maxHeight = maxOf(evidence.maxHeight, video?.supportedHeights?.upper ?: 0)
                    evidence.maxFrameRate = maxOf(
                        evidence.maxFrameRate,
                        (video?.supportedFrameRates?.upper ?: 0.0).toDouble(),
                    )
                    evidence.maxBitrateKbps = maxOf(
                        evidence.maxBitrateKbps,
                        (video?.bitrateRange?.upper ?: 0) / 1_000,
                    )
                    caps.profileLevels.forEach { profileLevel ->
                        profileName(codec, profileLevel.profile)?.let(evidence.profiles::add)
                        profileBitDepth(codec, profileLevel.profile)?.let(evidence.bitDepths::add)
                        normalizedLevel(codec, profileLevel.level)?.let(evidence.levels::add)
                    }
                }

                fun track(name: String) {
                    videoMaxHeights.merge(name, height) { a, b -> maxOf(a, b) }
                }

                when {
                    type.equals(MediaFormat.MIMETYPE_VIDEO_HEVC, ignoreCase = true) -> {
                        track("hevc")
                        hevcMultiInstance = runCatching { caps.maxSupportedInstances >= 2 }
                            .getOrDefault(false)
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
                                CodecProfileLevel.DolbyVisionProfileDvheDtr -> dv += 4
                                CodecProfileLevel.DolbyVisionProfileDvheDth -> dv += 6
                                CodecProfileLevel.DolbyVisionProfileDvheDtb -> {
                                    dv += 7
                                    if (multiInstance) dvP7DecoderMultiInstance = true
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
        val dvP7Supported = dvP7DecoderMultiInstance && hevcMultiInstance
        val dvProfiles = if (dvP7Supported || !dv.contains(7)) dv.toList()
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
            videoDecodeCapabilities = advertisedVideo.mapNotNull { codec ->
                detailedVideo[codec]?.let { evidence ->
                    VideoDecodeCapability(
                        codec = codec,
                        profiles = evidence.profiles.toList(),
                        levels = evidence.levels.toList(),
                        bitDepths = evidence.bitDepths.toList(),
                        maxWidth = evidence.maxWidth.takeIf { it > 0 },
                        maxHeight = evidence.maxHeight.takeIf { it > 0 },
                        maxFrameRate = evidence.maxFrameRate.takeIf { it > 0.0 },
                        maxBitrateKbps = evidence.maxBitrateKbps.takeIf { it > 0 },
                        hardware = true,
                    )
                }
            }.sortedBy { it.codec },
            hdr = HdrCapabilities(
                hdr10 = effectiveHdr10,
                hdr10Plus = effectiveHdr10p,
                hlg = effectiveHlg,
                dolbyVisionProfiles = dvProfiles,
            ),
            maxResolution = claimedBucket,
            supportsDvProfile7 = dvP7Supported,
        )
    }

    private fun codecName(mimeType: String): String? = when {
        mimeType.equals(MediaFormat.MIMETYPE_VIDEO_AVC, ignoreCase = true) -> "h264"
        mimeType.equals(MediaFormat.MIMETYPE_VIDEO_HEVC, ignoreCase = true) -> "hevc"
        mimeType.equals(MediaFormat.MIMETYPE_VIDEO_VP9, ignoreCase = true) -> "vp9"
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            mimeType.equals(MediaFormat.MIMETYPE_VIDEO_AV1, ignoreCase = true) -> "av1"
        else -> null
    }

    private fun isHardwareDecoder(info: android.media.MediaCodecInfo): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            info.isHardwareAccelerated
        } else {
            val name = info.name.lowercase()
            !name.startsWith("omx.google.") && !name.startsWith("c2.android.")
        }

    internal fun profileName(codec: String, profile: Int): String? = when (codec) {
        "h264" -> when (profile) {
            CodecProfileLevel.AVCProfileBaseline -> "baseline"
            CodecProfileLevel.AVCProfileMain -> "main"
            CodecProfileLevel.AVCProfileExtended -> "extended"
            CodecProfileLevel.AVCProfileHigh -> "high"
            CodecProfileLevel.AVCProfileHigh10 -> "high 10"
            CodecProfileLevel.AVCProfileHigh422 -> "high 4:2:2"
            CodecProfileLevel.AVCProfileHigh444 -> "high 4:4:4 predictive"
            else -> null
        }
        "hevc" -> when (profile) {
            CodecProfileLevel.HEVCProfileMain -> "main"
            CodecProfileLevel.HEVCProfileMain10,
            CodecProfileLevel.HEVCProfileMain10HDR10,
            CodecProfileLevel.HEVCProfileMain10HDR10Plus,
            -> "main 10"
            else -> null
        }
        "vp9" -> when (profile) {
            CodecProfileLevel.VP9Profile0 -> "profile 0"
            CodecProfileLevel.VP9Profile1 -> "profile 1"
            CodecProfileLevel.VP9Profile2,
            CodecProfileLevel.VP9Profile2HDR,
            CodecProfileLevel.VP9Profile2HDR10Plus,
            -> "profile 2"
            CodecProfileLevel.VP9Profile3,
            CodecProfileLevel.VP9Profile3HDR,
            CodecProfileLevel.VP9Profile3HDR10Plus,
            -> "profile 3"
            else -> null
        }
        "av1" -> when (profile) {
            CodecProfileLevel.AV1ProfileMain8 -> "main"
            CodecProfileLevel.AV1ProfileMain10,
            CodecProfileLevel.AV1ProfileMain10HDR10,
            CodecProfileLevel.AV1ProfileMain10HDR10Plus,
            -> "main"
            else -> null
        }
        else -> null
    }

    internal fun profileBitDepth(codec: String, profile: Int): Int? = when (codec) {
        "h264" -> when (profile) {
            CodecProfileLevel.AVCProfileHigh10,
            CodecProfileLevel.AVCProfileHigh422,
            CodecProfileLevel.AVCProfileHigh444,
            -> 10
            else -> 8
        }
        "hevc" -> when (profile) {
            CodecProfileLevel.HEVCProfileMain -> 8
            CodecProfileLevel.HEVCProfileMain10,
            CodecProfileLevel.HEVCProfileMain10HDR10,
            CodecProfileLevel.HEVCProfileMain10HDR10Plus,
            -> 10
            else -> null
        }
        "vp9" -> when (profile) {
            CodecProfileLevel.VP9Profile0, CodecProfileLevel.VP9Profile1 -> 8
            CodecProfileLevel.VP9Profile2,
            CodecProfileLevel.VP9Profile2HDR,
            CodecProfileLevel.VP9Profile2HDR10Plus,
            CodecProfileLevel.VP9Profile3,
            CodecProfileLevel.VP9Profile3HDR,
            CodecProfileLevel.VP9Profile3HDR10Plus,
            -> 10
            else -> null
        }
        "av1" -> when (profile) {
            CodecProfileLevel.AV1ProfileMain8 -> 8
            CodecProfileLevel.AV1ProfileMain10,
            CodecProfileLevel.AV1ProfileMain10HDR10,
            CodecProfileLevel.AV1ProfileMain10HDR10Plus,
            -> 10
            else -> null
        }
        else -> null
    }

    internal fun normalizedLevel(codec: String, level: Int): Int? = when (codec) {
        "h264" -> AVC_LEVELS[level]
        "hevc" -> HEVC_LEVELS[level]
        else -> null
    }

    private val AVC_LEVELS = mapOf(
        CodecProfileLevel.AVCLevel1 to 10,
        CodecProfileLevel.AVCLevel1b to 9,
        CodecProfileLevel.AVCLevel11 to 11,
        CodecProfileLevel.AVCLevel12 to 12,
        CodecProfileLevel.AVCLevel13 to 13,
        CodecProfileLevel.AVCLevel2 to 20,
        CodecProfileLevel.AVCLevel21 to 21,
        CodecProfileLevel.AVCLevel22 to 22,
        CodecProfileLevel.AVCLevel3 to 30,
        CodecProfileLevel.AVCLevel31 to 31,
        CodecProfileLevel.AVCLevel32 to 32,
        CodecProfileLevel.AVCLevel4 to 40,
        CodecProfileLevel.AVCLevel41 to 41,
        CodecProfileLevel.AVCLevel42 to 42,
        CodecProfileLevel.AVCLevel5 to 50,
        CodecProfileLevel.AVCLevel51 to 51,
        CodecProfileLevel.AVCLevel52 to 52,
        CodecProfileLevel.AVCLevel6 to 60,
        CodecProfileLevel.AVCLevel61 to 61,
        CodecProfileLevel.AVCLevel62 to 62,
    )

    private val HEVC_LEVELS = mapOf(
        CodecProfileLevel.HEVCMainTierLevel1 to 30,
        CodecProfileLevel.HEVCHighTierLevel1 to 30,
        CodecProfileLevel.HEVCMainTierLevel2 to 60,
        CodecProfileLevel.HEVCHighTierLevel2 to 60,
        CodecProfileLevel.HEVCMainTierLevel21 to 63,
        CodecProfileLevel.HEVCHighTierLevel21 to 63,
        CodecProfileLevel.HEVCMainTierLevel3 to 90,
        CodecProfileLevel.HEVCHighTierLevel3 to 90,
        CodecProfileLevel.HEVCMainTierLevel31 to 93,
        CodecProfileLevel.HEVCHighTierLevel31 to 93,
        CodecProfileLevel.HEVCMainTierLevel4 to 120,
        CodecProfileLevel.HEVCHighTierLevel4 to 120,
        CodecProfileLevel.HEVCMainTierLevel41 to 123,
        CodecProfileLevel.HEVCHighTierLevel41 to 123,
        CodecProfileLevel.HEVCMainTierLevel5 to 150,
        CodecProfileLevel.HEVCHighTierLevel5 to 150,
        CodecProfileLevel.HEVCMainTierLevel51 to 153,
        CodecProfileLevel.HEVCHighTierLevel51 to 153,
        CodecProfileLevel.HEVCMainTierLevel52 to 156,
        CodecProfileLevel.HEVCHighTierLevel52 to 156,
        CodecProfileLevel.HEVCMainTierLevel6 to 180,
        CodecProfileLevel.HEVCHighTierLevel6 to 180,
        CodecProfileLevel.HEVCMainTierLevel61 to 183,
        CodecProfileLevel.HEVCHighTierLevel61 to 183,
        CodecProfileLevel.HEVCMainTierLevel62 to 186,
        CodecProfileLevel.HEVCHighTierLevel62 to 186,
    )

    /**
     * Map the decoder's maximum supported height (in pixels) to the canonical
     * resolution label the server's playback resolver expects.
     *
     * The server's `access.qualityRank` map in
     * `Silo/internal/access/quality.go` keys off `480P`/`720P`/`1080P`/
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
