package org.siloserver.silo.cast

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.elementNames
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonClassDiscriminator
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Wire model for the `_silocast._tcp` phone→TV control channel.
 *
 * BYTE-COMPATIBLE WITH silo-apple's SiloControlMessage
 * (iosApp/Control/SiloControlProtocol.swift) — Apple clients ship this exact
 * schema and cannot be revved in lockstep, so every field name, casing, enum
 * string, and envelope key here mirrors the Swift Codable output: camelCase
 * payload fields, `{type, v, <kind>}` envelope with the payload nested under
 * the kind key, payloadless ping/pong/close, and Int64 track ids. Change this
 * file only together with silo-apple.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
@JsonClassDiscriminator("type")
sealed class SiloCastMessage {
    abstract val v: Int

    @Serializable
    @SerialName("hello")
    data class Hello(
        val hello: SiloCastHello,
        @EncodeDefault(EncodeDefault.Mode.ALWAYS) override val v: Int = SiloCastProtocol.version,
    ) : SiloCastMessage()

    @Serializable
    @SerialName("handoff_offer")
    data class HandoffOffer(
        val handoffOffer: SiloCastHandoffOffer,
        @EncodeDefault(EncodeDefault.Mode.ALWAYS) override val v: Int = SiloCastProtocol.version,
    ) : SiloCastMessage()

    @Serializable
    @SerialName("handoff_challenge")
    data class HandoffChallenge(
        val handoffChallenge: SiloCastHandoffChallenge,
        @EncodeDefault(EncodeDefault.Mode.ALWAYS) override val v: Int = SiloCastProtocol.version,
    ) : SiloCastMessage()

    @Serializable
    @SerialName("handoff_ready")
    data class HandoffReady(
        val handoffReady: SiloCastHandoffReady,
        @EncodeDefault(EncodeDefault.Mode.ALWAYS) override val v: Int = SiloCastProtocol.version,
    ) : SiloCastMessage()

    @Serializable
    @SerialName("handoff_cancel")
    data class HandoffCancel(
        val handoffCancel: SiloCastHandoffCancel,
        @EncodeDefault(EncodeDefault.Mode.ALWAYS) override val v: Int = SiloCastProtocol.version,
    ) : SiloCastMessage()

    @Serializable
    @SerialName("launch")
    data class Launch(
        val launch: SiloCastLaunchRequest,
        @EncodeDefault(EncodeDefault.Mode.ALWAYS) override val v: Int = SiloCastProtocol.version,
    ) : SiloCastMessage()

    @Serializable
    @SerialName("control")
    data class Control(
        val control: SiloCastControlCommand,
        @EncodeDefault(EncodeDefault.Mode.ALWAYS) override val v: Int = SiloCastProtocol.version,
    ) : SiloCastMessage()

    @Serializable
    @SerialName("state")
    data class State(
        val state: SiloCastPlaybackState,
        @EncodeDefault(EncodeDefault.Mode.ALWAYS) override val v: Int = SiloCastProtocol.version,
    ) : SiloCastMessage()

    @Serializable
    @SerialName("error")
    data class Error(
        val error: SiloCastError,
        @EncodeDefault(EncodeDefault.Mode.ALWAYS) override val v: Int = SiloCastProtocol.version,
    ) : SiloCastMessage()

    @Serializable
    @SerialName("ping")
    data class Ping(
        @EncodeDefault(EncodeDefault.Mode.ALWAYS) override val v: Int = SiloCastProtocol.version,
    ) : SiloCastMessage()

    @Serializable
    @SerialName("pong")
    data class Pong(
        @EncodeDefault(EncodeDefault.Mode.ALWAYS) override val v: Int = SiloCastProtocol.version,
    ) : SiloCastMessage()

    @Serializable
    @SerialName("close")
    data class Close(
        @EncodeDefault(EncodeDefault.Mode.ALWAYS) override val v: Int = SiloCastProtocol.version,
    ) : SiloCastMessage()

    companion object {
        /** Every `type` this build understands, read from the serializer so it can't drift. */
        val knownTypes: Set<String> by lazy {
            serializer().descriptor.getElementDescriptor(1).elementNames.toSet()
        }

        /**
         * Decodes one frame, or returns null for a `type` this build doesn't
         * know. A newer peer may send kinds added after this build; dropping
         * them keeps the session up where a decode error would tear it down
         * (Apple ignores them the same way). A known type with a malformed
         * payload still throws.
         */
        fun decodeOrNull(json: Json, text: String): SiloCastMessage? {
            val frame = json.parseToJsonElement(text) as? JsonObject
                ?: throw SerializationException("SiloCast frame is not a JSON object")
            val type = (frame["type"] as? JsonPrimitive)?.contentOrNull
            if (type != null && type !in knownTypes) return null
            return json.decodeFromJsonElement(serializer(), frame)
        }
    }
}

@Serializable
enum class SiloCastPeerRole {
    @SerialName("phone")
    Phone,

    @SerialName("tv")
    Tv,
}

/**
 * Session handshake. Both peers send one immediately after connecting; the TV
 * authorizes the controller only when [serverId] matches its own active
 * server — that check (plus same-LAN discovery) is the trust anchor, the
 * fixed TLS-PSK is confidentiality only.
 */
@Serializable
data class SiloCastHello(
    val role: SiloCastPeerRole,
    val deviceName: String,
    val deviceId: String,
    val serverId: String? = null,
    val serverName: String? = null,
    val supportedVersions: List<Int>,
    /**
     * Set by a phone reconnecting or silently resuming, as opposed to a person
     * picking this TV. A receiver refuses such a connection while another
     * phone holds the session, so a background retry never takes the TV from
     * whoever is using it. Optional on the wire: older peers omit and ignore it.
     */
    val resume: Boolean? = null,
)

