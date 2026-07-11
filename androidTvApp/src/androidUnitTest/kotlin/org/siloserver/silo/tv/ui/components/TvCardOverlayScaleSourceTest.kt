package org.siloserver.silo.tv.ui.components

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

class TvCardOverlayScaleSourceTest {
    private val tvMediaCardSource = File(
        "src/androidMain/kotlin/org/siloserver/silo/tv/ui/components/TvMediaCard.kt",
    ).readText()

    private val tvEpisodeCardSource = File(
        "src/androidMain/kotlin/org/siloserver/silo/tv/ui/components/TvEpisodeCard.kt",
    ).readText()

    private val tvSettingsSource = File(
        "src/androidMain/kotlin/org/siloserver/silo/tv/ui/screens/settings/TvCardOverlaySettingsScreen.kt",
    ).readText()

    private val sharedCardOverlaysSource = File(
        "../android-shared/src/androidMain/kotlin/org/siloserver/silo/common/overlays/CardOverlays.kt",
    ).readText()

    private val sharedBadgeSource = File(
        "../android-shared/src/androidMain/kotlin/org/siloserver/silo/common/overlays/OverlayBadge.kt",
    ).readText()

    private val sharedPresetSource = File(
        "../android-shared/src/androidMain/kotlin/org/siloserver/silo/common/overlays/OverlayPresetStyle.kt",
    ).readText()

    @Test
    fun tvCardsUseOpticalBadgeScaleAndTranslucentFills() {
        assertTrue(tvMediaCardSource.contains("const val TvCardOverlayScale: Float = 0.7f"))
        assertTrue(tvMediaCardSource.contains("scale = TvCardOverlayScale"))
        assertTrue(tvMediaCardSource.contains("forceOpaqueBackground = false"))
        assertTrue(tvEpisodeCardSource.contains("scale = TvCardOverlayScale"))
        assertTrue(tvEpisodeCardSource.contains("forceOpaqueBackground = false"))
    }

    @Test
    fun tvOverlaySettingsPreviewUsesSameTvScaleAndOpacity() {
        assertTrue(tvSettingsSource.contains("import org.siloserver.silo.tv.ui.components.TvCardOverlayScale"))
        assertTrue(tvSettingsSource.contains("scale = TvCardOverlayScale"))
        assertTrue(tvSettingsSource.contains("forceOpaqueBackground = false"))
    }

    @Test
    fun sharedOverlayRendererKeepsMobileDefaults() {
        assertTrue(sharedCardOverlaysSource.contains("scale: Float = 1f"))
        assertTrue(sharedCardOverlaysSource.contains("forceOpaqueBackground: Boolean = false"))
        assertTrue(sharedCardOverlaysSource.contains(".scaled(scale)"))
        assertTrue(sharedCardOverlaysSource.contains("forceOpaqueBackground = forceOpaqueBackground"))
        assertTrue(sharedBadgeSource.contains("forceOpaqueBackground: Boolean = false"))
        assertTrue(sharedBadgeSource.contains("val paintedBackground = if (forceOpaqueBackground"))
        assertTrue(sharedPresetSource.contains("fun scaled(scale: Float): OverlayPresetStyle"))
    }
}
