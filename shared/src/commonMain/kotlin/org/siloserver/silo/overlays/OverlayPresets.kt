package org.siloserver.silo.overlays

import kotlin.math.max

// MARK: - Card-relative metrics
//
// Every length below and in [OverlayPreset] is in units at the reference
// poster width: a renderer multiplies by `cardWidth / POSTER_REFERENCE_WIDTH`.
// Mirrors web's `overlayScale.ts` and `CardOverlays.tsx`.

/** The desktop Home carousel's measured overlay layer width, the visual reference. */
const val POSTER_REFERENCE_WIDTH = 185.0

/** Distance from the card edge to a corner stack, a rail and the watch-status corner. */
const val EDGE_INSET = 8.0

/** Gap between neighbouring groups (and between the top row and the status corner). */
const val EDGE_GAP = 8.0

/** Diameter of the watch-status corner ([CardWatchStatus]). */
const val WATCH_STATUS_SIZE = 22.0

/** How far the top badge row stops short of the right edge, so a badge never runs under the status corner. */
const val WATCH_STATUS_RESERVE = WATCH_STATUS_SIZE + EDGE_GAP

/** Font size of the episodes-left count in the status corner. */
const val WATCH_STATUS_FONT_SIZE = 11.0

/** A corner stack draws at most this many badges; the rest are dropped. */
const val MAX_BADGES_PER_CORNER = 3

/** An edge rail draws at most this many badges; the rest are dropped. */
const val MAX_BADGES_PER_RAIL = 4

/** An uploaded value image is this many times the height of the text badge it replaces. */
const val VALUE_IMAGE_SCALE = 1.6

// MARK: - Preset model

/** `Stack` stacks each corner's badges; `Band` draws each edge's badges as one strip of marks over a scrim. */
enum class OverlayLayout { Stack, Band }

/** A corner radius at the reference width, or fully round ends. */
sealed interface OverlayRadius {
    data object Full : OverlayRadius
    data class Fixed(val value: Double) : OverlayRadius
}

/** A text or box shadow; lengths at the reference width. */
data class OverlayShadow(
    val x: Double,
    val y: Double,
    val blur: Double,
    val spread: Double = 0.0,
    val color: OverlayColor,
)

/**
 * The colours one badge is painted with, resolved for its accent. Web's
 * `badgeStyle(accent)` plus the border colours from each preset's
 * `badgeClass`.
 */
data class OverlayBadgePaint(
    /** Fill; [OverlayColor.Transparent] for none. */
    val background: OverlayColor,
    val text: OverlayColor,
    /**
     * Colour of the border on all sides. Drawn only where the preset's
     * [OverlayPreset.borderWidth] is above 0. When the preset declares no
     * border colour this is the text colour (CSS `currentColor`, the Tailwind
     * preflight default).
     */
    val border: OverlayColor,
    /**
     * Colour of the left accent bar, [OverlayPreset.borderLeftWidth] wide;
     * `null` draws no bar. Only the square preset has one, and only with an
     * accent.
     */
    val accentBar: OverlayColor?,
    /** Shadow under the text, or `null`. */
    val textShadow: OverlayShadow?,
)

/**
 * A badge look (web `OverlayPreset`), with every length at the
 * [POSTER_REFERENCE_WIDTH] reference. Get one from [OverlayPresets.preset] and
 * layer the profile's style with [applyOverlayStyle]; colours come from
 * [paint]. Plain data: two presets with the same values are equal.
 */
