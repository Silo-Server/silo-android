package org.siloserver.silo.common.player

import android.graphics.Bitmap
import androidx.annotation.OptIn
import androidx.media3.common.text.Cue
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.UnstableApi
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.siloserver.silo.model.settings.SubtitleAppearance
import org.siloserver.silo.model.settings.SubtitleFontSizePreset
import org.siloserver.silo.model.settings.SubtitlePositionPreset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Media3's `SubtitlePainter` positions and sizes a bitmap cue from the cue's own
 * fields only — `setStyle`, `setFixedTextSize` and `setBottomPaddingFraction` are
 * read by the text branch alone. These lock the geometry rewrite that makes the
 * Position and Size presets reach PGS/DVB captions.
 */
@OptIn(UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
class SubtitleBitmapCueAppearanceTest {

    /** A PGS-shaped cue: left/top fractions, START anchors, LINE_TYPE_FRACTION. */
    private fun pgsCue(
        position: Float = 0.2f,
        line: Float = 0.8f,
        size: Float = 0.6f,
        bitmapHeight: Float = 0.1f,
        positionAnchor: Int = Cue.ANCHOR_TYPE_START,
        lineAnchor: Int = Cue.ANCHOR_TYPE_START,
        lineType: Int = Cue.LINE_TYPE_FRACTION,
    ): Cue = Cue.Builder()
        .setBitmap(Bitmap.createBitmap(64, 16, Bitmap.Config.ARGB_8888))
        .setPosition(position)
        .setPositionAnchor(positionAnchor)
        .setLine(line, lineType)
        .setLineAnchor(lineAnchor)
        .setSize(size)
        .setBitmapHeight(bitmapHeight)
        .build()

    private fun appearance(
        position: SubtitlePositionPreset = SubtitlePositionPreset.Bottom,
        fontSize: SubtitleFontSizePreset = SubtitleFontSizePreset.Medium,
    ) = SubtitleAppearance(position = position, fontSize = fontSize)

    @Test
    fun bottomPresetPutsTheCuesBottomEdgeAtTheTextPathsPadding() {
        val remapped = remapBitmapCue(
            cue = pgsCue(line = 0.5f, bitmapHeight = 0.1f),
            appearance = appearance(position = SubtitlePositionPreset.Bottom),
            titleSafeFraction = 0f,
        )

        // Bottom padding 0.06 (no title-safe inset) => bottom edge at 0.94, top (START anchor) at 0.84.
        assertEquals(0.84f, remapped.line, absoluteTolerance = 1e-4f)
        assertEquals(Cue.LINE_TYPE_FRACTION, remapped.lineType)
    }

    @Test
    fun positionPresetsMoveTheCueMonotonicallyUpTheScreen() {
        val cue = pgsCue(bitmapHeight = 0.1f)
        val bottom = remapBitmapCue(cue, appearance(SubtitlePositionPreset.Bottom), 0f).line
        val lowerThird = remapBitmapCue(cue, appearance(SubtitlePositionPreset.LowerThird), 0f).line
        val top = remapBitmapCue(cue, appearance(SubtitlePositionPreset.Top), 0f).line

        assertEquals(0.84f, bottom, absoluteTolerance = 1e-4f)
        assertEquals(0.72f, lowerThird, absoluteTolerance = 1e-4f)
        // Top is anchored from the top, not derived from a bottom padding.
        assertEquals(SUBTITLE_TOP_LINE_FRACTION, top, absoluteTolerance = 1e-4f)
        assertTrue(top < lowerThird && lowerThird < bottom)
    }

    @Test
    fun titleSafeInsetIsCompensatedExactlyAsForText() {
        val remapped = remapBitmapCue(
            cue = pgsCue(bitmapHeight = 0.1f),
            appearance = appearance(SubtitlePositionPreset.Bottom),
            titleSafeFraction = 0.05f,
        )

        // Physical 6% inside a 5% title-safe inset: (0.06 - 0.05) / 0.90.
        val padding = (0.06f - 0.05f) / 0.90f
        assertEquals(1f - padding - 0.1f, remapped.line, absoluteTolerance = 1e-4f)
    }

    /**
     * The screen-anchored Bottom preset: on a 2.39:1 title the canvas reaches
     * from the title-safe line down into the letterbox bar (902px of a 1080
     * player), so the fraction that puts the caption 6% above the SCREEN bottom
     * is 64.8/902, not the picture-relative one. The bitmap path has to take the
     * same fraction the text path is given or the two kinds of cue split apart.
     */
    @Test
    fun bottomBitmapCuesTakeTheCanvasFractionTheTextPathIsGiven() {
        val canvasPadding = 64.8f / 902f
        val remapped = remapBitmapCue(
            cue = pgsCue(bitmapHeight = 0.1f),
            appearance = appearance(SubtitlePositionPreset.Bottom),
            titleSafeFraction = 0.05f,
            bottomPaddingFraction = canvasPadding,
        )

        assertEquals(1f - canvasPadding - 0.1f, remapped.line, absoluteTolerance = 1e-4f)
        // 40 (canvas top in the frame) + 138 (frame top) + 902 * (bottom edge)
        // = 1015.2 on a 1080 screen: 6% up, exactly where the text lands.
        val bottomEdgeOnScreen = 138f + 40f + 902f * (remapped.line + remapped.bitmapHeight)
        assertEquals(1015.2f, bottomEdgeOnScreen, absoluteTolerance = 0.5f)
    }

    @Test
    fun lowerThirdBitmapCuesStayPictureAnchoredOnTheSameLetterboxedFrame() {
        // Lower Third's canvas is the picture (1728x723 at 96,40 in a frame
        // whose own top is 138), so the fraction it is handed is the
        // picture-relative one and nothing about the bar reaches it.
        val fraction = subtitleBottomPaddingFractionForCanvas(
            position = SubtitlePositionPreset.LowerThird,
            titleSafeFraction = 0.05f,
            canvasHeight = 723,
            canvasBottomInPlayerSpace = 138 + 40 + 723,
            playerHeight = 1080,
        )
        val remapped = remapBitmapCue(
            cue = pgsCue(bitmapHeight = 0.1f),
            appearance = appearance(SubtitlePositionPreset.LowerThird),
            titleSafeFraction = 0.05f,
            bottomPaddingFraction = fraction,
        )

        assertEquals((0.18f - 0.05f) / 0.90f, fraction, absoluteTolerance = 1e-4f)
        assertEquals(1f - fraction - 0.1f, remapped.line, absoluteTolerance = 1e-4f)
    }

    @Test
    fun topBitmapCuesIgnoreTheBottomCanvasFractionEntirely() {
        val remapped = remapBitmapCue(
            cue = pgsCue(bitmapHeight = 0.1f),
            appearance = appearance(SubtitlePositionPreset.Top),
            titleSafeFraction = 0.05f,
            bottomPaddingFraction = 64.8f / 902f,
        )

        assertEquals(SUBTITLE_TOP_LINE_FRACTION, remapped.line, absoluteTolerance = 1e-4f)
    }

    @Test
    fun sizePresetsScaleWidthAndHeightAroundTheCuesHorizontalCentre() {
        val cue = pgsCue(position = 0.2f, size = 0.6f, bitmapHeight = 0.1f)

        val large = remapBitmapCue(cue, appearance(fontSize = SubtitleFontSizePreset.Large), 0f)
        assertEquals(0.6f * 1.15f, large.size, absoluteTolerance = 1e-4f)
        assertEquals(0.1f * 1.15f, large.bitmapHeight, absoluteTolerance = 1e-4f)
        // Centre was 0.5; the wider cue keeps it.
        assertEquals(0.5f, large.position + large.size / 2f, absoluteTolerance = 1e-4f)

        val small = remapBitmapCue(cue, appearance(fontSize = SubtitleFontSizePreset.Small), 0f)
        assertEquals(0.6f * 0.85f, small.size, absoluteTolerance = 1e-4f)
        assertEquals(0.5f, small.position + small.size / 2f, absoluteTolerance = 1e-4f)
    }

    @Test
    fun sizeLadderIsMonotonicAndMediumIsTheAuthoredSize() {
        val scales = SubtitleFontSizePreset.entries.map(::bitmapCueScaleFor)
        assertEquals(1f, bitmapCueScaleFor(SubtitleFontSizePreset.Medium))
        assertEquals(scales.sorted(), scales)
    }

    @Test
    fun mediumSizeLeavesTheAuthoredWidthAlone() {
        val remapped = remapBitmapCue(
            cue = pgsCue(size = 0.6f, bitmapHeight = 0.1f),
            appearance = appearance(fontSize = SubtitleFontSizePreset.Medium),
            titleSafeFraction = 0f,
        )

        assertEquals(0.6f, remapped.size, absoluteTolerance = 1e-4f)
        assertEquals(0.1f, remapped.bitmapHeight, absoluteTolerance = 1e-4f)
        assertEquals(0.2f, remapped.position, absoluteTolerance = 1e-4f)
    }

    @Test
    fun scalingUpNeverPushesTheCueOffScreen() {
        val remapped = remapBitmapCue(
            cue = pgsCue(position = 0.05f, size = 0.9f, bitmapHeight = 0.3f),
            appearance = appearance(fontSize = SubtitleFontSizePreset.XXLarge),
            titleSafeFraction = 0f,
        )

        assertTrue(remapped.size <= 1f, "width ${remapped.size}")
        assertTrue(remapped.bitmapHeight <= 1f, "height ${remapped.bitmapHeight}")
        assertTrue(remapped.position >= 0f)
        assertTrue(remapped.position + remapped.size <= 1.0001f)
        assertTrue(remapped.line >= 0f)
        assertTrue(remapped.line + remapped.bitmapHeight <= 1.0001f)
    }

    @Test
    fun aTallCueKeepsTheTopMarginInsteadOfLeavingTheSurface() {
        val remapped = remapBitmapCue(
            cue = pgsCue(bitmapHeight = 0.95f),
            appearance = appearance(SubtitlePositionPreset.Top),
            titleSafeFraction = 0f,
        )

        assertEquals(SUBTITLE_TOP_LINE_FRACTION, remapped.line, absoluteTolerance = 1e-4f)
        assertTrue(remapped.line + remapped.bitmapHeight <= 1.0001f)
    }

    @Test
    fun endAndMiddleAnchorsArePreservedAndReExpressed() {
        val end = remapBitmapCue(
            cue = pgsCue(
                position = 0.8f,
                size = 0.6f,
                bitmapHeight = 0.1f,
                positionAnchor = Cue.ANCHOR_TYPE_END,
                lineAnchor = Cue.ANCHOR_TYPE_END,
            ),
            appearance = appearance(fontSize = SubtitleFontSizePreset.Large),
            titleSafeFraction = 0f,
        )
        assertEquals(Cue.ANCHOR_TYPE_END, end.positionAnchor)
        assertEquals(Cue.ANCHOR_TYPE_END, end.lineAnchor)
        // Authored span 0.2..0.8 (centre 0.5); END anchor reports the right edge.
        assertEquals(0.5f + (0.6f * 1.15f) / 2f, end.position, absoluteTolerance = 1e-4f)
        // END line anchor reports the bottom edge, which is 1 - 0.06.
        assertEquals(0.94f, end.line, absoluteTolerance = 1e-4f)

        val middle = remapBitmapCue(
            cue = pgsCue(
                position = 0.5f,
                size = 0.6f,
                bitmapHeight = 0.1f,
                positionAnchor = Cue.ANCHOR_TYPE_MIDDLE,
                lineAnchor = Cue.ANCHOR_TYPE_MIDDLE,
            ),
            appearance = appearance(),
            titleSafeFraction = 0f,
        )
        assertEquals(0.5f, middle.position, absoluteTolerance = 1e-4f)
        assertEquals(0.94f - 0.05f, middle.line, absoluteTolerance = 1e-4f)
    }

    @Test
    fun anUnsetLineTypeAndAnchorAreTreatedAsAStartFraction() {
        val cue = Cue.Builder()
            .setBitmap(Bitmap.createBitmap(64, 16, Bitmap.Config.ARGB_8888))
            .setPosition(0.2f)
            .setSize(0.6f)
            .setBitmapHeight(0.1f)
            .build()
        assertEquals(Cue.TYPE_UNSET, cue.lineType)

        val remapped = remapBitmapCue(cue, appearance(), titleSafeFraction = 0f)

        assertEquals(Cue.LINE_TYPE_FRACTION, remapped.lineType)
        assertEquals(0.84f, remapped.line, absoluteTolerance = 1e-4f)
        assertEquals(0.2f, remapped.position, absoluteTolerance = 1e-4f)
    }

    @Test
    fun aCueWithoutUsableGeometryIsLeftExactlyAsAuthored() {
        val noHeight = Cue.Builder()
            .setBitmap(Bitmap.createBitmap(64, 16, Bitmap.Config.ARGB_8888))
            .setPosition(0.2f)
            .setLine(0.8f, Cue.LINE_TYPE_FRACTION)
            .setSize(0.6f)
            .build()
        assertSame(noHeight, remapBitmapCue(noHeight, appearance(), 0f))

        val noSize = Cue.Builder()
            .setBitmap(Bitmap.createBitmap(64, 16, Bitmap.Config.ARGB_8888))
            .setPosition(0.2f)
            .setLine(0.8f, Cue.LINE_TYPE_FRACTION)
            .setBitmapHeight(0.1f)
            .build()
        assertSame(noSize, remapBitmapCue(noSize, appearance(), 0f))

        val noPosition = Cue.Builder()
            .setBitmap(Bitmap.createBitmap(64, 16, Bitmap.Config.ARGB_8888))
            .setLine(0.8f, Cue.LINE_TYPE_FRACTION)
            .setSize(0.6f)
            .setBitmapHeight(0.1f)
            .build()
        assertSame(noPosition, remapBitmapCue(noPosition, appearance(), 0f))
    }

    @Test
    fun textCuesAreNeverTouched() {
        val text = Cue.Builder()
            .setText("Hello")
            .setPosition(0.5f)
            .setLine(0.9f, Cue.LINE_TYPE_FRACTION)
            .setSize(1f)
            .build()

        assertSame(
            text,
            remapBitmapCue(
                text,
                appearance(SubtitlePositionPreset.Top, SubtitleFontSizePreset.XXLarge),
                0f,
            ),
        )
    }

    @Test
    fun anAlreadyCorrectCueIsReturnedByIdentitySoThePainterKeepsItsCache() {
        val once = remapBitmapCue(pgsCue(), appearance(), 0f)
        assertSame(once, remapBitmapCue(once, appearance(), 0f))
    }

    @Test
    fun theGroupWrapperRemapsBitmapCuesAndPreservesThePresentationTime() {
        val group = CueGroup(
            listOf(pgsCue(), Cue.Builder().setText("Hello").build()),
            /* presentationTimeUs= */ 1_234L,
        )

        val remapped = remapBitmapCues(group, appearance(SubtitlePositionPreset.Top), 0f)

        assertEquals(1_234L, remapped.presentationTimeUs)
        assertNotEquals(group.cues[0].line, remapped.cues[0].line)
        assertSame(group.cues[1], remapped.cues[1])
    }

    @Test
    fun aGroupWithNothingToChangeIsReturnedByIdentity() {
        val group = CueGroup(listOf(Cue.Builder().setText("Hello").build()), 0L)
        assertSame(group, remapBitmapCues(group, appearance(), 0f))
    }

    /**
     * The text counterpart: `bottomPaddingFraction` — how the Position preset is
     * applied to text — is read only when the cue carries no line of its own, so
     * the parser's default placement has to be cleared or the preset is a no-op.
     */
    @Test
    fun theParserDefaultPlacementIsClearedSoThePositionPresetApplies() {
        val cue = Cue.Builder()
            .setText("Hello")
            .setLine(-1f, Cue.LINE_TYPE_NUMBER)
            .build()

        val remapped = remapDefaultTextCuePlacement(cue, SubtitlePositionPreset.Bottom)

        assertEquals(Cue.DIMEN_UNSET, remapped.line)
        assertEquals(Cue.TYPE_UNSET, remapped.lineType)
        assertEquals("Hello", remapped.text.toString())
    }

    @Test
    fun theTopPresetGivesTextCuesATopAnchoredLine() {
        val cue = Cue.Builder()
            .setText("Hello")
            .setLine(-1f, Cue.LINE_TYPE_NUMBER)
            .build()

        val remapped = remapDefaultTextCuePlacement(cue, SubtitlePositionPreset.Top)

        assertEquals(SUBTITLE_TOP_LINE_FRACTION, remapped.line, absoluteTolerance = 1e-4f)
        assertEquals(Cue.LINE_TYPE_FRACTION, remapped.lineType)
        assertEquals(Cue.ANCHOR_TYPE_START, remapped.lineAnchor)
    }

    @Test
    fun theTopPresetStillLeavesAnAuthoredPlacementAlone() {
        val cue = Cue.Builder()
            .setText("Hello")
            .setLine(0.4f, Cue.LINE_TYPE_FRACTION)
            .build()

        assertSame(cue, remapDefaultTextCuePlacement(cue, SubtitlePositionPreset.Top))
    }

    @Test
    fun anAuthoredFractionPlacementIsLeftAlone() {
        val cue = Cue.Builder()
            .setText("Hello")
            .setLine(0.1f, Cue.LINE_TYPE_FRACTION)
            .build()

        assertSame(cue, remapDefaultTextCuePlacement(cue, SubtitlePositionPreset.Bottom))
    }

    @Test
    fun anAuthoredLineNumberIsLeftAlone() {
        val cue = Cue.Builder()
            .setText("Hello")
            .setLine(2f, Cue.LINE_TYPE_NUMBER)
            .build()

        assertSame(cue, remapDefaultTextCuePlacement(cue, SubtitlePositionPreset.Bottom))
    }

    @Test
    fun aDefaultLineWithItsOwnAnchorIsLeftAlone() {
        val cue = Cue.Builder()
            .setText("Hello")
            .setLine(-1f, Cue.LINE_TYPE_NUMBER)
            .setLineAnchor(Cue.ANCHOR_TYPE_END)
            .build()

        assertSame(cue, remapDefaultTextCuePlacement(cue, SubtitlePositionPreset.Bottom))
    }

    @Test
    fun bitmapCuesKeepTheirPlacement() {
        val cue = pgsCue(line = -1f, lineType = Cue.LINE_TYPE_NUMBER)

        assertSame(cue, remapDefaultTextCuePlacement(cue, SubtitlePositionPreset.Bottom))
    }

    @Test
    fun theTextPlacementGroupWrapperPreservesTimeAndUntouchedCues() {
        val authored = Cue.Builder().setText("A").setLine(0.2f, Cue.LINE_TYPE_FRACTION).build()
        val group = CueGroup(
            listOf(Cue.Builder().setText("B").setLine(-1f, Cue.LINE_TYPE_NUMBER).build(), authored),
            /* presentationTimeUs= */ 99L,
        )

        val remapped = remapDefaultTextCuePlacements(group, SubtitlePositionPreset.Bottom)

        assertEquals(99L, remapped.presentationTimeUs)
        assertEquals(Cue.DIMEN_UNSET, remapped.cues[0].line)
        assertSame(authored, remapped.cues[1])
    }

    @Test
    fun aTextPlacementGroupWithNothingToChangeIsReturnedByIdentity() {
        val group = CueGroup(
            listOf(Cue.Builder().setText("A").setLine(0.2f, Cue.LINE_TYPE_FRACTION).build()),
            0L,
        )

        assertSame(group, remapDefaultTextCuePlacements(group, SubtitlePositionPreset.Bottom))
    }
}
