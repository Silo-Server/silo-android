package org.siloserver.silo.network.apiv2

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import org.siloserver.silo.network.SiloJson
import org.siloserver.silo.network.ApiResult

const val PLAYBACK_OWNER_LOST = "playback_owner_lost"
const val PLAYBACK_OWNER_LOST_MESSAGE = "Playback ended after its server owner was lost. Open the item again to start playback."

/** Only the journal's validated local settlement, never an HTTP problem with the same reason string. */
fun ApiResult<*>?.isPlaybackOwnerLossTerminal(): Boolean =
    this is ApiResult.Error && code == 0 && error == PLAYBACK_OWNER_LOST

/** A distinct server abandonment, never acknowledgement of a client StopID or final sample. */
@Serializable
data class PlaybackOwnerLossV2(
    @SerialName("recovery_id") val recoveryId: String,
    @SerialName("playback_attempt_id") val playbackAttemptId: String,
    @SerialName("session_id") val sessionId: String,
    val state: String,
    val reason: String,
    val accepted: PlaybackSampleV2? = null,
)

/** Validate the wire union before ordinary START/STOP mandatory fields. Identity is checked by the journal. */
internal fun decodeOwnerLoss(body: JsonObject, status: Int, start: Boolean): PlaybackOwnerLossV2? {
    if ("recovery" !in body) return null
    val raw = body["recovery"] as? JsonObject ?: error("Invalid recovery object")
    val envelopeFields = if (start && status == 201) setOf("protocol_version", "server_features", "outcome", "terminal", "recovery")
        else setOf("outcome", "recovery")
    require(body.keys.all { it in envelopeFields })
    require(raw.keys.all { it in setOf("recovery_id", "playback_attempt_id", "session_id", "state", "reason", "accepted") })
    fun string(key: String): String = requireNotNull((raw[key] as? JsonPrimitive)
        ?.takeIf { it.isString }?.contentOrNull).also { require(it.isNotBlank()) }
    for (key in listOf("recovery_id", "playback_attempt_id", "session_id", "state", "reason")) string(key)
    require(string("recovery_id").matches(Regex("[0-9a-fA-F]{8}(?:-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}")))
    require(string("reason") == "owner_lost")
    require(listOf("session_id", "playback_plan", "progress_timeline", "route", "route_id", "activation", "stop_id", "accepted", "history_id").none { it in body })
    when (status) {
        202 -> {
            require(body["outcome"] == JsonPrimitive("draining") && string("state") == "draining")
            require("terminal" !in body && "accepted" !in raw)
        }
        if (start) 201 else 200 -> {
            require(string("state") == "aborted")
            require(body["outcome"] == JsonPrimitive(if (start) "adaptation_unavailable" else "aborted"))
            if (start) {
                require(body["protocol_version"] == JsonPrimitive(3))
                val features = body["server_features"] as? JsonArray ?: error("Missing features")
                require(features.all { it is JsonPrimitive && it.isString })
                val terminal = body["terminal"] as? JsonObject ?: error("Missing terminal")
                require(terminal["reason"] == JsonPrimitive("playback_owner_lost") && terminal["retryable"] == JsonPrimitive(false))
                require((terminal["message"] as? JsonPrimitive)?.isString == true)
            } else require("terminal" !in body)
        }
        else -> error("Invalid recovery status")
    }
    if ("accepted" in raw) {
        val accepted = raw["accepted"] as? JsonObject ?: error("Invalid accepted sample")
        val sequence = accepted["sequence"] as? JsonPrimitive
        val position = accepted["position"] as? JsonPrimitive
        val paused = accepted["is_paused"] as? JsonPrimitive
        require(sequence != null && !sequence.isString && (sequence.longOrNull ?: 0) > 0)
        require(position != null && !position.isString && position.doubleOrNull?.let { it.isFinite() && it >= 0 } == true)
        require(paused != null && !paused.isString && paused.booleanOrNull != null)
        if ("timeline_id" in accepted || "item_position" in accepted) {
            require((accepted["timeline_id"] as? JsonPrimitive)?.let { it.isString && it.content.matches(Regex("[0-9a-f]{64}")) } == true)
            require((accepted["item_position"] as? JsonPrimitive)?.let { !it.isString && it.doubleOrNull?.let { n -> n.isFinite() && n >= 0 } == true } == true)
        }
    }
    return SiloJson.decodeFromJsonElement(raw)
}
