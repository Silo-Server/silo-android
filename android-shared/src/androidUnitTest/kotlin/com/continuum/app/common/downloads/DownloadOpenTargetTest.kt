package com.continuum.app.common.downloads

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DownloadOpenTargetTest {
    @Test
    fun `ebook mime types preserve reader formats`() {
        assertEquals("application/epub+zip", mimeTypeForDownloadName("novel.epub", "epub"))
        assertEquals("application/pdf", mimeTypeForDownloadName("manual.pdf", "pdf"))
        assertEquals("application/vnd.comicbook+zip", mimeTypeForDownloadName("comic.cbz", "cbz"))
        assertEquals("application/vnd.comicbook-rar", mimeTypeForDownloadName("comic.cbr", "cbr"))
        assertEquals("application/x-mobipocket-ebook", mimeTypeForDownloadName("book.mobi", "mobi"))
        assertEquals("application/vnd.amazon.ebook", mimeTypeForDownloadName("book.azw", "azw"))
        assertEquals("application/vnd.amazon.ebook", mimeTypeForDownloadName("book.azw3", "azw3"))
        assertEquals("application/x-fictionbook+xml", mimeTypeForDownloadName("book.fb2", "fb2"))
        assertEquals("application/zip", mimeTypeForDownloadName("book.fbz", "fbz"))
        assertEquals("text/markdown", mimeTypeForDownloadName("notes.md", "md"))
    }

    @Test
    fun `audiobook mime types preserve original audio formats`() {
        assertEquals("audio/mp4", mimeTypeForDownloadName("book.m4b", "m4b"))
        assertEquals("audio/mp4", mimeTypeForDownloadName("book.m4a", "m4a"))
        assertEquals("audio/mpeg", mimeTypeForDownloadName("book.mp3", "mp3"))
        assertEquals("audio/aac", mimeTypeForDownloadName("book.aac", "aac"))
        assertEquals("audio/flac", mimeTypeForDownloadName("book.flac", "flac"))
        assertEquals("audio/ogg", mimeTypeForDownloadName("book.ogg", "ogg"))
        assertEquals("audio/opus", mimeTypeForDownloadName("book.opus", "opus"))
        assertEquals("audio/wav", mimeTypeForDownloadName("book.wav", "wav"))
    }

    @Test
    fun `video mime types preserve original video formats`() {
        assertEquals("video/mp4", mimeTypeForDownloadName("movie.mp4", "mp4"))
        assertEquals("video/mp4", mimeTypeForDownloadName("movie.m4v", "m4v"))
        assertEquals("video/x-matroska", mimeTypeForDownloadName("movie.mkv", "mkv"))
        assertEquals("video/webm", mimeTypeForDownloadName("movie.webm", "webm"))
        assertEquals("video/x-msvideo", mimeTypeForDownloadName("movie.avi", "avi"))
        assertEquals("video/quicktime", mimeTypeForDownloadName("movie.mov", "mov"))
        assertEquals("video/mp2t", mimeTypeForDownloadName("movie.ts", "ts"))
    }

    @Test
    fun `mime type falls back to container when display name has no extension`() {
        assertEquals("audio/mp4", mimeTypeForDownloadName("42", "m4b"))
        assertEquals("application/epub+zip", mimeTypeForDownloadName("42", "epub"))
        assertEquals("video/x-matroska", mimeTypeForDownloadName("42", ".MKV"))
        assertEquals("text/markdown", mimeTypeForDownloadName("42", "MD"))
    }

    @Test
    fun `mime type trims padded container metadata`() {
        assertEquals("application/epub+zip", mimeTypeForDownloadName("42", " .EPUB "))
        assertEquals("video/x-matroska", mimeTypeForDownloadName("42", " .MKV "))
    }

    @Test
    fun `unknown mime type falls back to octet stream`() {
        assertEquals(
            "application/octet-stream",
            mimeTypeForDownloadName("book.unknown-audio", null),
        )
    }

    @Test
    fun `open target only exists for completed local uri`() {
        val complete = DownloadOpenTarget.from(
            isComplete = true,
            localUri = "content://downloads/1",
            displayName = "book.m4b",
            container = "m4b",
        )
        val incomplete = DownloadOpenTarget.from(
            isComplete = false,
            localUri = "content://downloads/1",
            displayName = "book.m4b",
            container = "m4b",
        )
        val missingUri = DownloadOpenTarget.from(
            isComplete = true,
            localUri = null,
            displayName = "book.m4b",
            container = "m4b",
        )

        assertTrue(complete != null)
        assertEquals("book.m4b", complete?.displayName)
        assertEquals("audio/mp4", complete?.mimeType)
        assertFalse(incomplete != null)
        assertFalse(missingUri != null)
    }

    @Test
    fun `open target fallback name keeps original container`() {
        val target = DownloadOpenTarget.from(
            isComplete = true,
            localUri = "content://downloads/2",
            displayName = null,
            container = "mp3",
        )

        assertEquals("download.mp3", target?.displayName)
        assertEquals("audio/mpeg", target?.mimeType)
    }

    @Test
    fun `open target fallback name keeps video container`() {
        val target = DownloadOpenTarget.from(
            isComplete = true,
            localUri = "content://downloads/video",
            displayName = null,
            container = "mkv",
        )

        assertEquals("download.mkv", target?.displayName)
        assertEquals("video/x-matroska", target?.mimeType)
    }

    @Test
    fun `open target fallback name trims padded container metadata`() {
        val target = DownloadOpenTarget.from(
            isComplete = true,
            localUri = "content://downloads/video",
            displayName = null,
            container = " .MKV ",
        )

        assertEquals("download.mkv", target?.displayName)
        assertEquals("video/x-matroska", target?.mimeType)
    }

    @Test
    fun `open target refuses to invent unknown download format`() {
        val target = DownloadOpenTarget.from(
            isComplete = true,
            localUri = "content://downloads/unknown",
            displayName = null,
            container = null,
        )

        assertEquals(null, target)
    }

    @Test
    fun `open target supports completed video downloads`() {
        val target = DownloadOpenTarget.from(
            isComplete = true,
            localUri = "content://media/external/video/media/7",
            displayName = "episode.mkv",
            container = "mkv",
        )

        assertEquals("content://media/external/video/media/7", target?.uriString)
        assertEquals("episode.mkv", target?.displayName)
        assertEquals("video/x-matroska", target?.mimeType)
    }
}
