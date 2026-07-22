package org.siloserver.silo.common.diagnostics

import android.app.UiModeManager
import android.content.Context
import android.content.res.Configuration
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.os.Build
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.atomic.AtomicReference
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.encodeToJsonElement
import org.siloserver.silo.common.player.AudioCapabilityManager
import org.siloserver.silo.common.player.DisplayHdrProbe
import org.siloserver.silo.common.player.MediaCodecCapabilitiesProbe
import org.siloserver.silo.common.player.PlaybackNetworkEvidenceProvider
import org.siloserver.silo.common.player.audio.PassthroughSuppressionRegistry
import org.siloserver.silo.model.diagnostics.DeviceSnapshot
import org.siloserver.silo.model.diagnostics.DiagnosticsDeviceProvenance
import org.siloserver.silo.model.diagnostics.DiagnosticsDeviceSentinel
import org.siloserver.silo.model.diagnostics.validate
import org.siloserver.silo.model.playback.AudioPassthroughEntry

@Serializable
data class DiagnosticsIdentitySnapshot(
    val manufacturer: String,
    val model: String,
    val device: String,
    @SerialName("os_release") val osRelease: String,
    @SerialName("sdk_int") val sdkInt: Int,
    @SerialName("form_factor") val formFactor: String,
    @SerialName("build_fingerprint_hash") val buildFingerprintHash: String,
)

@Serializable
data class DiagnosticsDisplaySnapshot(
    @SerialName("width_px") val widthPx: Int,
    @SerialName("height_px") val heightPx: Int,
    @SerialName("refresh_rate_hz") val refreshRateHz: Double,
    @SerialName("current_mode") val currentMode: String = "unknown",
    @SerialName("supported_modes") val supportedModes: List<String> = emptyList(),
    @SerialName("wide_color_gamut") val wideColorGamut: Boolean? = null,
    val hdr10: Boolean,
    @SerialName("hdr10_plus") val hdr10Plus: Boolean,
    val hlg: Boolean,
    @SerialName("dolby_vision_profiles") val dolbyVisionProfiles: List<Int>,
)

@Serializable
data class DiagnosticsAudioOutputSnapshot(
    val type: String,
    val encodings: List<String>,
    @SerialName("max_channels") val maxChannels: Int,
)

@Serializable
data class DiagnosticsAudioSnapshot(
    @SerialName("sink_type") val sinkType: String,
    @SerialName("route_hashes") val routeHashes: List<String>,
    @SerialName("route_generation") val routeGeneration: Long,
    @SerialName("passthrough_codecs") val passthroughCodecs: List<String>,
    @SerialName("max_channels") val maxChannels: Int,
    @SerialName("spatializer_enabled") val spatializerEnabled: Boolean,
    @SerialName("passthrough_entries") val passthroughEntries: List<AudioPassthroughEntry> = emptyList(),
    @SerialName("suppressed_formats") val suppressedFormats: List<String>,
    @SerialName("suppression_retry_used") val suppressionRetryUsed: Boolean = false,
    val outputs: List<DiagnosticsAudioOutputSnapshot> = emptyList(),
)

@Serializable
data class DiagnosticsCodecSnapshot(
    val codec: String,
    @SerialName("decoder_name") val decoderName: String? = null,
    val hardware: Boolean,
    val profiles: List<String> = emptyList(),
    @SerialName("bit_depths") val bitDepths: List<Int> = emptyList(),
    @SerialName("max_width") val maxWidth: Int? = null,
    @SerialName("max_height") val maxHeight: Int? = null,
    @SerialName("max_frame_rate") val maxFrameRate: Double? = null,
    val levels: List<Int> = emptyList(),
    @SerialName("max_bitrate_kbps") val maxBitrateKbps: Int? = null,
)

@Serializable
data class DiagnosticsNetworkSnapshot(
    val connected: Boolean,
    val validated: Boolean,
    val metered: Boolean,
    val transport: String,
    @SerialName("bandwidth_estimate_kbps") val bandwidthEstimateKbps: Int? = null,
    @SerialName("link_downstream_kbps") val linkDownstreamKbps: Int? = null,
)

