package org.siloserver.silo.overlays

import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Pins the preset numbers, colour maths and style layer to web's `presets.ts` and `style.ts`. */
class OverlayPresetsTest {

    private val eps = 1e-12

    private fun assertColor(expected: OverlayColor, actual: OverlayColor?, message: String? = null) {
        assertTrue(actual != null, message)
        assertEquals(expected.red, actual.red, eps, message)
        assertEquals(expected.green, actual.green, eps, message)
        assertEquals(expected.blue, actual.blue, eps, message)
        assertEquals(expected.alpha, actual.alpha, eps, message)
    }

    private val gold = OverlayColor.parse("#f5c518")!!

    // MARK: - OverlayColor

    @Test
    fun parse_readsAlphaLast() {
        assertEquals(OverlayColor(1.0, 0.0, 0.0, 1.0), OverlayColor.parse("#ff0000"))
        assertEquals(OverlayColor(0.0, 0.0, 0.0, 0xb3 / 255.0), OverlayColor.parse("#000000b3"))
        assertEquals(OverlayColor(0xAA / 255.0, 0xBB / 255.0, 0xCC / 255.0, 0xDD / 255.0), OverlayColor.parse("#AABBCCDD"))
        assertNull(OverlayColor.parse("ff0000"))
        assertNull(OverlayColor.parse("#fff"))
        assertNull(OverlayColor.parse("#gg0000"))
        assertNull(OverlayColor.parse(null))
    }

    @Test
    fun mix_isCssColorMixOnPremultipliedChannels() {
        // color-mix(in srgb, #f5c518 28%, rgba(0,0,0,0.6)), worked by hand:
        // alpha = 1 × 0.28 + 0.6 × 0.72 = 0.712; each channel is
        // (c × 1 × 0.28 + 0 × 0.6 × 0.72) / 0.712.
        val alpha = 0.28 + 0.6 * 0.72
        val expected = OverlayColor(
            red = 245 / 255.0 * 0.28 / alpha,
            green = 197 / 255.0 * 0.28 / alpha,
            blue = 24 / 255.0 * 0.28 / alpha,
            alpha = alpha,
        )
        assertEquals(0.712, alpha, eps)
        assertColor(expected, gold.mix(0.28, OverlayColor.Black.withAlpha(0.6)))
        // Straight (non-premultiplied) mixing would give 0.28 × 245/255 for red.
        assertTrue(gold.mix(0.28, OverlayColor.Black.withAlpha(0.6)).red > 0.28 * 245 / 255.0 + 0.1)

        // With transparent: the colour itself at the share's alpha.
        assertColor(gold.withAlpha(0.45), gold.mix(0.45, OverlayColor.Transparent))
        // Two opaque colours: a plain linear mix.
        assertColor(OverlayColor(0.3, 0.3, 0.3), OverlayColor.White.mix(0.3, OverlayColor.Black))
        assertEquals(OverlayColor.Transparent, OverlayColor.Transparent.mix(0.5, OverlayColor.Transparent))
    }

    @Test
    fun luminanceAndContrast_areWcag() {
        assertEquals(1.0, OverlayColor.White.luminance, eps)
        assertEquals(0.0, OverlayColor.Black.luminance, eps)
        assertEquals(21.0, OverlayColor.White.contrast(OverlayColor.Black), eps)
        assertEquals(21.0, OverlayColor.Black.contrast(OverlayColor.White), eps)
        // #777777: channel 119/255 is above the 0.03928 knee.
        val c = 119 / 255.0
        val l = ((c + 0.055) / 1.055).pow(2.4)
        val grey = OverlayColor.parse("#777777")!!
        assertEquals(l, grey.luminance, eps)
        assertEquals(1.05 / (l + 0.05), grey.contrast(OverlayColor.White), eps)
        assertEquals(4.478, grey.contrast(OverlayColor.White), 1e-3)
        // Below the knee the curve is linear.
        val dark = OverlayColor.parse("#0a0a0a")!!
        assertEquals(10 / 255.0 / 12.92, dark.luminance, eps)
        // Alpha does not count.
        assertEquals(grey.luminance, grey.withAlpha(0.2).luminance, eps)
    }

