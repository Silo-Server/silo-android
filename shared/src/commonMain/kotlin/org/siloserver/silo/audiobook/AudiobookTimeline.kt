package org.siloserver.silo.audiobook

import org.siloserver.silo.model.catalog.FileVersion

/**
 * Client-side whole-book audiobook timeline. The server has no concept of a
 * whole book — it sends each audiobook file as an individual [FileVersion]
 * tagged `presentation_kind == "audiobook_part"` with a `presentation_part_index`
 * (pre-sorted by index). This file stitches those parts into one virtual
 * whole-book timeline so the player can drive a multi-part book through the
 * standard per-file playback API: the global offsets exist purely in the client.
 *
 * Faithful Kotlin port of Apple's client-side timeline
 * (`iosApp/Screens/Audio/AudiobookPlaybackContext.swift` and
 * `AudioPlaybackTimeline.swift`). Kept in `commonMain` with no Android/Compose
 * deps so the math is shared with the Apple clients and unit-testable in
 * commonTest.
 *
 * Note vs. Apple: Android's [FileVersion.duration] is a non-null `Double`
 * (0.0 when absent, not `null`), and `VersionChapter.startSeconds/endSeconds`
 * are non-null `Double`s, so the null-coalescing branches collapse to
 * "> 0" / value-present checks here.
 */

/**
 * One audio part of a book on the whole-book timeline. Built client-side from
 * the item's [FileVersion] list; the server's playback API only knows about
 * individual files, so the global [startOffsetSeconds] exists purely here.
 *
 * Mirrors Apple `AudioPlaybackTrack` (AudioPlaybackContext.swift:6-13).
 */
data class AudioPlaybackTrack(
    val index: Int,
    val fileId: Int,
    val durationSeconds: Double,
    val startOffsetSeconds: Double,
)

/**
 * Chapter mapped onto the whole-book timeline: file-local chapter starts
 * shifted by the owning track's [AudioPlaybackTrack.startOffsetSeconds].
 *
 * Mirrors Apple `AudioPlaybackChapter` (AudioPlaybackContext.swift:17-24).
 */
data class AudioPlaybackChapter(
    val index: Int,
    val title: String?,
    val startSeconds: Double,
    val endSeconds: Double?,
    val trackIndex: Int,
)

/**
 * Everything the audio player needs to treat a multi-part book as one timeline:
 * the ordered [tracks] stitched with cumulative offsets, the [chapters] merged
 * onto that timeline, and the whole-book [totalSeconds].
 *
 * Works uniformly for a single-part book (one track, trivial offset math) so
 * the view model can use it without special-casing. Build via
 * [buildAudiobookTimeline], which returns `null` only when there are zero
 * playable (non-zero-length) audio parts.
 *
 * Position math mirrors Apple `AudioPlaybackTimeline` (AudioPlaybackTimeline.swift:3-25).
 */
data class AudiobookTimeline(
    val tracks: List<AudioPlaybackTrack>,
    val chapters: List<AudioPlaybackChapter>,
    val totalSeconds: Double,
) {
    /** True when the book is a single part (offset math is trivial). */
    val isSingle: Boolean get() = tracks.size <= 1

    /**
     * Index of the track playing at [globalTime]: the first track whose span
     * `[start, start + duration)` contains the (non-negative) time. Past the
     * end of the last track, returns the last track's index.
     *
     * Mirrors Apple `trackIndex(at:tracks:)` (AudioPlaybackTimeline.swift:4-15).
     */
    fun trackIndexAt(globalTime: Double): Int {
        if (tracks.isEmpty()) return 0
        val clamped = maxOf(0.0, globalTime)
        for (track in tracks.sortedBy { it.index }) {
            val start = track.startOffsetSeconds
            val end = start + maxOf(0.0, track.durationSeconds)
            if (clamped >= start && clamped < end) return track.index
        }
        return tracks.last().index
    }

    /**
     * The file-local time within [track] for a whole-book [globalTime],
     * clamped to `[0, track.duration]`.
     *
     * Mirrors Apple `localTime(for:in:)` (AudioPlaybackTimeline.swift:17-20).
     */
    fun localTimeFor(globalTime: Double, track: AudioPlaybackTrack): Double {
        val local = globalTime - track.startOffsetSeconds
        return minOf(maxOf(0.0, local), maxOf(0.0, track.durationSeconds))
    }

    /**
     * The whole-book time for a file-local [localTime] within [track]:
     * the track's start offset plus the (clamped) local time.
     *
     * Mirrors Apple `globalTime(for:in:)` (AudioPlaybackTimeline.swift:22-24).
     */
    fun globalTimeFor(localTime: Double, track: AudioPlaybackTrack): Double =
        track.startOffsetSeconds + minOf(maxOf(0.0, localTime), maxOf(0.0, track.durationSeconds))
}

