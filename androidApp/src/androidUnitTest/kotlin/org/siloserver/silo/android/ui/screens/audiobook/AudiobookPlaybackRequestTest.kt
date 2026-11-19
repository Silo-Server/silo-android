package org.siloserver.silo.android.ui.screens.audiobook

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Opening the audiobook player either shows the session already playing or
 * starts the book again. Getting it wrong restarts a book the user only meant
 * to bring back up, or ignores a chapter or Start Over they picked.
 */
class AudiobookPlaybackRequestTest {
    private val playing = AudiobookPlaybackRequest(contentId = "book-1", libraryId = "4", fileId = "12")

    @Test
    fun resumeAndMiniPlayerOpenTheRunningSession() {
        // Detail Resume and the mini player pass only the book.
        assertTrue(AudiobookPlaybackRequest(contentId = "book-1").continues(playing))
        assertTrue(AudiobookPlaybackRequest(contentId = "book-1", libraryId = "4").continues(playing))
    }

    @Test
    fun downloadedRowWithTheSameFileOpensTheRunningSession() {
        assertTrue(AudiobookPlaybackRequest(contentId = "book-1", fileId = "12").continues(playing))
    }

    @Test
    fun anotherBookStartsANewSession() {
        assertFalse(AudiobookPlaybackRequest(contentId = "book-2").continues(playing))
    }

    @Test
    fun startOverChapterOrOtherFileStartsANewSession() {
        assertFalse(AudiobookPlaybackRequest(contentId = "book-1", fromStart = true).continues(playing))
        assertFalse(AudiobookPlaybackRequest(contentId = "book-1", startPosition = "754.0").continues(playing))
        assertFalse(AudiobookPlaybackRequest(contentId = "book-1", fileId = "13").continues(playing))
    }
}
