package org.siloserver.silo.tv.ui.screens.player

import org.siloserver.silo.model.playback.PlaybackAvailableQualityV3
import org.siloserver.silo.model.playback.PlaybackEffectiveRecipeV3
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TvPlaybackQualityOptionsTest {
    @Test
    fun displayNamesDoNotChangeSelectionIds() {
        val options = authoritativePlaybackQualityOptions(
            listOf(
                PlaybackAvailableQualityV3("1080p-medium", displayName = "1080p Medium"),
                PlaybackAvailableQualityV3("original", displayName = " ", preservesSource = true),
            ),
            selectedLabel = "1080p-medium",
        )
        assertEquals(listOf("auto", "1080p-medium", "original"), options.map { it.id })
        assertEquals(listOf("Auto", "1080p Medium", "Original"), options.map { it.label })
        assertEquals(listOf("1080p-medium"), options.filter { it.isSelected }.map { it.id })
    }

    @Test
    fun menuPutsAutoFirstThenServerEntriesWithBitrates() {
        val options = authoritativePlaybackQualityOptions(
            available = listOf(
                PlaybackAvailableQualityV3("original", 2160, 50_700, preservesSource = true),
                PlaybackAvailableQualityV3("1080p", 1080, 12_000, displayName = "1080p"),
                PlaybackAvailableQualityV3("720p-low", 720, 1_500, displayName = "720p Low"),
            ),
            selectedLabel = "1080p",
        )

        assertEquals(listOf("auto", "original", "1080p", "720p-low"), options.map { it.id })
        assertEquals(listOf("Auto", "Original", "1080p", "720p Low"), options.map { it.label })
        assertEquals(listOf(null, "50.7 Mbps", "12 Mbps", "1.5 Mbps"), options.map { it.bitrateLabel })
        assertEquals(listOf(null, "2160p", "1080p", "720p"), options.map { it.resolution })
        assertEquals(listOf("1080p"), options.filter { it.isSelected }.map { it.id })
    }

    @Test
    fun autoIntentSelectsTheAutoRowNotAServerEntry() {
        val options = authoritativePlaybackQualityOptions(
            available = listOf(
                PlaybackAvailableQualityV3("original", 1080, 8_000, preservesSource = true),
                PlaybackAvailableQualityV3("720p-medium", 720, 4_000),
            ),
            selectedLabel = "auto",
        )

        assertEquals(listOf("auto"), options.filter { it.isSelected }.map { it.id })
    }

    @Test
    fun storedResolutionPreferenceMarksTheEntryThePlannerUsed() {
        val options = authoritativePlaybackQualityOptions(
            available = listOf(
                PlaybackAvailableQualityV3("original", 2160, 40_000, preservesSource = true),
                PlaybackAvailableQualityV3("1080p-medium", 1080, 6_000),
                PlaybackAvailableQualityV3("720p-medium", 720, 4_000),
            ),
            selectedLabel = "1080p",
            delivered = PlaybackEffectiveRecipeV3(width = 1920, height = 1080, bitrateKbps = 6_000),
        )

        assertEquals(listOf("1080p-medium"), options.filter { it.isSelected }.map { it.id })
    }

    @Test
    fun aSingleEntryListsAutoAndOriginal() {
        val available = listOf(PlaybackAvailableQualityV3("original", 1080, 8_000, preservesSource = true))

        val auto = authoritativePlaybackQualityOptions(available, selectedLabel = "auto")
        assertEquals(listOf("auto", "original"), auto.map { it.id })
        assertEquals(listOf("Auto", "Original"), auto.map { it.label })
        assertEquals(listOf("auto"), auto.filter { it.isSelected }.map { it.id })

        val pinned = authoritativePlaybackQualityOptions(available, selectedLabel = "720p-medium")
        assertEquals(listOf("original"), pinned.filter { it.isSelected }.map { it.id })
    }

    @Test
    fun noPlanEntriesMeansNoMenu() {
        assertTrue(authoritativePlaybackQualityOptions(emptyList(), selectedLabel = "auto").isEmpty())
    }
}
