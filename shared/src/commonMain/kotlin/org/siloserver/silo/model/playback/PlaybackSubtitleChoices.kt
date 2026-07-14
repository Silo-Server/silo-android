package org.siloserver.silo.model.playback

import org.siloserver.silo.model.catalog.SubtitleTrack

/**
 * Combines catalog subtitle alternatives with artifacts selected by the playback plan.
 *
 * Catalog-only rows deliberately have no URL. They remain available as selection metadata,
 * while choosing one asks playback V3 to materialize the corresponding artifact. Giving every
 * catalog row a speculative stream URL makes Media3 prepare every sidecar eagerly; one missing
 * artifact can then prevent the primary media source from becoming ready.
 */
fun buildPlaybackSubtitleChoices(
    catalogTracks: List<SubtitleTrack>,
    plannedTracks: List<PlayerSubtitleInfo>,
): List<PlayerSubtitleInfo> {
    if (catalogTracks.isEmpty()) return plannedTracks

    val plannedByIndex = plannedTracks.associateBy(PlayerSubtitleInfo::index)
    val catalogChoices = catalogTracks.map { track ->
        plannedByIndex[track.index]?.let { planned ->
            planned.copy(
                language = planned.language ?: track.language,
                codec = planned.codec ?: track.codec,
                label = planned.label ?: track.title,
                source = planned.source ?: if (track.external) "external" else "embedded",
                forced = planned.forced ?: track.forced,
            )
        } ?: PlayerSubtitleInfo(
            index = track.index,
            language = track.language,
            codec = track.codec,
            label = track.title,
            source = if (track.external) "external" else "embedded",
            forced = track.forced,
            url = "",
        )
    }
    val catalogIndices = catalogTracks.mapTo(mutableSetOf(), SubtitleTrack::index)
    return catalogChoices + plannedTracks.filterNot { it.index in catalogIndices }
}
