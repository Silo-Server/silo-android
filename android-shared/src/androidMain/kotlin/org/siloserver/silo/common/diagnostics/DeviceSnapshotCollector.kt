package org.siloserver.silo.common.diagnostics

import android.content.Context
import android.content.res.Configuration
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.siloserver.silo.common.player.AudioCapabilityManager
import org.siloserver.silo.common.player.DisplayHdrProbe
import org.siloserver.silo.common.player.MediaCodecCapabilitiesProbe
import org.siloserver.silo.common.player.audio.PassthroughSuppressionRegistry
import org.siloserver.silo.model.diagnostics.DiagnosticsDeviceProvenance
import org.siloserver.silo.model.diagnostics.DiagnosticsDeviceSnapshot
import org.siloserver.silo.model.diagnostics.DiagnosticsManifest
import org.siloserver.silo.network.DeviceMetadataProvider
import org.siloserver.silo.network.SiloJson
import java.time.Instant

/**
 * Assembles `device.json` from the existing playback probes plus the
 * diagnostics-specific accessors (display mode list, audio output enumeration,
 * suppression-registry export). Fields a probe can't determine are the honest
 * sentinels `unknown` / `not_collected`, never guesses.
 *
 * A pre-rendered snapshot is kept warm for the crash marker (the UEH thread
 * must never run probes); refresh via [warmUpPreFailureSnapshot].
 */
