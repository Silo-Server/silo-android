package com.continuum.app.common.player

import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.Spatializer
import android.os.Build
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioCapabilities
import androidx.media3.exoplayer.audio.AudioCapabilitiesReceiver
import com.continuum.app.model.playback.AudioPassthroughCapabilities
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Tracks the current [AudioCapabilities] of the active audio sink (built-in
 * speaker, HDMI receiver, Bluetooth, USB DAC) and exposes them as an
 * [AudioPassthroughCapabilities] suitable for the server's playback resolver.
 *
 * Backed by [AudioCapabilitiesReceiver], which monitors:
 * - `Intent.ACTION_HDMI_AUDIO_PLUG` (TV form factor — AVR power on/off, EDID renegotiation)
 * - `AudioDeviceCallback` adds/removes (Bluetooth pair, USB DAC plug)
 * - `Settings.Global.ENCODED_SURROUND_OUTPUT` (the user "force Atmos" toggle on most TVs)
 * - `Spatializer.OnSpatializerStateChangedListener` on Android 12+
 *
 * The manager registers at construction and lives for the application lifetime
 * (Koin singleton), so the [capabilities] flow is always current when a
 * ViewModel reads it. The receiver is cheap and idle until a route changes.
 */
@UnstableApi
class AudioCapabilityManager(
    context: Context,
) {
    private val appContext = context.applicationContext

    private val mediaAttrs = AudioAttributes.Builder()
        .setUsage(C.USAGE_MEDIA)
        .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
        .build()

    private val _capabilities = MutableStateFlow(AudioPassthroughCapabilities())
    val capabilities: StateFlow<AudioPassthroughCapabilities> = _capabilities.asStateFlow()

    private val receiver = AudioCapabilitiesReceiver(
        appContext,
        AudioCapabilitiesReceiver.Listener { caps -> _capabilities.value = mapCapabilities(caps) },
        mediaAttrs,
        /* routedDevice = */ null,
    )

    // Spatializer (Android 12+ / API 31+). The head-tracking + enabled state
    // flips independently of the audio route (e.g. plugging in BT head-tracked
    // earbuds on the same device), so we subscribe and re-emit.
    private val spatializer: Spatializer? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching {
                (appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager).spatializer
            }.getOrNull()
        } else null

    private val spatializerListener: Any? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S_V2 && spatializer != null) {
            object : Spatializer.OnSpatializerStateChangedListener {
                override fun onSpatializerEnabledChanged(sp: Spatializer, enabled: Boolean) {
                    _capabilities.value = _capabilities.value.copy(spatializerEnabled = enabled)
                }
                override fun onSpatializerAvailableChanged(sp: Spatializer, available: Boolean) {
                    // Available but disabled == user has turned spatialization off —
                    // ride the enabledChanged callback above instead.
                }
            }
        } else null

    init {
        // register() fires the listener synchronously with the current state,
        // so the StateFlow is populated immediately.
        receiver.register()
        val sp = spatializer
        val spl = spatializerListener
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S_V2 && sp != null && spl != null) {
            runCatching {
                sp.addOnSpatializerStateChangedListener(
                    { it.run() },
                    spl as Spatializer.OnSpatializerStateChangedListener,
                )
            }
        }
    }

    private fun mapCapabilities(caps: AudioCapabilities): AudioPassthroughCapabilities {
        val codecs = mutableListOf<String>()
        if (caps.supportsEncoding(AudioFormat.ENCODING_AC3)) codecs += "ac3"
        if (caps.supportsEncoding(AudioFormat.ENCODING_E_AC3)) codecs += "eac3"
        if (caps.supportsEncoding(AudioFormat.ENCODING_E_AC3_JOC)) codecs += "eac3_joc"
        if (caps.supportsEncoding(AudioFormat.ENCODING_DTS)) codecs += "dts"
        // ENCODING_DTS_HD is API 23+; ENCODING_DOLBY_TRUEHD is API 25+; ENCODING_AC4 is API 28+.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
            caps.supportsEncoding(AudioFormat.ENCODING_DTS_HD)
        ) codecs += "dts_hd"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N_MR1 &&
            caps.supportsEncoding(AudioFormat.ENCODING_DOLBY_TRUEHD)
        ) codecs += "truehd"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
            caps.supportsEncoding(AudioFormat.ENCODING_AC4)
        ) codecs += "ac4"

        val spatializerEnabled = spatializer?.isEnabled ?: false

        // Per-encoding channel cap probe using `AudioTrack.isDirectPlaybackSupported`.
        // Iterate the passthrough encodings we announce × common channel counts
        // (2 / 6 / 8 / 12 / 16) so we report the highest channel layout the sink
        // actually accepts for that encoding. Falls back to the aggregate
        // `maxChannelCount` on API < 29 where `isDirectPlaybackSupported` isn't
        // available.
        val maxChannels = probeMaxChannels(caps, codecs)

        return AudioPassthroughCapabilities(
            passthroughCodecs = codecs,
            spatializerEnabled = spatializerEnabled,
            maxChannels = maxChannels,
        )
    }

    /**
     * Probe the highest channel count the sink actually accepts for each
     * passthrough encoding we advertise. On API 29+ uses
     * [AudioTrack.isDirectPlaybackSupported]; older APIs fall back to the
     * aggregate `maxChannelCount` reported by Media3's [AudioCapabilities]
     * (Media3 1.10.0 — previously this method carried a "Media3 1.6" note).
     */
    private fun probeMaxChannels(
        caps: AudioCapabilities,
        codecs: List<String>,
    ): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return caps.maxChannelCount.coerceAtLeast(2)
        }
        var best = 2
        val audioAttrs = android.media.AudioAttributes.Builder()
            .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MOVIE)
            .build()
        val encodingsToProbe = listOfNotNull(
            "ac3".takeIf { it in codecs } to AudioFormat.ENCODING_AC3,
            "eac3".takeIf { it in codecs } to AudioFormat.ENCODING_E_AC3,
            "eac3_joc".takeIf { it in codecs } to AudioFormat.ENCODING_E_AC3_JOC,
            "dts".takeIf { it in codecs } to AudioFormat.ENCODING_DTS,
            if ("dts_hd" in codecs && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
                "dts_hd" to AudioFormat.ENCODING_DTS_HD else null,
            if ("truehd" in codecs && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N_MR1)
                "truehd" to AudioFormat.ENCODING_DOLBY_TRUEHD else null,
            if ("ac4" in codecs && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
                "ac4" to AudioFormat.ENCODING_AC4 else null,
        ).mapNotNull { it }
        val channelMasks = listOf(
            2 to AudioFormat.CHANNEL_OUT_STEREO,
            6 to AudioFormat.CHANNEL_OUT_5POINT1,
            8 to AudioFormat.CHANNEL_OUT_7POINT1_SURROUND,
        )
        for ((_, encoding) in encodingsToProbe) {
            for ((ch, mask) in channelMasks) {
                val format = runCatching {
                    AudioFormat.Builder()
                        .setEncoding(encoding)
                        .setSampleRate(48_000)
                        .setChannelMask(mask)
                        .build()
                }.getOrNull() ?: continue
                val ok = runCatching {
                    AudioTrack.isDirectPlaybackSupported(format, audioAttrs)
                }.getOrDefault(false)
                if (ok && ch > best) best = ch
            }
        }
        return best.coerceAtLeast(2)
    }
}
