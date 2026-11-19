package org.siloserver.silo.common.player

import org.junit.Assert.assertEquals
import org.junit.Test

class TitleSafeInsetTest {
    @Test
    fun pullsTheSurfaceInOnEveryEdge() {
        val rect = SubtitleVideoRect(left = 0, top = 0, width = 1920, height = 1080)
        val safe = rect.insetByTitleSafe(0.05f)
        assertEquals(96, safe.left)
        assertEquals(54, safe.top)
        assertEquals(1728, safe.width)
        assertEquals(972, safe.height)
    }

    @Test
    fun zeroIsANoOpSoPhonesAreUntouched() {
        val rect = SubtitleVideoRect(left = 10, top = 20, width = 800, height = 400)
        assertEquals(rect, rect.insetByTitleSafe(0f))
    }

    @Test
    fun refusesAnInsetThatWouldConsumeTheSurface() {
        val rect = SubtitleVideoRect(left = 0, top = 0, width = 100, height = 100)
        assertEquals(rect, rect.insetByTitleSafe(0.6f))
    }

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
        data class Case(val name: String, val overlay: SubtitleVideoRect, val canvasHeight: Int, val paddingBottom: Int, val expected: SubtitleVideoRect?)
        val cases = listOf(
            Case("controls hidden", titleSafeOverlay, 972, 0, null),
            Case("controls lift the title-safe canvas", titleSafeOverlay, 972, 378,
                SubtitleVideoRect(left = 0, top = 0, width = 1920, height = 648)),
            Case("lift line below the video", SubtitleVideoRect(left = 0, top = 0, width = 1920, height = 800), 940, 100, null),
        )
        for (case in cases) {
            assertEquals(case.name, case.expected, libassOverlayClipInOverlay(case.overlay, case.canvasHeight, case.paddingBottom))
        }
    }
}
