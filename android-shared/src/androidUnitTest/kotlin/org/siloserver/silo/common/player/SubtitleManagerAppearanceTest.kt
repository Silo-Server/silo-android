package org.siloserver.silo.common.player

import android.app.Activity
import android.os.Looper
import android.view.View
import android.widget.FrameLayout
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.PlayerView
import org.siloserver.silo.model.settings.SubtitleAppearance
import org.siloserver.silo.model.settings.SubtitleBackgroundStylePreset
import org.siloserver.silo.model.settings.SubtitlePositionPreset
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w1920dp-h1080dp-mdpi")
class SubtitleManagerAppearanceTest {

    @Test
    fun defaultSubtitleStyleIsWhiteTextWithASoftShadow() {
        val style = captionStyleFor(SubtitleAppearance.DEFAULT)

        assertEquals(0xFFFFFFFF.toInt(), style.foregroundColor)
        assertEquals(0x00000000, style.backgroundColor)
        assertEquals(0x00000000, style.windowColor)
        assertEquals(CaptionStyleCompat.EDGE_TYPE_DROP_SHADOW, style.edgeType)
        assertEquals(0xFF000000.toInt(), style.edgeColor)
    }

    @Test
    fun bottomSubtitlesUseTheReferenceSafeMargin() {
        val method = SubtitleManager::class.java.getDeclaredMethod(
            "bottomPaddingFor",
            SubtitlePositionPreset::class.java,
        )
        method.isAccessible = true

        // No title-safe inset (phone): the physical 6% applies raw.
        assertEquals(
            0.06f,
            method.invoke(
                SubtitleManager(),
                SubtitlePositionPreset.Bottom,
            ) as Float,
        )
    }

    @Test
    fun titleSafeCompensationKeepsTheBottomAnchoredPresetsInPlace() {
        val manager = SubtitleManager().apply {
            titleSafeFraction = 0.05f
        }
        val method = SubtitleManager::class.java.getDeclaredMethod(
            "bottomPaddingFor",
            SubtitlePositionPreset::class.java,
        )
        method.isAccessible = true

        // The padding fraction is evaluated inside a surface scaled to 90% of
        // the original video height. Preserve the original physical presets:
        // f + p(1 - 2f) = base, so p = (base - f) / (1 - 2f).
        // Top is top-anchored (SUBTITLE_TOP_LINE_FRACTION) and reads no padding.
        // Bottom is a physical 6%: one percent of it lies inside the inset.
        assertEquals(
            expected = (0.06f - 0.05f) / 0.90f,
            actual = method.invoke(manager, SubtitlePositionPreset.Bottom) as Float,
            absoluteTolerance = 0.0001f,
        )
        assertEquals(
            expected = (0.18f - 0.05f) / 0.90f,
            actual = method.invoke(manager, SubtitlePositionPreset.LowerThird) as Float,
            absoluteTolerance = 0.0001f,
        )
    }

    /**
     * The Shield measurement this change answers: a 2.39:1 title in a 1920x1080
     * PlayerView leaves a 1920x803 content frame at y=138 and a 1728x723 canvas
     * at 96,40 inside it. Bottom must reach the screen, not stop at the picture.
     */
    @Test
    fun bottomCanvasDropsIntoTheLetterboxBar() {
        val picture = SubtitleVideoRect(left = 96, top = 40, width = 1728, height = 723)

        val canvas = subtitleCanvasRectFor(
            position = SubtitlePositionPreset.Bottom,
            pictureRect = picture,
            playerBottomInParentSpace = 1080 - 138,
        )

        assertEquals(SubtitleVideoRect(left = 96, top = 40, width = 1728, height = 902), canvas)
        // Frame origin 138 + canvas top 40 + height 902 = 1080, the screen edge.
        assertEquals(1080, 138 + canvas.top + canvas.height)
    }

    @Test
    fun lowerThirdAndTopCanvasesStayOnThePicture() {
        val picture = SubtitleVideoRect(left = 96, top = 40, width = 1728, height = 723)

        assertEquals(
            picture,
            subtitleCanvasRectFor(
                position = SubtitlePositionPreset.LowerThird,
                pictureRect = picture,
                playerBottomInParentSpace = 1080 - 138,
            ),
        )
        assertEquals(
            picture,
            subtitleCanvasRectFor(
                position = SubtitlePositionPreset.Top,
                pictureRect = picture,
                playerBottomInParentSpace = 1080 - 138,
            ),
        )
    }

