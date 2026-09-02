package org.siloserver.silo.common.player

import android.media.MediaCodecInfo.CodecProfileLevel
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import org.siloserver.silo.model.playback.DolbyVisionProfileCapability
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

    // Decoder/HDR support is static for the process lifetime; the MediaCodecList
    // enumeration is expensive and was being run up to 4× per playback start
    // (detect() runs it twice and is itself called twice). Probe once, cache.
    @Volatile
    private var cached: ProbeResult? = null

    fun probe(): ProbeResult =
        cached ?: synchronized(this) { cached ?: computeProbe().also { cached = it } }

    /** Returns an immutable copy for diagnostics without reconfiguring or opening any codec. */
    fun diagnosticsSnapshot(): ProbeResult {
        val result = probe()
        return result.copy(
            videoCodecs = result.videoCodecs.toSet(),
            videoDecodeCapabilities = result.videoDecodeCapabilities.map { capability ->
                capability.copy(
                    profiles = capability.profiles.toList(),
                    levels = capability.levels.toList(),
                    bitDepths = capability.bitDepths.toList(),
                )
            },
            hdr = result.hdr.copy(
                dolbyVisionProfiles = result.hdr.dolbyVisionProfiles.toList(),
                dolbyVisionProfileLevels = result.hdr.dolbyVisionProfileLevels.toList(),
            ),
        )
    }

    private fun computeProbe(): ProbeResult {
        val list = MediaCodecList(MediaCodecList.REGULAR_CODECS)
        // Keep per-codec limits. Android devices commonly have asymmetric
        // decoders (for example 4K AVC/HEVC but 1080p VP9 or VC-1). Protocol V3
        // carries these bounds, so a lower-ceiling codec must not be discarded
        // merely because another decoder establishes a higher global bucket.
        val videoMaxHeights = mutableMapOf<String, Int>()
        val detailedVideo = mutableListOf<VideoDecodeCapability>()
        var hdr10 = false
        var hdr10p = false
        var hlg = false
        val dv = sortedSetOf<Int>()
        // Highest Dolby Vision level any decoder reports per profile. Sent as
        // dolby_vision_profile_levels so the server can refuse a level-9
        // stream on a level-6 decoder instead of assuming unbounded support.
        val dvMaxLevels = mutableMapOf<Int, Int>()
        var dvP7DecoderMultiInstance = false
        var hevcMultiInstance = false
        var hevcHdrCapable = false

        for (info in list.codecInfos) {
            if (info.isEncoder) continue
            if (!isHardwareDecoder(info)) continue
            for (type in info.supportedTypes) {
                val caps = runCatching { info.getCapabilitiesForType(type) }.getOrNull() ?: continue
                val height = caps.videoCapabilities?.supportedHeights?.upper ?: 0

                codecName(type)?.let { codec ->
                    detailedVideo += buildVideoDecodeCapability(codec, info.name, caps)
                }

                fun track(name: String) {
                    videoMaxHeights.merge(name, height) { a, b -> maxOf(a, b) }
                }

                when {
                    type.equals(MediaFormat.MIMETYPE_VIDEO_HEVC, ignoreCase = true) -> {
                        track("hevc")
                        hevcMultiInstance = hevcMultiInstance ||
                            runCatching { caps.maxSupportedInstances >= 2 }.getOrDefault(false)
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
                            val profile = dolbyVisionProfileNumber(pl.profile) ?: continue
                            dv += profile
                            if (profile == 7) {
                                dvP7DecoderMultiInstance = dvP7DecoderMultiInstance || multiInstance
                            }
                            dolbyVisionLevelNumber(pl.level)?.let { level ->
                                dvMaxLevels[profile] = maxOf(dvMaxLevels[profile] ?: 0, level)
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
                    type.equals(MediaFormat.MIMETYPE_VIDEO_VP8, ignoreCase = true) -> track("vp8")
                    type.equals(MediaFormat.MIMETYPE_VIDEO_MPEG2, ignoreCase = true) -> track("mpeg2video")
                    type.equals(MediaFormat.MIMETYPE_VIDEO_MPEG4, ignoreCase = true) -> track("mpeg4")
                    type.equals(MediaFormat.MIMETYPE_VIDEO_H263, ignoreCase = true) -> track("h263")
                    isVc1Mime(type) -> track("vc1")
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
        // The server requires bounds for every advertised profile once any
        // are present. A profile whose decoder reported no recognizable level
        // therefore keeps the legacy unbounded contract for the whole list.
        val dvProfileLevels = if (dvProfiles.all { it in dvMaxLevels }) {
            dvProfiles.map { profile ->
                DolbyVisionProfileCapability(profile = profile, maxLevel = dvMaxLevels.getValue(profile))
            }
        } else {
            emptyList()
        }

        val overallMaxH = videoMaxHeights.values.maxOrNull() ?: 0
        val claimedBucket = resolutionBucket(overallMaxH)
        val advertisedVideo = videoMaxHeights.keys

        // An HDR profile from a non-video or unusable decoder is meaningless.
        // Keep the claim only while its hardware codec survived the probe.
        val hdrSurvives = advertisedVideo.any {
            it == "av1" || it == "dolby_vision" || (it == "hevc" && hevcHdrCapable)
        }
        val effectiveHdr10 = hdr10 && hdrSurvives
        val effectiveHdr10p = hdr10p && hdrSurvives
        val effectiveHlg = hlg && hdrSurvives

        return ProbeResult(
            videoCodecs = advertisedVideo,
            videoDecodeCapabilities = detailedVideo.sortedWith(
                compareBy(VideoDecodeCapability::codec, VideoDecodeCapability::decoderName),
            ),
            hdr = HdrCapabilities(
                hdr10 = effectiveHdr10,
                hdr10Plus = effectiveHdr10p,
                hlg = effectiveHlg,
                dolbyVisionProfiles = dvProfiles,
                dolbyVisionProfileLevels = dvProfileLevels,
            ),
            maxResolution = claimedBucket,
            supportsDvProfile7 = dvP7Supported,
        )
    }

    internal fun codecName(mimeType: String): String? = when {
        mimeType.equals(MediaFormat.MIMETYPE_VIDEO_AVC, ignoreCase = true) -> "h264"
        mimeType.equals(MediaFormat.MIMETYPE_VIDEO_HEVC, ignoreCase = true) -> "hevc"
        mimeType.equals(MediaFormat.MIMETYPE_VIDEO_VP9, ignoreCase = true) -> "vp9"
        mimeType.equals(MediaFormat.MIMETYPE_VIDEO_VP8, ignoreCase = true) -> "vp8"
        mimeType.equals(MediaFormat.MIMETYPE_VIDEO_AV1, ignoreCase = true) -> "av1"
        mimeType.equals(MediaFormat.MIMETYPE_VIDEO_DOLBY_VISION, ignoreCase = true) -> "dolby_vision"
        mimeType.equals(MediaFormat.MIMETYPE_VIDEO_MPEG2, ignoreCase = true) -> "mpeg2video"
        mimeType.equals(MediaFormat.MIMETYPE_VIDEO_MPEG4, ignoreCase = true) -> "mpeg4"
        mimeType.equals(MediaFormat.MIMETYPE_VIDEO_H263, ignoreCase = true) -> "h263"
        isVc1Mime(mimeType) -> "vc1"
        else -> null
    }

    internal fun isVc1Mime(mimeType: String): Boolean =
        mimeType.equals("video/wvc1", ignoreCase = true) ||
            mimeType.equals("video/x-ms-wmv", ignoreCase = true) ||
            mimeType.equals("video/vc1", ignoreCase = true)

    private fun buildVideoDecodeCapability(
        codec: String,
        decoderName: String,
        caps: android.media.MediaCodecInfo.CodecCapabilities,
    ): VideoDecodeCapability {
        val profiles = sortedSetOf<String>()
        val levels = sortedSetOf<Int>()
        val bitDepths = sortedSetOf<Int>()
        caps.profileLevels.forEach { profileLevel ->
            profileName(codec, profileLevel.profile)?.let(profiles::add)
            profileBitDepth(codec, profileLevel.profile)?.let(bitDepths::add)
            normalizedLevel(codec, profileLevel.level)?.let(levels::add)
        }
        val video = caps.videoCapabilities
        val limits = video?.let(::conservativeVideoLimits)
        return VideoDecodeCapability(
            codec = codec,
            decoderName = decoderName,
            profiles = profiles.toList(),
            levels = levels.toList(),
            bitDepths = bitDepths.toList(),
            maxWidth = limits?.maxWidth?.takeIf { it > 0 },
            maxHeight = limits?.maxHeight?.takeIf { it > 0 },
            maxFrameRate = limits?.maxFrameRate?.takeIf { it > 0.0 },
            maxBitrateKbps = ((video?.bitrateRange?.upper ?: 0) / 1_000).takeIf { it > 0 },
            hardware = true,
        )
    }

    /**
     * Vendor frame-rate ranges are frequently global (one Amlogic decoder
     * reports 960 fps even though its 4K performance point is 30 fps). Tie the
     * claim to the largest standard size the decoder accepts and, on API 29+,
     * cap it with the advertised performance points.
     */
    private fun conservativeVideoLimits(
        video: android.media.MediaCodecInfo.VideoCapabilities,
    ): ConservativeVideoLimits {
        val largestSupportedSize = STANDARD_VIDEO_SIZES.firstOrNull { (width, height) ->
            runCatching { video.isSizeSupported(width, height) }.getOrDefault(false)
        }
        val points = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching { video.supportedPerformancePoints }.getOrNull().orEmpty()
        } else {
            emptyList()
        }
        val performanceBoundedSize = points.takeIf { it.isNotEmpty() }?.let {
            STANDARD_VIDEO_SIZES.firstOrNull { (width, height) ->
                runCatching { video.isSizeSupported(width, height) }.getOrDefault(false) &&
                    it.any { point ->
                        point.covers(
                            android.media.MediaCodecInfo.VideoCapabilities.PerformancePoint(
                                width,
                                height,
                                24,
                            ),
                        )
                    }
            }
        }
        val rateSize = performanceBoundedSize ?: largestSupportedSize
        val rangeUpper = rateSize?.let { (width, height) ->
            runCatching { video.getSupportedFrameRatesFor(width, height).upper }.getOrNull()
        } ?: video.supportedFrameRates?.upper?.toDouble() ?: 0.0
        val performanceRate = if (points.isNotEmpty() && rateSize != null) {
            STANDARD_FRAME_RATES.firstOrNull { rate ->
                val requested = android.media.MediaCodecInfo.VideoCapabilities.PerformancePoint(
                    rateSize.first,
                    rateSize.second,
                    rate,
                )
                points.any { point -> point.covers(requested) }
            }?.toDouble()
        } else {
            null
        }
        return ConservativeVideoLimits(
            maxWidth = performanceBoundedSize?.first ?: video.supportedWidths?.upper ?: 0,
            maxHeight = performanceBoundedSize?.second ?: video.supportedHeights?.upper ?: 0,
            maxFrameRate = performanceRate?.let { minOf(rangeUpper, it) } ?: rangeUpper,
        )
    }

    private data class ConservativeVideoLimits(
        val maxWidth: Int,
        val maxHeight: Int,
        val maxFrameRate: Double,
    )

    private fun isHardwareDecoder(info: android.media.MediaCodecInfo): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            info.isHardwareAccelerated && !info.isSoftwareOnly
        } else {
            !isLikelySoftwareDecoderName(info.name)
        }

    internal fun isLikelySoftwareDecoderName(codecName: String): Boolean {
        val name = codecName.lowercase()
        return name.startsWith("omx.google.") ||
            name.startsWith("c2.android.") ||
            name.startsWith("c2.google.") ||
            name.startsWith("omx.ffmpeg.") ||
            name.startsWith("omx.pv.") ||
            name.startsWith("omx.avcodec.") ||
            ".software." in name ||
            name.endsWith(".sw.decoder")
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
        "dolby_vision" -> dolbyVisionProfileNumber(profile)?.let { "profile $it" }
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
        "mpeg2video" -> when (profile) {
            CodecProfileLevel.MPEG2ProfileSimple -> "simple"
            CodecProfileLevel.MPEG2ProfileMain -> "main"
            CodecProfileLevel.MPEG2Profile422 -> "4:2:2"
            CodecProfileLevel.MPEG2ProfileSNR -> "snr scalable"
            CodecProfileLevel.MPEG2ProfileSpatial -> "spatially scalable"
            CodecProfileLevel.MPEG2ProfileHigh -> "high"
            else -> null
        }
        "mpeg4" -> when (profile) {
            CodecProfileLevel.MPEG4ProfileSimple -> "simple profile"
            CodecProfileLevel.MPEG4ProfileSimpleScalable -> "simple scalable profile"
            CodecProfileLevel.MPEG4ProfileCore -> "core profile"
            CodecProfileLevel.MPEG4ProfileMain -> "main profile"
            CodecProfileLevel.MPEG4ProfileNbit -> "n-bit profile"
            CodecProfileLevel.MPEG4ProfileScalableTexture -> "scalable texture profile"
            CodecProfileLevel.MPEG4ProfileSimpleFace -> "simple face animation profile"
            CodecProfileLevel.MPEG4ProfileSimpleFBA -> "simple face animation profile"
            CodecProfileLevel.MPEG4ProfileBasicAnimated -> "basic animated texture profile"
            CodecProfileLevel.MPEG4ProfileHybrid -> "hybrid profile"
            CodecProfileLevel.MPEG4ProfileAdvancedRealTime -> "advanced real time simple profile"
            // FFmpeg exposes this historical profile string as "Code
            // Scalable Profile"; match its wire vocabulary exactly.
            CodecProfileLevel.MPEG4ProfileCoreScalable -> "code scalable profile"
            CodecProfileLevel.MPEG4ProfileAdvancedCoding -> "advanced coding profile"
            CodecProfileLevel.MPEG4ProfileAdvancedCore -> "advanced core profile"
            CodecProfileLevel.MPEG4ProfileAdvancedScalable -> "advanced scalable texture profile"
            CodecProfileLevel.MPEG4ProfileAdvancedSimple -> "advanced simple profile"
            else -> null
        }
        "vp8" -> when (profile) {
            CodecProfileLevel.VP8ProfileMain -> "main"
            else -> null
        }
        "h263" -> when (profile) {
            CodecProfileLevel.H263ProfileBaseline -> "baseline"
            CodecProfileLevel.H263ProfileH320Coding -> "h320 coding"
            CodecProfileLevel.H263ProfileBackwardCompatible -> "backward compatible"
            CodecProfileLevel.H263ProfileISWV2 -> "iswv2"
            CodecProfileLevel.H263ProfileISWV3 -> "iswv3"
            CodecProfileLevel.H263ProfileHighCompression -> "high compression"
            CodecProfileLevel.H263ProfileInternet -> "internet"
            CodecProfileLevel.H263ProfileInterlace -> "interlace"
            CodecProfileLevel.H263ProfileHighLatency -> "high latency"
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
            CodecProfileLevel.AVCProfileBaseline,
            CodecProfileLevel.AVCProfileMain,
            CodecProfileLevel.AVCProfileExtended,
            CodecProfileLevel.AVCProfileHigh,
            -> 8
            else -> null
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
        "mpeg2video" -> when (profile) {
            CodecProfileLevel.MPEG2ProfileSimple,
            CodecProfileLevel.MPEG2ProfileMain,
            CodecProfileLevel.MPEG2Profile422,
            CodecProfileLevel.MPEG2ProfileSNR,
            CodecProfileLevel.MPEG2ProfileSpatial,
            CodecProfileLevel.MPEG2ProfileHigh,
            -> 8
            else -> null
        }
        "mpeg4" -> when (profile) {
            CodecProfileLevel.MPEG4ProfileSimple,
            CodecProfileLevel.MPEG4ProfileSimpleScalable,
            CodecProfileLevel.MPEG4ProfileCore,
            CodecProfileLevel.MPEG4ProfileMain,
            CodecProfileLevel.MPEG4ProfileNbit,
            CodecProfileLevel.MPEG4ProfileScalableTexture,
            CodecProfileLevel.MPEG4ProfileSimpleFace,
            CodecProfileLevel.MPEG4ProfileSimpleFBA,
            CodecProfileLevel.MPEG4ProfileBasicAnimated,
            CodecProfileLevel.MPEG4ProfileHybrid,
            CodecProfileLevel.MPEG4ProfileAdvancedRealTime,
            CodecProfileLevel.MPEG4ProfileCoreScalable,
            CodecProfileLevel.MPEG4ProfileAdvancedCoding,
            CodecProfileLevel.MPEG4ProfileAdvancedCore,
            CodecProfileLevel.MPEG4ProfileAdvancedScalable,
            CodecProfileLevel.MPEG4ProfileAdvancedSimple,
            -> 8
            else -> null
        }
        "vp8" -> if (profile == CodecProfileLevel.VP8ProfileMain) 8 else null
        "h263" -> if (profileName("h263", profile) != null) 8 else null
        "dolby_vision" -> if (dolbyVisionProfileNumber(profile) != null) 10 else null
        else -> null
    }

    /** MediaCodec Dolby Vision profile constant → Dolby profile number. */
    internal fun dolbyVisionProfileNumber(profile: Int): Int? = when (profile) {
        CodecProfileLevel.DolbyVisionProfileDvavPer -> 0
        CodecProfileLevel.DolbyVisionProfileDvavPen -> 1
        CodecProfileLevel.DolbyVisionProfileDvheDer -> 2
        CodecProfileLevel.DolbyVisionProfileDvheDen -> 3
        CodecProfileLevel.DolbyVisionProfileDvheDtr -> 4
        CodecProfileLevel.DolbyVisionProfileDvheStn -> 5
        CodecProfileLevel.DolbyVisionProfileDvheDth -> 6
        CodecProfileLevel.DolbyVisionProfileDvheDtb -> 7
        CodecProfileLevel.DolbyVisionProfileDvheSt -> 8
        CodecProfileLevel.DolbyVisionProfileDvavSe -> 9
        else -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            profile == CodecProfileLevel.DolbyVisionProfileDvav110
        ) 10 else null
    }

    /** MediaCodec Dolby Vision level bit → Dolby level number (1..13). */
    internal fun dolbyVisionLevelNumber(level: Int): Int? = when (level) {
        CodecProfileLevel.DolbyVisionLevelHd24 -> 1
        CodecProfileLevel.DolbyVisionLevelHd30 -> 2
        CodecProfileLevel.DolbyVisionLevelFhd24 -> 3
        CodecProfileLevel.DolbyVisionLevelFhd30 -> 4
        CodecProfileLevel.DolbyVisionLevelFhd60 -> 5
        CodecProfileLevel.DolbyVisionLevelUhd24 -> 6
        CodecProfileLevel.DolbyVisionLevelUhd30 -> 7
        CodecProfileLevel.DolbyVisionLevelUhd48 -> 8
        CodecProfileLevel.DolbyVisionLevelUhd60 -> 9
        else -> when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && level == CodecProfileLevel.DolbyVisionLevelUhd120 -> 10
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && level == CodecProfileLevel.DolbyVisionLevel8k30 -> 11
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && level == CodecProfileLevel.DolbyVisionLevel8k60 -> 12
            else -> null
        }
    }

    internal fun normalizedLevel(codec: String, level: Int): Int? = when (codec) {
        "h264" -> AVC_LEVELS[level]
        "hevc" -> HEVC_LEVELS[level]
        "dolby_vision" -> dolbyVisionLevelNumber(level)
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

    private val STANDARD_VIDEO_SIZES = listOf(
        7680 to 4320,
        4096 to 2160,
        3840 to 2160,
        2560 to 1440,
        1920 to 1080,
        1280 to 720,
        720 to 576,
        720 to 480,
    )

    private val STANDARD_FRAME_RATES = listOf(240, 200, 120, 100, 60, 50, 48, 30, 25, 24)

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
