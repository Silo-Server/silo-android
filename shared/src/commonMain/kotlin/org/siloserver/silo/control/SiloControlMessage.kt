package org.siloserver.silo.control

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

/** Which side of the control connection a hello comes from. */
enum class SiloControlPeerRole(val wire: String) {
    Phone("phone"),
    Tv("tv");

    companion object {
        fun fromWire(value: String): SiloControlPeerRole =
            entries.firstOrNull { it.wire == value }
                ?: throw SiloControlMessageException("Unknown SiloControlPeerRole: $value")
    }
}

/** Thrown when a message cannot be encoded or decoded against the wire schema. */
class SiloControlMessageException(message: String) : Exception(message)

/** First message each peer sends after the connection opens. */
data class SiloControlHello(
    val role: SiloControlPeerRole,
    val deviceName: String,
    val deviceId: String,
    val serverId: String?,
    val serverName: String?,
    val supportedVersions: List<Int>,
)

/** What to play, carried inside a [SiloControlLaunchRequest]. */
data class SiloControlPlaybackRequest(
    val contentId: String,
    val fileId: Int? = null,
    val audioTrackIndex: Int? = null,
    val subtitleTrackIndex: Int? = null,
    val startFromBeginning: Boolean = false,
    val resumePosition: Double? = null,
)

/** phone → TV: start playing [playback] on the TV (part 3; unsupported here). */
data class SiloControlLaunchRequest(
    val serverId: String,
    val playback: SiloControlPlaybackRequest,
)

/** A selectable audio or subtitle track as advertised in playback state. */
data class SiloControlTrack(
    val kind: String,
    val trackId: Long,
    val title: String,
    val detail: String? = null,
)

/** A generic selectable option (currently video quality variants). */
data class SiloControlOption(
    val id: String,
    val label: String,
    val detail: String? = null,
)

/**
 * TV → phone playback state snapshot, pushed every 500 ms while a controller
 * is connected. Field set mirrors silo-apple's `SiloControlPlaybackState`
 * exactly; the four optional subtitle fields are omitted from the wire when
 * null (Swift `encodeIfPresent` behavior).
 */
data class SiloControlPlaybackState(
    val contentId: String?,
    val sessionId: String?,
    val title: String,
    val subtitle: String?,
    val isPlaying: Boolean,
    val isLoading: Boolean,
    val isBuffering: Boolean,
    val currentTime: Double,
    val duration: Double,
    val audioTracks: List<SiloControlTrack>,
    val subtitleTracks: List<SiloControlTrack>,
    val selectedAudioTrackId: Long?,
    val selectedSubtitleTrackId: Long?,
    val qualityOptions: List<SiloControlOption>,
    val activeQualityId: String,
    val isQualitySwitching: Boolean,
    val playbackSpeed: Double,
    val videoGravity: String,
    val hdrEnabled: Boolean,
    val supportsVideoGravity: Boolean,
    val supportsHDRToggle: Boolean,
    val subtitleSyncMs: Int? = null,
    val subtitlePosition: String? = null,
    val supportsSubtitleDelay: Boolean? = null,
    val supportsSubtitlePosition: Boolean? = null,
    val volume: Double,
    val isMuted: Boolean,
    val hasNextEpisode: Boolean,
    val nextEpisodeTitle: String?,
    val error: String?,
)

/** Wire names for [SiloControlCommand.name] — snake_case, matching Apple. */
enum class SiloControlCommandName(val wire: String) {
    Play("play"),
    Pause("pause"),
    PlayPause("play_pause"),
    Seek("seek"),
    Stop("stop"),
    SelectAudioTrack("select_audio_track"),
    SelectSubtitleTrack("select_subtitle_track"),
    SetPlaybackSpeed("set_playback_speed"),
    SetQuality("set_quality"),
    SetVideoGravity("set_video_gravity"),
    SetHdrEnabled("set_hdr_enabled"),
    SetSubtitleSyncMs("set_subtitle_sync_ms"),
    SetSubtitlePosition("set_subtitle_position"),
    SetVolume("set_volume"),
    SetMuted("set_muted"),
    PlayNext("play_next");

    companion object {
        fun fromWire(value: String): SiloControlCommandName =
            entries.firstOrNull { it.wire == value }
                ?: throw SiloControlMessageException("Unknown SiloControlCommandName: $value")
    }
}