    @Test
    fun bottomCanvasNeverShrinksOrReachesPastThePlayerView() {
        // Zoom: the picture already covers the screen, so there is no bar to
        // drop into and nothing to extend.
        val fullScreen = SubtitleVideoRect(left = 0, top = 0, width = 1920, height = 1080)

        assertEquals(
            fullScreen,
            subtitleCanvasRectFor(
                position = SubtitlePositionPreset.Bottom,
                pictureRect = fullScreen,
                playerBottomInParentSpace = 1080,
            ),
        )
    }

    /**
     * The whole point, stated as the number the owner reads off a screenshot:
     * Bottom sits 6% of the SCREEN above the screen's bottom whatever the
     * picture is doing. 16:9 is unchanged from the picture-anchored behaviour
     * because there the picture IS the screen.
     */
    @Test
    fun bottomLandsSixPercentAboveTheScreenOnEveryAspect() {
        val expected = 0.06f * 1080f

        assertEquals(
            expected,
            bottomCaptionGapAboveScreenBottom(frameTop = 0, frameHeight = 1080),
            absoluteTolerance = 1f,
        )
        assertEquals(
            expected,
            bottomCaptionGapAboveScreenBottom(frameTop = 138, frameHeight = 803),
            absoluteTolerance = 1f,
        )
        // Encoded bars inside a 16:9 frame: the detected letterbox insets the
        // picture, and Bottom drops back into that bar the same way.
        assertEquals(
            expected,
            bottomCaptionGapAboveScreenBottom(
                frameTop = 0,
                frameHeight = 1080,
                letterbox = LetterboxInsets(0.1278f, 0.1287f),
            ),
            absoluteTolerance = 1f,
        )
    }

    @Test
    fun lowerThirdKeepsItsPictureRelativeDistanceOnALetterboxedFrame() {
        // 18% of the 803-px picture above the picture's bottom edge, which is
        // 138 + 803 = 941 on screen: unchanged by the Bottom preset's work.
        val gap = lowerThirdCaptionGapAbovePictureBottom(frameTop = 138, frameHeight = 803)

        assertEquals(0.18f * 803f, gap, absoluteTolerance = 1f)
    }

    @Test
    fun boxBackgroundStyleAppliesConfiguredBackgroundAlpha() {
        val style = captionStyleFor(
            SubtitleAppearance.DEFAULT.copy(
                backgroundStyle = SubtitleBackgroundStylePreset.Box,
                backgroundColor = "#000000",
                backgroundOpacity = 75,
                textOutline = false,
            )
        )

        // Box paints through windowColor (Media3 pads the window block around
        // the cue); the glyph-hugging backgroundColor stays transparent.
        assertEquals(0xBF000000.toInt(), style.windowColor)
        assertEquals(0x00000000, style.backgroundColor)
        assertEquals(CaptionStyleCompat.EDGE_TYPE_NONE, style.edgeType)
    }

    @Test
    fun shadowBackgroundStyleKeepsBackgroundTransparent() {
        val style = captionStyleFor(
            SubtitleAppearance.DEFAULT.copy(
                backgroundStyle = SubtitleBackgroundStylePreset.Shadow,
                backgroundColor = "#000000",
                backgroundOpacity = 75,
            )
        )

        assertEquals(0x00000000, style.backgroundColor)
        assertEquals(0x00000000, style.windowColor)
        assertEquals(CaptionStyleCompat.EDGE_TYPE_DROP_SHADOW, style.edgeType)
    }

    @Test
    fun outlineBackgroundStyleKeepsBackgroundTransparent() {
        val style = captionStyleFor(
            SubtitleAppearance.DEFAULT.copy(
                backgroundStyle = SubtitleBackgroundStylePreset.Outline,
                backgroundColor = "#000000",
                backgroundOpacity = 75,
            )
        )

        assertEquals(0x00000000, style.backgroundColor)
        assertEquals(0x00000000, style.windowColor)
        assertEquals(CaptionStyleCompat.EDGE_TYPE_OUTLINE, style.edgeType)
    }

    @Test
    fun fitModeComputesPortraitVideoRectInsideLetterbox() {
        val rect = displayedSubtitleVideoRect(
            viewWidth = 1080,
            viewHeight = 2400,
            videoWidth = 1920,
            videoHeight = 1080,
            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT,
        )

        assertEquals(SubtitleVideoRect(left = 0, top = 896, width = 1080, height = 608), rect)
    }

    @Test
    fun fitModeComputesLandscapeVideoRectInsidePillarbox() {
        val rect = displayedSubtitleVideoRect(
            viewWidth = 2400,
            viewHeight = 1080,
            videoWidth = 1920,
            videoHeight = 1080,
            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT,
        )

        assertEquals(SubtitleVideoRect(left = 240, top = 0, width = 1920, height = 1080), rect)
    }

