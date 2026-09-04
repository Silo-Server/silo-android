// shared/src/commonMain/kotlin/org/siloserver/silo/model/playback/SubtitleTrackMerge.kt
package org.siloserver.silo.model.playback

import org.siloserver.silo.model.subtitles.DownloadedSubtitle

/** `source` value the server (and web client) use for provider-downloaded tracks. */
const val SUBTITLE_SOURCE_DOWNLOADED = "downloaded"

/**
 * Merges server-stored downloaded subtitles into a playback session's track
 * list without restarting the session. Pure; shared by the mobile and TV
 * players' `refreshSubtitles(autoSelectSubtitleId)`.
 *
 * Mirrors the web reference exactly (web/src/player/hooks/usePlaybackSession.ts
 * `refreshSubtitles`, lines ~473-514):
 *  - strips every previously merged `source == "downloaded"` track and
 *    rebuilds them all from the fresh GET /subtitles/{media_file_id} list
 *    (PlayerSubtitleInfo carries no downloaded-subtitle id, and the web
 *    dedupes by source replacement, not identity)
 *  - new indices continue from `max(existing.index) + 1` — not the list size,
 *    since server-side burn-in skipping can leave index gaps — starting at 0
 *    when there are no remaining tracks
 *  - label is `"${release_name} (${provider})"`, codec is the stored format
 *  - the track URL mirrors the playback API's own delivery-format extension:
 *    ASS/SSA stays raw, PGS stays `.sup`, and other text subtitles are served
 *    as WebVTT. Android resolves it through the same `/api/v1` stream mount as
 *    server-provided subtitle URLs and injects Authorization headers through
 *    MediaAuthInterceptor.
 *
 * Existing tracks keep their list positions, so the players' position-based
 * text-track selection (SubtitleManager.selectSubtitle) survives the merge.
 * The track for a given [DownloadedSubtitle.id] ends up at merged position
 * `mergedSize - downloaded.size + downloaded.indexOfFirst { it.id == id }`,
 * which is what auto-select-after-download callers rely on.
 */
fun mergeDownloadedSubtitles(
    existing: List<PlayerSubtitleInfo>,
    downloaded: List<DownloadedSubtitle>,
    sessionId: String,
): List<PlayerSubtitleInfo> {
    if (downloaded.isEmpty()) return existing

    val base = existing.filter { it.source != SUBTITLE_SOURCE_DOWNLOADED }
    val baseIndex = (base.maxOfOrNull { it.index } ?: -1) + 1
    val newTracks = downloaded.mapIndexed { i, dl ->
        val index = baseIndex + i
        PlayerSubtitleInfo(
            index = index,
            language = dl.language,
            codec = dl.format,
            label = "${dl.releaseName} (${dl.provider})",
            source = SUBTITLE_SOURCE_DOWNLOADED,
            forced = null,
            url = "/stream/$sessionId/subtitles/$index${subtitleUrlExtension(dl.format)}",
            downloadId = dl.id,
        )
    }

    return base + newTracks
}

private fun subtitleUrlExtension(format: String): String {
    return when (format.trim().lowercase()) {
        "ass", "ssa" -> ".ass"
        "pgs", "hdmv_pgs_subtitle" -> ".sup"
        else -> ".vtt"
    }
}
