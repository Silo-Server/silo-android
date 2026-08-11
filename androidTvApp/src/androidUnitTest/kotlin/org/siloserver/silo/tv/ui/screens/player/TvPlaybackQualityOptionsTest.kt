package org.siloserver.silo.tv.ui.screens.player

import org.siloserver.silo.model.playback.PlaybackAvailableQualityV3
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TvPlaybackQualityOptionsTest {
    @Test
    fun authoritativeMenuPreservesServerMembershipOrderAndLabels() {
        val options = authoritativePlaybackQualityOptions(
            available = listOf(
                PlaybackAvailableQualityV3("original", 1080, 8_000, preservesSource = true),
                PlaybackAvailableQualityV3("720p", 720, 4_000),
            ),
            selectedLabel = "720p",
        )

        assertEquals(listOf("original", "720p"), options.map { it.id })
        assertEquals(listOf("original", "720p"), options.map { it.label })
        assertFalse(options.first().isSelected)
        assertTrue(options.last().isSelected)
    }

    @Test
    fun autoIntentDoesNotInventASelectedServerRow() {
        val options = authoritativePlaybackQualityOptions(
            available = listOf(
                PlaybackAvailableQualityV3("original", 1080, 8_000, preservesSource = true),
            ),
            selectedLabel = "auto",
        )

        assertEquals(listOf("original"), options.map { it.id })
        assertFalse(options.single().isSelected)
    }
}