    @Test
    fun fitModeUsesVideoPixelAspectRatioForAnamorphicContent() {
        val rect = displayedSubtitleVideoRect(
            viewWidth = 1920,
            viewHeight = 1080,
            videoWidth = 720,
            videoHeight = 576,
            videoPixelWidthHeightRatio = 16f / 15f,
            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT,
        )

        assertEquals(SubtitleVideoRect(left = 240, top = 0, width = 1440, height = 1080), rect)
    }

    @Test
    fun zoomAndFillModesUseFullViewRect() {
        val zoom = displayedSubtitleVideoRect(
            viewWidth = 1080,
            viewHeight = 2400,
            videoWidth = 1920,
            videoHeight = 1080,
            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM,
        )
        val fill = displayedSubtitleVideoRect(
            viewWidth = 1080,
            viewHeight = 2400,
            videoWidth = 1920,
            videoHeight = 1080,
            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FILL,
        )

        assertEquals(SubtitleVideoRect(left = 0, top = 0, width = 1080, height = 2400), zoom)
        assertEquals(SubtitleVideoRect(left = 0, top = 0, width = 1080, height = 2400), fill)
    }

    @Test
    fun zoomAnchorsToTheContentFrameEvenMidResize() {
        // The frame is momentarily still the fitted one, because the resize
        // mode changed and layout has not run again yet. Anchoring to it keeps
        // the captions on the video that is actually rendered at this instant;
        // the view-space viewport would be applied at frame-relative margins
        // and shift them by the frame's offset.
        val staleFit = SubtitleVideoRect(left = 0, top = 236, width = 2404, height = 1352)
        val fullViewport = SubtitleVideoRect(left = 0, top = 0, width = 2404, height = 1080)

        assertEquals(
            staleFit,
            selectSubtitleCanvasRect(
                contentFrameRect = staleFit,
                displayedVideoRect = fullViewport,
            ),
        )
    }

    @Test
    fun captionsStayCentredOnAnOffsetContentFrame() {
        // The regression: a 1920x1080 title expanded on a 3120x1440 display
        // leaves the PlayerView 2814 wide, and mid-resize the frame is the
        // 2560-wide fitted rect inset 127px inside it. Anchoring to the frame
        // centres the captions on the video; the 2814-wide view-space viewport
        // used to be applied at frame margin 0 and pushed them 127px right.
        val offsetFrame = requireNotNull(
            displayedSubtitleContentFrameRect(
                viewWidth = 2814,
                viewHeight = 1440,
                frameLeft = 127,
                frameTop = 0,
                frameWidth = 2560,
                frameHeight = 1440,
            ),
        )
        val viewportSpaceRect = SubtitleVideoRect(left = 0, top = 0, width = 2814, height = 1440)
        val canvas = selectSubtitleCanvasRect(
            contentFrameRect = offsetFrame,
            displayedVideoRect = viewportSpaceRect,
        )

        assertEquals(SubtitleVideoRect(left = 0, top = 0, width = 2560, height = 1440), canvas)
        // Frame origin 127 + canvas centre 1280 = 1407, the centre of the 2814
        // view. The old answer centred at 1407 + 127 = 1534.
        assertEquals(1407, 127 + canvas.left + canvas.width / 2)
    }

    @Test
    fun captionsNeverExtendBelowTheVisibleBottom() {
        // Expansion overhangs the view by design: a 2814x1583 content frame in
        // a 2814x1440 view hangs ~71px off each edge. The canvas must be the
        // intersection, or a bottom-anchored caption is drawn off-screen.
        val visible = requireNotNull(
            displayedSubtitleContentFrameRect(
                viewWidth = 2814,
                viewHeight = 1440,
                frameLeft = 0,
                frameTop = -71,
                frameWidth = 2814,
                frameHeight = 1583,
            ),
        )
        val canvas = selectSubtitleCanvasRect(
            contentFrameRect = visible,
            displayedVideoRect = SubtitleVideoRect(0, 0, 2814, 1440),
        )

        assertEquals(SubtitleVideoRect(left = 0, top = 71, width = 2814, height = 1440), canvas)
        // Frame origin -71 + canvas top 71 = 0, and its bottom lands on 1440.
        assertEquals(0, -71 + canvas.top)
        assertEquals(1440, -71 + canvas.top + canvas.height)
    }

