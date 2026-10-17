package org.siloserver.silo.model.playback

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PlaybackQualityMenuTest {
    // The source entry is how servers before the Original removal list it.
    private val ladder = listOf(
        PlaybackAvailableQualityV3("original", 2160, 50_700, preservesSource = true),
        PlaybackAvailableQualityV3("1080p", 1080, 12_000, displayName = "1080p"),
        PlaybackAvailableQualityV3("1080p-medium", 1080, 6_000, displayName = "1080p Medium"),
        PlaybackAvailableQualityV3("720p-low", 720, 1_500, displayName = "720p Low"),
    )

    @Test
    fun menuListsAutoThenTheServerRungsInServerOrder() {
        val menu = playbackQualityMenu(ladder)

        assertEquals(listOf("auto", "1080p", "1080p-medium", "720p-low"), menu.map { it.id })
        assertEquals(listOf("Auto", "1080p", "1080p Medium", "720p Low"), menu.map { it.name })
        assertEquals(listOf(null, "12 Mbps", "6 Mbps", "1.5 Mbps"), menu.map { it.bitrateLabel })
    }

    @Test
    fun theSourceEntryIsHiddenHoweverAServerMarksIt() {
        val rung = PlaybackAvailableQualityV3("720p-medium", 720, 4_000)
        for (source in listOf(
            PlaybackAvailableQualityV3("original", 1080, 8_000, preservesSource = true),
            PlaybackAvailableQualityV3("original", 1080, 8_000, preservesSource = true, displayName = "Original"),
            PlaybackAvailableQualityV3("Original", 1080, 8_000),
        )) {
            assertEquals(listOf("auto", "720p-medium"), playbackQualityMenu(listOf(source, rung)).map { it.id })
        }
    }

    @Test
    fun aSingleRungStillGetsAutoAndASourceOnlyPlanGetsNoMenu() {
        val sole = listOf(PlaybackAvailableQualityV3("720p-medium", 720, 4_000, displayName = "720p Medium"))

        assertEquals(listOf("auto", "720p-medium"), playbackQualityMenu(sole).map { it.id })
        assertEquals(
            emptyList(),
            playbackQualityMenu(listOf(PlaybackAvailableQualityV3("original", 1080, 8_000, preservesSource = true))),
        )
        assertEquals(emptyList(), playbackQualityMenu(emptyList()))
    }

    @Test
    fun namePrefersTheServerDisplayNameThenTheLabel() {
        assertEquals("4K High", playbackQualityName(PlaybackAvailableQualityV3("2160p-high", displayName = "4K High")))
        assertEquals("480p", playbackQualityName(PlaybackAvailableQualityV3("480p", displayName = " ")))
        assertEquals("480p", playbackQualityName(PlaybackAvailableQualityV3("480p")))
    }

    @Test
    fun videoQualityPreferenceReadsOriginalAndItsAliasesAsAuto() {
        assertEquals("auto", videoQualityPreference("original"))
        assertEquals("auto", videoQualityPreference(" Original "))
        assertEquals("auto", videoQualityPreference("source"))
        assertEquals("auto", videoQualityPreference("max"))
        assertEquals("1080p-medium", videoQualityPreference("1080p-Medium"))
        assertNull(videoQualityPreference(" "))
        assertNull(videoQualityPreference(null))
    }

    @Test
    fun bitrateIsMbpsToOneDecimalAndOmittedWhenUnknown() {
        assertEquals("40 Mbps", formatPlaybackQualityBitrate(40_000))
        assertEquals("6 Mbps", formatPlaybackQualityBitrate(6_000))
        assertEquals("1.5 Mbps", formatPlaybackQualityBitrate(1_500))
        assertEquals("50.7 Mbps", formatPlaybackQualityBitrate(50_712))
        assertEquals("2 Mbps", formatPlaybackQualityBitrate(1_960))
        assertEquals("800 kbps", formatPlaybackQualityBitrate(800))
        assertNull(formatPlaybackQualityBitrate(0))
        assertNull(formatPlaybackQualityBitrate(-1))
    }

    @Test
    fun activeEntryMatchesThePreferenceExactly() {
        val menu = playbackQualityMenu(ladder)

        assertEquals("1080p-medium", activePlaybackQualityId(menu, "1080p-medium"))
        assertEquals("1080p", activePlaybackQualityId(menu, "1080p"))
    }

    @Test
    fun noPreferenceAndAutoSelectTheAutoRow() {
        val menu = playbackQualityMenu(ladder)

        assertEquals("auto", activePlaybackQualityId(menu, null))
        assertEquals("auto", activePlaybackQualityId(menu, " "))
        assertEquals("auto", activePlaybackQualityId(menu, "auto"))
    }

    @Test
    fun originalAndItsAliasesSelectAuto() {
        val menu = playbackQualityMenu(ladder)

        for (alias in listOf("original", "ORIGINAL", "source", "max")) {
            assertEquals("auto", activePlaybackQualityId(menu, alias))
        }
        assertNull(activePlaybackQualityId(menu, "unknown"))
        assertNull(activePlaybackQualityId(emptyList(), "auto"))
    }

    @Test
    fun aResolutionPreferenceTheSourcePlaysUnderLightsNoRow() {
        // The source plays as-is under a saved resolution: no row is lit, so
        // the viewer can still choose Auto, which is not what the session asked for.
        val menu = playbackQualityMenu(
            listOf(
                PlaybackAvailableQualityV3("1080p-medium", 1080, 6_000, displayName = "1080p Medium"),
                PlaybackAvailableQualityV3("720p-medium", 720, 4_000, displayName = "720p Medium"),
            ),
        )
        val source = PlaybackSourceDescriptorV3(width = 1920, height = 1080, bitrateKbps = 8_000)
        val delivered = PlaybackEffectiveRecipeV3(width = 1920, height = 1080, bitrateKbps = 8_000)

        assertNull(activePlaybackQualityId(menu, "2160p", delivered, source))
        assertNull(activePlaybackQualityId(menu, "1080p", delivered, source))
        assertEquals("1080p", playbackQualityValueLabel(menu, null, "1080p"))
        assertEquals("4K", playbackQualityValueLabel(menu, null, "2160p"))
        assertEquals("1080p Medium", playbackQualityValueLabel(menu, "1080p-medium", "1080p"))
        // Only an auto preference lights Auto.
        assertEquals("auto", activePlaybackQualityId(menu, "auto", delivered, source))
        assertEquals("720p-medium", activePlaybackQualityId(menu, "720p", source = source))
        // With no source facts the plan cannot show the source plays.
        assertEquals("1080p-medium", activePlaybackQualityId(menu, "1080p", delivered))
    }

    @Test
    fun aBitrateCapMovesAResolutionPreferenceToTheDeliveredClass() {
        val menu = playbackQualityMenu(
            listOf(
                PlaybackAvailableQualityV3("1080p-medium", 1080, 6_000),
                PlaybackAvailableQualityV3("720p-medium", 720, 4_000),
                PlaybackAvailableQualityV3("480p", 480, 1_500),
            ),
        )
        val source = PlaybackSourceDescriptorV3(width = 1920, height = 800, bitrateKbps = 8_000)

        assertEquals(
            "1080p-medium",
            activePlaybackQualityId(menu, "1080p", PlaybackEffectiveRecipeV3(width = 1920, height = 800, bitrateKbps = 6_000), source),
        )
        assertEquals(
            "720p-medium",
            activePlaybackQualityId(menu, "1080p", PlaybackEffectiveRecipeV3(width = 1280, height = 688, bitrateKbps = 4_000), source),
        )
        assertEquals(
            "480p",
            activePlaybackQualityId(menu, "1080p", PlaybackEffectiveRecipeV3(width = 854, height = 480, bitrateKbps = 1_500), source),
        )
        assertNull(
            activePlaybackQualityId(menu, "1080p", PlaybackEffectiveRecipeV3(width = 960, height = 540, bitrateKbps = 2_000), source),
        )
    }

    @Test
    fun aShorterDeliveredFrameMovesAResolutionPreferenceToTheDeliveredClass() {
        val menu = playbackQualityMenu(
            listOf(
                PlaybackAvailableQualityV3("1080p-medium", 1080, 6_000),
                PlaybackAvailableQualityV3("720p-medium", 720, 4_000),
            ),
        )
        val delivered720 = PlaybackEffectiveRecipeV3(width = 1280, height = 720, bitrateKbps = 9_000)

        // A 1080p source delivered at 720p is reduced even though the
        // delivered bitrate is not lower than the source's.
        assertEquals(
            "720p-medium",
            activePlaybackQualityId(menu, "1080p", delivered720, PlaybackSourceDescriptorV3(width = 1920, height = 1080, bitrateKbps = 8_000)),
        )
        // Or when the source bitrate is unknown.
        assertEquals(
            "720p-medium",
            activePlaybackQualityId(menu, "1080p", delivered720, PlaybackSourceDescriptorV3(width = 1920, height = 1080)),
        )
        // The same frame as the source is not a reduction.
        assertNull(
            activePlaybackQualityId(
                menu,
                "1080p",
                PlaybackEffectiveRecipeV3(width = 1920, height = 1080),
                PlaybackSourceDescriptorV3(width = 1920, height = 1080),
            ),
        )
    }
}