/** phone → TV: one remote-control command. Argument fields per [name]. */
data class SiloControlCommand(
    val name: SiloControlCommandName,
    val seconds: Double? = null,
    val trackId: Long? = null,
    val speed: Double? = null,
    val volume: Double? = null,
    val value: String? = null,
    val enabled: Boolean? = null,
    val milliseconds: Int? = null,
)

/** Either direction, non-fatal error surface (code is machine-readable). */
data class SiloControlErrorMessage(
    val code: String,
    val message: String,
)

/**
 * A message on the wire. Encoded as a JSON object with a `type` discriminator,
 * a `v` (version) field, and — unlike the flattened pairing protocol — the
 * payload NESTED under a key equal to the type. Byte-compatible with the
 * silo-apple `SiloControlMessage` Codable.
 */
sealed class SiloControlMessage {
    data class Hello(val hello: SiloControlHello) : SiloControlMessage()
    data class Launch(val launch: SiloControlLaunchRequest) : SiloControlMessage()
    data class Control(val command: SiloControlCommand) : SiloControlMessage()
    data class State(val state: SiloControlPlaybackState) : SiloControlMessage()
    data class Error(val error: SiloControlErrorMessage) : SiloControlMessage()
    data object Ping : SiloControlMessage()
    data object Pong : SiloControlMessage()
    data object Close : SiloControlMessage()
}

/**
 * Hand-written kotlinx.serialization codec producing the exact wire shape used
 * by silo-apple. The discriminator key is `type`; `v` is always
 * [SiloControlProtocol.VERSION]; the payload lives under a key equal to the
 * type value (`ping`/`pong`/`close` carry no payload). Optional fields are
 * omitted when null.
 */
object SiloControlMessageCodec {
    private const val KEY_TYPE = "type"
    private const val KEY_V = "v"

    private val json = Json {
        // Deterministic, compact output matching the iOS encoder's defaults.
        encodeDefaults = true
        explicitNulls = false
        ignoreUnknownKeys = true
    }

    fun encode(msg: SiloControlMessage): String =
        json.encodeToString(JsonObject.serializer(), toJsonObject(msg))

    fun decode(json: String): SiloControlMessage {
        val element = this.json.parseToJsonElement(json)
        val obj = element as? JsonObject
            ?: throw SiloControlMessageException("Control message must be a JSON object")
        return fromJsonObject(obj)
    }

    private fun toJsonObject(msg: SiloControlMessage): JsonObject = buildJsonObject {
        put(KEY_V, JsonPrimitive(SiloControlProtocol.VERSION))
        when (msg) {
            is SiloControlMessage.Hello -> {
                put(KEY_TYPE, JsonPrimitive("hello"))
                put("hello", helloToJson(msg.hello))
            }
            is SiloControlMessage.Launch -> {
                put(KEY_TYPE, JsonPrimitive("launch"))
                put("launch", launchToJson(msg.launch))
            }
            is SiloControlMessage.Control -> {
                put(KEY_TYPE, JsonPrimitive("control"))
                put("control", commandToJson(msg.command))
            }
            is SiloControlMessage.State -> {
                put(KEY_TYPE, JsonPrimitive("state"))
                put("state", stateToJson(msg.state))
            }
            is SiloControlMessage.Error -> {
                put(KEY_TYPE, JsonPrimitive("error"))
                put(
                    "error",
                    buildJsonObject {
                        put("code", JsonPrimitive(msg.error.code))
                        put("message", JsonPrimitive(msg.error.message))
                    },
                )
            }
            is SiloControlMessage.Ping -> put(KEY_TYPE, JsonPrimitive("ping"))
            is SiloControlMessage.Pong -> put(KEY_TYPE, JsonPrimitive("pong"))
            is SiloControlMessage.Close -> put(KEY_TYPE, JsonPrimitive("close"))
        }
    }

