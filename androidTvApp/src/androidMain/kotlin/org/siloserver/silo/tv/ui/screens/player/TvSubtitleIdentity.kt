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
 * Resolves a typed identity onto the Media3 text track that ALREADY carries it,
 * or null when the player exposes no such track.
 *
 * [resolveMountedSubtitle] on its own is not enough for a SERVER-ROW identity.
 * Protocol v3 types every non-burn-in inventory row `delivery = sidecar`,
 * including a row that merely DESCRIBES a track muxed into a direct-play
 * stream — so an embedded PGS track plainly mounted by Media3 maps to
 * [SubtitleIdentity.ServerSidecar], and a sidecar identity is matched by its
 * authored `silo-subtitle:N` id alone, which a muxed track can never carry.
 * The answer came back "not mounted" for the very track on screen, and the
 * selection was routed to a server replan that re-extracted the same subtitle
 * as a sidecar: new session, media-item swap, rebuffer, restore seek.
 *
 * The inventory row is the missing evidence: matching through it is the same
 * mapping [tvMountedSubtitleIdentity] used to mint the identity in the first
 * place, so the two directions can no longer disagree. Only an identity that is
 * exactly some row's identity gets that fallback, and the row match still has
 * to find a mounted track — a catalog-only row, a sidecar the player has not
 * loaded and a burn-in row all still answer null and go on replanning.
 */
internal fun tvResolveMountedSubtitleTrack(
    identity: SubtitleIdentity,
    subtitleRows: List<PlayerSubtitleInfo>,
    mounted: List<MountedSubtitleTrack>,
): MountedSubtitleTrack? {
    resolveMountedSubtitle(identity = identity, tracks = mounted)?.let { return it.track }
    val row = identity.tvInventoryRow(subtitleRows) ?: return null
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
