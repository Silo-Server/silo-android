package org.siloserver.silo.model.playback

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PlaybackQualityMenuTest {
    private val ladder = listOf(
        PlaybackAvailableQualityV3("original", 2160, 50_700, preservesSource = true),
        PlaybackAvailableQualityV3("1080p", 1080, 12_000, displayName = "1080p"),
        PlaybackAvailableQualityV3("1080p-medium", 1080, 6_000, displayName = "1080p Medium"),
        PlaybackAvailableQualityV3("720p-low", 720, 1_500, displayName = "720p Low"),
    )

    @Test
    fun menuListsAutoThenEveryServerEntryInServerOrder() {
        val menu = playbackQualityMenu(ladder)

        assertEquals(listOf("auto", "original", "1080p", "1080p-medium", "720p-low"), menu.map { it.id })
        assertEquals(listOf("Auto", "Original", "1080p", "1080p Medium", "720p Low"), menu.map { it.name })
        assertEquals(
            listOf(null, "50.7 Mbps", "12 Mbps", "6 Mbps", "1.5 Mbps"),
            menu.map { it.bitrateLabel },
        )
    }

    @Test
    fun aSingleEntryStillGetsAutoAndNoEntriesGetNoMenu() {
        val sole = listOf(PlaybackAvailableQualityV3("original", 1080, 8_000, preservesSource = true))

        assertEquals(listOf("auto", "original"), playbackQualityMenu(sole).map { it.id })
        assertEquals(listOf("Auto", "Original"), playbackQualityMenu(sole).map { it.name })
        assertEquals(emptyList(), playbackQualityMenu(emptyList()))
    }

    @Test
    fun namePrefersTheServerDisplayNameThenOriginalThenTheLabel() {
        assertEquals("4K High", playbackQualityName(PlaybackAvailableQualityV3("2160p-high", displayName = "4K High")))
        assertEquals("Original", playbackQualityName(PlaybackAvailableQualityV3("original", preservesSource = true)))
        assertEquals("Original", playbackQualityName(PlaybackAvailableQualityV3("original", displayName = " ", preservesSource = true)))
        assertEquals("480p", playbackQualityName(PlaybackAvailableQualityV3("480p")))
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
        assertEquals("original", activePlaybackQualityId(menu, "ORIGINAL"))
    }

    @Test
    fun noPreferenceAndAutoSelectTheAutoRow() {
        val menu = playbackQualityMenu(ladder)

        assertEquals("auto", activePlaybackQualityId(menu, null))
        assertEquals("auto", activePlaybackQualityId(menu, " "))
        assertEquals("auto", activePlaybackQualityId(menu, "auto"))
    }

    @Test
    fun aSoleEntryIsActiveForAnyPreferenceButAuto() {
        val menu = playbackQualityMenu(listOf(PlaybackAvailableQualityV3("original", preservesSource = true)))

        assertEquals("auto", activePlaybackQualityId(menu, null))
        assertEquals("auto", activePlaybackQualityId(menu, "auto"))
        assertEquals("original", activePlaybackQualityId(menu, "720p-low"))
        assertEquals("original", activePlaybackQualityId(menu, "1080p"))
        assertNull(activePlaybackQualityId(emptyList(), "auto"))
    }

    @Test
    fun sourceAliasesSelectTheOriginalEntry() {
        val menu = playbackQualityMenu(ladder)

        assertEquals("original", activePlaybackQualityId(menu, "max"))
        assertEquals("original", activePlaybackQualityId(menu, "source"))
        assertNull(activePlaybackQualityId(menu, "unknown"))
    }

    @Test
    fun aResolutionPreferenceAtOrAboveTheSourceSelectsOriginal() {
        val menu = playbackQualityMenu(
            listOf(
                PlaybackAvailableQualityV3("original", 1080, 8_000, preservesSource = true),
                PlaybackAvailableQualityV3("1080p-medium", 1080, 6_000, displayName = "1080p Medium"),
                PlaybackAvailableQualityV3("720p-medium", 720, 4_000, displayName = "720p Medium"),
            ),
        )

        assertEquals("original", activePlaybackQualityId(menu, "2160p"))
        assertEquals("original", activePlaybackQualityId(menu, "1080p"))
        assertEquals("720p-medium", activePlaybackQualityId(menu, "720p"))
    }

    @Test
    fun aBitrateCapMovesAResolutionPreferenceToTheDeliveredClass() {
        val menu = playbackQualityMenu(
            listOf(
                PlaybackAvailableQualityV3("original", 1080, 8_000, preservesSource = true),
                PlaybackAvailableQualityV3("1080p-medium", 1080, 6_000),
                PlaybackAvailableQualityV3("720p-medium", 720, 4_000),
                PlaybackAvailableQualityV3("480p", 480, 1_500),
            ),
        )

        assertEquals(
            "1080p-medium",
            activePlaybackQualityId(menu, "1080p", PlaybackEffectiveRecipeV3(width = 1920, height = 800, bitrateKbps = 6_000)),
        )
        assertEquals(
            "720p-medium",
            activePlaybackQualityId(menu, "1080p", PlaybackEffectiveRecipeV3(width = 1280, height = 688, bitrateKbps = 4_000)),
        )
        assertEquals(
            "480p",
            activePlaybackQualityId(menu, "1080p", PlaybackEffectiveRecipeV3(width = 854, height = 480, bitrateKbps = 1_500)),
        )
        assertNull(
            activePlaybackQualityId(menu, "1080p", PlaybackEffectiveRecipeV3(width = 960, height = 540, bitrateKbps = 2_000)),
        )
    }
}