    private fun fromJsonObject(obj: JsonObject): SiloControlMessage {
        val type = obj.requireString(KEY_TYPE)
        return when (type) {
            "hello" -> SiloControlMessage.Hello(helloFromJson(obj.requireObject("hello")))
            "launch" -> SiloControlMessage.Launch(launchFromJson(obj.requireObject("launch")))
            "control" -> SiloControlMessage.Control(commandFromJson(obj.requireObject("control")))
            "state" -> SiloControlMessage.State(stateFromJson(obj.requireObject("state")))
            "error" -> {
                val e = obj.requireObject("error")
                SiloControlMessage.Error(
                    SiloControlErrorMessage(
                        code = e.requireString("code"),
                        message = e.requireString("message"),
                    ),
                )
            }
            "ping" -> SiloControlMessage.Ping
            "pong" -> SiloControlMessage.Pong
            "close" -> SiloControlMessage.Close
            else -> throw SiloControlMessageException("Unknown control message type: $type")
        }
    }

    // ---- Payload codecs --------------------------------------------------------

    private fun helloToJson(hello: SiloControlHello): JsonObject = buildJsonObject {
        put("role", JsonPrimitive(hello.role.wire))
        put("deviceName", JsonPrimitive(hello.deviceName))
        put("deviceId", JsonPrimitive(hello.deviceId))
        if (hello.serverId != null) put("serverId", JsonPrimitive(hello.serverId))
        if (hello.serverName != null) put("serverName", JsonPrimitive(hello.serverName))
        put(
            "supportedVersions",
            buildJsonArray { hello.supportedVersions.forEach { add(JsonPrimitive(it)) } },
        )
    }

    private fun helloFromJson(obj: JsonObject): SiloControlHello = SiloControlHello(
        role = SiloControlPeerRole.fromWire(obj.requireString("role")),
        deviceName = obj.requireString("deviceName"),
        deviceId = obj.requireString("deviceId"),
        serverId = obj.optionalString("serverId"),
        serverName = obj.optionalString("serverName"),
        supportedVersions = obj.requireIntArray("supportedVersions"),
    )

    private fun launchToJson(launch: SiloControlLaunchRequest): JsonObject = buildJsonObject {
        put("serverId", JsonPrimitive(launch.serverId))
        put(
            "playback",
            buildJsonObject {
                val p = launch.playback
                put("contentId", JsonPrimitive(p.contentId))
                if (p.fileId != null) put("fileId", JsonPrimitive(p.fileId))
                if (p.audioTrackIndex != null) put("audioTrackIndex", JsonPrimitive(p.audioTrackIndex))
                if (p.subtitleTrackIndex != null) {
                    put("subtitleTrackIndex", JsonPrimitive(p.subtitleTrackIndex))
                }
                put("startFromBeginning", JsonPrimitive(p.startFromBeginning))
                if (p.resumePosition != null) put("resumePosition", JsonPrimitive(p.resumePosition))
            },
        )
    }

    private fun launchFromJson(obj: JsonObject): SiloControlLaunchRequest {
        val p = obj.requireObject("playback")
        return SiloControlLaunchRequest(
            serverId = obj.requireString("serverId"),
            playback = SiloControlPlaybackRequest(
                contentId = p.requireString("contentId"),
                fileId = p.optionalInt("fileId"),
                audioTrackIndex = p.optionalInt("audioTrackIndex"),
                subtitleTrackIndex = p.optionalInt("subtitleTrackIndex"),
                startFromBeginning = p.requireBoolean("startFromBeginning"),
                resumePosition = p.optionalDouble("resumePosition"),
            ),
        )
    }

    private fun commandToJson(command: SiloControlCommand): JsonObject = buildJsonObject {
        put("name", JsonPrimitive(command.name.wire))
        if (command.seconds != null) put("seconds", JsonPrimitive(command.seconds))
        if (command.trackId != null) put("trackId", JsonPrimitive(command.trackId))
        if (command.speed != null) put("speed", JsonPrimitive(command.speed))
        if (command.volume != null) put("volume", JsonPrimitive(command.volume))
        if (command.value != null) put("value", JsonPrimitive(command.value))
        if (command.enabled != null) put("enabled", JsonPrimitive(command.enabled))
        if (command.milliseconds != null) put("milliseconds", JsonPrimitive(command.milliseconds))
    }

    private fun commandFromJson(obj: JsonObject): SiloControlCommand = SiloControlCommand(
        name = SiloControlCommandName.fromWire(obj.requireString("name")),
        seconds = obj.optionalDouble("seconds"),
        trackId = obj.optionalLong("trackId"),
        speed = obj.optionalDouble("speed"),
        volume = obj.optionalDouble("volume"),
        value = obj.optionalString("value"),
        enabled = obj.optionalBoolean("enabled"),
        milliseconds = obj.optionalInt("milliseconds"),
    )

