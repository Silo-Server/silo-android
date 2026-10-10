package org.siloserver.silo.model.watchtogether

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.siloserver.silo.model.catalog.BrowseItem

/**
 * `GET /api/v2/watch-together/capabilities`. Each flag reflects an operation
 * the server actually wires. [socketProtocol] is `silo.room.v2` when the v2
 * room socket is served and empty otherwise; `lobby_ready` and
 * `connection_replaced` ride on that socket.
 */
@Serializable
data class WatchTogetherCapabilitiesV2(
    val state: String = "",
    val allowed: Boolean? = null,
    @SerialName("staged_selection") val stagedSelection: Boolean = false,
    @SerialName("lobby_ready") val lobbyReady: Boolean = false,
    @SerialName("connection_replaced") val connectionReplaced: Boolean = false,
    @SerialName("selection_mode_switch") val selectionModeSwitch: Boolean = false,
    @SerialName("member_state") val memberState: Boolean = false,
    val picker: Boolean = false,
    @SerialName("vote_host_override") val voteHostOverride: Boolean = false,
    @SerialName("stop_playback") val stopPlayback: Boolean = false,
    @SerialName("max_member_state_ids") val maxMemberStateIds: Int = 0,
    @SerialName("socket_protocol") val socketProtocol: String = "",
)

/** One member's watch state for one content id (`POST .../member-state`). */
@Serializable
data class MemberWatchState(
    @SerialName("user_id") val userId: String = "",
    @SerialName("profile_id") val profileId: String = "",
    val state: String = "",
    @SerialName("position_seconds") val positionSeconds: Double? = null,
    @SerialName("duration_seconds") val durationSeconds: Double? = null,
    @SerialName("on_watchlist") val onWatchlist: Boolean = false,
)

@Serializable
data class ItemMemberState(
    @SerialName("content_id") val contentId: String,
    val members: List<MemberWatchState> = emptyList(),
)

/**
 * Member watch state for the requested ids that the caller may see, in
 * request order. Inaccessible and missing ids are omitted; an omitted id is
 * unavailable, not unplayed.
 */
@Serializable
data class MemberStateResponse(
    val members: List<RoomMember> = emptyList(),
    val items: List<ItemMemberState> = emptyList(),
)

@Serializable
data class PickerMember(
    @SerialName("user_id") val userId: String = "",
    @SerialName("profile_id") val profileId: String = "",
    @SerialName("display_name") val displayName: String = "",
    @SerialName("position_seconds") val positionSeconds: Double? = null,
    @SerialName("duration_seconds") val durationSeconds: Double? = null,
)

/** For a series row: the episode most members would play next. */
@Serializable
data class PickerNextUp(
    @SerialName("content_id") val contentId: String,
    @SerialName("season_number") val seasonNumber: Int = 0,
    @SerialName("episode_number") val episodeNumber: Int = 0,
    val title: String = "",
    @SerialName("member_count") val memberCount: Int = 0,
)

@Serializable
data class PickerEntry(
    val item: BrowseItem,
    val members: List<PickerMember> = emptyList(),
    @SerialName("next_up") val nextUp: PickerNextUp? = null,
)

/**
 * `GET .../picker`: shared rows resolved through the caller's catalog access.
 * Continue Together lists only items two or more connected members are part
 * way through, so it is empty in a party of one.
 */
@Serializable
data class PickerResponse(
    val members: List<RoomMember> = emptyList(),
    @SerialName("continue_together") val continueTogether: List<PickerEntry> = emptyList(),
    @SerialName("watchlist_union") val watchlistUnion: List<PickerEntry> = emptyList(),
)