    @Test
    fun zoomUsesVisibleViewportInNegativeContentFrameParentCoordinates() {
        val visibleCanvas = requireNotNull(
            displayedSubtitleContentFrameRect(
                viewWidth = 1920,
                viewHeight = 1080,
                frameLeft = -120,
                frameTop = -64,
                frameWidth = 2160,
                frameHeight = 1208,
            ),
        )
        val fullViewport = SubtitleVideoRect(left = 0, top = 0, width = 1920, height = 1080)

        assertEquals(
            SubtitleVideoRect(left = 120, top = 64, width = 1920, height = 1080),
            selectSubtitleCanvasRect(
                contentFrameRect = visibleCanvas,
                displayedVideoRect = fullViewport,
            ),
        )
    }

    @Test
    fun stretchAnchorsToTheContentFrameEvenMidResize() {
        val staleFit = SubtitleVideoRect(left = 240, top = 0, width = 1920, height = 1080)
        val fullViewport = SubtitleVideoRect(left = 0, top = 0, width = 2400, height = 1080)

        assertEquals(
            staleFit,
            selectSubtitleCanvasRect(
                contentFrameRect = staleFit,
                displayedVideoRect = fullViewport,
            ),
        )
    }

    @Test
    fun fitContinuesToUsePostLayoutContentFrame() {
        val fittedFrame = SubtitleVideoRect(left = 0, top = 0, width = 1920, height = 1080)
        val computedFallback = SubtitleVideoRect(left = 240, top = 0, width = 1920, height = 1080)

        assertEquals(
            fittedFrame,
            selectSubtitleCanvasRect(
                contentFrameRect = fittedFrame,
                displayedVideoRect = computedFallback,
            ),
        )
    }

    @Test
    fun repeatedModeSelectionDoesNotRetainPreviousCanvas() {
        val fit = SubtitleVideoRect(left = 240, top = 0, width = 1920, height = 1080)
        val full = SubtitleVideoRect(left = 0, top = 0, width = 2400, height = 1080)

        val fill = selectSubtitleCanvasRect(fit, full)
        val stretch = selectSubtitleCanvasRect(fit, full)
        val restoredFit = selectSubtitleCanvasRect(fit, fit)

        // Every mode anchors to the same place now: the content frame is the
        // subtitle layer's parent whatever the video is being scaled to.
        assertEquals(fit, fill)
        assertEquals(fit, stretch)
        assertEquals(fit, restoredFit)
    }

    @Test
    fun invalidVideoSizeUsesFullViewRect() {
        val rect = displayedSubtitleVideoRect(
            viewWidth = 1080,
            viewHeight = 2400,
            videoWidth = 0,
            videoHeight = 0,
            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT,
        )

        assertEquals(SubtitleVideoRect(left = 0, top = 0, width = 1080, height = 2400), rect)
    }

    @Test
    fun contentFrameRectUsesSubtitleParentLocalBounds() {
        val rect = displayedSubtitleContentFrameRect(
            viewWidth = 1920,
            viewHeight = 1080,
            frameLeft = 0,
            frameTop = 140,
            frameWidth = 1920,
            frameHeight = 800,
        )

        assertEquals(SubtitleVideoRect(left = 0, top = 0, width = 1920, height = 800), rect)
    }

    @Test
    fun clippedContentFrameRectUsesSubtitleParentLocalIntersection() {
        val rect = displayedSubtitleContentFrameRect(
            viewWidth = 1920,
            viewHeight = 1080,
            frameLeft = -100,
            frameTop = -50,
            frameWidth = 2120,
            frameHeight = 1180,
        )

        assertEquals(SubtitleVideoRect(left = 100, top = 50, width = 1920, height = 1080), rect)
    }

    @Test
    fun invalidContentFrameRectFallsBackToComputedBounds() {
        assertEquals(
            null,
            displayedSubtitleContentFrameRect(
                viewWidth = 1920,
                viewHeight = 1080,
                frameLeft = 0,
                frameTop = 0,
                frameWidth = 0,
                frameHeight = 0,
            ),
        )
    }

    @Test
    fun mountedCanvasReconcilesFitToZoomAfterContentFrameLayout() {
        val canvas = MountedSubtitleCanvas()

        assertEquals(SubtitleVideoRect(0, 0, 1440, 1016), canvas.subtitleRect())

        canvas.transition(
            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM,
            frame = FrameBounds(-120, -64, 2040, 1080),
        )

        assertEquals(SubtitleVideoRect(120, 64, 1920, 1016), canvas.subtitleRect())
    }

    @Test
    fun mountedCanvasReconcilesFitToFillAfterContentFrameLayout() {
        val canvas = MountedSubtitleCanvas()

        canvas.transition(
            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FILL,
            frame = FrameBounds(0, 0, 1920, 1016),
        )

        assertEquals(SubtitleVideoRect(0, 0, 1920, 1016), canvas.subtitleRect())
    }

