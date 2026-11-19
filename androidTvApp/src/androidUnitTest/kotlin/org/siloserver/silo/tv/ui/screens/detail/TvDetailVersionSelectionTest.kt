package org.siloserver.silo.tv.ui.screens.detail

import org.siloserver.silo.model.catalog.FileVersion
import org.siloserver.silo.model.catalog.ItemDetail
import org.siloserver.silo.model.catalog.LeafItemUserData
import kotlin.test.Test
import kotlin.test.assertEquals

class TvDetailVersionSelectionTest {
    private val version1080 = FileVersion(fileId = 1080, resolution = "1080p")
    private val version4k = FileVersion(fileId = 2160, resolution = "2160p")

    private fun movie(lastFileId: Int?) = ItemDetail(
        contentId = "m1",
        type = "movie",
        title = "Movie",
        versions = listOf(version1080, version4k),
        userData = LeafItemUserData(lastFileId = lastFileId),
    )

    @Test
    fun autoDisplayFollowsThePreferenceRatherThanTheLastPlayedFile() {
        val selected = selectTvDetailDisplayVersion(
            detail = movie(lastFileId = 1080),
            selectedFileId = null,
            selectedEditionId = null,
            preferredQuality = "auto",
        )

        assertEquals(2160, selected?.fileId)
    }

    @Test
    fun autoDisplayUsesPreferredQualityWhenNothingWasExplicitlySelectedOrLastPlayed() {
        val selected = selectTvDetailDisplayVersion(
            detail = movie(lastFileId = null),
            selectedFileId = null,
            selectedEditionId = null,
            preferredQuality = "1080p",
        )

        assertEquals(1080, selected?.fileId)
    }
}
