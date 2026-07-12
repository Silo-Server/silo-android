package org.siloserver.silo.common.player

import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.Spatializer
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.os.Build
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioCapabilities
import androidx.media3.exoplayer.audio.AudioCapabilitiesReceiver
import org.siloserver.silo.model.playback.AudioPassthroughCapabilities
import org.siloserver.silo.model.playback.AudioPassthroughEntry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicLong

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
    private val generationCounter = AtomicLong(0)
    private val _outputRouteGeneration = MutableStateFlow(0L)
    val outputRouteGeneration: StateFlow<Long> = _outputRouteGeneration.asStateFlow()

    private fun publishCapabilities(next: AudioPassthroughCapabilities) {
        if (_capabilities.value == next) return
        _capabilities.value = next
        _outputRouteGeneration.value = generationCounter.incrementAndGet()
    }

    private fun bumpOutputRouteGeneration() {
        _outputRouteGeneration.value = generationCounter.incrementAndGet()
    }

    private var lastDisplayHdr = DisplayHdrProbe.probe(appContext)

    private fun publishDisplayCapabilitiesIfChanged() {
        val next = DisplayHdrProbe.probe(appContext)
        if (next == lastDisplayHdr) return
        lastDisplayHdr = next
        bumpOutputRouteGeneration()
    }

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = publishDisplayCapabilitiesIfChanged()
        override fun onDisplayRemoved(displayId: Int) = publishDisplayCapabilitiesIfChanged()
        override fun onDisplayChanged(displayId: Int) = publishDisplayCapabilitiesIfChanged()
    }

    private val receiver = AudioCapabilitiesReceiver(
        appContext,
        AudioCapabilitiesReceiver.Listener { caps -> publishCapabilities(mapCapabilities(caps)) },
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
                    publishCapabilities(_capabilities.value.copy(spatializerEnabled = enabled))
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
        (appContext.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager)
            ?.registerDisplayListener(displayListener, Handler(Looper.getMainLooper()))
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

        // Media3's aggregate maxChannelCount is not enough for route planning:
        // an AVR can accept eight-channel TrueHD but only six-channel AC3, for
        // example. Probe each encoded format and emit exact entries for V3.
        val entries = probePassthroughEntries(caps, codecs)
        val maxChannels = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            entries.flatMap(AudioPassthroughEntry::channelCounts).maxOrNull() ?: 2
        } else {
            caps.maxChannelCount.coerceAtLeast(2)
        }

        return AudioPassthroughCapabilities(
            passthroughCodecs = codecs,
            spatializerEnabled = spatializerEnabled,
            maxChannels = maxChannels,
            entries = entries,
        )
    }

    /**
     * Probe the channel layouts the current sink accepts for every encoded
     * format we advertise. Android before API 29 has no route-specific direct
     * playback probe, so those devices intentionally omit layout entries and
     * remain on the server's conservative compatibility path.
     */
    private fun probePassthroughEntries(
        caps: AudioCapabilities,
        codecs: List<String>,
    ): List<AudioPassthroughEntry> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return emptyList()
        }
        val audioAttrs = android.media.AudioAttributes.Builder()
            .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MOVIE)
            .build()
        val encodingsToProbe = listOfNotNull(
            ("ac3" to AudioFormat.ENCODING_AC3).takeIf { "ac3" in codecs },
            ("eac3" to AudioFormat.ENCODING_E_AC3).takeIf { "eac3" in codecs },
            ("eac3_joc" to AudioFormat.ENCODING_E_AC3_JOC).takeIf { "eac3_joc" in codecs },
            ("dts" to AudioFormat.ENCODING_DTS).takeIf { "dts" in codecs },
            if ("dts_hd" in codecs && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
                "dts_hd" to AudioFormat.ENCODING_DTS_HD else null,
            if ("truehd" in codecs && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N_MR1)
                "truehd" to AudioFormat.ENCODING_DOLBY_TRUEHD else null,
            if ("ac4" in codecs && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
                "ac4" to AudioFormat.ENCODING_AC4 else null,
        )
        val layoutsToProbe = listOf(
            AudioLayoutProbe(2, AudioFormat.CHANNEL_OUT_STEREO, listOf("stereo")),
            // FFprobe commonly distinguishes 5.1 and 5.1(side), while
            // Android exposes one encoded six-channel mask to AudioTrack.
            AudioLayoutProbe(6, AudioFormat.CHANNEL_OUT_5POINT1, listOf("5.1", "5.1(side)")),
            AudioLayoutProbe(8, AudioFormat.CHANNEL_OUT_7POINT1_SURROUND, listOf("7.1")),
        )
        return encodingsToProbe.mapNotNull { (codec, encoding) ->
            val channelCounts = sortedSetOf<Int>()
            val layouts = sortedSetOf<String>()
            for (candidate in layoutsToProbe) {
                val format = runCatching {
                    AudioFormat.Builder()
                        .setEncoding(encoding)
                        .setSampleRate(48_000)
                        .setChannelMask(candidate.channelMask)
                        .build()
                }.getOrNull() ?: continue
                val ok = supportsBitstreamOutput(format, audioAttrs)
                if (ok) {
                    channelCounts += candidate.channelCount
                    layouts += candidate.layoutNames
                }
            }
            if (channelCounts.isEmpty() || layouts.isEmpty()) null else AudioPassthroughEntry(
                codec = codec,
                channelCounts = channelCounts.toList(),
                layouts = layouts.toList(),
            )
        }
    }

    @Suppress("DEPRECATION")
    private fun supportsBitstreamOutput(
        format: AudioFormat,
        attributes: android.media.AudioAttributes,
    ): Boolean = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val support = AudioManager.getDirectPlaybackSupport(format, attributes)
            support and AudioManager.DIRECT_PLAYBACK_BITSTREAM_SUPPORTED != 0
        } else {
            AudioTrack.isDirectPlaybackSupported(format, attributes)
        }
    }.getOrDefault(false)

    private data class AudioLayoutProbe(
        val channelCount: Int,
        val channelMask: Int,
        val layoutNames: List<String>,
    )
}
