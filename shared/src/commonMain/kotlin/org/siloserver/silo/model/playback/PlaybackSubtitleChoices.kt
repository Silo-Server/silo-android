package org.siloserver.silo.model.playback

import org.siloserver.silo.model.catalog.SubtitleTrack

/**
 * Combines catalog subtitle alternatives with artifacts selected by the playback plan.
 *
 * Output rows live in the server's COMBINED index space — the identity that
 * session `subtitle_urls` carry and that `subtitle_track_index` replans
 * resolve: externals occupy 0..n-1 in catalog order, embedded tracks follow
 * at n+ordinal (the server counts burn-in-only tracks it omits from
 * `subtitle_urls`, and the catalog lists all of them, so the ordinals line
 * up). Catalog `index` values are deliberately ignored for identity: embedded
 * entries carry ffprobe stream indexes (a different space) and older servers
 * serialize every external as index 0, which used to produce duplicate rows —
 * the picker keys rows by index, so duplicates crashed it and made selection
 * ambiguous.
 *
 * Catalog-only rows deliberately have no URL. They remain available as selection metadata,
 * while choosing one asks playback V3 to materialize the corresponding artifact. Giving every
 * catalog row a speculative stream URL makes Media3 prepare every sidecar eagerly; one missing
 * artifact can then prevent the primary media source from becoming ready.
 */
/**
 * Maps each catalog track (by list position) to its COMBINED selection index:
 * externals count up from 0 in catalog order, embedded tracks follow after
 * every external. This is the identity `subtitle_track_index` requests and
 * session `subtitle_urls` resolve against — catalog `index` values are a
 * different space and must never be sent as a selection.
 */
fun combinedSubtitleSelectionIndexes(catalogTracks: List<SubtitleTrack>): List<Int> {
    val externalCount = catalogTracks.count(SubtitleTrack::external)
    var externalsSeen = 0
    var embeddedSeen = 0
    return catalogTracks.map { track ->
        if (track.external) externalsSeen++ else externalCount + embeddedSeen++
    }
}

fun buildPlaybackSubtitleChoices(
    catalogTracks: List<SubtitleTrack>,
    plannedTracks: List<PlayerSubtitleInfo>,
): List<PlayerSubtitleInfo> {
    // The picker keys rows by index — never publish duplicates, whatever the
    // server sent.
    if (catalogTracks.isEmpty()) return plannedTracks.distinctBy(PlayerSubtitleInfo::index)

    val plannedByIndex = plannedTracks.associateBy(PlayerSubtitleInfo::index)
    val externals = catalogTracks.filter(SubtitleTrack::external)
    val embedded = catalogTracks.filterNot(SubtitleTrack::external)

    fun choiceAt(combinedIndex: Int, track: SubtitleTrack): PlayerSubtitleInfo {
        val planned = plannedByIndex[combinedIndex] ?: return PlayerSubtitleInfo(
            index = combinedIndex,
            language = track.language,
            codec = track.codec,
            label = track.title,
            source = if (track.external) "external" else "embedded",
            forced = track.forced,
            url = "",
            catalogLabel = track.title,
            catalogSource = if (track.external) "external" else "embedded",
            isDefault = track.isDefault,
        )
        return planned.copy(
            language = planned.language ?: track.language,
            codec = planned.codec ?: track.codec,
            // Keep the runtime label intact: Media3 uses it to match the
            // mounted sidecar. Catalog identity is display metadata and must
            // survive independently of a generic label such as
            // "Server subtitle".
            label = planned.label ?: track.title,
            source = planned.source ?: if (track.external) "external" else "embedded",
            forced = planned.forced ?: track.forced,
            catalogLabel = track.title,
            catalogSource = if (track.external) "external" else "embedded",
            isDefault = track.isDefault,
        )
    }

    val catalogChoices = buildList {
        externals.forEachIndexed { i, track -> add(choiceAt(i, track)) }
        embedded.forEachIndexed { i, track -> add(choiceAt(externals.size + i, track)) }
    }
    val covered = catalogChoices.mapTo(mutableSetOf(), PlayerSubtitleInfo::index)
    return (catalogChoices + plannedTracks.filterNot { it.index in covered })
        .distinctBy(PlayerSubtitleInfo::index)
}

/**
 * Enriches a protocol-v3 subtitle inventory without changing its membership.
 *
 * Unlike [buildPlaybackSubtitleChoices], this function never synthesizes a row
 * from catalog metadata: `PlaybackPlanV3.subtitle.inventory` is complete and
 * authoritative, including when it is empty. Catalog data may only fill display
 * metadata on the exact combined ordinal the server already published.
 */
fun enrichAuthoritativePlaybackSubtitleChoices(
    catalogTracks: List<SubtitleTrack>,
    plannedTracks: List<PlayerSubtitleInfo>,
): List<PlayerSubtitleInfo> {
    if (plannedTracks.isEmpty()) return emptyList()
    val catalogByCombinedIndex = combinedSubtitleSelectionIndexes(catalogTracks)
        .zip(catalogTracks)
        .toMap()
    return plannedTracks
        .distinctBy(PlayerSubtitleInfo::index)
        .map { planned ->
            val catalog = catalogByCombinedIndex[planned.index] ?: return@map planned
            planned.copy(
                language = planned.language ?: catalog.language,
                codec = planned.codec ?: catalog.codec,
                label = planned.label ?: catalog.title,
                source = planned.source ?: if (catalog.external) "external" else "embedded",
                forced = planned.forced ?: catalog.forced,
                catalogLabel = catalog.title,
                catalogSource = if (catalog.external) "external" else "embedded",
                isDefault = catalog.isDefault,
            )
        }
}

private val DOWNLOADED_SUBTITLE_SESSION_PATH =
    Regex("""(/stream/)([^/]+)(/subtitles/[0-9]+\.[^/]+)$""")

/** Retargets a downloaded subtitle artifact to a replacement playback session. */
fun rebaseDownloadedSubtitleUrl(url: String, targetSessionId: String): String {
    if (
        targetSessionId.isEmpty() ||
        targetSessionId.any { it == '/' || it == '?' || it == '#' }
    ) {
        return url
    }
    val pathEnd = listOf(url.indexOf('?'), url.indexOf('#'))
        .filter { it >= 0 }
        .minOrNull()
        ?: url.length
    val resource = url.substring(0, pathEnd)
    val suffix = url.substring(pathEnd)
    val match = DOWNLOADED_SUBTITLE_SESSION_PATH.find(resource) ?: return url
    val sessionRange = match.groups[2]?.range ?: return url
    return resource.replaceRange(sessionRange, targetSessionId) + suffix
}
