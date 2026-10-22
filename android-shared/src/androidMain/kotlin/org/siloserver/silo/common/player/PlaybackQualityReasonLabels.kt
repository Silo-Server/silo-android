package org.siloserver.silo.common.player

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import org.siloserver.silo.common.R
import org.siloserver.silo.common.settings.connectionRateLabel
import org.siloserver.silo.model.playback.PLAYBACK_QUALITY_REASON_LINE_MAX_CHARS
import org.siloserver.silo.model.playback.PLAYBACK_QUALITY_REASON_SEPARATOR
import org.siloserver.silo.model.playback.PlaybackQualityReasonText
import org.siloserver.silo.model.playback.PlaybackQualityReasonV3
import org.siloserver.silo.model.playback.menuReasonTexts
import org.siloserver.silo.model.playback.text

/*
 * The words of the plan's quality reasons, the same on phone and TV. Each
 * app places them: a line under the Quality menu's checked row, and a Reason
 * row in its stats.
 */

/**
 * The Quality menu's reason line: the primary reason, with the second added
 * when it is a compatibility change and the line stays short. Null when the
 * plan lists no reasons (a server without `plan_quality_reasons_v1`).
 */
@Composable
fun qualityReasonMenuLine(reasons: List<PlaybackQualityReasonV3>): String? {
    val texts = reasons.menuReasonTexts()
    val primary = texts.firstOrNull()?.let { qualityReasonLabel(it, rawUnknown = false) } ?: return null
    val second = texts.getOrNull(1)?.let { qualityReasonLabel(it, rawUnknown = false) } ?: return primary
    val joined = primary + PLAYBACK_QUALITY_REASON_SEPARATOR + second
    return if (joined.length <= PLAYBACK_QUALITY_REASON_LINE_MAX_CHARS) joined else primary
}

/** Every reason for the stats' Reason row, one per line; an unknown code shows as itself. Null with none. */
@Composable
fun qualityReasonStatsValue(reasons: List<PlaybackQualityReasonV3>): String? {
    if (reasons.isEmpty()) return null
    return reasons.map { qualityReasonLabel(it.text(), rawUnknown = true) }.joinToString("\n")
}

@Composable
private fun qualityReasonLabel(text: PlaybackQualityReasonText, rawUnknown: Boolean): String = when (text) {
    PlaybackQualityReasonText.Original -> stringResource(R.string.quality_reason_original)
    PlaybackQualityReasonText.UserSelected -> stringResource(R.string.quality_reason_user_selected)
    is PlaybackQualityReasonText.LearnedLimit ->
        stringResource(R.string.quality_reason_network_learned_limit, connectionRateLabel(text.kbps))
    is PlaybackQualityReasonText.NetworkEstimate ->
        stringResource(R.string.quality_reason_network_estimate, connectionRateLabel(text.kbps))
    is PlaybackQualityReasonText.ClientCap ->
        stringResource(R.string.quality_reason_client_cap, connectionRateLabel(text.kbps))
    is PlaybackQualityReasonText.ServerCap ->
        stringResource(R.string.quality_reason_server_cap, connectionRateLabel(text.kbps))
    is PlaybackQualityReasonText.DeviceResolution ->
        stringResource(R.string.quality_reason_device_resolution_limit, text.resolution)
    PlaybackQualityReasonText.AlternateVersion -> stringResource(R.string.quality_reason_alternate_version)
    is PlaybackQualityReasonText.VideoCodecUnsupported ->
        stringResource(R.string.quality_reason_video_codec_unsupported, text.codec)
    is PlaybackQualityReasonText.DynamicRangeConverted ->
        stringResource(R.string.quality_reason_dynamic_range_converted, text.from, text.to)
    is PlaybackQualityReasonText.AudioConverted ->
        stringResource(R.string.quality_reason_audio_converted, text.from, text.to)
    PlaybackQualityReasonText.SubtitleBurnIn -> stringResource(R.string.quality_reason_subtitle_burn_in)
    PlaybackQualityReasonText.ContainerUnsupported -> stringResource(R.string.quality_reason_container_unsupported)
    is PlaybackQualityReasonText.Unknown ->
        if (rawUnknown) text.code else stringResource(R.string.quality_reason_unknown)
}