    private fun trackToJson(track: SiloControlTrack): JsonObject = buildJsonObject {
        put("kind", JsonPrimitive(track.kind))
        put("trackId", JsonPrimitive(track.trackId))
        put("title", JsonPrimitive(track.title))
        if (track.detail != null) put("detail", JsonPrimitive(track.detail))
    }

    private fun trackFromJson(obj: JsonObject): SiloControlTrack = SiloControlTrack(
        kind = obj.requireString("kind"),
        trackId = obj.requireLong("trackId"),
        title = obj.requireString("title"),
        detail = obj.optionalString("detail"),
    )

    private fun optionToJson(option: SiloControlOption): JsonObject = buildJsonObject {
        put("id", JsonPrimitive(option.id))
        put("label", JsonPrimitive(option.label))
        if (option.detail != null) put("detail", JsonPrimitive(option.detail))
    }

    private fun optionFromJson(obj: JsonObject): SiloControlOption = SiloControlOption(
        id = obj.requireString("id"),
        label = obj.requireString("label"),
        detail = obj.optionalString("detail"),
    )

    private fun stateToJson(state: SiloControlPlaybackState): JsonObject = buildJsonObject {
        if (state.contentId != null) put("contentId", JsonPrimitive(state.contentId))
        if (state.sessionId != null) put("sessionId", JsonPrimitive(state.sessionId))
        put("title", JsonPrimitive(state.title))
        if (state.subtitle != null) put("subtitle", JsonPrimitive(state.subtitle))
        put("isPlaying", JsonPrimitive(state.isPlaying))
        put("isLoading", JsonPrimitive(state.isLoading))
        put("isBuffering", JsonPrimitive(state.isBuffering))
        put("currentTime", JsonPrimitive(state.currentTime))
        put("duration", JsonPrimitive(state.duration))
        put("audioTracks", buildJsonArray { state.audioTracks.forEach { add(trackToJson(it)) } })
        put("subtitleTracks", buildJsonArray { state.subtitleTracks.forEach { add(trackToJson(it)) } })
        if (state.selectedAudioTrackId != null) {
            put("selectedAudioTrackId", JsonPrimitive(state.selectedAudioTrackId))
        }
        if (state.selectedSubtitleTrackId != null) {
            put("selectedSubtitleTrackId", JsonPrimitive(state.selectedSubtitleTrackId))
        }
        put("qualityOptions", buildJsonArray { state.qualityOptions.forEach { add(optionToJson(it)) } })
        put("activeQualityId", JsonPrimitive(state.activeQualityId))
        put("isQualitySwitching", JsonPrimitive(state.isQualitySwitching))
        put("playbackSpeed", JsonPrimitive(state.playbackSpeed))
        put("videoGravity", JsonPrimitive(state.videoGravity))
        put("hdrEnabled", JsonPrimitive(state.hdrEnabled))
        put("supportsVideoGravity", JsonPrimitive(state.supportsVideoGravity))
        put("supportsHDRToggle", JsonPrimitive(state.supportsHDRToggle))
        if (state.subtitleSyncMs != null) put("subtitleSyncMs", JsonPrimitive(state.subtitleSyncMs))
        if (state.subtitlePosition != null) put("subtitlePosition", JsonPrimitive(state.subtitlePosition))
        if (state.supportsSubtitleDelay != null) {
            put("supportsSubtitleDelay", JsonPrimitive(state.supportsSubtitleDelay))
        }
        if (state.supportsSubtitlePosition != null) {
            put("supportsSubtitlePosition", JsonPrimitive(state.supportsSubtitlePosition))
        }
        put("volume", JsonPrimitive(state.volume))
        put("isMuted", JsonPrimitive(state.isMuted))
        put("hasNextEpisode", JsonPrimitive(state.hasNextEpisode))
        if (state.nextEpisodeTitle != null) put("nextEpisodeTitle", JsonPrimitive(state.nextEpisodeTitle))
        if (state.error != null) put("error", JsonPrimitive(state.error))
    }