data class OverlayPreset(
    val id: PresetId,
    val layout: OverlayLayout,
    /** A rating badge draws its [OverlayDef.sourceMark] as an accent-coloured chip ahead of [OverlayDef.getScore]. */
    val sourceChips: Boolean,
    /** Numeric CSS weight, 400–800. */
    val fontWeight: Int,
    val uppercase: Boolean,
    /** Letter spacing as a share of the font size (Tailwind `tracking-wide` 0.025, `tracking-widest` 0.1). */
    val letterSpacingEm: Double,
    val fontSize: Double,
    val paddingInline: Double,
    val paddingBlock: Double,
    val borderRadius: OverlayRadius,
    /** Border on all sides, or `null` for none. */
    val borderWidth: Double? = null,
    /** Width of the left accent bar ([OverlayBadgePaint.accentBar]), drawn only with an accent. */
    val borderLeftWidth: Double? = null,
    val textShadow: OverlayShadow? = null,
    val boxShadow: OverlayShadow? = null,
    val iconSize: Double,
    val iconGap: Double,
    /** Icon-prefixed badges are preferred when the item's `showIcon` is unset. */
    val preferIcon: Boolean,
    /** Gap between stacked badges. */
    val stackGap: Double,
    val accentStrategy: AccentStrategy,
    /** Colour overrides from [applyOverlayStyle]; `null` paints the preset as declared. */
    val styleColors: OverlayStyleColors? = null,
) {
    /** Height of an uploaded value image that replaces a whole badge (web `valueImageHeight`). */
    val valueImageHeight: Double
        get() = (fontSize + paddingBlock * 2) * VALUE_IMAGE_SCALE

    /** [paint] for a `#RRGGBB` accent; an unparsable accent counts as none. */
    fun paintHex(accentHex: String?): OverlayBadgePaint = paint(OverlayColor.parse(accentHex))

    /**
     * The colours a badge with [accent] (its item's `accentColor`, else the
     * registry's `defaultAccent`, else `null`) is painted with.
     */
    fun paint(accent: OverlayColor?): OverlayBadgePaint {
        val base = OverlayPresets.basePaint(id, accent)
        var background = base.background
        var text = base.text
        var border = base.border
        styleColors?.let { style ->
            val solidFill = style.solidFill
            val accentText = accent != null && accentStrategy == AccentStrategy.Text &&
                (solidFill == null || accent.contrast(solidFill) >= ACCENT_TEXT_CONTRAST)
            val accentFill = accent != null &&
                (accentStrategy == AccentStrategy.Background || (accentStrategy == AccentStrategy.Text && !accentText))
            style.background?.let { fill ->
                background = if (accentFill && accent != null) accent.mix(STYLE_ACCENT_TINT, fill) else fill
            }
            val styleText = style.textColor ?: solidFill?.readableText
            if (styleText != null && !accentText) text = styleText
            if (style.borderColor != null && !(accent != null && accentStrategy == AccentStrategy.Border)) {
                border = style.borderColor
            }
        }
        return OverlayBadgePaint(
            background = background,
            text = text,
            // No declared border colour: CSS currentColor, the final text colour.
            border = border ?: text,
            accentBar = if (borderLeftWidth != null) base.accentBar else null,
            textShadow = textShadow,
        )
    }
}

/**
 * The colour part of an [OverlayStyle], parsed once by [applyOverlayStyle].
 * [solidFill] is [background] when its alpha is at least 0.5: from there the
 * fill, not the artwork, sits behind the text.
 */
data class OverlayStyleColors(
    val background: OverlayColor?,
    val textColor: OverlayColor?,
    val borderColor: OverlayColor?,
    val solidFill: OverlayColor?,
)

/** Base paint before a style; [border] `null` means CSS `currentColor`. */
internal data class OverlayBasePaint(
    val background: OverlayColor,
    val text: OverlayColor,
    val border: OverlayColor?,
    val accentBar: OverlayColor? = null,
)

/**
 * The seven presets with the web's numbers (`web/src/lib/overlays/presets.ts`).
 * On the web `minimal`, `vibrant` and `square` follow the theme's corner
 * radius; the apps draw the web's default theme: 8, 10 and 8.
 */
object OverlayPresets {

    /** Tailwind `tracking-wide`. */
    const val TRACKING_WIDE = 0.025

    /** Tailwind `tracking-widest`. */
    const val TRACKING_WIDEST = 0.1

    val all: List<OverlayPreset> by lazy { PresetId.entries.map(::preset) }

    fun preset(id: PresetId): OverlayPreset = when (id) {
        PresetId.Minimal -> minimal
        PresetId.Classic -> classic
        PresetId.Vibrant -> vibrant
        PresetId.Pill -> pill
        PresetId.Square -> square
        PresetId.Plate -> plate
        PresetId.Band -> band
    }

    /** The preset of [prefs] with its style applied: what cards draw. */
    fun resolved(prefs: CardOverlayPrefs): OverlayPreset = applyOverlayStyle(preset(prefs.preset), prefs.style)

    private val whiteBorder15 = OverlayColor.White.withAlpha(0.15)
    private val whiteBorder10 = OverlayColor.White.withAlpha(0.10)
    private val classicFill = OverlayColor.Black.withAlpha(0.6)
    private val vibrantFill = OverlayColor.rgb(220, 220, 220, 0.95)
    private val pillFill = OverlayColor.rgb(20, 20, 30, 0.7)
    private val squareFill = OverlayColor.Black.withAlpha(0.8)
    private val plateFill = OverlayColor.Black.withAlpha(0.72)

