package org.siloserver.silo.common.player.video

import kotlin.test.Test
import kotlin.test.assertEquals

class VideoTrackSelectionCoordinatorTest {
    @Test
    fun subtitleDescriptionAddsGeneratedAndEnhancedMarkers() {
        val coordinator = VideoTrackSelectionCoordinator()
        val track = VideoPlayerTrackEntry(
            index = 0,
            label = "English",
            language = "en",
            isSelected = false,
        )

        assertEquals("English", coordinator.describeSubtitle(track))
        assertEquals("English - AI", coordinator.describeSubtitle(track, isAiGenerated = true))
        assertEquals("English - Enhanced", coordinator.describeSubtitle(track, isEnhanced = true))
        assertEquals(
            "English - AI - Enhanced",
            coordinator.describeSubtitle(track, isAiGenerated = true, isEnhanced = true),
        )
    }
}
