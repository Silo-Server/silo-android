package org.siloserver.silo.watchtogether

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.siloserver.silo.network.SiloJson
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * A room access token and the expiry the server signed into it. Renewing the
 * proof never changes room membership.
 */
class RoomProof(
    val token: String,
    val expiresAtEpochMs: Long?,
) {
    override fun toString(): String = "RoomProof(token=<redacted>, expiresAtEpochMs=$expiresAtEpochMs)"
}

/**
 * Reads the signed `exp` claim (epoch seconds) from a room proof JWT without
 * verifying it; the server verifies. Returns epoch milliseconds, or null when
 * the token does not carry a readable expiry.
 */
@OptIn(ExperimentalEncodingApi::class)
fun roomProofExpiryMs(token: String): Long? {
    val payload = token.split('.').takeIf { it.size == 3 }?.get(1)?.takeIf { it.isNotEmpty() } ?: return null
    val padded = payload + "=".repeat((4 - payload.length % 4) % 4)
    val claims = try {
        SiloJson.parseToJsonElement(Base64.UrlSafe.decode(padded).decodeToString()) as? JsonObject
    } catch (_: Exception) {
        null
    } ?: return null
    val exp = (claims["exp"] as? JsonPrimitive)?.longOrNull ?: return null
    return exp.takeIf { it > 0 }?.times(1_000L)
}