@Serializable
data class SiloCastPlaybackRequest(
    val contentId: String,
    val fileId: Int? = null,
    val audioTrackIndex: Int? = null,
    val subtitleTrackIndex: Int? = null,
    val startFromBeginning: Boolean,
    val resumePosition: Double? = null,
    val libraryId: Int? = null,
)

@Serializable
data class SiloCastLaunchRequest(
    val serverId: String,
    val playback: SiloCastPlaybackRequest,
)

@Serializable
data class SiloCastHandoffOffer(
    val requestId: String,
    val serverId: String,
    val serverURL: String,
    val serverName: String? = null,
    val profileId: String,
    val profileName: String? = null,
)

@Serializable
data class SiloCastHandoffChallenge(
    val requestId: String,
    val userCode: String,
    val matchCode: String,
    val expiresAt: String,
)

@Serializable
data class SiloCastHandoffReady(
    val requestId: String,
    val serverId: String,
    val profileId: String,
    val sessionExpiresAt: String,
    val reused: Boolean,
)

@Serializable
data class SiloCastHandoffCancel(
    val requestId: String,
    val reason: String,
    val message: String? = null,
)

@Serializable
data class SiloCastTrack(
    val kind: String,
    val trackId: Long,
    val title: String,
    val detail: String? = null,
)

@Serializable
data class SiloCastQualityOption(
    val id: String,
    val label: String,
    val detail: String? = null,
)

@Serializable
data class SiloCastPlaybackState(
    val contentId: String? = null,
    val sessionId: String? = null,
    val title: String,
    val subtitle: String? = null,
    val isPlaying: Boolean,
    val isLoading: Boolean,
    val isBuffering: Boolean,
    val currentTime: Double,
    val duration: Double,
    val audioTracks: List<SiloCastTrack>,
    val subtitleTracks: List<SiloCastTrack>,
    val selectedAudioTrackId: Long? = null,
    val selectedSubtitleTrackId: Long? = null,
    val qualityOptions: List<SiloCastQualityOption>,
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
    val nextEpisodeTitle: String? = null,
    val error: String? = null,
)

/**
 * Command envelope: a [name] from Apple's closed Name enum plus the sparse
 * argument fields. Current Apple builds ignore a name they don't know, but
 * older ones reject the whole frame, so never send a string outside this set.
 */
@Serializable
data class SiloCastControlCommand(
    val name: String,
    val seconds: Double? = null,
    val trackId: Long? = null,
    val speed: Double? = null,
    val volume: Double? = null,
    val value: String? = null,
    val enabled: Boolean? = null,
    val milliseconds: Int? = null,
) {
    companion object {
        const val Play = "play"
        const val Pause = "pause"
        const val PlayPause = "play_pause"
        const val Seek = "seek"
        const val Stop = "stop"
        const val SelectAudioTrack = "select_audio_track"
        const val SelectSubtitleTrack = "select_subtitle_track"
        const val SetPlaybackSpeed = "set_playback_speed"
        const val SetQuality = "set_quality"
        const val SetVideoGravity = "set_video_gravity"
        const val SetSubtitleSyncMs = "set_subtitle_sync_ms"
        const val SetSubtitlePosition = "set_subtitle_position"
        const val SetVolume = "set_volume"
        const val SetMuted = "set_muted"
        const val PlayNext = "play_next"

        /** Every command name this build implements. */
        val knownNames: Set<String> = setOf(
            Play, Pause, PlayPause, Seek, Stop, SelectAudioTrack, SelectSubtitleTrack, SetPlaybackSpeed,
            SetQuality, SetVideoGravity, SetSubtitleSyncMs, SetSubtitlePosition, SetVolume, SetMuted, PlayNext,
        )

        fun play(): SiloCastControlCommand = SiloCastControlCommand(name = Play)

        fun pause(): SiloCastControlCommand = SiloCastControlCommand(name = Pause)

        fun playPause(): SiloCastControlCommand = SiloCastControlCommand(name = PlayPause)

        fun stop(): SiloCastControlCommand = SiloCastControlCommand(name = Stop)

        fun seek(seconds: Double): SiloCastControlCommand =
            SiloCastControlCommand(name = Seek, seconds = seconds)

        fun selectAudioTrack(trackId: Long): SiloCastControlCommand =
            SiloCastControlCommand(name = SelectAudioTrack, trackId = trackId)

        fun selectSubtitleTrack(trackId: Long?): SiloCastControlCommand =
            SiloCastControlCommand(name = SelectSubtitleTrack, trackId = trackId)

        fun setPlaybackSpeed(speed: Double): SiloCastControlCommand =
            SiloCastControlCommand(name = SetPlaybackSpeed, speed = speed)

        fun setQuality(qualityId: String): SiloCastControlCommand =
            SiloCastControlCommand(name = SetQuality, value = qualityId)

        fun setVideoGravity(value: String): SiloCastControlCommand =
            SiloCastControlCommand(name = SetVideoGravity, value = value)

        fun setSubtitleSyncMs(milliseconds: Int): SiloCastControlCommand =
            SiloCastControlCommand(name = SetSubtitleSyncMs, milliseconds = milliseconds)

        fun setSubtitlePosition(value: String): SiloCastControlCommand =
            SiloCastControlCommand(name = SetSubtitlePosition, value = value)

        fun setVolume(volume: Double): SiloCastControlCommand =
            SiloCastControlCommand(name = SetVolume, volume = volume)

        fun setMuted(muted: Boolean): SiloCastControlCommand =
            SiloCastControlCommand(name = SetMuted, enabled = muted)

        fun playNext(): SiloCastControlCommand = SiloCastControlCommand(name = PlayNext)
    }
}

@Serializable
data class SiloCastError(
    val code: String,
    val message: String,
)