    /** Each preset's `badgeStyle(accent)` and `badgeClass` border colour. */
    internal fun basePaint(id: PresetId, accent: OverlayColor?): OverlayBasePaint = when (id) {
        PresetId.Minimal -> OverlayBasePaint(
            background = OverlayColor.Transparent,
            text = accent ?: OverlayColor.White.withAlpha(0.85),
            border = null,
        )
        PresetId.Classic -> OverlayBasePaint(
            background = accent?.mix(0.28, classicFill) ?: classicFill,
            text = OverlayColor.White,
            border = whiteBorder15,
        )
        PresetId.Vibrant -> OverlayBasePaint(
            background = accent ?: vibrantFill,
            text = if (accent != null) OverlayColor.White else OverlayColor.Black,
            border = null,
        )
        PresetId.Pill -> OverlayBasePaint(
            background = accent?.mix(0.2, pillFill) ?: pillFill,
            text = OverlayColor.White,
            border = whiteBorder15,
        )
        PresetId.Square -> OverlayBasePaint(
            background = squareFill,
            text = accent ?: OverlayColor.White,
            border = null,
            accentBar = accent,
        )
        PresetId.Plate -> OverlayBasePaint(
            background = plateFill,
            text = OverlayColor.White,
            border = accent?.mix(0.45, OverlayColor.Transparent) ?: whiteBorder10,
        )
        PresetId.Band -> OverlayBasePaint(
            background = OverlayColor.Transparent,
            text = accent ?: OverlayColor.White,
            border = null,
        )
    }

    private val minimal = OverlayPreset(
        id = PresetId.Minimal,
        layout = OverlayLayout.Stack,
        sourceChips = false,
        fontWeight = 600,
        uppercase = true,
        letterSpacingEm = TRACKING_WIDEST,
        fontSize = 9.0,
        paddingInline = 4.0,
        paddingBlock = 0.0,
        borderRadius = OverlayRadius.Fixed(8.0),
        textShadow = OverlayShadow(x = 0.0, y = 1.0, blur = 2.0, color = OverlayColor.Black.withAlpha(0.85)),
        iconSize = 10.0,
        iconGap = 4.0,
        preferIcon = false,
        stackGap = 2.0,
        accentStrategy = AccentStrategy.Text,
    )

    private val classic = OverlayPreset(
        id = PresetId.Classic,
        layout = OverlayLayout.Stack,
        sourceChips = false,
        fontWeight = 600,
        uppercase = true,
        letterSpacingEm = TRACKING_WIDE,
        fontSize = 10.0,
        paddingInline = 8.0,
        paddingBlock = 2.0,
        borderRadius = OverlayRadius.Full,
        borderWidth = 1.0,
        iconSize = 11.0,
        iconGap = 4.0,
        preferIcon = false,
        stackGap = 4.0,
        accentStrategy = AccentStrategy.Background,
    )

    private val vibrant = OverlayPreset(
        id = PresetId.Vibrant,
        layout = OverlayLayout.Stack,
        sourceChips = false,
        fontWeight = 700,
        uppercase = true,
        letterSpacingEm = TRACKING_WIDE,
        fontSize = 10.0,
        paddingInline = 8.0,
        paddingBlock = 2.0,
        borderRadius = OverlayRadius.Fixed(10.0),
        boxShadow = OverlayShadow(x = 0.0, y = 1.0, blur = 2.0, spread = 0.0, color = OverlayColor.Black.withAlpha(0.25)),
        iconSize = 12.0,
        iconGap = 4.0,
        preferIcon = true,
        stackGap = 4.0,
        accentStrategy = AccentStrategy.Background,
    )

    private val pill = OverlayPreset(
        id = PresetId.Pill,
        layout = OverlayLayout.Stack,
        sourceChips = false,
        fontWeight = 600,
        uppercase = true,
        letterSpacingEm = TRACKING_WIDE,
        fontSize = 10.0,
        paddingInline = 10.0,
        paddingBlock = 4.0,
        borderRadius = OverlayRadius.Full,
        borderWidth = 1.0,
        iconSize = 12.0,
        iconGap = 4.0,
        preferIcon = true,
        stackGap = 4.0,
        accentStrategy = AccentStrategy.Background,
    )

    private val square = OverlayPreset(
        id = PresetId.Square,
        layout = OverlayLayout.Stack,
        sourceChips = false,
        fontWeight = 700,
        uppercase = true,
        letterSpacingEm = TRACKING_WIDEST,
        fontSize = 9.0,
        paddingInline = 6.0,
        paddingBlock = 2.0,
        borderRadius = OverlayRadius.Fixed(8.0),
        borderLeftWidth = 2.0,
        iconSize = 10.0,
        iconGap = 4.0,
        preferIcon = false,
        stackGap = 2.0,
        accentStrategy = AccentStrategy.Border,
    )

    private val plate = OverlayPreset(
        id = PresetId.Plate,
        layout = OverlayLayout.Stack,
        sourceChips = true,
        fontWeight = 800,
        uppercase = true,
        letterSpacingEm = TRACKING_WIDE,
        fontSize = 12.0,
        paddingInline = 7.0,
        paddingBlock = 4.0,
        borderRadius = OverlayRadius.Fixed(6.0),
        borderWidth = 1.0,
        boxShadow = OverlayShadow(x = 0.0, y = 2.0, blur = 6.0, spread = 0.0, color = OverlayColor.Black.withAlpha(0.35)),
        iconSize = 13.0,
        iconGap = 5.0,
        preferIcon = true,
        stackGap = 4.0,
        accentStrategy = AccentStrategy.Border,
    )

