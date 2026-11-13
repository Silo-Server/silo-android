package org.siloserver.silo.common.overlays

import org.siloserver.silo.model.settings.CardPosterSize
import kotlin.test.Test
import kotlin.test.assertEquals

class OverlayScaleTest {

    @Test
    fun textFactorNeverShrinksBadges() {
        assertEquals(1f, OverlayScale.textFactor(0.85f))
        assertEquals(1f, OverlayScale.textFactor(1f))
    }

    @Test
    fun textFactorFollowsLargerTextUpToTheCap() {
        assertEquals(1.15f, OverlayScale.textFactor(1.15f))
        assertEquals(1.3f, OverlayScale.textFactor(1.3f))
        assertEquals(1.35f, OverlayScale.textFactor(1.35f))
        assertEquals(1.35f, OverlayScale.textFactor(2f))
    }

    @Test
    fun textFactorIgnoresAnUnsetFontScale() {
        assertEquals(1f, OverlayScale.textFactor(Float.NaN))
    }

    @Test
    fun posterCardScaleIsWidthAgainstTheWebReference() {
        assertEquals(1f, OverlayScale.cardScale(CardOverlayVariant.Poster, 185f, CardPosterSize.Large))
        assertEquals(0.5f, OverlayScale.cardScale(CardOverlayVariant.Poster, 92.5f, CardPosterSize.Compact))
    }

    @Test
    fun wideCardScaleFollowsThePosterSizeSetting() {
        assertEquals(0.86f, OverlayScale.cardScale(CardOverlayVariant.Wide, 280f, CardPosterSize.Compact))
        assertEquals(1f, OverlayScale.cardScale(CardOverlayVariant.Wide, 280f, CardPosterSize.Standard))
        assertEquals(1.2f, OverlayScale.cardScale(CardOverlayVariant.Wide, 280f, CardPosterSize.Large))
    }

    @Test
    fun heroCardScaleIgnoresThePosterSizeSetting() {
        assertEquals(1f, OverlayScale.cardScale(CardOverlayVariant.Hero, 900f, CardPosterSize.Large))
    }

    @Test
    fun valueIsCardScaleTimesTextFactor() {
        assertEquals(1.2f * 1.35f, OverlayScale.value(cardScale = 1.2f, fontScale = 1.6f))
        assertEquals(0.86f, OverlayScale.value(cardScale = 0.86f, fontScale = 0.9f))
        assertEquals(1.3f, OverlayScale.value(cardScale = 1f, fontScale = 1.3f))
    }
}
