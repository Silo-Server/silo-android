package org.siloserver.silo.domain.player

import org.siloserver.silo.model.catalog.MarkerSegment
import org.siloserver.silo.model.catalog.TimeRange

enum class MarkerKind(val wire: String) {
    Intro("intro"), Credits("credits"), Recap("recap"), Preview("preview");

    companion object {
        fun fromWire(value: String): MarkerKind? = entries.firstOrNull { it.wire == value }
    }
}

/**
 * Every marker occurrence of one file, in source-time order.
 *
 * The server's `marker_segments` may list a kind more than once (for example
 * two credits ranges around a mid-credits scene); its singular
 * `intro`/`credits`/`recap`/`preview` fields project only the first occurrence.
 * Skips act on the occurrence playing and never span the content between two
 * of them. Mirrors the web player's `resolveMarkerRegions` /
 * `markerOccurrenceAtTime` (silo-server `web/src/player/utils/watchPageMarkers.ts`).
 */
class MarkerTimeline private constructor(private val regions: List<Pair<MarkerKind, TimeRange>>) {
    /** Valid occurrences of [kind], ordered by start then end. */
    fun ranges(kind: MarkerKind): List<TimeRange> = regions.filter { it.first == kind }.map { it.second }

    /** The occurrence of [kind] playing at [position]; ranges are end-exclusive. */
    fun active(kind: MarkerKind, position: Double): TimeRange? {
        if (!position.isFinite()) return null
        return ranges(kind).lastOrNull { position >= it.start && position < it.end }
    }

    /**
     * The occurrence a prompt for [kind] is about: the one playing or, between
     * occurrences, the one that last started, so an undo offered after a skip
     * stays anchored to the range it skipped. Before any starts, the first,
     * so scrubbing back past it keeps that undo too. The prompt still checks
     * the playhead against the range before offering anything.
     */
    fun occurrenceAt(kind: MarkerKind, position: Double): TimeRange? {
        if (!position.isFinite()) return null
        val occurrences = ranges(kind)
        var latest: TimeRange? = null
        var active: TimeRange? = null
        for (range in occurrences) {
            if (range.start > position) break
            if (latest == null || range.end > latest.end) latest = range
            if (position < range.end) active = range
        }
        // Equal-end occurrences keep one identity before and after a skip.
        active?.let { playing -> return occurrences.first { it.end == playing.end } }
        return latest ?: occurrences.firstOrNull()
    }

    /**
     * Where Up Next appears: the start of the last credits range, extended
     * back over credits ranges that run into it, so a scene between two
     * credits ranges still plays.
     */
    fun upNextCreditsStart(): Double? {
        val credits = ranges(MarkerKind.Credits)
        var start = credits.lastOrNull()?.start ?: return null
        for (range in credits.asReversed()) {
            // Allow timestamp rounding between touching ranges.
            if (range.end >= start - 1) start = minOf(start, range.start)
        }
        return start
    }

    companion object {
        val EMPTY = MarkerTimeline(emptyList())

        /** Prefers [segments]; the singular fields stand in when a server sends none. */
        fun resolve(
            segments: List<MarkerSegment>?,
            intro: TimeRange?,
            credits: TimeRange?,
            recap: TimeRange?,
            preview: TimeRange?,
        ): MarkerTimeline {
            val regions = segments?.mapNotNull { segment ->
                MarkerKind.fromWire(segment.kind)?.let { it to TimeRange(segment.startSeconds, segment.endSeconds) }
            } ?: listOfNotNull(
                intro?.let { MarkerKind.Intro to it },
                credits?.let { MarkerKind.Credits to it },
                recap?.let { MarkerKind.Recap to it },
                preview?.let { MarkerKind.Preview to it },
            )
            return MarkerTimeline(
                regions
                    .filter { (_, range) ->
                        range.start.isFinite() && range.end.isFinite() && range.start >= 0 && range.end > range.start
                    }
                    .sortedWith(compareBy({ it.second.start }, { it.second.end })),
            )
        }
    }
}
