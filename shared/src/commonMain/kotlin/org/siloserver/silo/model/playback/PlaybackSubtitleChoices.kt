package org.siloserver.silo.model.playback

import org.siloserver.silo.model.catalog.SubtitleTrack

/** Combines catalog subtitle alternatives with any artifact selected by the playback plan. */
fun buildPlaybackSubtitleChoices(
    catalogTracks: List<SubtitleTrack>,
    plannedTracks: List<PlayerSubtitleInfo>,
    sessionId: String,
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
            url = playbackSubtitleTrackUrl(sessionId, track),
        )
    }
    val catalogIndices = catalogTracks.mapTo(mutableSetOf(), SubtitleTrack::index)
    return catalogChoices + plannedTracks.filterNot { it.index in catalogIndices }
}

private fun playbackSubtitleTrackUrl(sessionId: String, track: SubtitleTrack): String {
    val format = track.codec?.trim()?.lowercase()
    val isBitmap = format in setOf(
        "pgs",
        "hdmv_pgs_subtitle",
        "dvd_subtitle",
        "dvdsub",
        "dvb_subtitle",
    )
    if (isBitmap && !track.external) return ""
    val extension = when (format) {
        "ass", "ssa" -> ".ass"
        "pgs", "hdmv_pgs_subtitle" -> ".sup"
        "ttml" -> ".ttml"
        else -> ".vtt"
    }
    return "/stream/$sessionId/subtitles/${track.index}$extension"
}