    @Test
    fun mountedCanvasDoesNotRetainOffsetsAcrossRepeatedZoomAndFillSwitches() {
        val canvas = MountedSubtitleCanvas()

        canvas.transition(
            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM,
            frame = FrameBounds(-120, -64, 2040, 1080),
        )
        assertEquals(SubtitleVideoRect(120, 64, 1920, 1016), canvas.subtitleRect())

        canvas.transition(
            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FILL,
            frame = FrameBounds(0, 0, 1920, 1016),
        )
        assertEquals(SubtitleVideoRect(0, 0, 1920, 1016), canvas.subtitleRect())

        canvas.transition(
            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM,
            frame = FrameBounds(-120, -64, 2040, 1080),
        )
        assertEquals(SubtitleVideoRect(120, 64, 1920, 1016), canvas.subtitleRect())
    }

    @Test
    fun mountedCanvasReconcilesZoomBackToFitAfterContentFrameLayout() {
        val canvas = MountedSubtitleCanvas()
        canvas.transition(
            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM,
            frame = FrameBounds(-120, -64, 2040, 1080),
        )

        canvas.transition(
            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT,
            frame = FrameBounds(240, 0, 1680, 1016),
        )

        assertEquals(SubtitleVideoRect(0, 0, 1440, 1016), canvas.subtitleRect())
    }

    @Test
    fun mountedCanvasCorrectsFillToFitWhenFirstPreDrawSeesCroppedFrame() {
        val canvas = MountedSubtitleCanvas()
        canvas.transition(
            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM,
            frame = FrameBounds(-120, -64, 2040, 1080),
        )

        canvas.transitionAfterEarlyPreDraw(
            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT,
            finalFrame = FrameBounds(240, 0, 1680, 1016),
        )

        assertEquals(SubtitleVideoRect(0, 0, 1440, 1016), canvas.subtitleRect())
    }

    @Test
    fun rapidEarlyTransitionsApplyOnlyLatestMode() {
        val canvas = MountedSubtitleCanvas()
        canvas.schedule(AspectRatioFrameLayout.RESIZE_MODE_ZOOM)
        canvas.schedule(AspectRatioFrameLayout.RESIZE_MODE_FILL)
        canvas.schedule(AspectRatioFrameLayout.RESIZE_MODE_FIT)
        canvas.dispatchEarlyPreDrawThenMount(FrameBounds(240, 0, 1680, 1016))

        assertEquals(SubtitleVideoRect(0, 0, 1440, 1016), canvas.subtitleRect())
        assertEquals(2, canvas.reconciliationCount)
    }

    @Test
    fun detachCancelsPostedSnapshotVerification() {
        val canvas = MountedSubtitleCanvas()
        canvas.schedule(AspectRatioFrameLayout.RESIZE_MODE_ZOOM)
        canvas.dispatchPreDraw()
        canvas.detach()
        canvas.mountFrameAndDrain(FrameBounds(-120, -64, 2040, 1080))

        assertEquals(1, canvas.reconciliationCount)
    }

    @Test
    fun detachedPendingReconciliationLeavesSubtitleLayoutSentinelUnchanged() {
        val canvas = MountedSubtitleCanvas()

        canvas.schedule(AspectRatioFrameLayout.RESIZE_MODE_ZOOM)
        val sentinel = SubtitleVideoRect(33, 44, 777, 555)
        canvas.setSubtitleRect(sentinel)
        canvas.detachAndDrain(FrameBounds(-120, -64, 2040, 1080))

        assertEquals(sentinel, canvas.subtitleRect())
    }

    @Test
    fun repeatedExplicitSyncsRunOnePostLayoutReconciliation() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val playerView = PlayerView(activity)
        val manager = SubtitleManager()
        activity.setContentView(playerView)
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        manager.syncSubtitleVideoBounds(playerView)
        playerView.viewTreeObserver.dispatchOnPreDraw()

        var reconciliations = 0
        manager.postLayoutReconciliationObserver = { reconciliations++ }
        repeat(3) {
            manager.syncSubtitleVideoBounds(playerView)
        }

        playerView.viewTreeObserver.dispatchOnPreDraw()
        playerView.viewTreeObserver.dispatchOnPreDraw()

