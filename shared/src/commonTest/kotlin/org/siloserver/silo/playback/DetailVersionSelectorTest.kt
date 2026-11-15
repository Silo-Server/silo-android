package org.siloserver.silo.playback

import org.siloserver.silo.model.catalog.FileVersion
import org.siloserver.silo.model.catalog.LeafItemUserData
import org.siloserver.silo.model.catalog.PlaybackVariant
import org.siloserver.silo.model.catalog.PlaybackVariantPart
import org.siloserver.silo.model.catalog.playbackEditions
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
        inProgress: Boolean = false,
        preferredQuality: String? = "2160p",
        files: List<FileVersion> = versions,
        variants: List<PlaybackVariant> = emptyList(),
    ) = selectDetailPlaybackVersion(
        files, variants, pinnedFileId, editionId, lastFileId, lastEditionKey, inProgress, preferredQuality,
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
        // The edition key wins over the last file's edition; a key no edition has falls back to the file.
        assertEquals(1, select(lastFileId = 3, lastEditionKey = "theatrical"))
        assertEquals(3, select(lastFileId = 3, lastEditionKey = "imax"))
        // A present "" names the unnamed edition.
        val unnamed = FileVersion(5, resolution = "720p")
        assertEquals(5, select(lastFileId = 3, lastEditionKey = "", files = versions + unnamed))
    }

    @Test
    fun autoBreaksResolutionTiesOnHdrThenFileSizeNotBitrate() {
        val sdrBig = FileVersion(1, resolution = "1080p", fileSize = 9_000, bitrate = 90_000)
        val hdrSmall = FileVersion(2, resolution = "1080p", hdr = true, fileSize = 1_000)
        val sdrSmall = FileVersion(3, resolution = "1080p", fileSize = 1_000, bitrate = 99_000)
        assertEquals(2, select(files = listOf(sdrBig, sdrSmall, hdrSmall)))
        assertEquals(1, select(files = listOf(sdrSmall, sdrBig)))
        // Equal on everything the server sorts by: list order.
        assertEquals(3, select(files = listOf(sdrSmall, sdrBig.copy(fileSize = 1_000))))
    }

    @Test
    fun anUnknownResolutionIsALastResort() {
        val unknown = FileVersion(1, resolution = null, fileSize = 9_000)
        val above = FileVersion(2, resolution = "2160p")
        assertEquals(2, select(files = listOf(unknown, above), preferredQuality = "1080p"))
        assertEquals(2, select(files = listOf(unknown, above), preferredQuality = "auto"))
        assertEquals(1, select(files = listOf(unknown, FileVersion(3, resolution = "1440p")), preferredQuality = "1080p"))
    }

    @Test
    fun withNoHistoryEachCutsAutoFileIsComparedAcrossCuts() {
        fun cut(id: Int, key: String?, resolution: String, hdr: Boolean = false, size: Long = 1) =
            FileVersion(id, resolution = resolution, hdr = hdr, fileSize = size, editionKey = key, editionRaw = key)
        // A cut with a file within the cap beats one only above it.
        assertEquals(2, select(preferredQuality = "1080p", files = listOf(cut(1, "imax", "2160p"), cut(2, "extended", "720p"))))
        // Above the cap, the closer one wins.
        assertEquals(2, select(preferredQuality = "480p", files = listOf(cut(1, "imax", "2160p"), cut(2, "extended", "720p"))))
        // Same resolution: HDR, then edition preference (unnamed or standard, then theatrical); never file size.
        assertEquals(2, select(files = listOf(cut(1, "theatrical", "1080p"), cut(2, "directors_cut", "1080p", hdr = true))))
        assertEquals(2, select(files = listOf(cut(1, "directors_cut", "1080p", size = 9_000), cut(2, "theatrical", "1080p"))))
        assertEquals(2, select(files = listOf(cut(1, "theatrical", "1080p"), cut(2, null, "1080p"))))
        assertEquals(2, select(files = listOf(cut(1, "theatrical", "1080p"), cut(2, "standard edition", "1080p"))))
        // A full tie keeps server order.
        assertEquals(1, select(files = listOf(cut(1, "extended", "1080p"), cut(2, "directors_cut", "1080p", size = 9_000))))
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
    fun autoRanksEveryPresentationOfTheCut() {
        val single4k = FileVersion(20, resolution = "2160p", editionRaw = "Final Cut", editionKey = "final_cut")
        val cd1 = FileVersion(11, resolution = "1080p", editionRaw = "Final Cut", editionKey = "final_cut", presentationKind = "multipart_movie", presentationPartIndex = 1)
        val cd2 = cd1.copy(fileId = 12, presentationPartIndex = 2)
        val variants = listOf(
            PlaybackVariant(variantId = "final_cut|single", editionKey = "final_cut",
                parts = listOf(PlaybackVariantPart(partIndex = 1, versions = listOf(single4k)))),
            PlaybackVariant(variantId = "final_cut|multipart", editionKey = "final_cut",
                parts = listOf(
                    PlaybackVariantPart(partIndex = 1, versions = listOf(cd1)),
                    PlaybackVariantPart(partIndex = 2, versions = listOf(cd2)),
                )),
        )
        val files = listOf(cd1, cd2, single4k)
        // Last played the first CD: the cut's best file wins.
        assertEquals(20, select(lastFileId = 11, files = files, variants = variants))
        // Stopped in CD2: Resume keeps that part, and Start Over plays CD1.
        assertEquals(12, select(lastFileId = 12, inProgress = true, files = files, variants = variants))
        assertEquals(11, firstPlaybackPart(files, files.first { it.fileId == 12 }).fileId)
        // One Edition row for the cut, offering both presentations' first parts.
        val edition = playbackEditions(files, variants).single()
        assertEquals(listOf(11, 20), edition.versions.map { it.fileId })
        assertEquals(setOf(11, 12, 20), edition.fileIds)
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
        assertEquals(11, select(lastFileId = 11, inProgress = true, files = listOf(part1, part2), variants = listOf(variant)))
        assertEquals(11, select(lastFileId = 11, inProgress = true, editionId = "", files = listOf(part1, part2), variants = listOf(variant)))
        assertEquals(10, select(files = listOf(part1, part2), variants = listOf(variant)))
        // Without variants, a later part is still recognised, and Auto starts at part 1
        // even when a later part has the better file.
        assertEquals(11, select(lastFileId = 11, inProgress = true, files = listOf(part1, part2)))
        assertEquals(10, select(files = listOf(part1, part2.copy(bitrate = 9_000))))
    }

    @Test
    fun onlyASavedPositionKeepsALaterPart() {
        val part1 = FileVersion(10, resolution = "1080p", presentationKind = "multipart_movie", presentationPartIndex = 1)
        val part2 = part1.copy(fileId = 11, presentationPartIndex = 2)
        fun selectFor(userData: LeafItemUserData) =
            select(lastFileId = 11, inProgress = userData.hasResumePoint, files = listOf(part1, part2))
        // Finished on the last part: the server reset the position, so a rewatch starts at part 1.
        assertEquals(10, selectFor(LeafItemUserData(played = true, positionSeconds = 0.0, lastFileId = 11)))
        // A rewatch stopped in part 2 keeps it, though the title stays marked played.
        assertEquals(11, selectFor(LeafItemUserData(played = true, positionSeconds = 600.0, lastFileId = 11)))
    }
}
