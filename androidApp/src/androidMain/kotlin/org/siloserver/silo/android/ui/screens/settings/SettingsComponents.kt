package org.siloserver.silo.android.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.UnfoldMore
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.siloserver.silo.android.ui.components.SiloDropdownMenu
import org.siloserver.silo.android.ui.theme.MenuDimens
import org.siloserver.silo.android.ui.theme.SettingsDimens
import org.siloserver.silo.android.ui.theme.SettingsTextStyles
import org.siloserver.silo.android.ui.theme.SiloDestructive
import org.siloserver.silo.android.ui.theme.SiloForeground
import org.siloserver.silo.android.ui.theme.SiloGroupedCell
import org.siloserver.silo.android.ui.theme.SiloGroupedSeparator
import org.siloserver.silo.android.ui.theme.SiloIconTile
import org.siloserver.silo.android.ui.theme.SiloOnSurface
import org.siloserver.silo.android.ui.theme.SiloSecondaryText
import org.siloserver.silo.android.ui.theme.SiloSwitchOff
import org.siloserver.silo.android.ui.theme.SiloSwitchOn
import kotlin.math.ceil

// --- Shared Settings UI Components ---
//
// The Apple apps' Settings in Compose (silo-apple #546): an inset-grouped list
// on a black ground, one-line rows with the current value trailing, a
// title-case header above each group and an optional footer below it. Overview
// rows carry a graphite icon tile; sub-page rows are text only, and anything a
// row would once have explained in a description line belongs in its group's
// footer. Metrics live in `ui.theme.SettingsDimens` / `SettingsTextStyles`.

/** Opacity of the disclosure chevron. */
private const val ChevronAlpha = 0.35f

/** Opacity of a menu row's up/down indicator, a little stronger than a chevron. */
private const val MenuIndicatorAlpha = 0.55f

/**
 * Draws the grouped-list hairline along this element's top edge, inset from
 * the leading edge to where the row's label starts (past the icon tile on an
 * overview row) and from the trailing edge by the row padding.
 */
fun Modifier.settingsRowDivider(
    show: Boolean,
    startInset: Dp = SettingsDimens.dividerStartInset,
): Modifier =
    if (!show) {
        this
    } else {
        drawBehind {
            val start = startInset.toPx()
            val end = SettingsDimens.dividerEndInset.toPx()
            drawRect(
                color = SiloGroupedSeparator,
                topLeft = Offset(start, 0f),
                size = Size(size.width - start - end, SettingsDimens.separatorThickness.toPx()),
            )
        }
    }

/**
 * A settings group: a title-case header above the grouped rows and an
 * optional footer below them, as in the Apple apps' inset-grouped lists.
 */
@Composable
fun SettingsSection(
    title: String?,
    modifier: Modifier = Modifier,
    footer: String? = null,
    footerColor: Color = SiloSecondaryText,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        if (!title.isNullOrBlank()) {
            SettingsSectionHeader(title)
        }
        SettingsSectionCard(content = content)
        if (!footer.isNullOrBlank()) {
            SettingsSectionFooter(footer, color = footerColor)
        }
    }
}

/**
 * Grouped rows.
 *
 * Every row draws a hairline along its top edge, and the card paints over its
 * own top edge, so whichever row is first right now — after search filters the
 * list or a row swaps in place — never shows one. Counting rows instead goes
 * stale as soon as the first row changes while the card stays composed.
 */
@Composable
fun SettingsSectionCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(SettingsDimens.groupRadius))
            .background(SiloGroupedCell)
            .drawWithContent {
                drawContent()
                drawRect(
                    color = SiloGroupedCell,
                    size = Size(size.width, ceil(SettingsDimens.separatorThickness.toPx())),
                )
            },
        content = content,
    )
}

/** Section header: title case, semibold, secondary, aligned with the row text. */
@Composable
fun SettingsSectionHeader(title: String) {
    Text(
        text = title,
        style = SettingsTextStyles.sectionHeader,
        color = SiloSecondaryText,
        modifier = Modifier.padding(
            start = SettingsDimens.headerStartInset,
            end = SettingsDimens.headerStartInset,
            bottom = SettingsDimens.headerBottomGap,
        ),
    )
}

/** Section footer: the explanation that would otherwise ride on each row. */
@Composable
fun SettingsSectionFooter(text: String, color: Color = SiloSecondaryText) {
    Text(
        text = text,
        style = SettingsTextStyles.rowDescription,
        color = color,
        modifier = Modifier.padding(
            start = SettingsDimens.headerStartInset,
            end = SettingsDimens.headerStartInset,
            top = SettingsDimens.footerTopGap,
        ),
    )
}