        assertEquals(1, reconciliations)
    }

    /**
     * The letterbox regression: the params carry the narrowed frame, the view
     * is still laid out at the outgoing 16:9 geometry because the parent
     * measured it before the params were written and never comes back, and a
     * params-only diff would go quiet forever. The sync has to notice the
     * BOUNDS and place the canvas itself.
     *
     * Stated on a picture-anchored preset, so the geometry under test is the
     * frame's alone — Bottom deliberately spans past the frame and is covered
     * by [bottomPresetSpansIntoTheBarAndAPositionChangeReplacesTheCanvas].
     */
    @Test
    fun canvasLeftLaidOutAtTheOldAspectIsPlacedAtTheLetterboxedFrame() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val playerView = PlayerView(activity)
        val contentFrame = requireNotNull(
            playerView.findViewById<AspectRatioFrameLayout>(
                androidx.media3.ui.R.id.exo_content_frame,
            ),
        )
        val subtitleView = requireNotNull(playerView.subtitleView)
        val manager = SubtitleManager()
        activity.setContentView(playerView)
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        playerView.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
        manager.applyAppearance(
            playerView,
            SubtitleAppearance.DEFAULT.copy(position = SubtitlePositionPreset.LowerThird),
        )

        manager.syncSubtitleVideoBounds(playerView)
        playerView.viewTreeObserver.dispatchOnPreDraw()
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        // 2.39:1 content inside the view: the frame narrows to 803 tall. The
        // aspect ratio is set too so a real traversal re-measures to the same
        // geometry instead of springing back to the full parent height.
        contentFrame.setAspectRatio(1920f / 803f)
        contentFrame.layout(0, 106, 1920, 909)

        val params = subtitleView.layoutParams as FrameLayout.LayoutParams
        assertEquals(1920, params.width)
        assertEquals(803, params.height)

        // The bounds the canvas keeps when the frame measured it a beat early.
        subtitleView.layout(0, 0, 1920, 1016)

        manager.syncSubtitleVideoBounds(playerView)
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        assertEquals(803, subtitleView.height)
        assertEquals(1920, subtitleView.width)
        assertEquals(0, subtitleView.top)
        assertEquals(0, subtitleView.left)
    }

    /**
     * Runs the production placement — content-frame rect, letterbox and
     * title-safe insets, the preset's canvas, the preset's bottom padding — and
     * reports how far the caption's bottom edge ends up above the PLAYER VIEW's
     * bottom, in pixels of a 1920x1080 television screen.
     */
    private fun bottomCaptionGapAboveScreenBottom(
        frameTop: Int,
        frameHeight: Int,
        letterbox: LetterboxInsets = LetterboxInsets.NONE,
        titleSafeFraction: Float = 0.05f,
        playerHeight: Int = 1080,
        playerWidth: Int = 1920,
    ): Float {
        val picture = requireNotNull(
            displayedSubtitleContentFrameRect(
                viewWidth = playerWidth,
                viewHeight = playerHeight,
                frameLeft = 0,
                frameTop = frameTop,
                frameWidth = playerWidth,
                frameHeight = frameHeight,
            ),
        ).insetByLetterbox(letterbox).insetByTitleSafe(titleSafeFraction)
        val canvas = subtitleCanvasRectFor(
            position = SubtitlePositionPreset.Bottom,
            pictureRect = picture,
            playerBottomInParentSpace = playerHeight - frameTop,
        )
        val padding = subtitleBottomPaddingFractionForCanvas(
            position = SubtitlePositionPreset.Bottom,
            titleSafeFraction = titleSafeFraction,
            canvasHeight = canvas.height,
            canvasBottomInPlayerSpace = frameTop + canvas.top + canvas.height,
            playerHeight = playerHeight,
        )
        val captionBottom = frameTop + canvas.top + canvas.height * (1f - padding)
        return playerHeight - captionBottom
    }

    /** The same walk for Lower Third, measured against the PICTURE's bottom. */
    private fun lowerThirdCaptionGapAbovePictureBottom(
        frameTop: Int,
        frameHeight: Int,
        titleSafeFraction: Float = 0.05f,
        playerHeight: Int = 1080,
        playerWidth: Int = 1920,
    ): Float {
        val picture = requireNotNull(
            displayedSubtitleContentFrameRect(
                viewWidth = playerWidth,
                viewHeight = playerHeight,
                frameLeft = 0,
                frameTop = frameTop,
                frameWidth = playerWidth,
                frameHeight = frameHeight,
            ),
        ).insetByTitleSafe(titleSafeFraction)
        val canvas = subtitleCanvasRectFor(
            position = SubtitlePositionPreset.LowerThird,
            pictureRect = picture,
            playerBottomInParentSpace = playerHeight - frameTop,
        )
        val padding = subtitleBottomPaddingFractionForCanvas(
            position = SubtitlePositionPreset.LowerThird,
            titleSafeFraction = titleSafeFraction,
            canvasHeight = canvas.height,
            canvasBottomInPlayerSpace = frameTop + canvas.top + canvas.height,
            playerHeight = playerHeight,
        )
        val captionBottom = canvas.top + canvas.height * (1f - padding)
        return frameHeight - captionBottom
    }

    /**
     * End to end on a letterboxed frame: Bottom spans into the bar, the content
     * frame stops clipping so the canvas can be drawn there, and a Position
     * change re-places the canvas on the spot — no parent layout pass, which is
     * the one thing a Compose-hosted PlayerView cannot be relied on to run.
     */
    @Test
    fun bottomPresetSpansIntoTheBarAndAPositionChangeReplacesTheCanvas() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val playerView = PlayerView(activity)
        val contentFrame = requireNotNull(
            playerView.findViewById<AspectRatioFrameLayout>(
                androidx.media3.ui.R.id.exo_content_frame,
            ),
        )
        val subtitleView = requireNotNull(playerView.subtitleView)
        val manager = SubtitleManager().apply { titleSafeFraction = 0.05f }
        activity.setContentView(playerView)
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        playerView.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
        // 2.39:1 inside the 1920x1016 view: an 803-tall frame at y=106.
        contentFrame.setAspectRatio(1920f / 803f)
        contentFrame.layout(0, 106, 1920, 909)

        manager.applyAppearance(
            playerView,
            SubtitleAppearance.DEFAULT.copy(position = SubtitlePositionPreset.Bottom),
        )

        val bottom = subtitleView.layoutParams as FrameLayout.LayoutParams
        assertEquals(1728, bottom.width)
        assertEquals(96, bottom.leftMargin)
        assertEquals(40, bottom.topMargin)
        // The title-safe canvas is 723 tall; 870 carries it 147px past the
        // picture, to 106 + 40 + 870 = 1016 — the player view's own bottom.
        assertEquals(870, bottom.height)
        assertEquals(1016, 106 + bottom.topMargin + bottom.height)
        assertFalse(contentFrame.clipChildren)

        manager.applyAppearance(
            playerView,
            SubtitleAppearance.DEFAULT.copy(position = SubtitlePositionPreset.LowerThird),
        )

        val lowerThird = subtitleView.layoutParams as FrameLayout.LayoutParams
        assertEquals(723, lowerThird.height)
        assertEquals(40, lowerThird.topMargin)
        assertTrue(contentFrame.clipChildren)
    }

    /** 16:9: the picture already is the screen, so nothing about it moves. */
    @Test
    fun bottomPresetLeavesTheFullScreenPictureCanvasAlone() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val playerView = PlayerView(activity)
        val contentFrame = requireNotNull(
            playerView.findViewById<AspectRatioFrameLayout>(
                androidx.media3.ui.R.id.exo_content_frame,
            ),
        )
        val subtitleView = requireNotNull(playerView.subtitleView)
        val manager = SubtitleManager().apply { titleSafeFraction = 0.05f }
        activity.setContentView(playerView)
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        playerView.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
        contentFrame.setAspectRatio(1920f / 1016f)
        contentFrame.layout(0, 0, 1920, 1016)

        manager.applyAppearance(
            playerView,
            SubtitleAppearance.DEFAULT.copy(position = SubtitlePositionPreset.Bottom),
        )

        val params = subtitleView.layoutParams as FrameLayout.LayoutParams
        // Title-safe puts the canvas at 51,96 with 1728x914; the extension only
        // reclaims the bottom inset, which the padding then gives straight back.
        assertEquals(96, params.leftMargin)
        assertEquals(51, params.topMargin)
        assertEquals(1016 - 51, params.height)
        // Still inside the frame, so the frame keeps clipping its children.
        assertTrue(contentFrame.clipChildren)
    }

    private fun captionStyleFor(appearance: SubtitleAppearance): CaptionStyleCompat {
        val method = SubtitleManager::class.java.getDeclaredMethod(
            "buildCaptionStyle",
            SubtitleAppearance::class.java,
        )
        method.isAccessible = true
        return method.invoke(SubtitleManager(), appearance) as CaptionStyleCompat
    }
}

