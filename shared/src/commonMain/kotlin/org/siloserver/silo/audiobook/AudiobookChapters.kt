package org.siloserver.silo.audiobook

import org.siloserver.silo.common.i18n.SharedString
import org.siloserver.silo.common.i18n.UiText

/**
 * Platform-agnostic view of one audiobook chapter: a half-open
 * [startSeconds, endSeconds) span on the playable file. Built from the
 * server's `VersionChapter` (org.siloserver.silo.model.catalog.VersionChapter)
 * but kept free of the serialization model so this math is reusable by the
 * Apple clients and unit-testable in commonTest with no Android deps.
 */
data class AudiobookChapter(
    val startSeconds: Double,
    val endSeconds: Double,
)

/**
 * Pure chapter math for the audiobook player. The Android
 * `AudiobookPlayerViewModel` delegates here so chapter logic is shared and
 * tested once. Position is "current playback position in seconds"; chapters
 * are assumed sorted ascending by start (the server normalizes/sorts them).
 *
 * Boundary rule: a chapter owns `[startSeconds, endSeconds)`. A position
 * exactly on a boundary belongs to the *later* chapter (start-inclusive).
 * Degrade rules: empty list -> index 0, progress 0, no count label; a single
 * chapter hides the count label (chapter-only chrome is suppressed by the UI).
 */
object AudiobookChapters {

    /** Threshold (seconds) for the "restart current chapter" prev rule. */
    const val PREV_RESTART_THRESHOLD_SECONDS = 3.0

    /**
     * Index of the chapter containing [positionSeconds]. Clamps below the
     * first chapter to 0 and at/after the last chapter end to the last index.
     * Empty list degrades to 0.
     */
    fun currentIndex(chapters: List<AudiobookChapter>, positionSeconds: Double): Int {
        if (chapters.isEmpty()) return 0
        // Last chapter whose start is <= position (start-inclusive boundary).
        var idx = 0
        for (i in chapters.indices) {
            if (positionSeconds >= chapters[i].startSeconds) idx = i else break
        }
        return idx
    }

    /** The current chapter, or null when there are none. */
    fun currentChapter(
        chapters: List<AudiobookChapter>,
        positionSeconds: Double,
    ): AudiobookChapter? =
        chapters.getOrNull(currentIndex(chapters, positionSeconds))

    /**
     * Progress within the current chapter in 0..1. Zero-length and
     * out-of-range positions clamp; empty list returns 0.
     */
    fun chapterProgress(chapters: List<AudiobookChapter>, positionSeconds: Double): Double {
        val ch = currentChapter(chapters, positionSeconds) ?: return 0.0
        val span = ch.endSeconds - ch.startSeconds
        if (span <= 0.0) return 0.0
        val raw = (positionSeconds - ch.startSeconds) / span
        return raw.coerceIn(0.0, 1.0)
    }

    /**
     * "Chapter N of M" label, one-based. Null when there are 0 or 1 chapters
     * (single-chapter / chapterless books hide the chapter header).
     */
    fun countLabel(chapters: List<AudiobookChapter>, positionSeconds: Double): UiText? {
        if (chapters.size < 2) return null
        val n = currentIndex(chapters, positionSeconds) + 1
        return UiText.Key(SharedString.AudiobookChapterOf, listOf(UiText.Number(n), UiText.Number(chapters.size)))
    }

    /**
     * Seek target (seconds) for "next chapter": the start of the chapter
     * after the current one, or — when already on the last chapter — the
     * current chapter's start (no-op-ish clamp). Empty list -> 0.
     */
    fun nextChapterTarget(chapters: List<AudiobookChapter>, positionSeconds: Double): Double {
        if (chapters.isEmpty()) return 0.0
        val current = currentIndex(chapters, positionSeconds)
        val target = (current + 1).coerceAtMost(chapters.lastIndex)
        return chapters[target].startSeconds
    }

    /**
     * Seek target (seconds) for "previous chapter", standard audiobook
     * behavior: if more than [PREV_RESTART_THRESHOLD_SECONDS] into the
     * current chapter, restart the current chapter; otherwise jump to the
     * previous chapter's start. On the first chapter, always its start.
     * Empty list -> 0.
     */
    fun previousChapterTarget(chapters: List<AudiobookChapter>, positionSeconds: Double): Double {
        if (chapters.isEmpty()) return 0.0
        val current = currentIndex(chapters, positionSeconds)
        val currentStart = chapters[current].startSeconds
        val intoChapter = positionSeconds - currentStart
        return if (intoChapter > PREV_RESTART_THRESHOLD_SECONDS || current == 0) {
            currentStart
        } else {
            chapters[current - 1].startSeconds
        }
    }
}

/**
 * A human display label for a chapter. Many audiobooks ship useless chapter
 * "titles" that are just the track number ("1", "007") or blank; for those we
 * show "Chapter N" (1-based). Genuine titles are returned trimmed as-is. Shared
 * by the phone and TV chapter lists so the behavior is identical and tested once.
 */
fun audiobookChapterLabel(index: Int, title: String): UiText {
    val trimmed = title.trim()
    return if (trimmed.isEmpty() || trimmed.all { it.isDigit() }) {
        UiText.Key(SharedString.AudiobookChapter, listOf(UiText.Number(index + 1)))
    } else {
        UiText.Raw(trimmed)
    }
}
