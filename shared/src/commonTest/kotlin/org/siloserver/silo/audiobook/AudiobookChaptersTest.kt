package org.siloserver.silo.audiobook

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.siloserver.silo.common.i18n.UiText
import org.siloserver.silo.common.i18n.SharedString

class AudiobookChaptersTest {

    // Three chapters: [0,100), [100,250), [250,400)
    private val chapters = listOf(
        AudiobookChapter(startSeconds = 0.0, endSeconds = 100.0),
        AudiobookChapter(startSeconds = 100.0, endSeconds = 250.0),
        AudiobookChapter(startSeconds = 250.0, endSeconds = 400.0),
    )

    @Test
    fun `current index within a chapter`() {
        assertEquals(0, AudiobookChapters.currentIndex(chapters, 0.0))
        assertEquals(0, AudiobookChapters.currentIndex(chapters, 50.0))
        assertEquals(1, AudiobookChapters.currentIndex(chapters, 150.0))
        assertEquals(2, AudiobookChapters.currentIndex(chapters, 399.9))
    }

    @Test
    fun `position exactly on a boundary belongs to the later chapter`() {
        // 100.0 is the end of ch0 and the start of ch1 -> ch1 (start-inclusive).
        assertEquals(1, AudiobookChapters.currentIndex(chapters, 100.0))
        assertEquals(2, AudiobookChapters.currentIndex(chapters, 250.0))
    }

    @Test
    fun `position past the last chapter end clamps to last index`() {
        assertEquals(2, AudiobookChapters.currentIndex(chapters, 500.0))
    }

    @Test
    fun `negative position clamps to first index`() {
        assertEquals(0, AudiobookChapters.currentIndex(chapters, -5.0))
    }

    @Test
    fun `empty chapters degrade to index 0`() {
        assertEquals(0, AudiobookChapters.currentIndex(emptyList(), 42.0))
    }

    @Test
    fun `chapter progress is position within the current chapter`() {
        // ch1 = [100,250), span 150. At 175 -> (175-100)/150 = 0.5
        assertEquals(0.5, AudiobookChapters.chapterProgress(chapters, 175.0), 1e-9)
        assertEquals(0.0, AudiobookChapters.chapterProgress(chapters, 100.0), 1e-9)
    }

    @Test
    fun `chapter progress clamps to 0_1 outside bounds`() {
        assertEquals(0.0, AudiobookChapters.chapterProgress(chapters, -10.0), 1e-9)
        assertEquals(1.0, AudiobookChapters.chapterProgress(chapters, 500.0), 1e-9)
    }

    @Test
    fun `chapter progress of a zero-length chapter is 0`() {
        val degenerate = listOf(AudiobookChapter(10.0, 10.0))
        assertEquals(0.0, AudiobookChapters.chapterProgress(degenerate, 10.0), 1e-9)
    }

    @Test
    fun `count label is one-based current of total`() {
        assertEquals(chapterOf(2, 3), AudiobookChapters.countLabel(chapters, 150.0))
        assertEquals(chapterOf(1, 3), AudiobookChapters.countLabel(chapters, 0.0))
    }

    @Test
    fun `count label degrades to null for single or empty chapters`() {
        assertNull(AudiobookChapters.countLabel(emptyList(), 0.0))
        assertNull(AudiobookChapters.countLabel(listOf(AudiobookChapter(0.0, 100.0)), 10.0))
    }

    @Test
    fun `next chapter target is the next chapter start`() {
        assertEquals(100.0, AudiobookChapters.nextChapterTarget(chapters, 50.0))
        assertEquals(250.0, AudiobookChapters.nextChapterTarget(chapters, 150.0))
    }

    @Test
    fun `next chapter target on the last chapter stays at last chapter start`() {
        assertEquals(250.0, AudiobookChapters.nextChapterTarget(chapters, 300.0))
    }

    @Test
    fun `previous chapter restarts current when more than 3s in`() {
        // In ch1 (start 100) at 110 -> 10s in -> restart ch1 at 100.0
        assertEquals(100.0, AudiobookChapters.previousChapterTarget(chapters, 110.0))
    }

    @Test
    fun `previous chapter goes to prior chapter when 3s or less in`() {
        // In ch1 (start 100) at 102 -> 2s in -> previous chapter ch0 start 0.0
        assertEquals(0.0, AudiobookChapters.previousChapterTarget(chapters, 102.0))
        // Exactly 3s in -> still "within threshold" -> previous chapter.
        assertEquals(0.0, AudiobookChapters.previousChapterTarget(chapters, 103.0))
    }

    @Test
    fun `previous chapter on the first chapter restarts at its start`() {
        // In ch0 (start 0) at 1.0 -> 1s in (<=3) but no prior chapter -> 0.0
        assertEquals(0.0, AudiobookChapters.previousChapterTarget(chapters, 1.0))
        // In ch0 at 50 -> >3s in -> restart ch0 at 0.0
        assertEquals(0.0, AudiobookChapters.previousChapterTarget(chapters, 50.0))
    }

    @Test
    fun `navigation targets degrade safely for empty chapters`() {
        assertEquals(0.0, AudiobookChapters.nextChapterTarget(emptyList(), 5.0))
        assertEquals(0.0, AudiobookChapters.previousChapterTarget(emptyList(), 5.0))
    }

    @Test
    fun `chapter label uses Chapter N for numeric or blank titles`() {
        assertEquals(chapter(1), audiobookChapterLabel(0, "1"))
        assertEquals(chapter(8), audiobookChapterLabel(7, "007"))
        assertEquals(chapter(3), audiobookChapterLabel(2, "   "))
        assertEquals(chapter(1), audiobookChapterLabel(0, ""))
    }

    private fun chapter(number: Int) = UiText.Key(SharedString.AudiobookChapter, listOf(UiText.Number(number)))

    private fun chapterOf(number: Int, total: Int) =
        UiText.Key(SharedString.AudiobookChapterOf, listOf(UiText.Number(number), UiText.Number(total)))

    @Test
    fun `chapter label keeps meaningful titles trimmed`() {
        assertEquals(UiText.Raw("The Body in the Library"), audiobookChapterLabel(0, "  The Body in the Library  "))
        assertEquals(UiText.Raw("Prologue"), audiobookChapterLabel(0, "Prologue"))
    }
}
