package org.siloserver.silo.common.player

import android.content.Context
import android.media.AudioFormat
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.AudioTrack
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.os.Build
import android.util.Log
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
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

data class AudioDiagnosticsSnapshot(
    val sinkType: String,
    val routeHashes: List<String>,
    val routeGeneration: Long,
    val capabilities: AudioPassthroughCapabilities,
)

/** One atomically published planning view of the active audio route. */
data class AudioPlaybackRouteSnapshot(
    val sinkType: String,
    val routeGeneration: Long,
    val capabilities: AudioPassthroughCapabilities,
)

/**
 * Identity used to decide whether playback must be replanned for a new output.
 *
 * Spatializer enablement is deliberately excluded. Android can toggle it while
 * Media3 tears down and recreates an audio sink, so including it makes a player
 * remount look like a physical route change and feeds the remount back into
 * another server replan. The server does not route on this flag; it remains in
 * the published diagnostics/capability snapshot.
 */
internal data class AudioPlanningRouteIdentity(
    val sinkType: String,
    val routeHashes: List<String>,
    val capabilities: AudioPassthroughCapabilities,
)

internal fun audioPlanningRouteIdentity(
    sinkType: String,
    routeHashes: List<String>,
    capabilities: AudioPassthroughCapabilities,
): AudioPlanningRouteIdentity = AudioPlanningRouteIdentity(
    sinkType = sinkType,
    routeHashes = routeHashes.distinct().sorted(),
    capabilities = capabilities.copy(spatializerEnabled = false),
)

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
    @Volatile
    private var playbackRouteSnapshot = AudioPlaybackRouteSnapshot(
        sinkType = "unknown",
        routeGeneration = 0L,
        capabilities = AudioPassthroughCapabilities(),
    )
    private var routeSnapshotInitialized = false
    private var planningRouteIdentity: AudioPlanningRouteIdentity? = null

    private fun publishCapabilities(next: AudioPassthroughCapabilities) {
        val capabilitiesChanged = _capabilities.value != next
        val devices = runCatching(::currentOutputDevices).getOrDefault(emptyList())
        val sinkType = sinkType(devices)
        val nextRouteIdentity = audioPlanningRouteIdentity(
            sinkType = sinkType,
            routeHashes = devices.map(::routeHash),
            capabilities = next,
        )
        val routeChanged = planningRouteIdentity != nextRouteIdentity
        if (!capabilitiesChanged && !routeChanged && routeSnapshotInitialized) return
        val generation = if (routeChanged) {
            generationCounter.incrementAndGet()
        } else {
            _outputRouteGeneration.value
        }
        playbackRouteSnapshot = AudioPlaybackRouteSnapshot(
            sinkType = sinkType,
            routeGeneration = generation,
            capabilities = next.immutableCopy(),
        )
        planningRouteIdentity = nextRouteIdentity
        routeSnapshotInitialized = true
        _capabilities.value = next
        _outputRouteGeneration.value = generation
        Log.i(
            TAG,
            "Audio output capabilities updated: " +
                "codecs=${next.passthroughCodecs} maxChannels=${next.maxChannels} " +
                "entries=${next.entries}",
        )
    }

    private fun bumpOutputRouteGeneration() {
        val generation = generationCounter.incrementAndGet()
        playbackRouteSnapshot = AudioPlaybackRouteSnapshot(
            sinkType = currentSinkType(),
            routeGeneration = generation,
            capabilities = _capabilities.value.immutableCopy(),
        )
        routeSnapshotInitialized = true
        _outputRouteGeneration.value = generation
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
    /**
     * All Spatializer access is confined to [SpatializerBridge], held here as
     * `Any?` so no Spatializer type appears in this class's fields, signatures
     * or method bodies. That isolation is the point: ART resolves a method's
     * referenced classes when the method runs, before any version branch inside
     * it is evaluated, so a reference sitting in an untaken `if` still throws
     * NoClassDefFoundError on a device without the class — and a runCatching
     * around it does not help, because the failure happens outside the try.
     */
    private val spatializerBridge: Any? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S_V2) {
            runCatching {
                SpatializerBridge(audioManager) { enabled ->
                    publishCapabilities(_capabilities.value.copy(spatializerEnabled = enabled))
                }
            }.getOrNull()
        } else null

    init {
        // register() returns the current snapshot; the listener is only for
        // later changes. Discarding this value leaves a stable HDMI route at
        // the empty default forever because no route-change callback follows.
        val initialCapabilities = receiver.register()
        publishCapabilities(mapCapabilities(initialCapabilities))
        (appContext.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager)
            ?.registerDisplayListener(displayListener, Handler(Looper.getMainLooper()))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S_V2) {
            runCatching { (spatializerBridge as? SpatializerBridge)?.subscribe() }
        }
    }

    private fun mapCapabilities(caps: AudioCapabilities): AudioPassthroughCapabilities {
        val supportedEncodings = encodingSupport.filter { support ->
            Build.VERSION.SDK_INT >= support.minSdk && caps.supportsEncoding(support.encoding)
        }
        val codecs = supportedEncodings.map(EncodingSupport::codec)

        val spatializerEnabled =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S_V2) {
                runCatching { (spatializerBridge as? SpatializerBridge)?.isEnabled() }.getOrNull() ?: false
            } else false

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
        val devices = runCatching(::currentOutputDevices).getOrDefault(emptyList())
        return sinkType(devices)
    }

    /** Planning callers consume this single value, never separate route flows. */
    fun playbackRouteSnapshot(): AudioPlaybackRouteSnapshot = playbackRouteSnapshot

    /** Privacy-safe immutable route evidence. Raw device names and addresses never leave this class. */
    fun diagnosticsSnapshot(): AudioDiagnosticsSnapshot {
        val devices = currentOutputDevices()
        val planning = playbackRouteSnapshot
        return AudioDiagnosticsSnapshot(
            sinkType = planning.sinkType,
            routeHashes = devices.map(::routeHash).distinct().sorted(),
            routeGeneration = planning.routeGeneration,
            capabilities = planning.capabilities,
        )
    }

    private fun AudioPassthroughCapabilities.immutableCopy(): AudioPassthroughCapabilities = copy(
        passthroughCodecs = passthroughCodecs.toList(),
        entries = entries.map { entry ->
            entry.copy(
                channelCounts = entry.channelCounts.toList(),
                layouts = entry.layouts.toList(),
            )
        },
    )

    private fun currentOutputDevices(): List<AudioDeviceInfo> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val attrs = android.media.AudioAttributes.Builder()
                .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MOVIE)
                .build()
            audioManager.getAudioDevicesForAttributes(attrs)
        } else {
            audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).toList()
        }

    private fun sinkType(devices: List<AudioDeviceInfo>): String =
        devices.map(::sinkCategory).minByOrNull(::sinkPriority) ?: "unknown"

    private fun routeHash(device: AudioDeviceInfo): String {
        // getAddress() is API 28; below that the route is identified by type and
        // id alone. Unguarded this threw NoSuchMethodError on Android 7-8.1
        // whenever diagnostics were collected.
        val address =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) device.address else ""
        val raw = "${device.type}|${device.id}|$address"
        return MessageDigest.getInstance("SHA-256")
            .digest(raw.encodeToByteArray())
            .take(ROUTE_HASH_BYTES)
            .joinToString(separator = "") { byte -> "%02x".format(Locale.ROOT, byte.toInt() and 0xff) }
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
        val layoutsToProbe = PASSTHROUGH_LAYOUT_PROBES
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
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            AudioTrack.isDirectPlaybackSupported(format, attributes)
        } else {
            // API 29 introduced the query. Below it the platform cannot be
            // asked, and runCatching was silently answering "no passthrough" —
            // so pre-Q devices were transcoding audio they could have played
            // directly. Same answer, but now it is a deliberate one.
            false
        }
    }.getOrDefault(false)

    internal data class AudioLayoutProbe(
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
        const val TAG = "AudioCapabilityMgr"
        const val ROUTE_HASH_BYTES = 16

        val encodingSupport = listOf(
            EncodingSupport("ac3", AudioFormat.ENCODING_AC3),
            EncodingSupport("eac3", AudioFormat.ENCODING_E_AC3),
            EncodingSupport("eac3_joc", AudioFormat.ENCODING_E_AC3_JOC, Build.VERSION_CODES.P),
            EncodingSupport("dts", AudioFormat.ENCODING_DTS),
            EncodingSupport("dts_hd", AudioFormat.ENCODING_DTS_HD, Build.VERSION_CODES.M),
            EncodingSupport("truehd", AudioFormat.ENCODING_DOLBY_TRUEHD, Build.VERSION_CODES.N_MR1),
            EncodingSupport("ac4", AudioFormat.ENCODING_AC4, Build.VERSION_CODES.P),
        )
    }
}

