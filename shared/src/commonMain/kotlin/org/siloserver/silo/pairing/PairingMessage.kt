package org.siloserver.silo.pairing

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

/**
 * The TV's advertised state, carried in the NSD TXT record and in [PairingMessage.Hello].
 *
 * Serialized as its lowercase raw value to match the Swift `PairingReceiverState`.
 */
enum class PairingReceiverState(val wire: String) {
    /** Blank TV with no server configured — needs a URL pushed. */
    Setup("setup"),

    /** TV already has a server — only needs a user signed in. */
    Login("login");

    companion object {
        fun fromWire(value: String): PairingReceiverState =
            entries.firstOrNull { it.wire == value }
                ?: throw PairingMessageException("Unknown PairingReceiverState: $value")
    }
}

/** Terminal per-server outcome status. Serialized as its raw value. */
enum class PairingServerStatus(val wire: String) {
    SignedIn("signedIn"),
    Failed("failed");

    companion object {
        fun fromWire(value: String): PairingServerStatus =
            entries.firstOrNull { it.wire == value }
                ?: throw PairingMessageException("Unknown PairingServerStatus: $value")
    }
}

/**
 * Why a pushed server failed on the TV. Carried in [PairingMessage.ServerResult.error]
 * so the phone can say what to fix. Older TVs send only [AuthFailed] (or, on
 * older Android TVs, free text), and any value this build doesn't know reads
 * as that generic failure. Mirrors silo-apple `PairingFailureCode`.
 */
enum class PairingFailureCode(val wire: String) {
    /** Device authorization could not be started or completed; the legacy catch-all. */
    AuthFailed("auth_failed"),

    /** The phone's user declined the request, or the server refused it. */
    Denied("denied"),

    /** The device code expired before approval, or was already used. */
    Expired("expired"),

    /** The TV could not reach the pushed address, and no offered alternative worked. */
    Unreachable("unreachable"),

    /** An address answered with a different deployment identity. */
    IdentityMismatch("identity_mismatch"),

    /** The server is v1-only, or no longer accepts this app version. */
    UpdateRequired("update_required");

    companion object {
        fun fromWire(value: String?): PairingFailureCode =
            entries.firstOrNull { it.wire == value } ?: AuthFailed
    }
}

/**
 * One address a deployment offers. The same value crosses the
 * `system/connections` document and the pairing push, so a TV that can't reach
 * the phone's address can verify and use one it can. Mirrors silo-apple
 * `ServerEndpoint`. Build it with [of] so [url] carries no trailing slash.
 */
data class PairingEndpoint(
    val url: String,
    val kind: Kind,
    /** Provider slug for [Kind.Provider], e.g. `tailscale`. */
    val provider: String? = null,
    /** Provider display name from its manifest; never derived from the hostname. */
    val displayName: String? = null,
) {
    enum class Kind(val wire: String) {
        Public("public"),
        Provider("provider");

        companion object {
            /** Unknown kinds read as public, matching silo-apple. */
            fun fromWire(value: String?): Kind = entries.firstOrNull { it.wire == value } ?: Public
        }
    }

    companion object {
        /** Builds an endpoint with [url] normalized the way both platforms compare addresses. */
        fun of(
            url: String,
            kind: Kind,
            provider: String? = null,
            displayName: String? = null,
        ): PairingEndpoint = PairingEndpoint(normalizeEndpointUrl(url), kind, provider, displayName)
    }
}

fun normalizeEndpointUrl(url: String): String = url.trim().trimEnd('/')

/** Thrown when a message cannot be encoded or decoded against the wire schema. */
class PairingMessageException(message: String) : Exception(message)

/**
 * A message on the wire. Encoded as a JSON object with a `type` discriminator
 * and a `v` (version) field; per-type fields are flattened alongside. Tokens
 * NEVER appear in any message — the server delivers those to the TV over HTTPS.
 *
 * Byte-compatible with the silo-apple `PairingMessage` Codable.
 */
sealed class PairingMessage {
    /** TV → phone, first message after the connection opens. */
    data class Hello(
        val tvName: String,
        val tvDeviceId: String,
        val state: PairingReceiverState,
        val supportedVersions: List<Int>,
    ) : PairingMessage()

    /**
     * phone → TV, one per chosen server. [serverIdentity] and [endpoints] are
     * optional additions (protocol still v1): the deployment identity the phone
     * verified at [serverURL], and the other addresses the deployment offers,
     * so a TV that cannot reach the phone's address can verify and use one it
     * can. Older peers omit and ignore them.
     */
    data class PushServer(
        val serverURL: String,
        val serverName: String?,
        val serverIdentity: String? = null,
        val endpoints: List<PairingEndpoint>? = null,
    ) : PairingMessage()

    /**
     * TV → phone, after the TV called device/start for a pushed server.
     * `matchCode` is advisory display only; the phone re-fetches the
     * authoritative match code from the server via lookup before approving.
     */
    data class DeviceStarted(
        val serverURL: String,
        val userCode: String,
        val matchCode: String,
    ) : PairingMessage()

    /**
     * TV → phone, terminal per-server outcome. [serverURL] always echoes the
     * pushed URL, even when the TV signed in at another address. [error] is a
     * [PairingFailureCode] wire value on failure.
     */
    data class ServerResult(
        val serverURL: String,
        val status: PairingServerStatus,
        val error: String?,
    ) : PairingMessage()

    /** phone → TV, no more servers; finish. */
    data object Done : PairingMessage()

    /** either direction, abort. */
    data class Cancel(
        val reason: String,
    ) : PairingMessage()
}

