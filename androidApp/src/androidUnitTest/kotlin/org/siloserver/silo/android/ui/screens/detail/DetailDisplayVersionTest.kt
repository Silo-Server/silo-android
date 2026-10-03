package org.siloserver.silo.android.ui.screens.detail

import org.siloserver.silo.model.catalog.FileVersion
import kotlin.test.Test
import kotlin.test.assertEquals

class DetailDisplayVersionTest {
    // The 1080p file is listed first; the 720p file is the one with subtitles
    // the profile last played.
    private val versions = listOf(
        FileVersion(fileId = 1080, resolution = "1080p"),
        FileVersion(fileId = 720, resolution = "720p"),
    )

    @Test
    fun `auto shows and downloads the last played version, not the first listed`() {
        val index = detailDisplayVersionIndex(
            versions = versions,
            explicitIndex = null,
            lastFileId = 720,
            preferredQuality = "auto",
            fallbackIndex = 0,
        )

        assertEquals(720, versions[index].fileId)
    }

    @Test
    fun `an explicit pick wins over the last played version`() {
        val index = detailDisplayVersionIndex(
            versions = versions,
            explicitIndex = 0,
            lastFileId = 720,
            preferredQuality = "auto",
            fallbackIndex = 0,
        )

        assertEquals(1080, versions[index].fileId)
    }

    @Test
    fun `auto names no version until the quality preference loads`() {
        val index = detailDisplayVersionIndex(
            versions = versions,
            explicitIndex = null,
            lastFileId = null,
            preferredQuality = null,
            fallbackIndex = 0,
        )

        assertEquals(-1, index)
    }

    @Test
    fun `an explicit pick with no versions loaded yields the fallback`() {
        val index = detailDisplayVersionIndex(
            versions = emptyList(),
            explicitIndex = 1,
            lastFileId = null,
            preferredQuality = "auto",
            fallbackIndex = 0,
        )

        assertEquals(0, index)
    }

    @Test
    fun `a stale explicit pick names no version instead of another one`() {
        val index = detailDisplayVersionIndex(
            versions = versions,
            explicitIndex = 5,
            lastFileId = 720,
            preferredQuality = "auto",
            fallbackIndex = 0,
        )

        assertEquals(-1, index)
    }
}
