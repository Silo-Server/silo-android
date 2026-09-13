package org.siloserver.silo.model.watchtogether

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * Watch Together shared wire models. Mirrors silo-server `origin/main`
 * `internal/watchtogether/types.go` (Snapshot, Suggestion, TransportCommand)
 * and `internal/api/handlers/watch_together.go` (request/response wrappers,
 * WS client/server frames).
 *
 * Every enum is parsed leniently with an [Unknown] fallback so a future server
 * value never breaks decoding (matching the notifications-type convention).
 */

// ---- Enums (lenient, string-backed) ---------------------------------------

@Serializable(with = RoomPhaseSerializer::class)
enum class RoomPhase(val wire: String) {
    Lobby("lobby"),
    Playing("playing"),
    Ended("ended"),
    Unknown("");

    companion object {
        fun fromWire(w: String): RoomPhase = entries.firstOrNull { it.wire == w } ?: Unknown
    }
}

@Serializable(with = RoomPlaybackStateSerializer::class)
enum class RoomPlaybackState(val wire: String) {
    Idle("idle"),
    Waiting("waiting"),
    Paused("paused"),
    Playing("playing"),
    Unknown("");

    companion object {
        fun fromWire(w: String): RoomPlaybackState = entries.firstOrNull { it.wire == w } ?: Unknown
    }
}

@Serializable(with = RoomSelectionModeSerializer::class)
enum class RoomSelectionMode(val wire: String) {
    HostPick("host_pick"),
    Vote("vote"),
    Unknown("");

    companion object {
        fun fromWire(w: String): RoomSelectionMode = entries.firstOrNull { it.wire == w } ?: Unknown
    }
}

@Serializable(with = GuestControlPolicySerializer::class)
enum class GuestControlPolicy(val wire: String) {
    HostOnly("host_only"),
    GuestPlayPause("guest_play_pause"),
    Unknown("");

    companion object {
        fun fromWire(w: String): GuestControlPolicy = entries.firstOrNull { it.wire == w } ?: Unknown
    }
}

@Serializable(with = MemberRoleSerializer::class)
enum class MemberRole(val wire: String) {
    Host("host"),
    Guest("guest"),
    Unknown("");

    companion object {
        fun fromWire(w: String): MemberRole = entries.firstOrNull { it.wire == w } ?: Unknown
    }
}

@Serializable(with = TransportActionSerializer::class)
enum class TransportAction(val wire: String) {
    Play("play"),
    Pause("pause"),
    Seek("seek"),
    Unknown("");

    companion object {
        fun fromWire(w: String): TransportAction = entries.firstOrNull { it.wire == w } ?: Unknown
    }
}

private class EnumWireSerializer<T : Enum<T>>(
    name: String,
    private val toWire: (T) -> String,
    private val fromWire: (String) -> T,
) : KSerializer<T> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor(name, PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: T) = encoder.encodeString(toWire(value))
    override fun deserialize(decoder: Decoder): T = fromWire(decoder.decodeString())
}

object RoomPhaseSerializer : KSerializer<RoomPhase> by EnumWireSerializer(
    "RoomPhase", { it.wire }, RoomPhase::fromWire,
)
object RoomPlaybackStateSerializer : KSerializer<RoomPlaybackState> by EnumWireSerializer(
    "RoomPlaybackState", { it.wire }, RoomPlaybackState::fromWire,
)
object RoomSelectionModeSerializer : KSerializer<RoomSelectionMode> by EnumWireSerializer(
    "RoomSelectionMode", { it.wire }, RoomSelectionMode::fromWire,
)
object GuestControlPolicySerializer : KSerializer<GuestControlPolicy> by EnumWireSerializer(
    "GuestControlPolicy", { it.wire }, GuestControlPolicy::fromWire,
)
object MemberRoleSerializer : KSerializer<MemberRole> by EnumWireSerializer(
    "MemberRole", { it.wire }, MemberRole::fromWire,
)
object TransportActionSerializer : KSerializer<TransportAction> by EnumWireSerializer(
    "TransportAction", { it.wire }, TransportAction::fromWire,
)

// ---- Core payloads --------------------------------------------------------

/**
 * Universal room payload (server `Snapshot`). Several fields are per-recipient
 * (`self_*`, `invite_path` is host-only). `selection_revision` and
 * `generation` are Longs; `anchor_position_seconds` is a Double; timestamps are
 * RFC3339 strings.
 */
@Serializable
data class RoomSnapshot(
    @SerialName("room_id") val roomId: String,
    val phase: RoomPhase = RoomPhase.Unknown,
    @SerialName("playback_state") val playbackState: RoomPlaybackState = RoomPlaybackState.Unknown,
    @SerialName("selection_mode") val selectionMode: RoomSelectionMode = RoomSelectionMode.Unknown,
    @SerialName("selection_revision") val selectionRevision: Long = 0L,
    @SerialName("selected_content_id") val selectedContentId: String? = null,
    @SerialName("selected_file_id") val selectedFileId: Int? = null,
    @SerialName("selected_library_id") val selectedLibraryId: Int? = null,
    val code: String = "",
    @SerialName("guest_control_policy") val guestControlPolicy: GuestControlPolicy = GuestControlPolicy.HostOnly,
    @SerialName("is_paused") val isPaused: Boolean = false,
    @SerialName("anchor_position_seconds") val anchorPositionSeconds: Double = 0.0,
    @SerialName("anchor_updated_at") val anchorUpdatedAt: String = "",
    val generation: Long = 0L,
    @SerialName("member_count") val memberCount: Int = 0,
    @SerialName("host_connected") val hostConnected: Boolean = false,
    @SerialName("self_role") val selfRole: MemberRole = MemberRole.Unknown,
    @SerialName("self_can_control_transport") val selfCanControlTransport: Boolean = false,
    @SerialName("self_can_manage_room") val selfCanManageRoom: Boolean = false,
    @SerialName("self_ignore_wait") val selfIgnoreWait: Boolean = false,
    @SerialName("attached_session_id") val attachedSessionId: String? = null,
    @SerialName("invite_path") val invitePath: String? = null,
)

