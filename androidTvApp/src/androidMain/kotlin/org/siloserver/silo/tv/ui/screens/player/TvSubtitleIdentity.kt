package org.siloserver.silo.tv.ui.screens.player

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