    @Test
    fun readableText_isBlackAboveLuminance04() {
        assertEquals(OverlayColor.Black, OverlayColor.White.readableText)
        assertEquals(OverlayColor.White, OverlayColor.Black.readableText)
        assertEquals(OverlayColor.Black, gold.readableText) // IMDb yellow, luminance ≈ 0.59
        assertEquals(OverlayColor.White, OverlayColor.parse("#fa320a")!!.readableText) // RT red
    }

    // MARK: - Presets

    @Test
    fun presets_carryTheWebNumbers() {
        data class Row(
            val weight: Int, val tracking: Double, val font: Double, val padX: Double, val padY: Double,
            val radius: OverlayRadius, val border: Double?, val bar: Double?, val icon: Double, val iconGap: Double,
            val preferIcon: Boolean, val gap: Double, val accent: AccentStrategy,
        )
        val wide = OverlayPresets.TRACKING_WIDE
        val widest = OverlayPresets.TRACKING_WIDEST
        val full = OverlayRadius.Full
        fun r(v: Double) = OverlayRadius.Fixed(v)
        val expected = mapOf(
            PresetId.Minimal to Row(600, widest, 9.0, 4.0, 0.0, r(8.0), null, null, 10.0, 4.0, false, 2.0, AccentStrategy.Text),
            PresetId.Classic to Row(600, wide, 10.0, 8.0, 2.0, full, 1.0, null, 11.0, 4.0, false, 4.0, AccentStrategy.Background),
            PresetId.Vibrant to Row(700, wide, 10.0, 8.0, 2.0, r(10.0), null, null, 12.0, 4.0, true, 4.0, AccentStrategy.Background),
            PresetId.Pill to Row(600, wide, 10.0, 10.0, 4.0, full, 1.0, null, 12.0, 4.0, true, 4.0, AccentStrategy.Background),
            PresetId.Square to Row(700, widest, 9.0, 6.0, 2.0, r(8.0), null, 2.0, 10.0, 4.0, false, 2.0, AccentStrategy.Border),
            PresetId.Plate to Row(800, wide, 12.0, 7.0, 4.0, r(6.0), 1.0, null, 13.0, 5.0, true, 4.0, AccentStrategy.Border),
            PresetId.Band to Row(800, wide, 12.5, 0.0, 1.0, r(0.0), null, null, 13.0, 3.0, false, 7.0, AccentStrategy.Text),
        )
        for ((id, row) in expected) {
            val p = OverlayPresets.preset(id)
            assertEquals(
                row,
                Row(
                    p.fontWeight, p.letterSpacingEm, p.fontSize, p.paddingInline, p.paddingBlock, p.borderRadius,
                    p.borderWidth, p.borderLeftWidth, p.iconSize, p.iconGap, p.preferIcon, p.stackGap, p.accentStrategy,
                ),
                id.raw,
            )
            assertTrue(p.uppercase)
            assertEquals(id, p.id)
            assertEquals(id == PresetId.Plate, p.sourceChips)
            assertEquals(if (id == PresetId.Band) OverlayLayout.Band else OverlayLayout.Stack, p.layout)
        }
        assertEquals(OverlayShadow(0.0, 1.0, 2.0, 0.0, OverlayColor.Black.withAlpha(0.85)), OverlayPresets.preset(PresetId.Minimal).textShadow)
        assertEquals(OverlayShadow(0.0, 1.0, 3.0, 0.0, OverlayColor.Black.withAlpha(0.75)), OverlayPresets.preset(PresetId.Band).textShadow)
        assertEquals(OverlayShadow(0.0, 1.0, 2.0, 0.0, OverlayColor.Black.withAlpha(0.25)), OverlayPresets.preset(PresetId.Vibrant).boxShadow)
        assertEquals(OverlayShadow(0.0, 2.0, 6.0, 0.0, OverlayColor.Black.withAlpha(0.35)), OverlayPresets.preset(PresetId.Plate).boxShadow)
        assertEquals((12.0 + 8.0) * 1.6, OverlayPresets.preset(PresetId.Plate).valueImageHeight, eps)
    }

