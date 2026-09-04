// shared/src/commonTest/kotlin/org/siloserver/silo/model/playback/SubtitleTrackMergeTest.kt
package org.siloserver.silo.model.playback

import org.siloserver.silo.model.subtitles.DownloadedSubtitle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

class SubtitleTrackMergeTest {

    private fun track(
        index: Int,
        source: String?,
        url: String = "/stream/sess-1/subtitles/$index.srt",
        label: String? = "Track $index",
    ) = PlayerSubtitleInfo(
        index = index,
        language = "en",
        codec = "subrip",
        label = label,
        source = source,
        forced = null,
        url = url,
    )

    private fun downloaded(
        id: Int,
        language: String = "en",
        format: String = "srt",
        releaseName: String = "Release.$id",
        provider: String = "opensubtitles",
    ) = DownloadedSubtitle(
        id = id,
        mediaFileId = 1048,
        provider = provider,
        language = language,
        format = format,
        releaseName = releaseName,
    )

    @Test
    fun `appends downloaded tracks after existing with web-matching fields`() {
        val existing = listOf(track(0, source = "external"), track(1, source = "embedded"))

        val merged = mergeDownloadedSubtitles(
            existing = existing,
            downloaded = listOf(
                downloaded(id = 312, language = "nl", format = "srt",
                    releaseName = "Dune.Part.Three.WEB-DL", provider = "opensubtitles"),
                downloaded(id = 313, language = "en", format = "ass",
                    releaseName = "Dune Part Three", provider = "subdl"),
            ),
            sessionId = "sess-1",
        )

        assertEquals(4, merged.size)
        assertEquals(existing, merged.take(2))  // existing tracks untouched, order preserved

        val first = merged[2]
        assertEquals(2, first.index)  // continues the existing sequence
        assertEquals("nl", first.language)
        assertEquals("srt", first.codec)
        assertEquals("Dune.Part.Three.WEB-DL (opensubtitles)", first.label)  // `${release_name} (${provider})`
        assertEquals("downloaded", first.source)
        assertEquals(312, first.downloadId)
        assertNull(first.forced)
        assertEquals("/stream/sess-1/subtitles/2.vtt", first.url)

        val second = merged[3]
        assertEquals(3, second.index)
        assertEquals(313, second.downloadId)
        assertEquals("Dune Part Three (subdl)", second.label)
        assertEquals("/stream/sess-1/subtitles/3.ass", second.url)
    }

    @Test
    fun `download identity survives provider result reordering`() {
        val first = mergeDownloadedSubtitles(
            existing = listOf(track(0, source = "embedded")),
            downloaded = listOf(downloaded(id = 312), downloaded(id = 313)),
            sessionId = "sess-1",
        )
        val reordered = mergeDownloadedSubtitles(
            existing = listOf(track(0, source = "embedded")),
            downloaded = listOf(downloaded(id = 313), downloaded(id = 312)),
            sessionId = "sess-1",
        )

        assertEquals(1, first.single { it.downloadId == 312 }.index)
        assertEquals(2, reordered.single { it.downloadId == 312 }.index)
        assertEquals(312, reordered.single { it.label == "Release.312 (opensubtitles)" }.downloadId)
    }

    @Test
    fun `download identity survives deletion of an earlier provider result`() {
        val beforeDeletion = mergeDownloadedSubtitles(
            existing = listOf(track(0, source = "embedded")),
            downloaded = listOf(downloaded(id = 312), downloaded(id = 313)),
            sessionId = "sess-1",
        )
        val afterDeletion = mergeDownloadedSubtitles(
            existing = listOf(track(0, source = "embedded")),
            downloaded = listOf(downloaded(id = 313)),
            sessionId = "sess-1",
        )

        assertEquals(2, beforeDeletion.single { it.downloadId == 313 }.index)
        assertEquals(1, afterDeletion.single { it.source == "downloaded" }.index)
        assertEquals(313, afterDeletion.single { it.source == "downloaded" }.downloadId)
    }

    @Test
    fun `download identity survives catalog artifact count changes`() {
        val shortCatalog = mergeDownloadedSubtitles(
            existing = listOf(track(0, source = "embedded")),
            downloaded = listOf(downloaded(id = 312)),
            sessionId = "sess-1",
        )
        val expandedCatalog = mergeDownloadedSubtitles(
            existing = listOf(track(0, source = "embedded"), track(7, source = "external")),
            downloaded = listOf(downloaded(id = 312)),
            sessionId = "sess-1",
        )

        assertEquals(1, shortCatalog.single { it.source == "downloaded" }.index)
        assertEquals(8, expandedCatalog.single { it.source == "downloaded" }.index)
        assertEquals(312, shortCatalog.single { it.source == "downloaded" }.downloadId)
        assertEquals(312, expandedCatalog.single { it.source == "downloaded" }.downloadId)
    }

    @Test
    fun `empty downloaded list returns existing unchanged`() {
        val existing = listOf(track(0, source = "embedded"))

        val merged = mergeDownloadedSubtitles(
            existing = existing,
            downloaded = emptyList(),
            sessionId = "sess-1",
        )

        assertSame(existing, merged)
    }

    @Test
    fun `re-merge replaces previously merged downloaded tracks instead of duplicating`() {
        val firstMerge = mergeDownloadedSubtitles(
            existing = listOf(track(0, source = "embedded")),
            downloaded = listOf(downloaded(id = 312)),
            sessionId = "sess-1",
        )
        assertEquals(2, firstMerge.size)

        val secondMerge = mergeDownloadedSubtitles(
            existing = firstMerge,
            downloaded = listOf(downloaded(id = 312), downloaded(id = 313)),
            sessionId = "sess-1",
        )

        assertEquals(3, secondMerge.size)  // 1 embedded + 2 downloaded, no duplicate of 312
        assertEquals(listOf("embedded", "downloaded", "downloaded"), secondMerge.map { it.source })
        assertEquals(listOf(0, 1, 2), secondMerge.map { it.index })
    }

    @Test
    fun `base index continues from max existing index not list size`() {
        // Server-side burn-in skipping can leave index gaps (playback.go:1484).
        val existing = listOf(track(0, source = "external"), track(3, source = "embedded"))

        val merged = mergeDownloadedSubtitles(
            existing = existing,
            downloaded = listOf(downloaded(id = 312)),
            sessionId = "sess-1",
        )

        assertEquals(4, merged.last().index)  // max(0,3)+1, not size (2)
        assertEquals("/stream/sess-1/subtitles/4.vtt", merged.last().url)
    }

    @Test
    fun `starts at index zero when there are no existing tracks`() {
        val merged = mergeDownloadedSubtitles(
            existing = emptyList(),
            downloaded = listOf(downloaded(id = 312)),
            sessionId = "sess-1",
        )

        assertEquals(0, merged.single().index)
        assertEquals("/stream/sess-1/subtitles/0.vtt", merged.single().url)
    }
}
