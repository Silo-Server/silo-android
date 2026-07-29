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
import org.siloserver.silo.model.settings.SubtitleFontSizePreset
import org.siloserver.silo.model.settings.SubtitlePositionPreset
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals

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
    fun phoneSubtitleTextFractionsAreOneEighthLarger() {
        val manager = SubtitleManager(
            presentation = AndroidSubtitlePresentation.Phone,
        )

        assertEquals(22.5f / 720f, fractionalSize(manager, SubtitleFontSizePreset.Small))
        assertEquals(29.25f / 720f, fractionalSize(manager, SubtitleFontSizePreset.Medium))
        assertEquals(36f / 720f, fractionalSize(manager, SubtitleFontSizePreset.Large))
        assertEquals(45f / 720f, fractionalSize(manager, SubtitleFontSizePreset.XLarge))
        assertEquals(54f / 720f, fractionalSize(manager, SubtitleFontSizePreset.XXLarge))
    }

    @Test
    fun televisionSubtitleTextFractionsPreserveExistingScale() {
        val manager = SubtitleManager(
            presentation = AndroidSubtitlePresentation.Television,
        )

        assertEquals(20f / 720f, fractionalSize(manager, SubtitleFontSizePreset.Small))
        assertEquals(26f / 720f, fractionalSize(manager, SubtitleFontSizePreset.Medium))
        assertEquals(32f / 720f, fractionalSize(manager, SubtitleFontSizePreset.Large))
        assertEquals(40f / 720f, fractionalSize(manager, SubtitleFontSizePreset.XLarge))
        assertEquals(48f / 720f, fractionalSize(manager, SubtitleFontSizePreset.XXLarge))
    }

    private fun fractionalSize(
        manager: SubtitleManager,
        preset: SubtitleFontSizePreset,
    ): Float {
        val method = SubtitleManager::class.java.getDeclaredMethod(
            "fractionalSizeFor",
            SubtitleFontSizePreset::class.java,
        )
        method.isAccessible = true
        return method.invoke(manager, preset) as Float
    }

    @Test
    fun bottomSubtitlesUseTheReferenceSafeMargin() {
        val method = SubtitleManager::class.java.getDeclaredMethod(
            "bottomPaddingFor",
            SubtitlePositionPreset::class.java,
        )
        method.isAccessible = true

        assertEquals(
            0.09f,
            method.invoke(
                SubtitleManager(),
                SubtitlePositionPreset.Bottom,
            ) as Float,
        )
    }

    @Test
    fun titleSafeCompensationDoesNotDoubleShiftTopSubtitles() {
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
        assertEquals(
            expected = (0.74f - 0.05f) / 0.90f,
            actual = method.invoke(manager, SubtitlePositionPreset.Top) as Float,
            absoluteTolerance = 0.0001f,
        )
        assertEquals(
            expected = (0.09f - 0.05f) / 0.90f,
            actual = method.invoke(manager, SubtitlePositionPreset.Bottom) as Float,
            absoluteTolerance = 0.0001f,
        )
        assertEquals(
            expected = (0.18f - 0.05f) / 0.90f,
            actual = method.invoke(manager, SubtitlePositionPreset.LowerThird) as Float,
            absoluteTolerance = 0.0001f,
        )
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
    fun zoomIgnoresStaleFittedContentFrameAndUsesFullViewport() {
        val staleFit = SubtitleVideoRect(left = 0, top = 236, width = 2404, height = 1352)
        val fullViewport = SubtitleVideoRect(left = 0, top = 0, width = 2404, height = 1080)

        assertEquals(
            fullViewport,
            selectSubtitleCanvasRect(
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM,
                contentFrameRect = staleFit,
                displayedVideoRect = fullViewport,
            ),
        )
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
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM,
                contentFrameRect = visibleCanvas,
                displayedVideoRect = fullViewport,
            ),
        )
    }

    @Test
    fun stretchIgnoresStaleFittedContentFrameAndUsesFullViewport() {
        val staleFit = SubtitleVideoRect(left = 240, top = 0, width = 1920, height = 1080)
        val fullViewport = SubtitleVideoRect(left = 0, top = 0, width = 2400, height = 1080)

        assertEquals(
            fullViewport,
            selectSubtitleCanvasRect(
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FILL,
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
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT,
                contentFrameRect = fittedFrame,
                displayedVideoRect = computedFallback,
            ),
        )
    }

    @Test
    fun repeatedModeSelectionDoesNotRetainPreviousCanvas() {
        val fit = SubtitleVideoRect(left = 240, top = 0, width = 1920, height = 1080)
        val full = SubtitleVideoRect(left = 0, top = 0, width = 2400, height = 1080)

        val fill = selectSubtitleCanvasRect(
            AspectRatioFrameLayout.RESIZE_MODE_ZOOM,
            fit,
            full,
        )
        val stretch = selectSubtitleCanvasRect(
            AspectRatioFrameLayout.RESIZE_MODE_FILL,
            fit,
            full,
        )
        val restoredFit = selectSubtitleCanvasRect(
            AspectRatioFrameLayout.RESIZE_MODE_FIT,
            fit,
            fit,
        )

        assertEquals(full, fill)
        assertEquals(full, stretch)
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
        contentFrame.layout(240, 0, 1680, 1016)
        manager.syncSubtitleVideoBounds(playerView)
        manager.postLayoutReconciliationObserver = { reconciliationCount++ }
    }

    fun schedule(resizeMode: Int) {
        playerView.resizeMode = resizeMode
        manager.syncSubtitleVideoBounds(playerView)
    }

    fun transition(resizeMode: Int, frame: FrameBounds) {
        schedule(resizeMode)
        contentFrame.layout(frame.left, frame.top, frame.right, frame.bottom)
        playerView.viewTreeObserver.dispatchOnPreDraw()
    }

    fun transitionAfterEarlyPreDraw(resizeMode: Int, finalFrame: FrameBounds) {
        schedule(resizeMode)
        playerView.viewTreeObserver.dispatchOnPreDraw()
        contentFrame.layout(
            finalFrame.left,
            finalFrame.top,
            finalFrame.right,
            finalFrame.bottom,
        )
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        // Robolectric's parent traversal has no renderer-backed aspect ratio,
        // so re-mount the observed Media3 frame before the corrective pre-draw.
        contentFrame.layout(
            finalFrame.left,
            finalFrame.top,
            finalFrame.right,
            finalFrame.bottom,
        )
        playerView.viewTreeObserver.dispatchOnPreDraw()
    }

    fun dispatchEarlyPreDrawThenMount(finalFrame: FrameBounds) {
        contentFrame.layout(-120, -64, 2040, 1080)
        playerView.viewTreeObserver.dispatchOnPreDraw()
        contentFrame.layout(
            finalFrame.left,
            finalFrame.top,
            finalFrame.right,
            finalFrame.bottom,
        )
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        // Keep the synthetic final frame mounted after Robolectric drains the
        // posted verifier and its unrelated full-width parent traversal.
        contentFrame.layout(
            finalFrame.left,
            finalFrame.top,
            finalFrame.right,
            finalFrame.bottom,
        )
        playerView.viewTreeObserver.dispatchOnPreDraw()
    }

    fun dispatchPreDraw() {
        playerView.viewTreeObserver.dispatchOnPreDraw()
    }

    fun detach() {
        activity.setContentView(FrameLayout(activity))
    }

    fun mountFrameAndDrain(frame: FrameBounds) {
        contentFrame.layout(frame.left, frame.top, frame.right, frame.bottom)
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