    private fun stateFromJson(obj: JsonObject): SiloControlPlaybackState = SiloControlPlaybackState(
        contentId = obj.optionalString("contentId"),
        sessionId = obj.optionalString("sessionId"),
        title = obj.requireString("title"),
        subtitle = obj.optionalString("subtitle"),
        isPlaying = obj.requireBoolean("isPlaying"),
        isLoading = obj.requireBoolean("isLoading"),
        isBuffering = obj.requireBoolean("isBuffering"),
        currentTime = obj.requireDouble("currentTime"),
        duration = obj.requireDouble("duration"),
        audioTracks = obj.requireObjectArray("audioTracks").map(::trackFromJson),
        subtitleTracks = obj.requireObjectArray("subtitleTracks").map(::trackFromJson),
        selectedAudioTrackId = obj.optionalLong("selectedAudioTrackId"),
        selectedSubtitleTrackId = obj.optionalLong("selectedSubtitleTrackId"),
        qualityOptions = obj.requireObjectArray("qualityOptions").map(::optionFromJson),
        activeQualityId = obj.requireString("activeQualityId"),
        isQualitySwitching = obj.requireBoolean("isQualitySwitching"),
        playbackSpeed = obj.requireDouble("playbackSpeed"),
        videoGravity = obj.requireString("videoGravity"),
        hdrEnabled = obj.requireBoolean("hdrEnabled"),
        supportsVideoGravity = obj.requireBoolean("supportsVideoGravity"),
        supportsHDRToggle = obj.requireBoolean("supportsHDRToggle"),
        subtitleSyncMs = obj.optionalInt("subtitleSyncMs"),
        subtitlePosition = obj.optionalString("subtitlePosition"),
        supportsSubtitleDelay = obj.optionalBoolean("supportsSubtitleDelay"),
        supportsSubtitlePosition = obj.optionalBoolean("supportsSubtitlePosition"),
        volume = obj.requireDouble("volume"),
        isMuted = obj.requireBoolean("isMuted"),
        hasNextEpisode = obj.requireBoolean("hasNextEpisode"),
        nextEpisodeTitle = obj.optionalString("nextEpisodeTitle"),
        error = obj.optionalString("error"),
    )

    // ---- JSON helpers ------------------------------------------------------------

    private fun JsonObject.requireObject(key: String): JsonObject =
        (this[key] as? JsonObject)
            ?: throw SiloControlMessageException("Missing object field: $key")

    private fun JsonObject.requireObjectArray(key: String): List<JsonObject> {
        val arr = this[key]?.jsonArray
            ?: throw SiloControlMessageException("Missing array field: $key")
        return arr.map {
            it as? JsonObject ?: throw SiloControlMessageException("Expected object in array: $key")
        }
    }

    private fun JsonObject.requireString(key: String): String =
        ((this[key] as? JsonPrimitive)
            ?: throw SiloControlMessageException("Missing field: $key")).content

    private fun JsonObject.optionalString(key: String): String? =
        (this[key] as? JsonPrimitive)?.content

    private fun JsonObject.requireBoolean(key: String): Boolean =
        ((this[key] as? JsonPrimitive)
            ?: throw SiloControlMessageException("Missing field: $key")).boolean

    private fun JsonObject.optionalBoolean(key: String): Boolean? =
        (this[key] as? JsonPrimitive)?.boolean

    private fun JsonObject.requireDouble(key: String): Double =
        ((this[key] as? JsonPrimitive)
            ?: throw SiloControlMessageException("Missing field: $key")).double

    private fun JsonObject.optionalDouble(key: String): Double? =
        (this[key] as? JsonPrimitive)?.double

    private fun JsonObject.requireLong(key: String): Long =
        ((this[key] as? JsonPrimitive)
            ?: throw SiloControlMessageException("Missing field: $key")).long

    private fun JsonObject.optionalLong(key: String): Long? =
        (this[key] as? JsonPrimitive)?.long

    private fun JsonObject.optionalInt(key: String): Int? =
        (this[key] as? JsonPrimitive)?.int

    private fun JsonObject.requireIntArray(key: String): List<Int> {
        val arr = this[key]?.jsonArray
            ?: throw SiloControlMessageException("Missing field: $key")
        return arr.map { it.jsonPrimitive.int }
    }
}
