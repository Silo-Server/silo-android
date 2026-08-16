package org.siloserver.silo.android.ui.theme

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Phone spacing scale. The TV app has carried a `Spacing` scale since its
 * first cut; the phone never did, so every dp lived at its use site and
 * drifted section by section. This is the phone half of that pair — roughly
 * the tvOS scale halved, which is where the iPhone values in
 * `SiloTheme.swift` already sit.
 */
object Spacing {
    val xxs = 2.dp
    val xs = 4.dp
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp
    val xl = 20.dp
    val xxl = 24.dp
    val xxxl = 32.dp

    /** Horizontal inset for scrolling page content. */
    val pageGutter = 16.dp
}

/**
 * Metrics for the grouped-settings surface.
 *
 * Mirrors the Silo web client's settings pages: a lifted page ground, opaque
 * grouped cards with a generous radius, a lettered section heading sitting
 * *above* its card, and rows that are tall enough to carry a label over a
 * description. Android row mechanics are kept — the control stays trailing
 * rather than stacking under the label the way the web layout does.
 *
 * Every value the settings tree needs lives here so the next visual pass has
 * one file to edit instead of twenty-two literals spread across six files.
 */
object SettingsDimens {
    /** Page gutter for the settings list. */
    val pageGutter = Spacing.pageGutter

    /** Leading gap above the first section. */
    val pageTopPadding = Spacing.sm

    /** Trailing scroll runway so the last card clears the navigation bar. */
    val pageBottomSpacer = Spacing.xxxl

    /** Gap between two grouped cards, heading included. */
    val sectionGap = 22.dp

    /** Grouped card corner radius. */
    val cardRadius = 20.dp

    /** Inset of the section heading relative to the card's leading edge. */
    val headerStartInset = Spacing.xs

    /** Gap between a section heading and its card. */
    val headerBottomGap = Spacing.sm

    /** Minimum row height. Every row honours this so a row with a description
     *  and a row without still read as the same list. */
    val rowMinHeight = 60.dp

    /** Row content insets. */
    val rowHorizontalPadding = Spacing.lg
    val rowVerticalPadding = Spacing.md

    /** Gap between a row label and its description. */
    val rowLabelGap = Spacing.xxs

    /**
     * Gap between the row text block and its trailing control — chevron,
     * switch, radio. Never text: a trailing *value* rides on the label's own
     * line instead (see [rowLabelValueGap]), so a description can never end up
     * a hairsbreadth from it.
     */
    val rowTrailingGap = Spacing.md

    /**
     * Minimum gap between a row label and the trailing value sharing its line.
     * Only binds when the label is long enough to reach the value; a short
     * label leaves the value trailing-aligned with slack between them.
     */
    val rowLabelValueGap = Spacing.md

    /**
     * Cap on a trailing value's width, so a long one (a server URL) cannot
     * crush the label it sits beside. The value ellipsizes at this width; the
     * label wraps. Sized so the longest authored value on this surface —
     * "30 seconds before end" — still renders whole.
     */
    val rowValueMaxWidth = 170.dp

    /** Hairline between rows. */
    val dividerThickness = 1.dp

    /** Divider inset, aligned to the row label. */
    val dividerStartInset = Spacing.lg

    /** Disclosure chevron. */
    val chevronSize = 18.dp

    /** Account header avatar. */
    val avatarSize = 56.dp
    val avatarIconSize = 30.dp
    val avatarGap = 14.dp

    /** Inset for prose blocks that sit inside a card rather than on a row. */
    val proseHorizontalPadding = Spacing.lg
    val proseVerticalPadding = Spacing.md

    /** Divider opacity over [SiloSurfaceContainer]. */
    const val dividerAlpha = 0.55f

    /** Opacity applied to a disabled row's text and controls. */
    const val disabledAlpha = 0.45f
}

/**
 * Metrics for the app's popup menus.
 *
 * A menu is the settings card's terse sibling — same opaque surface, same
 * hairline, same label type — with three deliberate differences:
 *
 * - No description line, so [SettingsDimens.rowMinHeight]'s 60dp floor (which
 *   exists to carry that second line) would only pad a menu out. 48dp is the
 *   platform touch-target minimum and the height a Material menu row already
 *   uses.
 * - No leading icon, for the same reason the settings rows dropped theirs.
 * - A tighter corner than [SettingsDimens.cardRadius]: a popup is smaller than
 *   a full-width card, and the card's 20dp on a ~190dp-wide surface reads as a
 *   pill rather than as the same shape family.
 *
 * [rowHorizontalPadding] is deliberately the settings value, so a menu
 * hairline drawn at [SettingsDimens.dividerStartInset] lands exactly on the
 * label's leading edge the way it does on a settings card.
 */
object MenuDimens {
    /** Popup corner radius. */
    val cornerRadius = 16.dp

    /** Menu row height. Clears the 48dp touch-target minimum exactly. */
    val rowMinHeight = 48.dp

    val rowHorizontalPadding = SettingsDimens.rowHorizontalPadding
    val rowVerticalPadding = Spacing.sm

    /**
     * Floor on the popup's width. A menu of short labels ("Settings") would
     * otherwise size down to a sliver; Material's own menus carry a similar
     * minimum.
     */
    val minWidth = 184.dp

    /** Outline separating the popup from whatever artwork sits behind it. */
    val borderThickness = SettingsDimens.dividerThickness
}

/**
 * The hairline colour shared by every grouped row and menu row.
 */
val SiloRowDividerColor = SiloBorder.copy(alpha = SettingsDimens.dividerAlpha)

/**
 * Draws the standard inter-row hairline along this element's top edge, inset
 * from the leading edge so it starts at the row's label.
 *
 * Lives in the theme package rather than beside the settings rows because the
 * popup menus draw the same line, and two implementations of one hairline is
 * exactly the drift these tokens exist to prevent.
 */
fun Modifier.siloRowTopDivider(show: Boolean): Modifier =
    if (!show) {
        this
    } else {
        drawBehind {
            val start = SettingsDimens.dividerStartInset.toPx()
            drawRect(
                color = SiloRowDividerColor,
                topLeft = Offset(start, 0f),
                size = Size(size.width - start, SettingsDimens.dividerThickness.toPx()),
            )
        }
    }

/**
 * Type ramp for the grouped-settings surface.
 *
 * The M3 ramp in [SiloTypography] mirrors the iOS point sizes and has no slot
 * for a row description, so the settings rows previously had a single 16sp
 * label and nothing else. These four styles are the hierarchy the web client
 * uses: a lettered caps heading, a medium-weight label, a muted description,
 * and a smaller trailing value that reads as a picker's current choice rather
 * than as a second label.
 */
object SettingsTextStyles {
    val sectionHeader = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 13.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.9.sp,
    )

    val rowLabel = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 14.5.sp,
        lineHeight = 19.sp,
    )

    val rowDescription = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 12.5.sp,
        lineHeight = 17.sp,
    )

    val rowValue = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 13.5.sp,
        lineHeight = 18.sp,
    )

    val accountName = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 19.sp,
        lineHeight = 24.sp,
    )
}