private fun SubtitleManager.subtitleRectSyncForTest(playerView: PlayerView): Any {
    val field = SubtitleManager::class.java.getDeclaredField("videoRectSyncs")
    field.isAccessible = true
    val syncs = field.get(this) as Map<*, *>
    return requireNotNull(syncs[playerView])
}

private data class FrameBounds(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
)

private class MountedSubtitleCanvas {
    private val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
    private val playerView = PlayerView(activity)
    private val contentFrame = requireNotNull(
        playerView.findViewById<AspectRatioFrameLayout>(
            androidx.media3.ui.R.id.exo_content_frame,
        ),
    )
    private val subtitleView = requireNotNull(playerView.subtitleView)
    private val manager = SubtitleManager()
    var reconciliationCount = 0
        private set

    init {
        activity.setContentView(playerView)
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        check(playerView.width == 1920 && playerView.height == 1016)
        playerView.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
        manager.syncSubtitleVideoBounds(playerView)
        drainScheduledWork()

        // Isolate the explicit post-layout reconciliation from the permanent
        // layout listeners: production has both, but this harness proves the
        // bounded fallback still works when an early callback runs before the
        // content-frame traversal that supplies the final geometry.
        val syncListener =
            manager.subtitleRectSyncForTest(playerView) as View.OnLayoutChangeListener
        playerView.removeOnLayoutChangeListener(syncListener)
        contentFrame.removeOnLayoutChangeListener(syncListener)
        mountFrame(FrameBounds(240, 0, 1680, 1016))
        manager.syncSubtitleVideoBounds(playerView)
        manager.postLayoutReconciliationObserver = { reconciliationCount++ }
    }