/** The graphite tile behind an overview row's glyph (Apple `siloIconTile`). */
@Composable
fun SettingsIconTile(icon: ImageVector, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(SettingsDimens.iconTileSize)
            .clip(RoundedCornerShape(SettingsDimens.iconTileRadius))
            .background(SiloIconTile),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(SettingsDimens.iconGlyphSize),
        )
    }
}

/** Disclosure chevron. */
@Composable
fun SettingsRowChevron(enabled: Boolean = true) {
    Icon(
        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
        contentDescription = null,
        tint = SiloOnSurface.copy(alpha = ChevronAlpha * if (enabled) 1f else SettingsDimens.disabledAlpha),
        modifier = Modifier.size(SettingsDimens.chevronSize),
    )
}

/**
 * The one settings row.
 *
 * Every other row type in this package is this one with a different trailing
 * slot: an optional icon tile, the label, the current [value] trailing on the
 * label's line, then the control. A hairline sits above every row but the
 * group's first.
 *
 * @param description A second line under the label. The Apple lists put this
 *   in the group footer instead; it remains for rows whose explanation is
 *   per-item (a diagnostics report's date and size).
 * @param showDivider Draws the hairline above the row. The group hides it on
 *   whichever row is first, so only a row that must never have one sets it
 *   false.
 */
@Composable
fun SettingsRow(
    label: String,
    modifier: Modifier = Modifier,
    description: String? = null,
    value: String? = null,
    icon: ImageVector? = null,
    labelColor: Color = SiloOnSurface,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
    showDivider: Boolean = true,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    val contentAlpha = if (enabled) 1f else SettingsDimens.disabledAlpha
    val dividerStart = if (icon != null) {
        SettingsDimens.rowHorizontalPadding + SettingsDimens.iconTileSize + SettingsDimens.iconTileGap
    } else {
        SettingsDimens.dividerStartInset
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = if (icon != null) SettingsDimens.overviewRowMinHeight else SettingsDimens.rowMinHeight)
            .settingsRowDivider(showDivider, startInset = dividerStart)
            .then(
                if (onClick != null) {
                    Modifier.clickable(enabled = enabled, onClick = onClick)
                } else {
                    Modifier
                },
            )
            .padding(
                horizontal = SettingsDimens.rowHorizontalPadding,
                vertical = SettingsDimens.rowVerticalPadding,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            SettingsIconTile(icon)
            Spacer(modifier = Modifier.width(SettingsDimens.iconTileGap))
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(SettingsDimens.rowLabelGap),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = label,
                    style = SettingsTextStyles.rowLabel,
                    color = labelColor.copy(alpha = labelColor.alpha * contentAlpha),
                    // Fills the line so the value stays trailing-aligned, and
                    // yields — by wrapping — when a capped value needs room.
                    modifier = Modifier.weight(1f),
                )
                if (value != null) {
                    Spacer(modifier = Modifier.width(SettingsDimens.rowLabelValueGap))
                    SettingsRowValue(value = value, enabled = enabled)
                }
            }
            if (!description.isNullOrBlank()) {
                Text(
                    text = description,
                    style = SettingsTextStyles.rowDescription,
                    color = SiloSecondaryText.copy(alpha = SiloSecondaryText.alpha * contentAlpha),
                )
            }
        }
        trailing()
    }
}

/** A row that navigates somewhere, optionally showing the current value. */
@Composable
fun SettingsNavigationRow(
    label: String,
    modifier: Modifier = Modifier,
    description: String? = null,
    value: String? = null,
    icon: ImageVector? = null,
    onClick: (() -> Unit)? = null,
    showChevron: Boolean = onClick != null,
    enabled: Boolean = true,
    labelColor: Color = SiloOnSurface,
) {
    SettingsRow(
        label = label,
        modifier = modifier,
        description = description,
        value = value,
        icon = icon,
        labelColor = labelColor,
        enabled = enabled,
        onClick = onClick,
    ) {
        if (showChevron) {
            Spacer(modifier = Modifier.width(SettingsDimens.rowTrailingGap))
            SettingsRowChevron(enabled = enabled)
        }
    }
}

/**
 * Destructive row: the label in [SiloDestructive] and no chevron, like the
 * Apple apps' "Reset Playback Overrides".
 */
