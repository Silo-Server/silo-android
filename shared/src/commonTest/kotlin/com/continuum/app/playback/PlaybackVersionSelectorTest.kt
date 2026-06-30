package com.continuum.app.playback

import com.continuum.app.model.catalog.FileVersion
import kotlin.test.Test
import kotlin.test.assertEquals

class PlaybackVersionSelectorTest {
    private val version1080 = FileVersion(fileId = 1080, resolution = "1080p")
    private val version4k = FileVersion(fileId = 2160, resolution = "2160p")
    private val version720 = FileVersion(fileId = 720, resolution = "720p")

    @Test
    fun autoChoosesHighestResolutionInsteadOfFirstServerOrder() {
        val selected = selectPlaybackVersion(
            versions = listOf(version1080, version4k, version720),
            lastFileId = null,
            preferredQuality = "auto",
        )

        assertEquals(2160, selected.fileId)
    }

    @Test
    fun explicitQualityCapChoosesBestVersionAtOrBelowCap() {
        val selected = selectPlaybackVersion(
            versions = listOf(version720, version4k, version1080),
            lastFileId = null,
            preferredQuality = "1080p",
        )

        assertEquals(1080, selected.fileId)
    }

    @Test
    fun lastPlayedFileStillWinsWhenAvailable() {
        val selected = selectPlaybackVersion(
            versions = listOf(version4k, version1080, version720),
            lastFileId = 720,
            preferredQuality = "original",
        )

        assertEquals(720, selected.fileId)
    }
}