/** A content suggestion (vote-mode room). In WS broadcasts [votedByMe] is forced false. */
@Serializable
data class Suggestion(
    val id: String,
    @SerialName("room_id") val roomId: String,
    @SerialName("suggester_user_id") val suggesterUserId: String = "",
    @SerialName("suggester_profile_id") val suggesterProfileId: String = "",
    @SerialName("content_id") val contentId: String,
    @SerialName("content_type") val contentType: String,
    val title: String,
    val subtitle: String = "",
    @SerialName("poster_url") val posterUrl: String = "",
    val note: String = "",
    @SerialName("vote_count") val voteCount: Int = 0,
    @SerialName("voted_by_me") val votedByMe: Boolean = false,
    @SerialName("created_at") val createdAt: String,
)

/**
 * Discrete transport directive broadcast on the room WS. [executeAt]/[issuedAt]
 * are RFC3339Nano strings (server clock); the RoomSyncEngine converts [executeAt]
 * to a local schedule via the offset.
 */
@Serializable
data class TransportCommand(
    @SerialName("command_id") val commandId: String,
    @SerialName("session_id") val sessionId: String = "",
    @SerialName("selection_revision") val selectionRevision: Long = 0L,
    val action: TransportAction = TransportAction.Unknown,
    @SerialName("position_seconds") val positionSeconds: Double = 0.0,
    @SerialName("execute_at") val executeAt: String,
    @SerialName("issued_at") val issuedAt: String = "",
    @SerialName("playback_state") val playbackState: RoomPlaybackState = RoomPlaybackState.Unknown,
)

// ---- REST request models --------------------------------------------------

/** POST /rooms. */
@Serializable
data class CreateRoomRequest(
    @SerialName("selection_mode") val selectionMode: String? = null,
)

/** POST /join — one of [code]/[joinToken] required; token wins. */
@Serializable
data class JoinRoomRequest(
    val code: String? = null,
    @SerialName("join_token") val joinToken: String? = null,
)

/** PUT /rooms/{id}/selection (host-only). */
@Serializable
data class SetSelectionRequest(
    @SerialName("content_id") val contentId: String,
    @SerialName("file_id") val fileId: Int? = null,
    @SerialName("library_id") val libraryId: Int? = null,
)

/** PATCH /rooms/{id}/policy (host-only). */
@Serializable
data class UpdatePolicyRequest(
    @SerialName("guest_control_policy") val guestControlPolicy: String,
)

/** POST /rooms/{id}/suggestions. */
@Serializable
data class AddSuggestionRequest(
    @SerialName("content_id") val contentId: String,
    @SerialName("content_type") val contentType: String,
    val title: String,
    val subtitle: String? = null,
    @SerialName("poster_url") val posterUrl: String? = null,
    val note: String? = null,
)

/** POST /rooms/{id}/suggestions/promote (host-only). */
@Serializable
data class PromoteSuggestionRequest(
    @SerialName("suggestion_id") val suggestionId: String,
)

// ---- REST response wrappers -----------------------------------------------

/** `{room, room_access_token}` — create/join/selection/policy/promote/get. */
@Serializable
data class RoomResponse(
    val room: RoomSnapshot,
    @SerialName("room_access_token") val roomAccessToken: String = "",
)

/** `{suggestions:[…]}` — all suggestion list/mutation responses. */
@Serializable
data class SuggestionsResponse(
    @SerialName("items") val suggestions: List<Suggestion> = emptyList(),
)

// ---- Client→server WS frames ----------------------------------------------

@Serializable
data class WsAttachSession(
    val type: String = "attach_session",
    @SerialName("session_id") val sessionId: String,
)

@Serializable
data class WsTransportRequest(
    val type: String = "transport_request",
    val action: String,
    @SerialName("position_seconds") val positionSeconds: Double? = null,
    @SerialName("is_paused") val isPaused: Boolean,
)

@Serializable
data class WsStateReport(
    val type: String = "state_report",
    @SerialName("session_id") val sessionId: String,
    @SerialName("position_seconds") val positionSeconds: Double,
    @SerialName("is_paused") val isPaused: Boolean,
)

@Serializable
data class WsReady(
    val type: String = "ready",
    @SerialName("session_id") val sessionId: String,
    @SerialName("position_seconds") val positionSeconds: Double,
    @SerialName("is_paused") val isPaused: Boolean,
)

@Serializable
data class WsBuffering(
    val type: String = "buffering",
    @SerialName("session_id") val sessionId: String,
    @SerialName("position_seconds") val positionSeconds: Double,
    @SerialName("is_paused") val isPaused: Boolean,
)

@Serializable
data class WsPing(
    val type: String = "ping",
    @SerialName("client_sent_at") val clientSentAt: String,
)

// ---- Server→client WS frame payloads --------------------------------------

@Serializable
data class WsPong(
    val type: String = "pong",
    @SerialName("client_sent_at") val clientSentAt: String = "",
    @SerialName("server_received_at") val serverReceivedAt: String = "",
    @SerialName("server_sent_at") val serverSentAt: String = "",
)

@Serializable
data class WsRoomClosed(
    val type: String = "room_closed",
    val reason: String = "",
)

@Serializable
data class WsError(
    val type: String = "error",
    val code: String = "",
    val message: String = "",
)
