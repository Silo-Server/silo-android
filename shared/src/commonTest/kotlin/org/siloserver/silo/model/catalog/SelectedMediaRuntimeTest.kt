package org.siloserver.silo.model.catalog

import kotlin.test.Test
import kotlin.test.assertEquals

class SelectedMediaRuntimeTest {
    private val theatrical = FileVersion(fileId = 1, duration = 13_740.0)
    private val directorsCut = FileVersion(fileId = 2, duration = 15_060.0)

    @Test
    fun selectedSinglePartVersionOverridesEditorialRuntimeImmediately() {
        val detail = detail(versions = listOf(theatrical, directorsCut))

        assertEquals(229, selectedMediaRuntimeMinutes(detail, theatrical))
        assertEquals(251, selectedMediaRuntimeMinutes(detail, directorsCut))
    }

    @Test
    fun multipartVariantUsesCombinedDuration() {
        val partOne = FileVersion(fileId = 3, duration = 3_600.0)
        val partTwo = FileVersion(fileId = 4, duration = 3_000.0)
        val detail = detail(
            versions = listOf(partOne, partTwo),
            playbackVariants = listOf(
                PlaybackVariant(
                    variantId = "extended",
                    partCount = 2,
                    totalDuration = 6_600.0,
                    parts = listOf(
                        PlaybackVariantPart(0, versions = listOf(partOne)),
                        PlaybackVariantPart(1, versions = listOf(partTwo)),
                    ),
                ),
            ),
        )

        assertEquals(110, selectedMediaRuntimeMinutes(detail, partOne))
    }

    @Test
    fun singlePartVariantNeverReplacesSelectedFileDurationWithVariantTotal() {
        val detail = detail(
            versions = listOf(directorsCut),
            playbackVariants = listOf(
                PlaybackVariant(
                    variantId = "single",
                    partCount = 1,
                    totalDuration = 99_999.0,
                    parts = listOf(PlaybackVariantPart(0, versions = listOf(directorsCut))),
                ),
            ),
        )

        assertEquals(251, selectedMediaRuntimeMinutes(detail, directorsCut))
    }

    @Test
    fun unusableOrMissingSelectedDurationFallsBackToEditorialRuntime() {
        listOf(0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY).forEachIndexed { index, duration ->
            assertEquals(
                229,
                selectedMediaRuntimeMinutes(detail(), FileVersion(fileId = index, duration = duration)),
            )
        }
        assertEquals(229, selectedMediaRuntimeMinutes(detail(), null))
    }

    @Test
    fun missingPlaybackVariantsRemainsBackwardCompatible() {
        assertEquals(251, selectedMediaRuntimeMinutes(detail(), directorsCut))
    }

    private fun detail(
        versions: List<FileVersion> = emptyList(),
        playbackVariants: List<PlaybackVariant> = emptyList(),
    ) = ItemDetail(
        contentId = "movie",
        type = "movie",
        title = "Once Upon a Time in America",
        runtime = 229,
        versions = versions,
        playbackVariants = playbackVariants,
    )
}
