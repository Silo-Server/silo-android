package org.siloserver.silo.model.diagnostics

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * `device.json` archive entry, schema v1.
 *
 * The capability sections are deliberately open-shaped: each is either a JSON
 * object (`video_codecs`: an array), or one of the honesty sentinels
 * `"unknown"` (a probe exists but couldn't determine the value) /
 * `"not_collected"` (no probe on this platform). [JsonElement] carries that
 * union directly; [validateShape] enforces it.
 */
@Serializable
data class DiagnosticsDeviceSnapshot(
    @SerialName("captured_at") val capturedAt: String,
    val provenance: DiagnosticsDeviceProvenance,
    val identity: JsonElement,
    val display: JsonElement,
    val audio: JsonElement,
    @SerialName("video_codecs") val videoCodecs: JsonElement,
    val network: JsonElement,
) {
    companion object {
        const val SENTINEL_UNKNOWN = "unknown"
        const val SENTINEL_NOT_COLLECTED = "not_collected"

        val unknown: JsonElement get() = JsonPrimitive(SENTINEL_UNKNOWN)
        val notCollected: JsonElement get() = JsonPrimitive(SENTINEL_NOT_COLLECTED)
    }
}

internal fun JsonElement.isTriStateObject(): Boolean = when (this) {
    is JsonObject -> true
    is JsonNull -> false
    is JsonPrimitive -> isString &&
        (content == DiagnosticsDeviceSnapshot.SENTINEL_UNKNOWN ||
            content == DiagnosticsDeviceSnapshot.SENTINEL_NOT_COLLECTED)
    else -> false
}

internal fun JsonElement.isTriStateArray(): Boolean = when (this) {
    is JsonArray -> true
    is JsonNull -> false
    is JsonPrimitive -> isString &&
        (content == DiagnosticsDeviceSnapshot.SENTINEL_UNKNOWN ||
            content == DiagnosticsDeviceSnapshot.SENTINEL_NOT_COLLECTED)
    else -> false
}
