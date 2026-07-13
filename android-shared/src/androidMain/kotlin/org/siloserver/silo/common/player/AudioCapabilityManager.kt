package org.siloserver.silo.common.player

import android.content.Context
import android.media.AudioFormat
import android.media.AudioDeviceInfo
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
    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager

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
                audioManager.spatializer
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
        val supportedEncodings = encodingSupport.filter { support ->
            Build.VERSION.SDK_INT >= support.minSdk && caps.supportsEncoding(support.encoding)
        }
        val codecs = supportedEncodings.map(EncodingSupport::codec)

        val spatializerEnabled = spatializer?.isEnabled ?: false

        // Media3's aggregate maxChannelCount is not enough for route planning:
        // an AVR can accept eight-channel TrueHD but only six-channel AC3, for
        // example. Probe each encoded format and emit exact entries for V3.
        val entries = probePassthroughEntries(supportedEncodings)
        val maxChannels = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            entries.flatMap(AudioPassthroughEntry::channelCounts).maxOrNull()
                ?: caps.maxChannelCount.coerceAtLeast(2)
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
     * Returns a privacy-safe category for the active media sink. Device names,
     * addresses, and HDMI product strings are intentionally never sent to the
     * server. API 33+ exposes the route selected for media attributes; older
     * releases can only expose connected outputs, so their result is a
     * conservative category ordered by the routes most relevant to playback.
     */
    fun currentSinkType(): String {
        val devices = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val attrs = android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MOVIE)
                    .build()
                audioManager.getAudioDevicesForAttributes(attrs)
            } else {
                audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).toList()
            }
        }.getOrDefault(emptyList())
        return devices.map(::sinkCategory).minByOrNull(::sinkPriority) ?: "unknown"
    }

    private fun sinkCategory(device: AudioDeviceInfo): String = when (device.type) {
        AudioDeviceInfo.TYPE_HDMI,
        AudioDeviceInfo.TYPE_HDMI_ARC,
        -> "hdmi"
        AudioDeviceInfo.TYPE_HDMI_EARC -> "hdmi_earc"
        AudioDeviceInfo.TYPE_USB_ACCESSORY,
        AudioDeviceInfo.TYPE_USB_DEVICE,
        AudioDeviceInfo.TYPE_USB_HEADSET,
        -> "usb"
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
        AudioDeviceInfo.TYPE_BLE_HEADSET,
        AudioDeviceInfo.TYPE_BLE_SPEAKER,
        -> "bluetooth"
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
        AudioDeviceInfo.TYPE_WIRED_HEADSET,
        AudioDeviceInfo.TYPE_LINE_ANALOG,
        AudioDeviceInfo.TYPE_LINE_DIGITAL,
        -> "wired"
        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE,
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
        -> "built_in"
        else -> "other"
    }

    private fun sinkPriority(category: String): Int = when (category) {
        "hdmi_earc" -> 0
        "hdmi" -> 1
        "usb" -> 2
        "bluetooth" -> 3
        "wired" -> 4
        "built_in" -> 5
        else -> 6
    }

    /**
     * Probe the channel layouts the current sink accepts for every encoded
     * format we advertise. Android before API 29 has no route-specific direct
     * playback probe, so those devices intentionally omit layout entries and
     * remain on the server's conservative compatibility path.
     */
    private fun probePassthroughEntries(
        encodings: List<EncodingSupport>,
    ): List<AudioPassthroughEntry> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return emptyList()
        }
        val audioAttrs = android.media.AudioAttributes.Builder()
            .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MOVIE)
            .build()
        val layoutsToProbe = listOf(
            AudioLayoutProbe(2, AudioFormat.CHANNEL_OUT_STEREO, listOf("stereo")),
            // FFprobe commonly distinguishes 5.1 and 5.1(side), while
            // Android exposes one encoded six-channel mask to AudioTrack.
            AudioLayoutProbe(6, AudioFormat.CHANNEL_OUT_5POINT1, listOf("5.1", "5.1(side)")),
            AudioLayoutProbe(8, AudioFormat.CHANNEL_OUT_7POINT1_SURROUND, listOf("7.1")),
        )
        return encodings.mapNotNull { support ->
            val channelCounts = sortedSetOf<Int>()
            val layouts = sortedSetOf<String>()
            for (candidate in layoutsToProbe) {
                val format = runCatching {
                    AudioFormat.Builder()
                        .setEncoding(support.encoding)
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
                codec = support.codec,
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

    private data class EncodingSupport(
        val codec: String,
        val encoding: Int,
        val minSdk: Int = 1,
    )

    private companion object {
        val encodingSupport = listOf(
            EncodingSupport("ac3", AudioFormat.ENCODING_AC3),
            EncodingSupport("eac3", AudioFormat.ENCODING_E_AC3),
            EncodingSupport("eac3_joc", AudioFormat.ENCODING_E_AC3_JOC),
            EncodingSupport("dts", AudioFormat.ENCODING_DTS),
            EncodingSupport("dts_hd", AudioFormat.ENCODING_DTS_HD, Build.VERSION_CODES.M),
            EncodingSupport("truehd", AudioFormat.ENCODING_DOLBY_TRUEHD, Build.VERSION_CODES.N_MR1),
            EncodingSupport("ac4", AudioFormat.ENCODING_AC4, Build.VERSION_CODES.P),
        )
    }
}
