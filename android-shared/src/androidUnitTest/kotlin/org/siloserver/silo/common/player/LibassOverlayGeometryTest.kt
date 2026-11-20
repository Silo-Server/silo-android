package org.siloserver.silo.common.player

import org.junit.Assert.assertEquals
import org.junit.Test

class LibassOverlayGeometryTest {
    /**
     * libass places ASS events in the script's own PlayRes space, scaled to
     * the overlay it draws on, so the overlay must cover the whole video
     * whatever insets the text-cue canvas takes. Rects are in the content
     * frame's space; the result is in the canvas's.
     */
    @Test
    fun theAssOverlayCoversTheWholeVideoWhateverTheCanvasInsets() {
        val video = SubtitleVideoRect(left = 0, top = 0, width = 1920, height = 1080)
        val letterboxed = SubtitleVideoRect(left = 0, top = 140, width = 1920, height = 800)
        data class Case(val name: String, val video: SubtitleVideoRect, val canvas: SubtitleVideoRect, val expected: SubtitleVideoRect?)
        val cases = listOf(
            Case("TV title-safe canvas", video, video.insetByTitleSafe(0.05f),
                SubtitleVideoRect(left = -96, top = -54, width = 1920, height = 1080)),
            Case("Bottom canvas reaching into the bar", letterboxed, letterboxed.copy(height = 940),
                SubtitleVideoRect(left = 0, top = 0, width = 1920, height = 800)),
            Case("title-safe Bottom canvas reaching into the bar", letterboxed,
                letterboxed.insetByTitleSafe(0.05f).let { it.copy(height = 1080 - it.top) },
                SubtitleVideoRect(left = -96, top = -40, width = 1920, height = 800)),
            Case("canvas is the video", video, video, null),
        )
        for (case in cases) {
            assertEquals(case.name, case.expected, libassOverlayRectInCanvas(case.video, case.canvas))
        }
        // The phone's docked menu pads the canvas's right side; ASS stays left of it.
        assertEquals(
            SubtitleVideoRect(left = 0, top = 0, width = 1200, height = 800),
            libassOverlayRectInCanvas(letterboxed, letterboxed.copy(height = 940), canvasPaddingRight = 720),
        )
    }

    /**
     * While the TV's controls are up they pad the canvas's bottom so text cues
     * rise above them. The full-video ASS overlay cannot rise, so only what
     * lies below that line is hidden; its left, right and top edges stay
     * visible. Result in the overlay's own coordinates, null for no clip.
     */
    @Test
    fun theControlsLiftHidesOnlyTheAssOverlayBelowIt() {
        val titleSafeOverlay = SubtitleVideoRect(left = -96, top = -54, width = 1920, height = 1080)
        data class Case(val name: String, val overlay: SubtitleVideoRect, val canvasHeight: Int, val paddingBottom: Int, val expected: Int?)
        val cases = listOf(
            Case("controls hidden", titleSafeOverlay, 972, 0, null),
            Case("controls lift the title-safe canvas", titleSafeOverlay, 972, 378, 648),
            Case("lift line below the video", SubtitleVideoRect(left = 0, top = 0, width = 1920, height = 800), 940, 100, null),
        )
        for (case in cases) {
            assertEquals(case.name, case.expected, libassOverlayVisibleHeight(case.overlay, case.canvasHeight, case.paddingBottom))
        }
    }
}
