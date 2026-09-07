package org.siloserver.silo.tv.ui.screens.player

import org.siloserver.silo.common.player.MountedSubtitleTrack
import org.siloserver.silo.common.player.resolveMountedSubtitle
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