/** Presentation-kind tag the server stamps on audiobook part files. */
private const val AUDIOBOOK_PART_KIND = "audiobook_part"

/**
 * Stitch [versions] into a whole-book [AudiobookTimeline], or `null` when none
 * of them are playable audio parts. [serverTotalSeconds] is the server's whole-book
 * total (`AudiobookMetadata.totalDurationSeconds`, converted to `Double`), or
 * `null` when the server doesn't report one — in which case the cumulative part
 * duration is used.
 *
 * Mirrors Apple `AudiobookPlaybackContext.init?` (AudiobookPlaybackContext.swift:39-84).
 */
fun buildAudiobookTimeline(
    versions: List<FileVersion>,
    serverTotalSeconds: Double?,
    preferredFileId: Int? = null,
): AudiobookTimeline? {
    val parts = audioParts(versions, preferredFileId)
    if (parts.isEmpty()) return null

    val tracks = ArrayList<AudioPlaybackTrack>(parts.size)
    val chapters = ArrayList<AudioPlaybackChapter>()
    var offset = 0.0
    for (part in parts) {
        val duration = partDuration(part)
        // Skip zero-length parts (no probed duration AND no chapter-edge
        // fallback): Android's end-of-part detection is poll-based (time >=
        // duration - epsilon), so a 0s track would trip the advance check on
        // every tick. Apple can include such parts because it advances on a
        // discrete end EVENT rather than a poll. A dropped part contributes
        // nothing to the offsets, so the following parts are unaffected.
        if (duration <= 0.0) continue
        val index = tracks.size
        tracks.add(
            AudioPlaybackTrack(
                index = index,
                fileId = part.fileId,
                durationSeconds = duration,
                startOffsetSeconds = offset,
            )
        )
        for (chapter in part.chapters.orEmpty()) {
            chapters.add(
                AudioPlaybackChapter(
                    // File chapter indexes restart at zero for every part.
                    // The player exposes one book-wide list, so its index must
                    // be the merged ordinal rather than the file-local value.
                    index = chapters.size,
                    title = chapter.title,
                    startSeconds = offset + chapter.startSeconds,
                    endSeconds = offset + chapter.endSeconds,
                    trackIndex = index,
                )
            )
        }
        offset += duration
    }
    if (tracks.isEmpty()) return null

    // total = max(server-reported total, stitched offset); if the server
    // reports nothing, the cumulative offset is the whole-book length.
    val total = maxOf(serverTotalSeconds ?: 0.0, offset)

    val sortedChapters = chapters
        .sortedBy { it.startSeconds }
        .mapIndexed { index, chapter -> chapter.copy(index = index) }

    return AudiobookTimeline(
        tracks = tracks,
        chapters = sortedChapters,
        totalSeconds = total,
    )
}

/**
 * True only when [fileId] by itself holds the whole book, so a position in that
 * file is a position in the book: the detail lists at most one logical part, the
 * file is one of its audio files, and, when the server reports the book's total,
 * the file covers nearly all of it. A file the detail can't vouch for is false,
 * so one part's local time never becomes the book's position.
 */