interface DiagnosticsDeviceProbe {
    fun identity(): DiagnosticsIdentitySnapshot
    fun display(): DiagnosticsDisplaySnapshot?
    fun audio(): DiagnosticsAudioSnapshot?
    fun codecs(): List<DiagnosticsCodecSnapshot>?
    fun network(): DiagnosticsNetworkSnapshot?
}

class DeviceSnapshotCollector(
    private val probe: DiagnosticsDeviceProbe,
    private val nowRfc3339: () -> String = ::currentRfc3339,
) {
    fun capture(provenance: DiagnosticsDeviceProvenance): DeviceSnapshot = DeviceSnapshot(
        capturedAt = nowRfc3339(),
        provenance = provenance,
        identity = section(probe::identity),
        display = section(probe::display),
        audio = section(probe::audio),
        videoCodecs = section { probe.codecs()?.take(MAX_CODECS) },
        network = section(probe::network),
    ).also(DeviceSnapshot::validate)

    private inline fun <reified T> section(block: () -> T?): JsonElement =
        try {
            block()?.let(JSON::encodeToJsonElement) ?: NOT_COLLECTED
        } catch (_: Exception) {
            UNKNOWN
        }

    private companion object {
        const val MAX_CODECS = 128
        val UNKNOWN = JsonPrimitive(DiagnosticsDeviceSentinel.UNKNOWN.wireValue)
        val NOT_COLLECTED = JsonPrimitive(DiagnosticsDeviceSentinel.NOT_COLLECTED.wireValue)
        val JSON = Json { encodeDefaults = true; explicitNulls = false }
    }
}

class DeviceSnapshotCache {
    private val bytes = AtomicReference<ByteArray?>(null)

    fun update(snapshot: DeviceSnapshot) {
        snapshot.validate()
        bytes.set(JSON.encodeToString(snapshot).encodeToByteArray())
    }

    fun currentBytes(): ByteArray? = bytes.get()?.copyOf()

    private companion object {
        val JSON = Json { encodeDefaults = true; explicitNulls = false }
    }
}

