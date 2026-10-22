package org.siloserver.silo.model.playback

/*
 * Why a plan plays at the quality it does ([PlaybackPlanV3.qualityReasons]),
 * as the parts of the line the player shows. Each app turns a
 * [PlaybackQualityReasonText] into its words; the codes and their figures
 * are the server's contract (`plan_quality_reasons_v1`).
 */

/** One quality reason, ready to word. */
sealed interface PlaybackQualityReasonText {
    data object Original : PlaybackQualityReasonText
    data object UserSelected : PlaybackQualityReasonText
    data class LearnedLimit(val kbps: Int) : PlaybackQualityReasonText
    data class NetworkEstimate(val kbps: Int) : PlaybackQualityReasonText
    data class ClientCap(val kbps: Int) : PlaybackQualityReasonText
    data class ServerCap(val kbps: Int) : PlaybackQualityReasonText

    /** [resolution] is a [playbackResolutionLabel], such as "1080p". */
    data class DeviceResolution(val resolution: String) : PlaybackQualityReasonText
    data object AlternateVersion : PlaybackQualityReasonText

    /** [codec] is the source codec's display name, such as "HEVC". */
    data class VideoCodecUnsupported(val codec: String) : PlaybackQualityReasonText

    /** Display names, such as "Dolby Vision" and "HDR10". */
    data class DynamicRangeConverted(val from: String, val to: String) : PlaybackQualityReasonText

    /** Display names, such as "TrueHD" and "AAC". */
    data class AudioConverted(val from: String, val to: String) : PlaybackQualityReasonText
    data object SubtitleBurnIn : PlaybackQualityReasonText
    data object ContainerUnsupported : PlaybackQualityReasonText

    /**
     * A code this client does not know, or a known one missing the figure
     * its words need. The menu says "Adjusted for this device"; stats show
     * [code].
     */
    data class Unknown(val code: String) : PlaybackQualityReasonText

    /** A change made for compatibility, which may share the menu line with the primary reason. */
    val isCompatibilityChange: Boolean
        get() = this is DynamicRangeConverted || this is AudioConverted || this == SubtitleBurnIn
}

/** This reason's [PlaybackQualityReasonText]. */
fun PlaybackQualityReasonV3.text(): PlaybackQualityReasonText {
    val kbps = kbps?.takeIf { it > 0 }
    val unknown = PlaybackQualityReasonText.Unknown(code)
    return when (code) {
        "original" -> PlaybackQualityReasonText.Original
        "user_selected" -> PlaybackQualityReasonText.UserSelected
        "network_learned_limit" -> kbps?.let(PlaybackQualityReasonText::LearnedLimit) ?: unknown
        "network_estimate" -> kbps?.let(PlaybackQualityReasonText::NetworkEstimate) ?: unknown
        "client_cap" -> kbps?.let(PlaybackQualityReasonText::ClientCap) ?: unknown
        "server_cap" -> kbps?.let(PlaybackQualityReasonText::ServerCap) ?: unknown
        "device_resolution_limit" ->
            height?.let { playbackResolutionLabel(height = it) }?.let(PlaybackQualityReasonText::DeviceResolution) ?: unknown
        "alternate_version" -> PlaybackQualityReasonText.AlternateVersion
        "video_codec_unsupported" ->
            from.token()?.let { PlaybackQualityReasonText.VideoCodecUnsupported(videoCodecName(it)) } ?: unknown
        "dynamic_range_converted" -> {
            val source = from.token() ?: return unknown
            val target = to.token() ?: return unknown
            PlaybackQualityReasonText.DynamicRangeConverted(dynamicRangeName(source), dynamicRangeName(target))
        }
        "audio_converted" -> {
            val source = from.token() ?: return unknown
            val target = to.token() ?: return unknown
            PlaybackQualityReasonText.AudioConverted(audioCodecName(source), audioCodecName(target))
        }
        "subtitle_burn_in" -> PlaybackQualityReasonText.SubtitleBurnIn
        "container_unsupported" -> PlaybackQualityReasonText.ContainerUnsupported
        else -> unknown
    }
}

/**
 * The reasons the Quality menu's line names: the primary one, then the
 * second when it is a compatibility change. Whether the second fits is
 * [playbackQualityReasonLine]'s call, once both are worded.
 */
fun List<PlaybackQualityReasonV3>.menuReasonTexts(): List<PlaybackQualityReasonText> {
    val primary = firstOrNull()?.text() ?: return emptyList()
    val second = getOrNull(1)?.text()?.takeIf { it.isCompatibilityChange }
    return listOfNotNull(primary, second)
}

/**
 * The Quality menu's reason line from the worded [menuReasonTexts]: [primary],
 * with [second] joined on only while the line stays within 72 characters.
 */
fun playbackQualityReasonLine(primary: String, second: String?): String {
    if (second == null) return primary
    val joined = primary + PLAYBACK_QUALITY_REASON_SEPARATOR + second
    return if (joined.length <= PLAYBACK_QUALITY_REASON_LINE_MAX_CHARS) joined else primary
}

/** The longest the menu's reason line gets before it drops its second reason. */
private const val PLAYBACK_QUALITY_REASON_LINE_MAX_CHARS = 72

/** What joins two reasons on one line. */
private const val PLAYBACK_QUALITY_REASON_SEPARATOR = " · "

/**
 * A frame's resolution class: "4K", "1080p", "720p", "480p" or "360p", null
 * for an unknown frame. A frame wide enough for a class counts as it, so a
 * 1920x800 scope encode is 1080p.
 */
fun playbackResolutionLabel(width: Int? = null, height: Int?): String? {
    val h = height?.takeIf { it > 0 } ?: return null
    val w = width?.takeIf { it > 0 } ?: 0
    return when {
        h >= 2000 || w >= 3200 -> "4K"
        h >= 1000 || w >= 1700 -> "1080p"
        h >= 700 || w >= 1200 -> "720p"
        h >= 450 || w >= 800 -> "480p"
        else -> "360p"
    }
}

/** The delivered video's resolution class, else the source's. */
val PlaybackExecutionPlan.deliveredResolutionLabel: String?
    get() = playbackResolutionLabel(effectiveRecipe?.width, effectiveRecipe?.height)
        ?: playbackResolutionLabel(sourceFile.width, sourceFile.height)

/** The Quality control's Auto value: "Auto · 1080p", or "Auto" when the resolution is unknown. */
fun playbackAutoQualityLabel(resolution: String?): String =
    resolution?.let { "Auto$PLAYBACK_QUALITY_REASON_SEPARATOR$it" } ?: "Auto"

private fun String?.token(): String? = this?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }

private fun videoCodecName(token: String): String = when (token) {
    "hevc", "h265" -> "HEVC"
    "av1" -> "AV1"
    "vp9" -> "VP9"
    "mpeg2" -> "MPEG-2"
    "vc1" -> "VC-1"
    "h264" -> "H.264"
    else -> token.uppercase()
}

private fun dynamicRangeName(token: String): String = when (token) {
    "dolby_vision" -> "Dolby Vision"
    "hdr10" -> "HDR10"
    "hdr10_plus" -> "HDR10+"
    "hlg" -> "HLG"
    "sdr" -> "SDR"
    else -> token.uppercase()
}

private fun audioCodecName(token: String): String = when (token) {
    "truehd" -> "TrueHD"
    "dts" -> "DTS"
    "eac3" -> "Dolby Digital Plus"
    "ac3" -> "Dolby Digital"
    "aac" -> "AAC"
    "flac" -> "FLAC"
    "opus" -> "Opus"
    else -> token.uppercase()
}
