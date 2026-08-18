package org.siloserver.silo.android.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import org.siloserver.silo.android.ui.theme.MenuDimens
import org.siloserver.silo.android.ui.theme.SettingsTextStyles
import org.siloserver.silo.android.ui.theme.SiloBorder
import org.siloserver.silo.android.ui.theme.SiloForeground
import org.siloserver.silo.android.ui.theme.SiloSurfaceContainer
import org.siloserver.silo.android.ui.theme.siloRowTopDivider

// The popup half of the grouped-surface pass.
//
// A stock `DropdownMenu` full of `DropdownMenuItem`s renders on M3's own
// container colour at M3's own radius with M3's own `bodyLarge` label, which
// is why the profile menu read as visibly cheaper than the settings screen it
// opens. These two primitives put a menu on the same surface, radius, hairline
// and label type as a settings card — and nothing else: no descriptions, no
// leading icons, no trailing controls. A menu is terse by definition, and the
// settings rows dropped their leading icons for exactly the reason a menu
// should not gain them.

/**
 * A [DropdownMenu] wearing the grouped-surface treatment.
 *
 * Every colour the popup paints is passed explicitly. M3 would otherwise
 * default `containerColor` to `surfaceContainer` and `tonalElevation` to 3dp —
 * currently harmless (the scheme's `surfaceContainer` *is* the card colour and
 * its `surfaceTint` is transparent, so the tonal overlay resolves to nothing),
 * but harmless by coincidence of three unrelated theme values. Naming them
 * keeps a future tweak to any one of those from quietly re-tinting every menu.
 *
 * The drop shadow is left at the M3 default: a popup floats over artwork and
 * needs the lift, and unlike the tonal overlay a shadow does not push the
 * surface colour off-palette.
 */
@Composable
fun SiloDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    offset: DpOffset = DpOffset(0.dp, 0.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        offset = offset,
        shape = RoundedCornerShape(MenuDimens.cornerRadius),
        containerColor = SiloSurfaceContainer,
        tonalElevation = 0.dp,
        border = BorderStroke(MenuDimens.borderThickness, SiloBorder),
        content = content,
    )
}

/**
 * One row of a [SiloDropdownMenu]: a label, and nothing else.
 *
 * @param showDivider Draws the settings hairline above this row. Menus use it
 *   to separate groups, not to rule every row — a settings card separates its
 *   groups by being a different card, which a single popup cannot do.
 */
@Composable
fun SiloMenuItem(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    labelColor: Color = SiloForeground,
    showDivider: Boolean = false,
) {
    Row(
        // `fillMaxWidth` resolves against the menu's `IntrinsicSize.Max`
        // column, so every row ends up as wide as the widest label rather
        // than as wide as the window.
        modifier = modifier
            .fillMaxWidth()
            .widthIn(min = MenuDimens.minWidth)
            .heightIn(min = MenuDimens.rowMinHeight)
            .siloRowTopDivider(showDivider)
            .clickable(onClick = onClick)
            .padding(
                horizontal = MenuDimens.rowHorizontalPadding,
                vertical = MenuDimens.rowVerticalPadding,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = SettingsTextStyles.rowLabel,
            color = labelColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
