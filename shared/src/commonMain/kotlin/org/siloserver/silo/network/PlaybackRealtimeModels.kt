package org.siloserver.silo.network

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/** Command names the server may send (mirror realtime-protocol.ts ALL_PLAYBACK_COMMANDS). */
object PlaybackCommandNames {
    const val Pause = "pause"
    const val Unpause = "unpause"
    const val PlayPause = "play_pause"
    const val Seek = "seek"
    const val SetVolume = "set_volume"
    const val Stop = "stop"
    const val Terminate = "terminate"
    const val DisplayMessage = "display_message"
    const val ServerRestarting = "server_restarting"
    const val ServerShuttingDown = "server_shutting_down"
    const val PlayMedia = "play_media"
    const val SetAudioTrack = "set_audio_track"
    const val SetSubtitleTrack = "set_subtitle_track"

    /**
     * What this client advertises in hello + actually handles. Only advertise
     * commands we act on — `set_volume`/`play_media` are NOT advertised (the
     * dispatcher rejects them) so the server won't issue a command we'd silently
     * no-op. Track commands ARE advertised: they re-select the audio/subtitle
     * track by index (per-client, so they're not gated by Watch Together).
     */
    val Supported = listOf(
        Pause, Unpause, PlayPause, Seek, Stop, Terminate,
        DisplayMessage, ServerRestarting, ServerShuttingDown,
        SetAudioTrack, SetSubtitleTrack,
    )
}

@Serializable
data class PlaybackHelloEnvelope(
    val type: String = "hello",
    @SerialName("session_id") val sessionId: String,
    val client: HelloClient,
    val capabilities: HelloCapabilities,
)

@Serializable
data class HelloClient(val name: String = "silo-android", val version: String = "1")

@Serializable
data class HelloCapabilities(val commands: List<String>)

@Serializable
data class PlaybackAckEnvelope(
    val type: String = "ack",
    @SerialName("command_id") val commandId: String,
    @SerialName("session_id") val sessionId: String,
    val status: String = "accepted",
)

@Serializable
data class PlaybackResultEnvelope(
    val type: String = "result",
    @SerialName("command_id") val commandId: String,
    @SerialName("session_id") val sessionId: String,
    val status: String, // "completed" | "rejected"
    val error: String? = null,
)

/** Parsed inbound message surfaced to the controller. */
sealed interface PlaybackRealtimeEvent {
    /** Emitted once the socket is open (R2) — the controller sends hello on this. */
    data object Opened : PlaybackRealtimeEvent

    data class Command(
        val commandId: String,
        val sessionId: String,
        val name: String,
        val payload: JsonObject,
        /** `issued_by.kind`: "admin" for an administrator's command, "server" for the server's own. */
        val issuedBy: String? = null,
    ) : PlaybackRealtimeEvent

    data class ServerEvent(
        val sessionId: String,
        val name: String,
        val payload: JsonObject,
    ) : PlaybackRealtimeEvent

    data class Closed(val reason: String? = null) : PlaybackRealtimeEvent
}