class DeviceSnapshotCollector(
    private val context: Context,
    private val audioCapabilityManager: AudioCapabilityManager,
    private val deviceMetadataProvider: DeviceMetadataProvider,
    private val isTv: Boolean,
) {

    fun collect(
        provenance: DiagnosticsDeviceProvenance,
        deviceId: String?,
    ): DiagnosticsDeviceSnapshot = DiagnosticsDeviceSnapshot(
        capturedAt = Instant.now().toString(),
        provenance = provenance,
        identity = identityJson(deviceId),
        display = displayJson(),
        audio = audioJson(),
        videoCodecs = videoCodecsJson(),
        network = networkJson(),
    )

    /** The manifest's small `device_summary`, from the same sources. */
    fun deviceSummary(): DiagnosticsManifest.DeviceSummary = DiagnosticsManifest.DeviceSummary(
        manufacturer = Build.MANUFACTURER?.trim().orEmpty().ifBlank { "unknown" },
        model = Build.MODEL?.trim().orEmpty().ifBlank { "unknown" },
        os = Build.VERSION.RELEASE?.trim().orEmpty().ifBlank { "unknown" },
        formFactor = formFactor(),
    )

    fun osVersion(): String = "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"

    /**
     * Computes and caches a rendered pre-failure snapshot for the crash
     * marker. Cheap after first call (the codec probe caches internally), but
     * still runs off the main thread at app start and on output-route changes.
     */
    suspend fun warmUpPreFailureSnapshot() {
        val deviceId = runCatching { deviceMetadataProvider.current()?.id }.getOrNull()
        val snapshot = collect(DiagnosticsDeviceProvenance.PRE_FAILURE, deviceId)
        val rendered = SiloJson.encodeToString(DiagnosticsDeviceSnapshot.serializer(), snapshot)
        CrashContextCache.deviceSnapshotJson = rendered.encodeToByteArray()
    }

    private fun identityJson(deviceId: String?): JsonElement = buildJsonObject {
        put("manufacturer", Build.MANUFACTURER?.trim().orEmpty().ifBlank { "unknown" })
        put("model", Build.MODEL?.trim().orEmpty().ifBlank { "unknown" })
        put("device", Build.DEVICE?.trim().orEmpty().ifBlank { "unknown" })
        put("form_factor", formFactor())
        put("device_id", deviceId ?: "unknown")
    }

    private fun displayJson(): JsonElement = runCatching {
        val modes = DisplayHdrProbe.probeModes(context)
        val hdr = DisplayHdrProbe.probe(context)
        buildJsonObject {
            put("mode", modes.currentMode ?: "unknown")
            putJsonArray("modes_supported") { modes.supportedModes.forEach { add(JsonPrimitive(it)) } }
            putJsonArray("hdr_types") {
                if (hdr.hdr10) add(JsonPrimitive("HDR10"))
                if (hdr.hdr10Plus) add(JsonPrimitive("HDR10+"))
                if (hdr.hlg) add(JsonPrimitive("HLG"))
                if (hdr.dolbyVisionProfiles.isNotEmpty()) add(JsonPrimitive("DV"))
            }
            put("wide_gamut", modes.wideColorGamut)
        }
    }.getOrElse { DiagnosticsDeviceSnapshot.unknown }

    private fun audioJson(): JsonElement = runCatching {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            ?: return@runCatching DiagnosticsDeviceSnapshot.unknown
        val outputs = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).orEmpty()
        val passthrough = audioCapabilityManager.capabilities.value
        buildJsonObject {
            putJsonArray("outputs") {
                outputs.forEach { device ->
                    add(
                        buildJsonObject {
                            put("type", audioDeviceTypeName(device.type))
                            putJsonArray("encodings") {
                                device.encodings.toList().distinct().forEach {
                                    add(JsonPrimitive(encodingName(it)))
                                }
                            }
                            put("channels", device.channelCounts?.maxOrNull() ?: 0)
                        },
                    )
                }
            }
            putJsonArray("passthrough") {
                passthrough.passthroughCodecs.forEach { add(JsonPrimitive(it)) }
            }
            put("spatializer_enabled", passthrough.spatializerEnabled)
            put("max_channels", passthrough.maxChannels)
            putJsonArray("suppressions") {
                PassthroughSuppressionRegistry.snapshot().forEach { entry ->
                    add(
                        buildJsonObject {
                            put("mime", entry.mime)
                            put("channels", entry.channels)
                        },
                    )
                }
            }
        }
    }.getOrElse { DiagnosticsDeviceSnapshot.unknown }

    private fun videoCodecsJson(): JsonElement = runCatching {
        val probe = MediaCodecCapabilitiesProbe.probe()
        buildJsonArray {
            probe.videoDecodeCapabilities.forEach { capability ->
                add(
                    buildJsonObject {
                        put("codec", capability.codec)
                        put("hw", capability.hardware)
                        capability.decoderName?.let { put("decoder", it) }
                        putJsonArray("profiles") {
                            capability.profiles.forEach { add(JsonPrimitive(it)) }
                        }
                        val maxWidth = capability.maxWidth
                        val maxHeight = capability.maxHeight
                        if (maxWidth != null && maxHeight != null) {
                            val rate = capability.maxFrameRate?.toInt()
                            put("max", if (rate != null) "${maxWidth}x$maxHeight@$rate" else "${maxWidth}x$maxHeight")
                        }
                    },
                )
            }
        }
    }.getOrElse { DiagnosticsDeviceSnapshot.unknown }

    private fun networkJson(): JsonElement = runCatching {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return@runCatching DiagnosticsDeviceSnapshot.unknown
        val transport = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val caps = cm.getNetworkCapabilities(cm.activeNetwork)
            when {
                caps == null -> "unknown"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
                else -> "other"
            }
        } else {
            "unknown"
        }
        buildJsonObject { put("transport", transport) }
    }.getOrElse { DiagnosticsDeviceSnapshot.unknown }

    private fun formFactor(): String = when {
        isTv -> "tv"
        context.resources.configuration.smallestScreenWidthDp >= 600 -> "tablet"
        else -> "phone"
    }

    private fun audioDeviceTypeName(type: Int): String = when (type) {
        AudioDeviceInfo.TYPE_HDMI -> "HDMI"
        AudioDeviceInfo.TYPE_HDMI_ARC -> "HDMI_ARC"
        30 -> "HDMI_EARC" // AudioDeviceInfo.TYPE_HDMI_EARC, API 31 constant
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "BLUETOOTH_A2DP"
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "BLUETOOTH_SCO"
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "BUILTIN_SPEAKER"
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "WIRED_HEADPHONES"
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> "WIRED_HEADSET"
        AudioDeviceInfo.TYPE_USB_DEVICE -> "USB_DEVICE"
        AudioDeviceInfo.TYPE_USB_HEADSET -> "USB_HEADSET"
        AudioDeviceInfo.TYPE_DOCK -> "DOCK"
        AudioDeviceInfo.TYPE_AUX_LINE -> "AUX_LINE"
        else -> "TYPE_$type"
    }

    private fun encodingName(encoding: Int): String = when (encoding) {
        AudioFormat.ENCODING_PCM_16BIT -> "PCM_16BIT"
        AudioFormat.ENCODING_PCM_8BIT -> "PCM_8BIT"
        AudioFormat.ENCODING_PCM_FLOAT -> "PCM_FLOAT"
        AudioFormat.ENCODING_AC3 -> "AC3"
        AudioFormat.ENCODING_E_AC3 -> "EAC3"
        AudioFormat.ENCODING_E_AC3_JOC -> "EAC3_JOC"
        AudioFormat.ENCODING_DOLBY_TRUEHD -> "TRUEHD"
        AudioFormat.ENCODING_DOLBY_MAT -> "DOLBY_MAT"
        AudioFormat.ENCODING_DTS -> "DTS"
        AudioFormat.ENCODING_DTS_HD -> "DTS_HD"
        19 -> "DTS_UHD" // AudioFormat.ENCODING_DTS_UHD_P1, API 30 constant
        AudioFormat.ENCODING_AAC_LC -> "AAC_LC"
        else -> "ENCODING_$encoding"
    }
}