/**
 * Hand-written kotlinx.serialization codec producing the exact wire shape used
 * by silo-apple. The discriminator key is `type`; `v` is always
 * [PairingProtocol.VERSION]. Optional fields (`serverName`, `error`) are omitted
 * when null.
 */
object PairingMessageCodec {
    private const val KEY_TYPE = "type"
    private const val KEY_V = "v"

    private val json = Json {
        // Deterministic, compact output matching the iOS encoder's defaults.
        encodeDefaults = true
        explicitNulls = false
        ignoreUnknownKeys = true
    }

    fun encode(msg: PairingMessage): String = json.encodeToString(JsonObject.serializer(), toJsonObject(msg))

    fun decode(json: String): PairingMessage {
        val element = this.json.parseToJsonElement(json)
        val obj = element as? JsonObject
            ?: throw PairingMessageException("Pairing message must be a JSON object")
        return fromJsonObject(obj)
    }

    private fun toJsonObject(msg: PairingMessage): JsonObject = buildJsonObject {
        put(KEY_V, JsonPrimitive(PairingProtocol.VERSION))
        when (msg) {
            is PairingMessage.Hello -> {
                put(KEY_TYPE, JsonPrimitive("hello"))
                put("tvName", JsonPrimitive(msg.tvName))
                put("tvDeviceId", JsonPrimitive(msg.tvDeviceId))
                put("state", JsonPrimitive(msg.state.wire))
                put(
                    "supportedVersions",
                    buildJsonArray { msg.supportedVersions.forEach { add(JsonPrimitive(it)) } },
                )
            }
            is PairingMessage.PushServer -> {
                put(KEY_TYPE, JsonPrimitive("pushServer"))
                put("serverURL", JsonPrimitive(msg.serverURL))
                if (msg.serverName != null) put("serverName", JsonPrimitive(msg.serverName))
                if (msg.serverIdentity != null) put("serverIdentity", JsonPrimitive(msg.serverIdentity))
                msg.endpoints?.let { endpoints ->
                    put("endpoints", buildJsonArray { endpoints.forEach { add(it.toJson()) } })
                }
            }
            is PairingMessage.DeviceStarted -> {
                put(KEY_TYPE, JsonPrimitive("deviceStarted"))
                put("serverURL", JsonPrimitive(msg.serverURL))
                put("userCode", JsonPrimitive(msg.userCode))
                put("matchCode", JsonPrimitive(msg.matchCode))
            }
            is PairingMessage.ServerResult -> {
                put(KEY_TYPE, JsonPrimitive("serverResult"))
                put("serverURL", JsonPrimitive(msg.serverURL))
                put("status", JsonPrimitive(msg.status.wire))
                if (msg.error != null) put("error", JsonPrimitive(msg.error))
            }
            is PairingMessage.Done -> {
                put(KEY_TYPE, JsonPrimitive("done"))
            }
            is PairingMessage.Cancel -> {
                put(KEY_TYPE, JsonPrimitive("cancel"))
                put("reason", JsonPrimitive(msg.reason))
            }
        }
    }

    private fun fromJsonObject(obj: JsonObject): PairingMessage {
        val type = obj.requireString(KEY_TYPE)
        return when (type) {
            "hello" -> PairingMessage.Hello(
                tvName = obj.requireString("tvName"),
                tvDeviceId = obj.requireString("tvDeviceId"),
                state = PairingReceiverState.fromWire(obj.requireString("state")),
                supportedVersions = obj.requireIntArray("supportedVersions"),
            )
            "pushServer" -> PairingMessage.PushServer(
                serverURL = obj.requireString("serverURL"),
                serverName = obj.optionalString("serverName"),
                serverIdentity = obj.optionalString("serverIdentity"),
                endpoints = (obj["endpoints"] as? JsonArray)?.map { element ->
                    val endpoint = element as? JsonObject
                        ?: throw PairingMessageException("endpoints must hold objects")
                    PairingEndpoint.of(
                        url = endpoint.requireString("url"),
                        kind = PairingEndpoint.Kind.fromWire(endpoint.requireString("kind")),
                        provider = endpoint.optionalString("provider"),
                        displayName = endpoint.optionalString("displayName"),
                    )
                },
            )
            "deviceStarted" -> PairingMessage.DeviceStarted(
                serverURL = obj.requireString("serverURL"),
                userCode = obj.requireString("userCode"),
                matchCode = obj.requireString("matchCode"),
            )
            "serverResult" -> PairingMessage.ServerResult(
                serverURL = obj.requireString("serverURL"),
                status = PairingServerStatus.fromWire(obj.requireString("status")),
                error = obj.optionalString("error"),
            )
            "done" -> PairingMessage.Done
            "cancel" -> PairingMessage.Cancel(
                reason = obj.requireString("reason"),
            )
            else -> throw PairingMessageException("Unknown pairing message type: $type")
        }
    }

    private fun PairingEndpoint.toJson(): JsonObject = buildJsonObject {
        put("url", JsonPrimitive(url))
        put("kind", JsonPrimitive(kind.wire))
        if (provider != null) put("provider", JsonPrimitive(provider))
        if (displayName != null) put("displayName", JsonPrimitive(displayName))
    }

    private fun JsonObject.requireString(key: String): String {
        val prim = (this[key] as? JsonPrimitive)
            ?: throw PairingMessageException("Missing field: $key")
        return prim.content
    }

    private fun JsonObject.optionalString(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content

    private fun JsonObject.requireIntArray(key: String): List<Int> {
        val arr = this[key]?.jsonArray
            ?: throw PairingMessageException("Missing field: $key")
        return arr.map { it.jsonPrimitive.int }
    }
}
