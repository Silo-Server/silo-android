package org.siloserver.silo.playback

import org.siloserver.silo.model.catalog.FileVersion
import org.siloserver.silo.model.catalog.PlaybackMarkerSegment
import org.siloserver.silo.model.catalog.TimeRange
import org.siloserver.silo.model.catalog.WatchDetail

private val supportedMarkerKinds = setOf("intro", "credits", "recap", "preview")

fun validMarkerSegments(segments: List<PlaybackMarkerSegment>): List<PlaybackMarkerSegment> =
    segments.filter {
        it.kind in supportedMarkerKinds && it.startSeconds.isFinite() && it.endSeconds.isFinite() &&
            it.startSeconds >= 0.0 && it.endSeconds > it.startSeconds
    }.distinct().sortedWith(compareBy({ it.startSeconds }, { it.endSeconds }, { it.kind }))

fun legacyMarkerSegments(
    intro: TimeRange? = null,
    credits: TimeRange? = null,
    recap: TimeRange? = null,
    preview: TimeRange? = null,
): List<PlaybackMarkerSegment> = validMarkerSegments(
    listOf("intro" to intro, "credits" to credits, "recap" to recap, "preview" to preview)
        .mapNotNull { (kind, range) -> range?.let { PlaybackMarkerSegment(kind, it.start, it.end) } },
)

fun WatchDetail.markersForVersion(version: FileVersion): List<PlaybackMarkerSegment> =
    version.markerSegments?.let(::validMarkerSegments) ?: legacyMarkerSegments(
        intro = version.intro ?: intro,
        credits = version.credits ?: credits,
        recap = version.recap ?: recap,
        preview = version.preview ?: preview,
    )

fun activeMarkerSegment(
    segments: List<PlaybackMarkerSegment>,
    positionSeconds: Double,
    kind: String? = null,
): PlaybackMarkerSegment? = segments.firstOrNull {
    (kind == null || it.kind == kind) && positionSeconds >= it.startSeconds && positionSeconds < it.endSeconds
}

/** Only credits reaching the end can trigger episode advancement; gaps may contain a scene. */
fun terminalCreditsRange(segments: List<PlaybackMarkerSegment>, durationSeconds: Double): TimeRange? =
    segments.lastOrNull {
        it.kind == "credits" && durationSeconds.isFinite() && durationSeconds > 0 &&
            it.endSeconds >= durationSeconds - 2.0
    }?.range

/** Keep the last intro selected after its end so an automatic skip can still be undone. */
fun introMarkerSegment(segments: List<PlaybackMarkerSegment>, positionSeconds: Double): PlaybackMarkerSegment? =
    segments.lastOrNull { it.kind == "intro" && it.startSeconds <= positionSeconds }
        ?: segments.firstOrNull { it.kind == "intro" }