/**
 * Every reference to [android.media.Spatializer] lives here, and this class is
 * only ever loaded on API 32+.
 *
 * `Spatializer` and `AudioManager.getSpatializer()` were added in API 32
 * (S_V2), not 31 — an Android 12 device therefore has no such class, and
 * touching it from a class that loads on every API level takes the whole
 * process down at construction time.
 */
@androidx.annotation.RequiresApi(Build.VERSION_CODES.S_V2)
private class SpatializerBridge(
    audioManager: AudioManager,
    private val onEnabledChanged: (Boolean) -> Unit,
) {
    private val spatializer: android.media.Spatializer = audioManager.spatializer

    private val listener = object : android.media.Spatializer.OnSpatializerStateChangedListener {
        override fun onSpatializerEnabledChanged(sp: android.media.Spatializer, enabled: Boolean) {
            onEnabledChanged(enabled)
        }

        override fun onSpatializerAvailableChanged(sp: android.media.Spatializer, available: Boolean) {
            // Available but disabled == the user turned spatialisation off —
            // ride the enabledChanged callback above instead.
        }
    }

    fun subscribe() {
        spatializer.addOnSpatializerStateChangedListener({ it.run() }, listener)
    }

    fun isEnabled(): Boolean = spatializer.isEnabled
}

/**
 * The encoded layouts probed per audio format. Deliberately the single source
 * of truth: [PROBED_PASSTHROUGH_CHANNEL_COUNTS] is derived from it, so the
 * reader of a passthrough entry can never disagree with the writer about which
 * counts were actually asked. A constant that merely *claimed* to match would
 * need a runtime check, and a structural invariant is not worth crashing
 * capability detection over.
 */
@UnstableApi
internal val PASSTHROUGH_LAYOUT_PROBES: List<AudioCapabilityManager.AudioLayoutProbe> = listOf(
    AudioCapabilityManager.AudioLayoutProbe(2, AudioFormat.CHANNEL_OUT_STEREO, listOf("stereo")),
    // FFprobe commonly distinguishes 5.1 and 5.1(side), while Android exposes
    // one encoded six-channel mask to AudioTrack.
    AudioCapabilityManager.AudioLayoutProbe(
        6,
        AudioFormat.CHANNEL_OUT_5POINT1,
        listOf("5.1", "5.1(side)"),
    ),
    AudioCapabilityManager.AudioLayoutProbe(
        8,
        AudioFormat.CHANNEL_OUT_7POINT1_SURROUND,
        listOf("7.1"),
    ),
)
