package org.siloserver.silo.tv.ui.screens.player

import org.siloserver.silo.model.playback.PlaybackAvailableQualityV3
import org.siloserver.silo.model.playback.PlaybackEffectiveRecipeV3
import org.siloserver.silo.model.playback.PlaybackSourceDescriptorV3
import org.siloserver.silo.model.playback.ReportedPlaybackQuality
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TvPlaybackQualityOptionsTest {
    @Test
    fun displayNamesDoNotChangeSelectionIds() {
        val options = authoritativePlaybackQualityOptions(
            listOf(
                PlaybackAvailableQualityV3("1080p-medium", displayName = "1080p Medium"),
                PlaybackAvailableQualityV3("720p-low", displayName = " "),
            ),
            selectedLabel = "1080p-medium",
        )
        assertEquals(listOf("auto", "1080p-medium", "720p-low"), options.map { it.id })
        assertEquals(listOf("Auto", "1080p Medium", "720p-low"), options.map { it.label })
        assertEquals(listOf("1080p-medium"), options.filter { it.isSelected }.map { it.id })
    }

    @Test
    fun menuPutsAutoFirstThenServerRungsWithBitratesAndHidesTheSource() {
        val options = authoritativePlaybackQualityOptions(
            available = listOf(
                PlaybackAvailableQualityV3("original", 2160, 50_700, preservesSource = true),
                PlaybackAvailableQualityV3("1080p", 1080, 12_000, displayName = "1080p"),
                PlaybackAvailableQualityV3("720p-low", 720, 1_500, displayName = "720p Low"),
            ),
            selectedLabel = "1080p",
        )

        assertEquals(listOf("auto", "1080p", "720p-low"), options.map { it.id })
        assertEquals(listOf("Auto", "1080p", "720p Low"), options.map { it.label })
        assertEquals(listOf(null, "12 Mbps", "1.5 Mbps"), options.map { it.bitrateLabel })
        assertEquals(listOf(null, "1080p", "720p"), options.map { it.resolution })
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
    fun aStoredOriginalSelectsAutoAndASourceUnderASavedResolutionSelectsNothing() {
        val available = listOf(
            PlaybackAvailableQualityV3("original", 1080, 8_000, preservesSource = true),
            PlaybackAvailableQualityV3("720p-medium", 720, 4_000),
        )
        val source = PlaybackSourceDescriptorV3(width = 1920, height = 1080, bitrateKbps = 8_000)
        val sourcePlays = PlaybackEffectiveRecipeV3(width = 1920, height = 1080, bitrateKbps = 8_000)

        val original = authoritativePlaybackQualityOptions(available, selectedLabel = "original", source = source)
        assertEquals(listOf("auto"), original.filter { it.isSelected }.map { it.id })

        // The source plays as-is under the saved 1080p: no row is lit, so the
        // viewer can still choose Auto.
        val underCap = authoritativePlaybackQualityOptions(
            available,
            selectedLabel = "1080p",
            delivered = sourcePlays,
            source = source,
        )
        assertEquals(emptyList(), underCap.filter { it.isSelected }.map { it.id })

        val auto = authoritativePlaybackQualityOptions(available, selectedLabel = "auto", delivered = sourcePlays, source = source)
        assertEquals(listOf("auto"), auto.filter { it.isSelected }.map { it.id })
    }

    @Test
    fun siloCastReportsNoRowWhenTheServerNamesNone() {
        val available = listOf(
            PlaybackAvailableQualityV3("1080p-low", 1080, 3_000),
            PlaybackAvailableQualityV3("720p-medium", 720, 2_000),
        )

        // A fixed preference whose plan delivers no entry: remotes check
        // nothing, not Auto (whose row id is "auto", legacy "-1").
        val none = authoritativePlaybackQualityOptions(
            available,
            selectedLabel = "1080p",
            reported = ReportedPlaybackQuality(null),
        )
        assertEquals("", siloCastActiveQualityId(none))

        val reported = authoritativePlaybackQualityOptions(
            available,
            selectedLabel = "1080p",
            reported = ReportedPlaybackQuality("720p-medium"),
        )
        assertEquals("720p-medium", siloCastActiveQualityId(reported))
    }

    @Test
    fun aSourceOnlyPlanHasNoMenu() {
        val available = listOf(PlaybackAvailableQualityV3("original", 1080, 8_000, preservesSource = true))

        assertTrue(authoritativePlaybackQualityOptions(available, selectedLabel = "auto").isEmpty())
    }

    @Test
    fun noPlanEntriesMeansNoMenu() {
        assertTrue(authoritativePlaybackQualityOptions(emptyList(), selectedLabel = "auto").isEmpty())
    }
}
