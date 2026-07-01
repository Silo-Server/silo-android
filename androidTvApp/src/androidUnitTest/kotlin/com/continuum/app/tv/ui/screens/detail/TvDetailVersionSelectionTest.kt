package com.continuum.app.tv.ui.screens.detail

import com.continuum.app.model.catalog.FileVersion
import kotlin.test.Test
import kotlin.test.assertEquals

class TvDetailVersionSelectionTest {
    @Test
    fun autoDisplayUsesLastPlayedFileBeforeFirstServerVersion() {
        val version1080 = FileVersion(fileId = 1080, resolution = "1080p")
        val version4k = FileVersion(fileId = 2160, resolution = "2160p")

        val selected = selectTvDetailDisplayVersion(
            versions = listOf(version1080, version4k),
            selectedFileId = null,
            lastFileId = 2160,
            preferredQuality = "auto",
        )

        assertEquals(2160, selected?.fileId)
    }

    @Test
    fun autoDisplayUsesPreferredQualityWhenNothingWasExplicitlySelectedOrLastPlayed() {
        val version1080 = FileVersion(fileId = 1080, resolution = "1080p")
        val version4k = FileVersion(fileId = 2160, resolution = "2160p")

        val selected = selectTvDetailDisplayVersion(
            versions = listOf(version1080, version4k),
            selectedFileId = null,
            lastFileId = null,
            preferredQuality = "1080p",
        )

        assertEquals(1080, selected?.fileId)
    }
}