fun isWholeBookFile(versions: List<FileVersion>, fileId: Int, serverTotalSeconds: Double?): Boolean {
    val audio = versions.filter { it.isAudioPartCandidate() }
    val file = audio.firstOrNull { it.fileId == fileId } ?: return false
    if (audio.mapNotNull { it.presentationPartIndex }.distinct().size > 1) return false
    // Without the server's total, several unlabeled audio files could be parts;
    // files sharing one part index are alternates of that part.
    val total = serverTotalSeconds?.takeIf { it.isFinite() && it > 0.0 }
        ?: return audio.size == 1 || audio.all { it.presentationPartIndex != null }
    return partDuration(file) >= total * WHOLE_BOOK_MIN_SHARE
}

/** How much of the server's total a file must cover to count as the whole book. */
private const val WHOLE_BOOK_MIN_SHARE = 0.9

private fun FileVersion.isAudioPartCandidate(): Boolean =
    presentationKind == AUDIOBOOK_PART_KIND || codecAudio != null || duration > 0.0

/**
 * Selects one playable file for each ordered audiobook part. A version is
 * playable when it is tagged `audiobook_part`, or (fallback for un-tagged
 * libraries) when it carries an audio codec or a positive duration. Alternate
 * files in the same part are reduced to the variant matching [preferredFileId]
 * so their durations and chapters cannot be stitched into the book twice.
 *
 * Mirrors Apple `AudiobookPlaybackContext.audioParts(of:)` (AudiobookPlaybackContext.swift:89-101).
 */
internal fun audioParts(versions: List<FileVersion>, preferredFileId: Int? = null): List<FileVersion> {
    val candidates = versions.filter { it.isAudioPartCandidate() }

    if (candidates.isEmpty()) return emptyList()

    // Detail responses contain alternate files for each logical audiobook
    // part. Pick one coherent variant across all parts; treating every file as
    // a sequential part shifts chapter offsets by the duration/chapter count
    // of each alternate file.
    val preferred = preferredFileId?.let { id -> candidates.firstOrNull { it.fileId == id } }
    val preferredKey = preferred?.presentationVariantKey()
    val indexed = candidates.filter { it.presentationPartIndex != null }
    val selected = if (indexed.isNotEmpty()) {
        val byPart = indexed
            .groupBy { it.presentationPartIndex }
            .toSortedMap(compareBy(nullsLast()) { it })
        val candidateKeys = candidates
            .map { it.presentationVariantKey() }
            .distinct()
        fun isCompleteVariant(key: String): Boolean = byPart.values.all { partVersions ->
            partVersions.any { it.presentationVariantKey() == key }
        }
        val selectedKey = preferredKey
            ?.takeIf(::isCompleteVariant)
            ?: candidateKeys.firstOrNull(::isCompleteVariant)

        byPart.values.mapNotNull { partVersions ->
            selectedKey?.let { key ->
                partVersions.firstOrNull { it.presentationVariantKey() == key }
            } ?: partVersions.firstOrNull { it.fileId == preferredFileId }
                ?: partVersions.firstOrNull()
        }
    } else {
        listOf(
            preferred
                ?: preferredKey?.let { key -> candidates.firstOrNull { it.presentationVariantKey() == key } }
                ?: candidates.first(),
        )
    }

    return selected.sortedWith(compareBy({ it.presentationPartIndex ?: Int.MAX_VALUE }, { it.fileId }))
}

private fun FileVersion.presentationVariantKey(): String =
    listOf(presentationKind.orEmpty(), presentationGroupKey.orEmpty())
        .joinToString("\u0000")

/**
 * Duration of a part: the probed [FileVersion.duration] when finite and
 * positive, otherwise the furthest chapter edge — some scanned parts carry
 * chapters but no container duration.
 *
 * Mirrors Apple `AudiobookPlaybackContext.partDuration(_:)` (AudiobookPlaybackContext.swift:105-113).
 */
internal fun partDuration(part: FileVersion): Double {
    val duration = part.duration
    if (duration.isFinite() && duration > 0.0) return duration
    val chapterEnd = part.chapters.orEmpty()
        .maxOfOrNull { maxOf(it.endSeconds, it.startSeconds) } ?: 0.0
    return maxOf(0.0, chapterEnd)
}