    @Test
    fun paint_followsEachPresetsBadgeStyle() {
        fun paint(id: PresetId, accent: OverlayColor?) = OverlayPresets.preset(id).paint(accent)
        val white15 = OverlayColor.White.withAlpha(0.15)

        paint(PresetId.Minimal, null).let {
            assertEquals(OverlayColor.Transparent, it.background)
            assertEquals(OverlayColor.White.withAlpha(0.85), it.text)
            assertEquals(it.text, it.border)
            assertTrue(it.textShadow != null)
        }
        assertEquals(gold, paint(PresetId.Minimal, gold).text)

        paint(PresetId.Classic, null).let {
            assertEquals(OverlayColor.Black.withAlpha(0.6), it.background)
            assertEquals(OverlayColor.White, it.text)
            assertEquals(white15, it.border)
            assertNull(it.accentBar)
            assertNull(it.textShadow)
        }
        assertColor(gold.mix(0.28, OverlayColor.Black.withAlpha(0.6)), paint(PresetId.Classic, gold).background)

        assertEquals(OverlayColor.rgb(220, 220, 220, 0.95), paint(PresetId.Vibrant, null).background)
        assertEquals(OverlayColor.Black, paint(PresetId.Vibrant, null).text)
        assertEquals(gold, paint(PresetId.Vibrant, gold).background)
        assertEquals(OverlayColor.White, paint(PresetId.Vibrant, gold).text)

        assertEquals(OverlayColor.rgb(20, 20, 30, 0.7), paint(PresetId.Pill, null).background)
        assertColor(gold.mix(0.2, OverlayColor.rgb(20, 20, 30, 0.7)), paint(PresetId.Pill, gold).background)
        assertEquals(white15, paint(PresetId.Pill, gold).border)

        paint(PresetId.Square, gold).let {
            assertEquals(OverlayColor.Black.withAlpha(0.8), it.background)
            assertEquals(gold, it.text)
            assertEquals(gold, it.accentBar)
        }
        assertNull(paint(PresetId.Square, null).accentBar)
        assertEquals(OverlayColor.White, paint(PresetId.Square, null).text)

        assertEquals(OverlayColor.Black.withAlpha(0.72), paint(PresetId.Plate, null).background)
        assertEquals(OverlayColor.White.withAlpha(0.10), paint(PresetId.Plate, null).border)
        assertColor(gold.withAlpha(0.45), paint(PresetId.Plate, gold).border)

        paint(PresetId.Band, gold).let {
            assertEquals(OverlayColor.Transparent, it.background)
            assertEquals(gold, it.text)
            assertTrue(it.textShadow != null)
        }
        // An accent that is not #RRGGBB counts as none.
        assertEquals(paint(PresetId.Classic, null), OverlayPresets.preset(PresetId.Classic).paintHex("gold"))
    }

    // MARK: - applyOverlayStyle

    private fun styled(id: PresetId, style: OverlayStyle?) = applyOverlayStyle(OverlayPresets.preset(id), style)

    @Test
    fun noStyle_isThePreset() {
        assertEquals(OverlayPresets.preset(PresetId.Classic), styled(PresetId.Classic, null))
    }

    @Test
    fun size_scalesTextPaddingIconsAndGaps() {
        val p = styled(PresetId.Classic, OverlayStyle(size = 1.5))
        assertEquals(15.0, p.fontSize, eps)
        assertEquals(12.0, p.paddingInline, eps)
        assertEquals(3.0, p.paddingBlock, eps)
        assertEquals(16.5, p.iconSize, eps)
        assertEquals(6.0, p.iconGap, eps)
        assertEquals(6.0, p.stackGap, eps)
        // Not scaled: border, radius, shadows.
        assertEquals(1.0, p.borderWidth)
        assertEquals(OverlayRadius.Full, p.borderRadius)
    }

