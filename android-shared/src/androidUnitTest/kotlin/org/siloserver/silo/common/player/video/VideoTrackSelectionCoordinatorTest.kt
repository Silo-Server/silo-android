package org.siloserver.silo.common.player.video

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VideoTrackSelectionCoordinatorTest {
    private val sourceFile = java.io.File(
        "src/androidMain/kotlin/org/siloserver/silo/common/player/video/VideoTrackSelectionCoordinator.kt",
    )

    @Test
    fun selectingSubtitleOffDisablesTextTracks() {
        val source = sourceFile.readText()

        assertTrue(
            source.contains("subtitleManager.selectSubtitle(player, -1)"),
            "subtitle Off must clear text track selection through SubtitleManager",
        )
    }

    @Test
    fun selectingExternalSubtitleRemountsAndPreservesPositionThroughSharedRefresh() {
        val source = sourceFile.readText()

        assertTrue(
            source.contains("val mountedMediaSpec = mediaSpec ?: return false"),
            "external subtitle selection must report not-ready instead of remounting before media exists",
        )
        assertTrue(
            source.contains("refreshMountedVideoMedia("),
            "external subtitle selection must remount via the shared refresh helper",
        )
        assertTrue(
            source.contains("mountedMediaSpec.copy(subtitles = listOf(subtitle))"),
            "external subtitle selection must remount with the selected subtitle configuration",
        )
    }

    @Test
    fun selectingEmbeddedSubtitleUsesMedia3TrackSelection() {
        val source = sourceFile.readText()

        assertTrue(
            source.contains("subtitleManager.selectSubtitle(player, selectedTrack.index)"),
            "embedded subtitle selection must update Media3 text track selection",
        )
    }

    @Test
    fun reselectingMountedSubtitleDoesNotRemountMedia() {
        val source = sourceFile.readText()
        val methodBody = source
            .substringAfter("fun selectMountedSubtitle(")
            .substringBefore("fun selectAudioTrack(")

        assertTrue(
            methodBody.contains("subtitleManager.selectSubtitle(player, identity)"),
            "mounted subtitle re-selection must use typed SubtitleManager selection",
        )
        assertTrue(
            !methodBody.contains("refreshMountedVideoMedia("),
            "mounted subtitle re-selection must not remount the MediaItem",
        )
    }

    @Test
    fun selectingAudioTrackUsesAudioTrackManager() {
        val source = sourceFile.readText()

        assertTrue(
            source.contains("audioTrackManager.selectAudioTrack(player, selectedTrack.index)"),
            "audio track selection must update Media3 audio track selection",
        )
    }

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