@Composable
fun SettingsDestructiveRow(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    description: String? = null,
    value: String? = null,
    enabled: Boolean = true,
) {
    SettingsNavigationRow(
        label = label,
        modifier = modifier,
        description = description,
        value = value,
        onClick = onClick,
        showChevron = false,
        enabled = enabled,
        labelColor = SiloDestructive,
    )
}

/**
 * A full-width centred button row, the Apple apps' "Sign Out" group.
 */
@Composable
fun SettingsButtonRow(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    labelColor: Color = SiloDestructive,
    enabled: Boolean = true,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = SettingsDimens.rowMinHeight)
            .settingsRowDivider(show = true)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = SettingsDimens.rowHorizontalPadding),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = SettingsTextStyles.rowLabel,
            color = labelColor.copy(alpha = if (enabled) 1f else SettingsDimens.disabledAlpha),
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * Trailing value text in the secondary colour, so a picker's current choice
 * does not read as a second label.
 *
 * Unweighted, so [SettingsRow]'s label line measures it first: it gets the
 * width it asks for up to [SettingsDimens.rowValueMaxWidth], and the label
 * takes what is left.
 */
@Composable
private fun SettingsRowValue(value: String, enabled: Boolean) {
    Text(
        text = value,
        style = SettingsTextStyles.rowValue,
        color = SiloSecondaryText.copy(
            alpha = SiloSecondaryText.alpha * if (enabled) 1f else SettingsDimens.disabledAlpha,
        ),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        textAlign = TextAlign.End,
        modifier = Modifier.widthIn(max = SettingsDimens.rowValueMaxWidth),
    )
}

/**
 * Settings row with a switch. The whole row toggles, not just the thumb.
 */
@Composable
fun SettingsSwitchRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    description: String? = null,
    icon: ImageVector? = null,
    enabled: Boolean = true,
) {
    SettingsRow(
        label = label,
        description = description,
        icon = icon,
        enabled = enabled,
        modifier = modifier.toggleable(
            value = checked,
            enabled = enabled,
            role = Role.Switch,
            onValueChange = onCheckedChange,
        ),
    ) {
        Spacer(modifier = Modifier.width(SettingsDimens.rowTrailingGap))
        Switch(
            checked = checked,
            // The row owns the gesture; the switch is the indicator.
            onCheckedChange = null,
            enabled = enabled,
            // An empty thumb slot keeps the thumb full size in both states,
            // the way Apple's switch thumb never shrinks.
            thumbContent = {},
            colors = settingsSwitchColors(),
        )
    }
}

/** Apple's switch: green track while on, a white thumb in both states. */
@Composable
internal fun settingsSwitchColors() = SwitchDefaults.colors(
    checkedThumbColor = Color.White,
    checkedTrackColor = SiloSwitchOn,
    checkedBorderColor = Color.Transparent,
    uncheckedThumbColor = Color.White,
    uncheckedTrackColor = SiloSwitchOff,
    uncheckedBorderColor = Color.Transparent,
    disabledCheckedThumbColor = Color.White.copy(alpha = SettingsDimens.disabledAlpha),
    disabledCheckedTrackColor = SiloSwitchOn.copy(alpha = SettingsDimens.disabledAlpha),
    disabledCheckedBorderColor = Color.Transparent,
    disabledUncheckedThumbColor = Color.White.copy(alpha = SettingsDimens.disabledAlpha),
    disabledUncheckedTrackColor = SiloSwitchOff.copy(alpha = SettingsDimens.disabledAlpha),
    disabledUncheckedBorderColor = Color.Transparent,
)

/**
 * One option in a mutually exclusive set, marked with a trailing checkmark
 * the way Apple's selection lists are. Like the switch row, the whole row is
 * the target.
 */
@Composable
fun SettingsChoiceRow(
    label: String,
    selected: Boolean,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier,
    description: String? = null,
    enabled: Boolean = true,
) {
    SettingsRow(
        label = label,
        description = description,
        enabled = enabled,
        modifier = modifier.selectable(
            selected = selected,
            enabled = enabled,
            role = Role.RadioButton,
            onClick = onSelect,
        ),
    ) {
        Spacer(modifier = Modifier.width(SettingsDimens.rowTrailingGap))
        Icon(
            imageVector = Icons.Filled.Check,
            contentDescription = null,
            tint = SiloOnSurface.copy(alpha = if (selected) 1f else 0f),
            modifier = Modifier.size(SettingsDimens.chevronSize),
        )
    }
}

