package org.siloserver.silo.common.player

import android.content.Intent
import android.media.AudioManager
import android.os.Build
import androidx.media3.common.util.UnstableApi
import org.siloserver.silo.model.playback.AudioPassthroughEntry

/** Raw explicit HDMI port data, before checking route ownership and surround policy. */
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
 * On pre-Q the caller supplies a report only after excluding competing
 * USB/Bluetooth/wired/line outputs and non-auto or external-surround policy.
 * Media3 defaults and policy-forced formats cannot earn entries.
 *
 * Android 9 exposes one flattened encoding list and channel ceiling, not
 * per-codec profiles. Only TrueHD and sink-reported DTS-HD may use an 8-channel
 * ceiling; all other codecs (including DTS core) are capped at six. Residual
 * risk: a sink supporting TrueHD/DTS-HD at only six channels can still be
 * over-claimed when its aggregate ceiling is eight; pre-Q exposes no per-codec
 * data to rule that out. Use only the established layout mapping below.
 * Q+ continues to query each encoding/layout independently, ignoring this data.
 */
@UnstableApi
internal fun buildPassthroughEntries(
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
                    val codecCeiling = if (encoding.codec == "truehd" || encoding.codec == "dts_hd") {
                        encoding.maxChannels
                    } else {
                        minOf(6, encoding.maxChannels)
                    }
                    encoding.encoding in sink.encodings &&
                        candidate.channelCount <= minOf(sink.maxChannelCount, codecCeiling)
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