class AndroidDiagnosticsDeviceProbe(
    context: Context,
    private val audioCapabilityManager: AudioCapabilityManager,
    private val networkEvidenceProvider: PlaybackNetworkEvidenceProvider,
) : DiagnosticsDeviceProbe {
    private val appContext = context.applicationContext

    override fun identity(): DiagnosticsIdentitySnapshot = DiagnosticsIdentitySnapshot(
        manufacturer = Build.MANUFACTURER.safeBuildValue(),
        model = Build.MODEL.safeBuildValue(),
        device = Build.DEVICE.safeBuildValue(),
        osRelease = Build.VERSION.RELEASE.safeBuildValue(),
        sdkInt = Build.VERSION.SDK_INT,
        formFactor = formFactor(appContext),
        buildFingerprintHash = stableIdentifierHash(Build.FINGERPRINT),
    )

    override fun display(): DiagnosticsDisplaySnapshot? =
        DisplayHdrProbe.diagnosticsSnapshot(appContext)?.let { snapshot ->
            DiagnosticsDisplaySnapshot(
                widthPx = snapshot.widthPx,
                heightPx = snapshot.heightPx,
                refreshRateHz = snapshot.refreshRateHz,
                currentMode = snapshot.currentMode,
                supportedModes = snapshot.supportedModes.take(MAX_NESTED_ITEMS),
                wideColorGamut = snapshot.wideColorGamut,
                hdr10 = snapshot.hdr.hdr10,
                hdr10Plus = snapshot.hdr.hdr10Plus,
                hlg = snapshot.hdr.hlg,
                dolbyVisionProfiles = snapshot.hdr.dolbyVisionProfiles.toList(),
            )
        }

    override fun audio(): DiagnosticsAudioSnapshot {
        val snapshot = audioCapabilityManager.diagnosticsSnapshot()
        val suppression = PassthroughSuppressionRegistry.diagnosticsSnapshot()
        val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        return DiagnosticsAudioSnapshot(
            sinkType = snapshot.sinkType,
            routeHashes = snapshot.routeHashes.toList(),
            routeGeneration = snapshot.routeGeneration,
            passthroughCodecs = snapshot.capabilities.passthroughCodecs.toList(),
            maxChannels = snapshot.capabilities.maxChannels,
            spatializerEnabled = snapshot.capabilities.spatializerEnabled,
            passthroughEntries = snapshot.capabilities.entries.toList(),
            suppressedFormats = suppression.suppressedFormats,
            suppressionRetryUsed = suppression.retryUsed,
            outputs = audioManager
                ?.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                .orEmpty()
                .take(MAX_NESTED_ITEMS)
                .map { device ->
                    DiagnosticsAudioOutputSnapshot(
                        type = audioDeviceTypeName(device.type),
                        encodings = device.encodings
                            .map(::audioEncodingName)
                            .distinct()
                            .take(MAX_NESTED_ITEMS),
                        maxChannels = device.channelCounts.maxOrNull() ?: 0,
                    )
                },
        )
    }

    override fun codecs(): List<DiagnosticsCodecSnapshot> =
        MediaCodecCapabilitiesProbe.diagnosticsSnapshot().videoDecodeCapabilities.map { capability ->
            DiagnosticsCodecSnapshot(
                codec = capability.codec.take(MAX_TEXT_BYTES),
                decoderName = capability.decoderName?.take(MAX_TEXT_BYTES),
                hardware = capability.hardware,
                profiles = capability.profiles.take(MAX_NESTED_ITEMS).map { it.take(MAX_TEXT_BYTES) },
                bitDepths = capability.bitDepths.take(MAX_NESTED_ITEMS),
                maxWidth = capability.maxWidth,
                maxHeight = capability.maxHeight,
                maxFrameRate = capability.maxFrameRate,
                levels = capability.levels.take(MAX_NESTED_ITEMS),
                maxBitrateKbps = capability.maxBitrateKbps,
            )
        }

    override fun network(): DiagnosticsNetworkSnapshot {
        val snapshot = networkEvidenceProvider.snapshot()
        return DiagnosticsNetworkSnapshot(
            connected = snapshot.connected,
            validated = snapshot.validated,
            metered = snapshot.metered,
            transport = snapshot.transport,
            bandwidthEstimateKbps = snapshot.bandwidthEstimateKbps,
            linkDownstreamKbps = snapshot.linkDownstreamKbps,
        )
    }
}

private fun audioDeviceTypeName(type: Int): String = when (type) {
    AudioDeviceInfo.TYPE_HDMI -> "HDMI"
    AudioDeviceInfo.TYPE_HDMI_ARC -> "HDMI_ARC"
    AudioDeviceInfo.TYPE_HDMI_EARC -> "HDMI_EARC"
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

private fun audioEncodingName(encoding: Int): String = when (encoding) {
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
    AudioFormat.ENCODING_DTS_UHD -> "DTS_UHD"
    AudioFormat.ENCODING_AAC_LC -> "AAC_LC"
    else -> "ENCODING_$encoding"
}

internal fun stableIdentifierHash(value: String): String =
    MessageDigest.getInstance("SHA-256")
        .digest(value.encodeToByteArray())
        .take(IDENTIFIER_HASH_BYTES)
        .joinToString(separator = "") { byte -> "%02x".format(Locale.ROOT, byte.toInt() and 0xff) }

private fun String.safeBuildValue(): String = trim().ifEmpty { DiagnosticsDeviceSentinel.UNKNOWN.wireValue }.take(128)

private fun formFactor(context: Context): String {
    val uiMode = (context.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager)?.currentModeType
    return when {
        uiMode == Configuration.UI_MODE_TYPE_TELEVISION -> "tv"
        uiMode == Configuration.UI_MODE_TYPE_WATCH -> "watch"
        uiMode == Configuration.UI_MODE_TYPE_CAR -> "automotive"
        context.resources.configuration.smallestScreenWidthDp >= 600 -> "tablet"
        else -> "mobile"
    }
}

private fun currentRfc3339(): String = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).run {
    timeZone = TimeZone.getTimeZone("UTC")
    format(Date())
}

private const val IDENTIFIER_HASH_BYTES = 16
private const val MAX_TEXT_BYTES = 512
private const val MAX_NESTED_ITEMS = 128