    private val band = OverlayPreset(
        id = PresetId.Band,
        layout = OverlayLayout.Band,
        sourceChips = false,
        fontWeight = 800,
        uppercase = true,
        letterSpacingEm = TRACKING_WIDE,
        fontSize = 12.5,
        paddingInline = 0.0,
        paddingBlock = 1.0,
        borderRadius = OverlayRadius.Fixed(0.0),
        textShadow = OverlayShadow(x = 0.0, y = 1.0, blur = 3.0, color = OverlayColor.Black.withAlpha(0.75)),
        iconSize = 13.0,
        iconGap = 3.0,
        preferIcon = false,
        stackGap = 7.0,
        accentStrategy = AccentStrategy.Text,
    )
}

// MARK: - Style layer (web `style.ts`)

/** The shadow a style's shadow switch adds to a preset that has none. */
val STYLE_SHADOW = OverlayShadow(x = 0.0, y = 2.0, blur = 6.0, spread = 0.0, color = OverlayColor.Black.withAlpha(0.35))

/**
 * The least padding, as a share of the font size, a badge gets when the style
 * gives it a fill or a border. Minimal and Edge band draw bare text with
 * little or no padding; framed as they are, the letters run to the frame's
 * edge and a pill's rounded ends cut into them.
 */
const val FRAMED_PADDING_INLINE = 0.6
const val FRAMED_PADDING_BLOCK = 0.2

/** From this opacity a fill, not the artwork, sits behind the text. */
const val SOLID_FILL_ALPHA = 0.5

/** The least contrast ratio at which accent-coloured text stays on a solid fill. */
const val ACCENT_TEXT_CONTRAST = 3.0

/** Share of the accent in a custom background the accent tints. */
const val STYLE_ACCENT_TINT = 0.3

/** Corner radius a style `shape` sets, replacing any preset or theme radius. */
fun OverlayShape.radius(): OverlayRadius = when (this) {
    OverlayShape.Pill -> OverlayRadius.Full
    OverlayShape.Rounded -> OverlayRadius.Fixed(6.0)
    OverlayShape.Square -> OverlayRadius.Fixed(2.0)
}

/**
 * [preset] with the viewer's [style] applied (web `applyOverlayStyle`). The
 * result is another preset, so the renderer does not care where a value came
 * from. Per-badge accents keep working: a custom background is tinted by the
 * accent in presets that fill with it, and a custom text or border colour
 * yields to the accent in presets that paint the accent there. A solid fill
 * without a chosen text colour gets black or white text, since the preset's
 * own colour was picked to read on the artwork. An accent the preset writes as
 * text that would not read on that fill tints it instead.
 */
fun applyOverlayStyle(preset: OverlayPreset, style: OverlayStyle?): OverlayPreset {
    if (style == null) return preset
    val scale = style.size ?: 1.0
    var next = preset.copy(
        fontSize = preset.fontSize * scale,
        paddingInline = preset.paddingInline * scale,
        paddingBlock = preset.paddingBlock * scale,
        iconSize = preset.iconSize * scale,
        iconGap = preset.iconGap * scale,
        stackGap = preset.stackGap * scale,
        fontWeight = style.fontWeight?.weight ?: preset.fontWeight,
        uppercase = style.uppercase ?: preset.uppercase,
    )
    style.shape?.let { next = next.copy(borderRadius = it.radius()) }
    style.borderWidth?.let { next = next.copy(borderWidth = it.toDouble()) }
    if (style.shadow == false) next = next.copy(boxShadow = null)
    if (style.shadow == true) next = next.copy(boxShadow = preset.boxShadow ?: STYLE_SHADOW)

    val background = OverlayColor.parse(style.background)
    val textColor = OverlayColor.parse(style.textColor)
    val borderColor = OverlayColor.parse(style.borderColor)
    val fillAlpha = background?.alpha ?: 0.0
    if (fillAlpha > 0.0 || (style.borderWidth ?: 0) > 0) {
        next = next.copy(
            paddingInline = max(next.paddingInline, next.fontSize * FRAMED_PADDING_INLINE),
            paddingBlock = max(next.paddingBlock, next.fontSize * FRAMED_PADDING_BLOCK),
        )
    }
    val solidFill = if (fillAlpha >= SOLID_FILL_ALPHA) background else null
    // A shadow drawn to lift text off the artwork only muddies it on a fill.
    if (solidFill != null) next = next.copy(textShadow = null)
    if (background != null || textColor != null || borderColor != null) {
        next = next.copy(styleColors = OverlayStyleColors(background, textColor, borderColor, solidFill))
    }
    return next
}
