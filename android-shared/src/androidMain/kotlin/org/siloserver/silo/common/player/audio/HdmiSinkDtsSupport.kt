package org.siloserver.silo.common.player.audio

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.os.Build
import android.util.Log
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import org.siloserver.silo.common.player.FfmpegAudioSupport

/**
 * Whether the HDMI sink itself decodes DTS, read from the encodings the sink's
 * output port lists (`AudioDeviceInfo.getEncodings()`) rather than from the
 * platform's passthrough answer (Media3 `AudioCapabilities`).
 *
 * The two disagree on Amlogic boxes (onn 4K Pro, Mi Box S): the platform
 * accepts DTS encodings and exposes a platform DTS decoder because the SoC
 * handles DTS itself, while the port of a sink with no DTS descriptor (every
 * LG 2025 TV) lists only Dolby codecs. On an onn 4K Pro in front of an LG C5,
 * DTS through that platform decoder played silent; the bundled FFmpeg decoder
 * plays it as multichannel PCM.
 */
@UnstableApi
object HdmiSinkDtsSupport {
    private const val TAG = "HdmiSinkDtsSupport"

    // AudioFormat constants newer than minSdk, as literals so they are read on
    // every platform level (a HAL can report them before the SDK names them).
    private const val ENCODING_PCM_24BIT_PACKED = 21
    private const val ENCODING_PCM_32BIT = 22
    private const val ENCODING_DTS_UHD_P1 = 27
    private const val ENCODING_DTS_HD_MA = 29
    private const val ENCODING_DTS_UHD_P2 = 30

    private val dtsMimeTypes = setOf(
        MimeTypes.AUDIO_DTS,
        MimeTypes.AUDIO_DTS_EXPRESS,
        MimeTypes.AUDIO_DTS_HD,
    )

    private val dtsSinkEncodings = setOf(
        AudioFormat.ENCODING_DTS,
        AudioFormat.ENCODING_DTS_HD,
        ENCODING_DTS_UHD_P1,
        ENCODING_DTS_HD_MA,
        ENCODING_DTS_UHD_P2,
    )

    // Neither PCM nor the IEC 61937 transport says which codecs a sink decodes.
    private val nonCodecEncodings = setOf(
        AudioFormat.ENCODING_DEFAULT,
        AudioFormat.ENCODING_PCM_8BIT,
        AudioFormat.ENCODING_PCM_16BIT,
        AudioFormat.ENCODING_PCM_FLOAT,
        ENCODING_PCM_24BIT_PACKED,
        ENCODING_PCM_32BIT,
        AudioFormat.ENCODING_IEC61937,
    )

    @Volatile private var lastLoggedVerdict: String? = null

    /**
     * Whether the sinks in [hdmiSinkEncodings] (one `AudioDeviceInfo.getEncodings()`
     * array each) decode DTS. A sink counts as described only when it lists a
     * compressed codec; with no sink, or any undescribed one, the answer is
     * true so a HAL that reports nothing keeps today's behavior.
     */
    internal fun sinkAdvertisesDts(hdmiSinkEncodings: List<IntArray>): Boolean {
        val undescribed = { encodings: IntArray -> encodings.all { it in nonCodecEncodings } }
        if (hdmiSinkEncodings.isEmpty() || hdmiSinkEncodings.any(undescribed)) return true
        return hdmiSinkEncodings.any { encodings -> encodings.any { it in dtsSinkEncodings } }
    }

    /**
     * True when a DTS track of [mimeType] must be decoded by FFmpeg: the sink
     * does not decode DTS and the packaged FFmpeg build decodes this MIME.
     * Without FFmpeg the platform path stays, since it is then the only
     * decoder there is.
     */
    internal fun shouldDecodeWithFfmpeg(
        mimeType: String?,
        ffmpegSupportsMimeType: (String) -> Boolean,
        sinkDecodesDts: () -> Boolean,
    ): Boolean = mimeType != null &&
        mimeType in dtsMimeTypes &&
        ffmpegSupportsMimeType(mimeType) &&
        !sinkDecodesDts()

    /** [shouldDecodeWithFfmpeg] against the HDMI sink on the active media route. */
    fun shouldDecodeWithFfmpeg(context: Context, mimeType: String?): Boolean =
        shouldDecodeWithFfmpeg(
            mimeType = mimeType,
            ffmpegSupportsMimeType = FfmpegAudioSupport::supportsMimeType,
            sinkDecodesDts = { hdmiSinkDecodesDts(context) },
        )

    /**
     * [sinkAdvertisesDts] against the HDMI sink on the active media route.
     * Inspection failures answer true, keeping the platform's own handling.
     */
    fun hdmiSinkDecodesDts(context: Context): Boolean = runCatching {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val encodings = activeHdmiSinkEncodings(audioManager)
        sinkAdvertisesDts(encodings).also { decodes ->
            val verdict = "$decodes ${encodings.joinToString { it.contentToString() }}"
            if (verdict != lastLoggedVerdict) {
                lastLoggedVerdict = verdict
                Log.i(TAG, "HDMI sink DTS support: $verdict")
            }
        }
    }.getOrElse { error ->
        Log.w(TAG, "HDMI sink inspection failed; keeping platform DTS handling", error)
        true
    }

    /**
     * Encodings of the HDMI outputs a movie plays through. API 33+ reads the
     * active media route; a route with no HDMI device (USB, Bluetooth, the
     * phone speaker) yields none, which [sinkAdvertisesDts] treats as
     * undescribed. Older releases can only list every connected HDMI output.
     */
    private fun activeHdmiSinkEncodings(audioManager: AudioManager): List<IntArray> {
        val devices = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val attrs = android.media.AudioAttributes.Builder()
                .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MOVIE)
                .build()
            audioManager.getAudioDevicesForAttributes(attrs)
        } else {
            audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).toList()
        }
        return devices.filter { isHdmi(it.type) }.map(AudioDeviceInfo::getEncodings)
    }

    private fun isHdmi(type: Int): Boolean = type == AudioDeviceInfo.TYPE_HDMI ||
        type == AudioDeviceInfo.TYPE_HDMI_ARC ||
        (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && type == AudioDeviceInfo.TYPE_HDMI_EARC)
}
