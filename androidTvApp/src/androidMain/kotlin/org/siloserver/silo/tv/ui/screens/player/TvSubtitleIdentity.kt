package org.siloserver.silo.tv.ui.screens.player

import org.siloserver.silo.common.player.MountedSubtitleTrack
import org.siloserver.silo.common.player.resolveMountedSubtitle
import org.siloserver.silo.model.playback.PlaybackExecutionPlan
import org.siloserver.silo.model.playback.PlayerSubtitleInfo
import org.siloserver.silo.model.playback.SubtitleIdentity
import org.siloserver.silo.model.playback.SubtitleMediaIdentity
import org.siloserver.silo.playback.canonicalSubtitleLanguage
import org.siloserver.silo.playback.canonicalSubtitleCodecFamily
import org.siloserver.silo.playback.playbackSubtitleIdentity

internal fun tvSubtitleIdentity(subtitle: PlayerSubtitleInfo): SubtitleIdentity =
    playbackSubtitleIdentity(subtitle)

/**
 * Maps a mounted Media3 text track onto the SAME typed identity the HUD options
 * carry, so an app-derived selection and a viewer's pick are indistinguishable
 * to the transaction adapter. A track the server also describes resolves to its
 * server row identity; anything the player discovered on its own (in-stream
 * CEA-608, a sidecar the plan does not list) stays [SubtitleIdentity.LocalMedia3].
 */
internal fun tvMountedSubtitleIdentity(
    track: PlayerTrackEntry,
    subtitleTracks: List<PlayerTrackEntry>,
    subtitleRows: List<PlayerSubtitleInfo>,
): SubtitleIdentity =
    resolveMountedSubtitleRow(track, subtitleTracks, subtitleRows)
        ?.let(::tvSubtitleIdentity)
        ?: tvSubtitleIdentity(track)

/**
 * Server-native decisions resolve by their exact container track ID; sidecars
 * resolve by their authored artifact ID. Only local/catalog rows without a v3
 * delivery can use the metadata fallback below.
 */
internal fun tvResolveMountedSubtitleTrack(
    identity: SubtitleIdentity,
    subtitleRows: List<PlayerSubtitleInfo>,
    mounted: List<MountedSubtitleTrack>,
): MountedSubtitleTrack? {
    resolveMountedSubtitle(identity = identity, tracks = mounted)?.let { return it.track }
    val row = identity.tvInventoryRow(subtitleRows) ?: return null
    if (row.serverDelivery != null) return null
    return resolveMountedSubtitle(subtitle = row, tracks = mounted)?.track
}

/**
 * Whether the player can select [identity] without the server: the mounted
 * media lists its exact track. Server-native and sidecar decisions require
 * exact mounted IDs. A switch on the playing stream leaves the previous sidecar
 * in the MediaItem, so a server sidecar counts only while [plan] selects it;
 * another one takes the replan, or the server would keep playing a different
 * track than the one shown.
 */
internal fun isTvLocallyMountable(
    identity: SubtitleIdentity,
    plan: PlaybackExecutionPlan?,
    subtitleRows: List<PlayerSubtitleInfo>,
    mounted: List<MountedSubtitleTrack>,
): Boolean {
    if (identity is SubtitleIdentity.ServerSidecar && plan != null &&
        plan.selectedTracks.subtitleIndex != identity.serverIndex
    ) {
        return false
    }
    return tvResolveMountedSubtitleTrack(identity, subtitleRows, mounted) != null
}

/** The inventory row this identity was minted from, if it is exactly that row's. */
private fun SubtitleIdentity.tvInventoryRow(
    rows: List<PlayerSubtitleInfo>,
): PlayerSubtitleInfo? {
    // Off and burn-in are never a mounted text track, and downloaded or
    // player-discovered identities already carry their own exact Media3 id.
    val serverIndex = when (this) {
        is SubtitleIdentity.Embedded -> serverIndex
        is SubtitleIdentity.ServerSidecar -> serverIndex
        else -> return null
    }
    return rows.firstOrNull { it.index == serverIndex && tvSubtitleIdentity(it) == this }
}

internal fun tvSubtitleIdentity(track: PlayerTrackEntry): SubtitleIdentity =
    SubtitleIdentity.LocalMedia3(
        SubtitleMediaIdentity(
            trackId = track.trackId,
            label = track.displayLabel.ifBlank { track.label },
            language = canonicalSubtitleLanguage(track.language),
            codecFamily = canonicalSubtitleCodecFamily(track.codecOrMime),
            forced = track.isForced,
            hearingImpaired = track.isHearingImpaired,
        ),
    )

/** What a same-route reopen of the transport arms the subtitle reselection with. */
internal sealed interface TvReopenSubtitleRestore {
    /** The adapter is still mounting [identity] under its owner [generation]: keep that owner. */
    data class AdapterMount(val identity: SubtitleIdentity, val generation: Long) : TvReopenSubtitleRestore

    /** Nothing in flight: restore what the player shows, as a server index (-1 for Off). */
    data class PlayerSelection(val serverIndex: Int?) : TvReopenSubtitleRestore
}

/**
 * A reopen replaces the MediaItem, and a stalled transport may show no
 * subtitle at all while a pick the adapter is mounting has not been
 * confirmed. Restoring what the player shows would then arm Off over that
 * pick, at the same authority, and the pick would fail its mount deadline.
 */
internal fun tvReopenSubtitleRestore(
    adapterMountIdentity: SubtitleIdentity?,
    adapterMountGeneration: Long?,
    playerSelectedServerIndex: Int?,
): TvReopenSubtitleRestore =
    if (adapterMountIdentity != null && adapterMountGeneration != null) {
        TvReopenSubtitleRestore.AdapterMount(adapterMountIdentity, adapterMountGeneration)
    } else {
        TvReopenSubtitleRestore.PlayerSelection(playerSelectedServerIndex)
    }
