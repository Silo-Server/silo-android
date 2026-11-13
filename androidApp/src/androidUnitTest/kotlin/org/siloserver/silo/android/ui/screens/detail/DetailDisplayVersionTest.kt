package org.siloserver.silo.android.ui.screens.detail

import org.siloserver.silo.model.catalog.FileVersion
import org.siloserver.silo.model.catalog.LeafItemUserData
import org.siloserver.silo.playback.detailPlaybackFileId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DetailDisplayVersionTest {
    // The 1080p file is listed first; the 720p file is the one the profile
    // last played.
    private val versions = listOf(
        FileVersion(fileId = 1080, resolution = "1080p"),
        FileVersion(fileId = 720, resolution = "720p"),
    )
    private val playedThe720p = LeafItemUserData(lastFileId = 720)

    private fun index(
        explicitIndex: Int? = null,
        userData: LeafItemUserData? = playedThe720p,
        preferredQuality: String? = "auto",
        files: List<FileVersion> = versions,
        editionId: String? = null,
    ) = detailDisplayVersionIndex(
        versions = files,
        variants = emptyList(),
        explicitIndex = explicitIndex,
        editionId = editionId,
        userData = userData,
        preferredQuality = preferredQuality,
        fallbackIndex = 0,
    )

    @Test
    fun `auto shows the best file for the preference, not the last played one`() {
        assertEquals(1080, versions[index()].fileId)
        assertEquals(720, versions[index(preferredQuality = "720p")].fileId)
    }

    @Test
    fun `an explicit pick wins`() {
        assertEquals(720, versions[index(explicitIndex = 1)].fileId)
    }

    @Test
    fun `auto names no version until the quality preference loads`() {
        assertEquals(-1, index(preferredQuality = null))
    }

    @Test
    fun `a picked edition keeps its file while the quality preference loads`() {
        val theatrical = FileVersion(fileId = 1, resolution = "2160p", editionRaw = "Theatrical", editionKey = "theatrical")
        val extended = FileVersion(fileId = 2, resolution = "1080p", editionRaw = "Extended", editionKey = "extended")
        val files = listOf(theatrical, extended)
        assertEquals(2, files[index(preferredQuality = null, files = files, editionId = "extended", userData = null)].fileId)
    }

    @Test
    fun `an explicit pick with no versions loaded yields the fallback`() {
        assertEquals(0, index(explicitIndex = 1, files = emptyList()))
    }

    @Test
    fun `a stale explicit pick names no version instead of another one`() {
        assertEquals(-1, index(explicitIndex = 5))
    }

    @Test
    fun `play leaves the last played file to the player and sends any other`() {
        assertNull(detailPlaybackFileId(versions[1], pinned = false, tracksPicked = false, lastFileId = 720))
        assertEquals(1080, detailPlaybackFileId(versions[0], pinned = false, tracksPicked = false, lastFileId = 720))
        assertEquals(720, detailPlaybackFileId(versions[1], pinned = true, tracksPicked = false, lastFileId = 720))
        assertEquals(720, detailPlaybackFileId(versions[1], pinned = false, tracksPicked = true, lastFileId = 720))
    }
}
