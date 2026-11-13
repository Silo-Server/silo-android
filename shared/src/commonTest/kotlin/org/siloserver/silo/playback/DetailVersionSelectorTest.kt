package org.siloserver.silo.playback

import org.siloserver.silo.model.catalog.FileVersion
import org.siloserver.silo.model.catalog.PlaybackVariant
import org.siloserver.silo.model.catalog.PlaybackVariantPart
import kotlin.test.Test
import kotlin.test.assertEquals

class DetailVersionSelectorTest {
    // The issue's example: Theatrical in 4K and 1080p, Extended in 1080p only.
    private val theatrical4k = FileVersion(1, resolution = "2160p", editionRaw = "Theatrical", editionKey = "theatrical")
    private val theatrical1080 = FileVersion(2, resolution = "1080p", editionRaw = "Theatrical", editionKey = "theatrical")
    private val extended1080 = FileVersion(3, resolution = "1080p", editionRaw = "Extended", editionKey = "extended")
    private val versions = listOf(extended1080, theatrical1080, theatrical4k)

    private fun select(
        pinnedFileId: Int? = null,
        editionId: String? = null,
        lastFileId: Int? = null,
        lastEditionKey: String? = null,
        preferredQuality: String? = "2160p",
        files: List<FileVersion> = versions,
        variants: List<PlaybackVariant> = emptyList(),
    ) = selectDetailPlaybackVersion(
        files, variants, pinnedFileId, editionId, lastFileId, lastEditionKey, preferredQuality,
    )?.fileId

    @Test
    fun nothingPlayedOpensOnTheEditionHoldingTheBestFile() {
        assertEquals(1, select())
    }

    @Test
    fun aPickedEditionKeepsAutoInsideItEvenWhenAnotherCutIsBetter() {
        assertEquals(3, select(editionId = "extended"))
    }

    @Test
    fun theLastPlayedEditionOpensWithItsBestFileForThePreference() {
        assertEquals(3, select(lastFileId = 3))
        // Last played the Theatrical 1080p file: the cut comes from history, the file from the preference.
        assertEquals(1, select(lastFileId = 2))
        // The last file is gone; its edition key still names the cut.
        assertEquals(3, select(lastFileId = 99, lastEditionKey = "extended"))
    }

    @Test
    fun aPinWins() {
        assertEquals(2, select(pinnedFileId = 2))
        assertEquals(2, select(pinnedFileId = 2, editionId = "theatrical"))
    }

    @Test
    fun withNoFileAtThePreferenceAutoTakesTheClosestAbove() {
        val theatrical720 = theatrical1080.copy(fileId = 4, resolution = "720p")
        assertEquals(4, select(editionId = "theatrical", preferredQuality = "480p", files = versions + theatrical720))
        assertEquals(2, select(editionId = "theatrical", preferredQuality = "720p"))
        // A preference that names no resolution reads as Auto.
        assertEquals(1, select(editionId = "theatrical", preferredQuality = "high"))
    }

    @Test
    fun resumeStaysOnTheLaterPartOfAMultiPartEdition() {
        val part1 = FileVersion(10, resolution = "1080p", presentationKind = "multipart_movie", presentationPartIndex = 1)
        val part2 = FileVersion(11, resolution = "1080p", presentationKind = "multipart_movie", presentationPartIndex = 2)
        val variant = PlaybackVariant(
            variantId = "standard",
            parts = listOf(
                PlaybackVariantPart(partIndex = 1, defaultFileId = 10, versions = listOf(part1)),
                PlaybackVariantPart(partIndex = 2, defaultFileId = 11, versions = listOf(part2)),
            ),
        )
        assertEquals(11, select(lastFileId = 11, files = listOf(part1, part2), variants = listOf(variant)))
        assertEquals(11, select(lastFileId = 11, editionId = "standard", files = listOf(part1, part2), variants = listOf(variant)))
        assertEquals(10, select(files = listOf(part1, part2), variants = listOf(variant)))
        // Without variants, a later part is still recognised.
        assertEquals(11, select(lastFileId = 11, files = listOf(part1, part2)))
    }
}