    @Test
    fun weightUppercaseAndShape() {
        assertEquals(400, styled(PresetId.Plate, OverlayStyle(fontWeight = OverlayFontWeight.Regular)).fontWeight)
        assertEquals(600, styled(PresetId.Plate, OverlayStyle(fontWeight = OverlayFontWeight.Semibold)).fontWeight)
        assertEquals(800, styled(PresetId.Minimal, OverlayStyle(fontWeight = OverlayFontWeight.Extrabold)).fontWeight)
        assertEquals(false, styled(PresetId.Classic, OverlayStyle(uppercase = false)).uppercase)
        assertEquals(OverlayRadius.Full, styled(PresetId.Minimal, OverlayStyle(shape = OverlayShape.Pill)).borderRadius)
        assertEquals(OverlayRadius.Fixed(6.0), styled(PresetId.Classic, OverlayStyle(shape = OverlayShape.Rounded)).borderRadius)
        assertEquals(OverlayRadius.Fixed(2.0), styled(PresetId.Vibrant, OverlayStyle(shape = OverlayShape.Square)).borderRadius)
    }

    @Test
    fun borderWidth_replacesThePresets_andFramesBareText() {
        assertEquals(0.0, styled(PresetId.Classic, OverlayStyle(borderWidth = 0)).borderWidth)
        // A zero border is no frame: the padding stays.
        assertEquals(0.0, styled(PresetId.Band, OverlayStyle(borderWidth = 0)).paddingInline)
        val framed = styled(PresetId.Band, OverlayStyle(borderWidth = 2))
        assertEquals(2.0, framed.borderWidth)
        assertEquals(12.5 * 0.6, framed.paddingInline, eps)
        assertEquals(12.5 * 0.2, framed.paddingBlock, eps)
        // The floor never lowers a preset's own padding.
        val pill = styled(PresetId.Pill, OverlayStyle(borderWidth = 2))
        assertEquals(10.0, pill.paddingInline, eps)
        assertEquals(4.0, pill.paddingBlock, eps)
    }

    @Test
    fun borderWithoutAColour_isTheTextColour() {
        // Tailwind v4's preflight sets `border: 0 solid`, so the colour is
        // currentColor: the badge's final text colour.
        val minimal = styled(PresetId.Minimal, OverlayStyle(borderWidth = 2))
        assertEquals(minimal.paint(null).text, minimal.paint(null).border)
        assertEquals(gold, minimal.paint(gold).border)
        val green = OverlayColor.parse("#00ff00")!!
        val withText = styled(PresetId.Vibrant, OverlayStyle(borderWidth = 1, textColor = "#00ff00"))
        assertEquals(green, withText.paint(null).border)
        // A preset's own border colour stays.
        assertEquals(OverlayColor.White.withAlpha(0.15), styled(PresetId.Classic, OverlayStyle(borderWidth = 3)).paint(null).border)
    }

    @Test
    fun shadowSwitch() {
        assertNull(styled(PresetId.Vibrant, OverlayStyle(shadow = false)).boxShadow)
        assertEquals(STYLE_SHADOW, styled(PresetId.Classic, OverlayStyle(shadow = true)).boxShadow)
        assertEquals(OverlayPresets.preset(PresetId.Vibrant).boxShadow, styled(PresetId.Vibrant, OverlayStyle(shadow = true)).boxShadow)
        assertEquals(OverlayShadow(0.0, 2.0, 6.0, 0.0, OverlayColor.Black.withAlpha(0.35)), STYLE_SHADOW)
    }

    @Test
    fun translucentFill_framesButKeepsTextAndShadow() {
        val p = styled(PresetId.Minimal, OverlayStyle(background = "#00000040"))
        assertEquals(9.0 * 0.6, p.paddingInline, eps)
        assertEquals(9.0 * 0.2, p.paddingBlock, eps)
        assertTrue(p.textShadow != null)
        assertEquals(OverlayColor.parse("#00000040"), p.paint(null).background)
        assertEquals(OverlayColor.White.withAlpha(0.85), p.paint(null).text)
        // An accent the preset writes as text keeps that role off a solid fill.
        assertEquals(gold, p.paint(gold).text)
        assertEquals(OverlayColor.parse("#00000040"), p.paint(gold).background)
    }