    /**
     * Mounts an observed Media3 content frame. The aspect ratio is set as well
     * as the bounds so a real Robolectric traversal re-measures to the SAME
     * geometry — without it the frame springs back to the full parent width and
     * the scenario under test evaporates.
     */
    private fun mountFrame(frame: FrameBounds) {
        val width = frame.right - frame.left
        val height = frame.bottom - frame.top
        contentFrame.setAspectRatio(width.toFloat() / height.toFloat())
        contentFrame.layout(frame.left, frame.top, frame.right, frame.bottom)
    }

    fun schedule(resizeMode: Int) {
        playerView.resizeMode = resizeMode
        manager.syncSubtitleVideoBounds(playerView)
    }

    fun transition(resizeMode: Int, frame: FrameBounds) {
        schedule(resizeMode)
        mountFrame(frame)
        playerView.viewTreeObserver.dispatchOnPreDraw()
    }

    fun transitionAfterEarlyPreDraw(resizeMode: Int, finalFrame: FrameBounds) {
        schedule(resizeMode)
        playerView.viewTreeObserver.dispatchOnPreDraw()
        mountFrame(finalFrame)
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        // Robolectric's parent traversal has no renderer-backed aspect ratio,
        // so re-mount the observed Media3 frame before the corrective pre-draw.
        mountFrame(finalFrame)
        playerView.viewTreeObserver.dispatchOnPreDraw()
    }

    fun dispatchEarlyPreDrawThenMount(finalFrame: FrameBounds) {
        mountFrame(FrameBounds(-120, -64, 2040, 1080))
        playerView.viewTreeObserver.dispatchOnPreDraw()
        mountFrame(finalFrame)
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        // Keep the synthetic final frame mounted after Robolectric drains the
        // posted verifier and its unrelated full-width parent traversal.
        mountFrame(finalFrame)
        playerView.viewTreeObserver.dispatchOnPreDraw()
    }

    fun dispatchPreDraw() {
        playerView.viewTreeObserver.dispatchOnPreDraw()
    }

    fun detach() {
        activity.setContentView(FrameLayout(activity))
    }

    fun mountFrameAndDrain(frame: FrameBounds) {
        mountFrame(frame)
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        if (playerView.viewTreeObserver.isAlive) {
            playerView.viewTreeObserver.dispatchOnPreDraw()
        }
    }

    fun detachAndDrain(frame: FrameBounds) {
        detach()
        mountFrameAndDrain(frame)
    }

    fun subtitleRect(): SubtitleVideoRect {
        val params = subtitleView.layoutParams as FrameLayout.LayoutParams
        return SubtitleVideoRect(
            left = params.leftMargin,
            top = params.topMargin,
            width = params.width,
            height = params.height,
        )
    }

    fun setSubtitleRect(rect: SubtitleVideoRect) {
        val params = subtitleView.layoutParams as FrameLayout.LayoutParams
        params.leftMargin = rect.left
        params.topMargin = rect.top
        params.width = rect.width
        params.height = rect.height
        subtitleView.layoutParams = params
    }

    private fun drainScheduledWork() {
        if (playerView.viewTreeObserver.isAlive) {
            playerView.viewTreeObserver.dispatchOnPreDraw()
        }
        Shadows.shadowOf(Looper.getMainLooper()).idle()
    }
}