/**
 * A row that opens a menu of options: the current value and Apple's up/down
 * menu indicator trailing, and a popup at the trailing edge that checks the
 * current choice.
 */
@Composable
fun SettingsDropdownRow(
    label: String,
    value: String,
    options: List<String>,
    onOptionSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
    description: String? = null,
    icon: ImageVector? = null,
    enabled: Boolean = true,
) {
    var expanded by remember { mutableStateOf(false) }

    Box(modifier = modifier) {
        SettingsRow(
            label = label,
            description = description,
            value = value,
            icon = icon,
            enabled = enabled,
            onClick = { expanded = true },
        ) {
            Spacer(modifier = Modifier.width(SettingsDimens.rowTrailingGap))
            Icon(
                imageVector = Icons.Filled.UnfoldMore,
                contentDescription = null,
                tint = SiloOnSurface.copy(
                    alpha = MenuIndicatorAlpha * if (enabled) 1f else SettingsDimens.disabledAlpha,
                ),
                modifier = Modifier.size(SettingsDimens.menuIndicatorSize),
            )
        }

        // Anchored at the row's trailing edge, beside the value it changes.
        Box(modifier = Modifier.align(Alignment.BottomEnd)) {
            SiloDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
            ) {
                options.forEach { option ->
                    SettingsMenuItem(
                        label = option,
                        selected = option == value,
                        onClick = {
                            onOptionSelected(option)
                            expanded = false
                        },
                    )
                }
            }
        }
    }
}

/** A popup-menu option with a leading checkmark on the current choice. */
@Composable
private fun SettingsMenuItem(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .widthIn(min = MenuDimens.minWidth)
            .heightIn(min = MenuDimens.rowMinHeight)
            .clickable(onClick = onClick)
            .padding(horizontal = MenuDimens.rowHorizontalPadding, vertical = MenuDimens.rowVerticalPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.width(SettingsDimens.menuCheckWidth)) {
            if (selected) {
                Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = "Selected",
                    tint = SiloForeground,
                    modifier = Modifier.size(SettingsDimens.menuIndicatorSize),
                )
            }
        }
        Text(
            text = label,
            style = SettingsTextStyles.menuLabel,
            color = SiloForeground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * A prose block inside a group, for the notices that are explanation rather
 * than setting. Carries a row's hairline.
 */
@Composable
fun SettingsProse(
    body: String,
    modifier: Modifier = Modifier,
    title: String? = null,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .settingsRowDivider(show = true)
            .padding(
                horizontal = SettingsDimens.proseHorizontalPadding,
                vertical = SettingsDimens.proseVerticalPadding,
            ),
        verticalArrangement = Arrangement.spacedBy(SettingsDimens.rowLabelGap),
    ) {
        if (title != null) {
            Text(
                text = title,
                style = SettingsTextStyles.rowLabel,
                color = SiloOnSurface,
            )
        }
        Text(
            text = body,
            style = SettingsTextStyles.rowDescription,
            color = SiloSecondaryText,
        )
    }
}

/**
 * The overview's search capsule (Apple `SettingsSearchField`): a magnifier, a
 * "Search" placeholder, and a clear button once there is text.
 */
@Composable
fun SettingsSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(SettingsDimens.searchFieldHeight)
            .clip(CircleShape)
            .background(SiloGroupedCell)
            // Apple's search capsule insets its glyph 14pt.
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Filled.Search,
            contentDescription = null,
            tint = SiloSecondaryText,
            modifier = Modifier.size(SettingsDimens.menuIndicatorSize),
        )
        Spacer(modifier = Modifier.width(SettingsDimens.rowTrailingGap))
        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (query.isEmpty()) {
                Text(text = "Search", style = SettingsTextStyles.rowLabel, color = SiloSecondaryText)
            }
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                singleLine = true,
                textStyle = SettingsTextStyles.rowLabel.copy(color = SiloOnSurface),
                cursorBrush = SolidColor(SiloOnSurface),
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.None,
                    autoCorrectEnabled = false,
                    imeAction = ImeAction.Search,
                ),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (query.isNotEmpty()) {
            Icon(
                imageVector = Icons.Filled.Cancel,
                contentDescription = "Clear search",
                tint = SiloSecondaryText,
                modifier = Modifier
                    .size(SettingsDimens.menuIndicatorSize + 4.dp)
                    .clip(CircleShape)
                    .clickable { onQueryChange("") },
            )
        }
    }
}