    @Test
    fun solidFill_dropsTextShadow_andPicksReadableText() {
        val dark = styled(PresetId.Minimal, OverlayStyle(background = "#000000cc"))
        assertNull(dark.textShadow)
        assertNull(dark.paint(null).textShadow)
        assertEquals(OverlayColor.White, dark.paint(null).text)
        // Exactly 50% is solid.
        val light = styled(PresetId.Band, OverlayStyle(background = "#ffffff80"))
        assertNull(light.textShadow)
        assertEquals(OverlayColor.Black, light.paint(null).text)
        // A chosen text colour wins.
        val red = styled(PresetId.Band, OverlayStyle(background = "#ffffff80", textColor = "#ff0000"))
        assertEquals(OverlayColor(1.0, 0.0, 0.0), red.paint(null).text)
    }

    @Test
    fun accentText_staysOnlyAtContrast3_elseTintsTheFill() {
        val black = OverlayColor.Black
        val p = styled(PresetId.Minimal, OverlayStyle(background = "#000000ff"))
        // Gold on black reads (contrast ≈ 12.8): the accent stays the text.
        assertTrue(gold.contrast(black) >= 3.0)
        assertEquals(gold, p.paint(gold).text)
        assertEquals(black, p.paint(gold).background)
        // #333333 on black does not (≈ 1.66): readable text, accent tints the fill.
        val dim = OverlayColor.parse("#333333")!!
        assertTrue(dim.contrast(black) < 3.0)
        assertEquals(OverlayColor.White, p.paint(dim).text)
        assertColor(dim.mix(0.3, black), p.paint(dim).background)
        // Even a chosen text colour yields to an accent that reads.
        val withText = styled(PresetId.Band, OverlayStyle(background = "#000000ff", textColor = "#00ff00"))
        assertEquals(gold, withText.paint(gold).text)
        assertEquals(OverlayColor(0.0, 1.0, 0.0), withText.paint(dim).text)
    }

    @Test
    fun backgroundStrategy_tintsTheCustomFill() {
        val p = styled(PresetId.Classic, OverlayStyle(background = "#ffffffff"))
        assertColor(gold.mix(0.3, OverlayColor.White), p.paint(gold).background)
        assertEquals(OverlayColor.White, p.paint(null).background)
        // Readable text on the (untinted) white fill.
        assertEquals(OverlayColor.Black, p.paint(gold).text)
        // Classic's text is white by design; a translucent fill keeps it.
        val translucent = styled(PresetId.Classic, OverlayStyle(background = "#ffffff33"))
        assertEquals(OverlayColor.White, translucent.paint(null).text)
        assertColor(gold.mix(0.3, OverlayColor.parse("#ffffff33")!!), translucent.paint(gold).background)
    }

    @Test
    fun borderColour_yieldsToTheAccentInBorderStrategyPresets() {
        val red = OverlayColor.parse("#ff0000ff")!!
        val plate = styled(PresetId.Plate, OverlayStyle(borderColor = "#ff0000ff"))
        assertEquals(red, plate.paint(null).border)
        assertColor(gold.withAlpha(0.45), plate.paint(gold).border)
        // A background-strategy preset takes the colour with or without an accent.
        val classic = styled(PresetId.Classic, OverlayStyle(borderColor = "#ff0000ff"))
        assertEquals(red, classic.paint(gold).border)
        // Square keeps its accent bar.
        val square = styled(PresetId.Square, OverlayStyle(borderColor = "#ff0000ff", borderWidth = 1))
        assertEquals(gold, square.paint(gold).accentBar)
        assertEquals(gold, square.paint(gold).border) // currentColor: square's text is the accent
        assertEquals(red, square.paint(null).border)
    }

    @Test
    fun resolved_appliesTheDocumentsStyle() {
        val prefs = OverlaySchema.buildDefaults().copy(preset = PresetId.Pill, style = OverlayStyle(size = 2.0))
        assertEquals(20.0, OverlayPresets.resolved(prefs).fontSize, eps)
    }

    @Test
    fun metrics() {
        assertEquals(185.0, POSTER_REFERENCE_WIDTH)
        assertEquals(8.0, EDGE_INSET)
        assertEquals(8.0, EDGE_GAP)
        assertEquals(22.0, WATCH_STATUS_SIZE)
        assertEquals(30.0, WATCH_STATUS_RESERVE)
        assertEquals(3, MAX_BADGES_PER_CORNER)
        assertEquals(4, MAX_BADGES_PER_RAIL)
        assertEquals(1.6, VALUE_IMAGE_SCALE)
    }
}
