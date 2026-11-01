package org.siloserver.silo.common.player

import android.content.Intent
import android.media.AudioManager
import android.os.Build
import androidx.media3.common.util.UnstableApi
import org.siloserver.silo.model.playback.AudioPassthroughEntry

/** Explicit HDMI sink data, without Media3's defaults or global surround settings. */
internal data class HdmiAudioSinkReport(
    val encodings: Set<Int>,
    val maxChannelCount: Int,
)

internal fun hdmiAudioSinkReport(intent: Intent?): HdmiAudioSinkReport? {
    if (intent?.action != AudioManager.ACTION_HDMI_AUDIO_PLUG ||
        intent.getIntExtra(AudioManager.EXTRA_AUDIO_PLUG_STATE, 0) != 1
    ) return null
    val encodings = intent.getIntArrayExtra(AudioManager.EXTRA_ENCODINGS) ?: return null
    // Unlike Media3, never substitute a default ceiling for a missing extra.
    val maxChannelCount = intent.getIntExtra(AudioManager.EXTRA_MAX_CHANNEL_COUNT, 0)
    if (encodings.isEmpty() || maxChannelCount <= 0) return null
    return HdmiAudioSinkReport(encodings.toSet(), maxChannelCount)
}

/**
 * [encodings] has already passed Media3 support, minSdk and HDMI DTS checks.
 * On pre-Q only the sink's own report earns entries. Media3 1.11 merges HDMI
 * encodings with global surround settings and does not expose their origin:
 * https://github.com/androidx/media/blob/1.11.0/libraries/exoplayer/src/main/java/androidx/media3/exoplayer/audio/AudioCapabilities.java
 *
 * The HDMI broadcast has a single channel ceiling, not per-encoding profiles;
 * bound it by each codec's limit and use only our established layout mapping.
 * Q+ continues to query each encoding/layout independently, ignoring this data.
 */
@UnstableApi
internal fun probePassthroughEntries(
    sdkInt: Int,
    encodings: List<AudioCapabilityManager.EncodingSupport>,
    hdmiSinkReport: () -> HdmiAudioSinkReport?,
    supportsLayout: (AudioCapabilityManager.EncodingSupport, AudioCapabilityManager.AudioLayoutProbe) -> Boolean,
): List<AudioPassthroughEntry> {
    val sink = if (sdkInt < Build.VERSION_CODES.Q) hdmiSinkReport() ?: return emptyList() else null
    return encodings.mapNotNull { support ->
        // V3 identifies DTS core, DTS-HD MA and DTS:X sources as "dts".
        // Only eligible DTS-HD support backed by the sink can extend that
        // family entry to eight channels; a core-only report stays at six.
        val sinkEncodings = encodings.filter {
            it == support || (support.codec == "dts" && it.codec == "dts_hd")
        }
        val channelCounts = sortedSetOf<Int>()
        val layouts = sortedSetOf<String>()
        for (candidate in PASSTHROUGH_LAYOUT_PROBES) {
            val ok = if (sink != null) {
                sinkEncodings.any { encoding ->
                    encoding.encoding in sink.encodings &&
                        candidate.channelCount <= minOf(sink.maxChannelCount, encoding.maxChannels)
                }
            } else {
                supportsLayout(support, candidate)
            }
            if (ok) {
                channelCounts += candidate.channelCount
                layouts += candidate.layoutNames
            }
        }
        if (channelCounts.isEmpty() || layouts.isEmpty()) null else AudioPassthroughEntry(
            codec = support.codec,
            channelCounts = channelCounts.toList(),
            layouts = layouts.toList(),
        )
    }
}
