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
 * Mirrors the Apple apps' Settings (silo-apple #546): a native inset-grouped
 * list on a black ground, one-line rows with the current value trailing, a
 * title-case section header above each group and an optional footer below it.
 * Overview rows carry a graphite icon tile; sub-page rows are text only.
 *
 * Every value the settings tree needs lives here so the next visual pass has
 * one file to edit instead of literals spread across the settings files.
 */
object SettingsDimens {
    /** Page gutter for the settings list. */
    val pageGutter = Spacing.pageGutter

    /** Leading gap above the first section. */
    val pageTopPadding = Spacing.sm

    /** Trailing scroll runway so the last card clears the navigation bar. */
    val pageBottomSpacer = Spacing.xxxl

    /** Gap between two groups, header and footer included. */
    val sectionGap = 20.dp

    /** Dialog corner radius. [SiloConfirmDialog] shares it. */
    val cardRadius = 20.dp

    /** Inset-grouped list corner radius, Apple's large grouped-cell rounding. */
    val groupRadius = 26.dp

    /** Header and footer inset, aligned with the row text inside the group. */
    val headerStartInset = Spacing.lg

    /** Gap between a section header and its group. */
    val headerBottomGap = Spacing.sm

    /** Gap between a group and its footer. */
    val footerTopGap = Spacing.sm

    /** Minimum row height on a sub-page (Apple's 52pt grouped row). */
    val rowMinHeight = 52.dp

    /** Minimum height of an overview row with an icon tile (Apple's 60pt). */
    val overviewRowMinHeight = 60.dp

    /** Row content insets. */
    val rowHorizontalPadding = Spacing.lg
    val rowVerticalPadding = Spacing.md

    /** Gap between a row label and a description, where a row still has one. */
    val rowLabelGap = Spacing.xxs

    /** Gap between the row text block and its trailing control. */
    val rowTrailingGap = Spacing.sm

    /**
     * Minimum gap between a row label and the trailing value sharing its line.
     * Only binds when the label is long enough to reach the value.
     */
    val rowLabelValueGap = Spacing.md

    /**
     * Cap on a trailing value's width, so a long one (a server URL) cannot
     * crush the label it sits beside. The value ellipsizes at this width; the
     * label wraps.
     */
    val rowValueMaxWidth = 190.dp

    /** Overview icon tile: Apple's 30pt square with continuous 0.24 rounding. */
    val iconTileSize = 30.dp
    val iconTileRadius = 7.dp
    val iconGlyphSize = 17.dp

    /** Gap between the icon tile and the row label. */
    val iconTileGap = 14.dp

    /** Hairline between popup-menu rows; also the menus' border width. */
    val dividerThickness = 1.dp

    /** Hairline between grouped settings rows, Apple's single-pixel separator. */
    val separatorThickness = 0.5.dp

    /** Divider inset, aligned to the row label. */
    val dividerStartInset = Spacing.lg

    /** Divider trailing inset. */
    val dividerEndInset = Spacing.lg

    /** Disclosure chevron. */
    val chevronSize = 20.dp

    /** Menu-picker indicator, the search glyph, and a menu row's checkmark. */
    val menuIndicatorSize = 18.dp

    /** Leading slot that holds a menu row's checkmark, kept for every row. */
    val menuCheckWidth = 28.dp

    /** Account card avatar (Apple `ProfileAvatarView` at 56pt). */
    val avatarSize = 56.dp
    val avatarGap = 14.dp

    /** Settings overview search field. */
    val searchFieldHeight = 44.dp

    /** Inset for prose blocks that sit inside a card rather than on a row. */
    val proseHorizontalPadding = Spacing.lg
    val proseVerticalPadding = Spacing.md

    /** Divider opacity over [SiloSurfaceContainer], for popup menus. */
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
 * Type ramp for the grouped-settings surface: Apple's grouped list at Android
 * sizes. Row labels and values share one regular-weight size, the value in the
 * secondary colour; headers are title case and semibold; footers are small.
 */
object SettingsTextStyles {
    /** The large "Settings" title at the top of the overview. */
    val largeTitle = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Bold,
        fontSize = 34.sp,
        lineHeight = 41.sp,
    )

    val sectionHeader = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 21.sp,
    )

    val rowLabel = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 17.sp,
        lineHeight = 22.sp,
    )

    /** Section footers and the few rows that still carry a description. */
    val rowDescription = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        lineHeight = 17.sp,
    )

    val rowValue = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 17.sp,
        lineHeight = 22.sp,
    )

    val accountName = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
        lineHeight = 25.sp,
    )

    /** The account card's "Admin" capsule. */
    val badge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 12.sp,
        lineHeight = 16.sp,
    )

    /** Popup menu rows (`SiloMenuItem`), kept at the menus' own size. */
    val menuLabel = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 14.5.sp,
        lineHeight = 19.sp,
    )
}
