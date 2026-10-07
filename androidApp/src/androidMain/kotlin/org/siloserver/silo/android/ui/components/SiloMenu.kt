package org.siloserver.silo.android.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.PopupProperties
import org.siloserver.silo.android.ui.theme.MenuDimens
import org.siloserver.silo.android.ui.theme.SettingsTextStyles
import org.siloserver.silo.android.ui.theme.SiloDestructive
import org.siloserver.silo.android.ui.theme.SiloForeground

// Menus share one surface with the dialogs: the raised card colour, a faint
// hairline, a 20dp radius and a real shadow, because a popup floats over
// artwork. Rows are 48dp with an optional leading icon — the Apple clients
// label menu items with symbols, and an icon is how a viewer finds "Sign out"
// in a list without reading it. Destructive rows are red, icon and all.

internal object SiloMenuColors {
    val Surface = Color(0xFF1C1C1F)
    val Stroke = Color.White.copy(alpha = 0.07f)
    val Icon = SiloForeground.copy(alpha = 0.78f)
    val Detail = SiloForeground.copy(alpha = 0.58f)
    val Separator = Color.White.copy(alpha = 0.07f)
    val Selected = Color.White.copy(alpha = 0.07f)
}

/**
 * A [DropdownMenu] wearing the Silo menu surface. Takes the same parameters as
 * the Material menu it wraps, so any stock menu can switch to it by name.
 */
@Composable
fun SiloDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    offset: DpOffset = DpOffset(0.dp, 0.dp),
    scrollState: ScrollState = rememberScrollState(),
    properties: PopupProperties = PopupProperties(focusable = true),
    content: @Composable ColumnScope.() -> Unit,
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        modifier = modifier.widthIn(min = MenuDimens.minWidth),
        offset = offset,
        scrollState = scrollState,
        properties = properties,
        shape = RoundedCornerShape(MenuDimens.cornerRadius),
        containerColor = SiloMenuColors.Surface,
        tonalElevation = 0.dp,
        shadowElevation = 12.dp,
        border = BorderStroke(MenuDimens.borderThickness, SiloMenuColors.Stroke),
        content = content,
    )
}

/**
 * Drop-in replacement for [DropdownMenuItem] with the Silo row: 48dp, the
 * menu label type, muted leading icon, and red when [destructive].
 */
@Composable
fun SiloDropdownMenuItem(
    text: @Composable () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    leadingIcon: (@Composable () -> Unit)? = null,
    trailingIcon: (@Composable () -> Unit)? = null,
    enabled: Boolean = true,
    destructive: Boolean = false,
) {
    val labelColor = if (destructive) SiloDestructive else SiloForeground
    DropdownMenuItem(
        text = { ProvideTextStyle(SettingsTextStyles.menuLabel.copy(color = labelColor)) { text() } },
        onClick = onClick,
        modifier = modifier.heightIn(min = MenuDimens.rowMinHeight),
        leadingIcon = leadingIcon,
        trailingIcon = trailingIcon,
        enabled = enabled,
        colors = MenuDefaults.itemColors(
            textColor = labelColor,
            leadingIconColor = if (destructive) SiloDestructive else SiloMenuColors.Icon,
            trailingIconColor = SiloMenuColors.Detail,
            disabledTextColor = labelColor.copy(alpha = 0.38f),
            disabledLeadingIconColor = SiloMenuColors.Icon.copy(alpha = 0.38f),
            disabledTrailingIconColor = SiloMenuColors.Detail.copy(alpha = 0.38f),
        ),
        contentPadding = PaddingValues(horizontal = MenuDimens.rowHorizontalPadding),
    )
}

/**
 * One row of a [SiloDropdownMenu]: an optional leading [icon], the label, an
 * optional [detail] line, and a check when [checked].
 *
 * @param showDivider Draws a hairline above this row, to separate groups.
 */
@Composable
fun SiloMenuItem(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    detail: String? = null,
    checked: Boolean = false,
    destructive: Boolean = false,
    labelColor: Color = if (destructive) SiloDestructive else SiloForeground,
    showDivider: Boolean = false,
) {
    if (showDivider) SiloMenuDivider()
    SiloDropdownMenuItem(
        text = {
            Column {
                Text(
                    text = label,
                    color = labelColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!detail.isNullOrBlank()) {
                    Text(
                        text = detail,
                        color = SiloMenuColors.Detail,
                        fontSize = 12.5.sp,
                        lineHeight = 16.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        },
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .then(if (checked) Modifier.background(SiloMenuColors.Selected) else Modifier),
        leadingIcon = icon?.let { vector ->
            { Icon(imageVector = vector, contentDescription = null, modifier = Modifier.size(22.dp)) }
        },
        trailingIcon = if (checked) {
            { Icon(Icons.Rounded.Check, contentDescription = "Selected", tint = SiloForeground, modifier = Modifier.size(20.dp)) }
        } else {
            null
        },
        destructive = destructive,
    )
}

/** Hairline between groups of menu rows. */
@Composable
fun SiloMenuDivider() {
    Box(
        modifier = Modifier
            .padding(vertical = 4.dp)
            .fillMaxWidth()
            .height(1.dp)
            .background(SiloMenuColors.Separator),
    )
}

/**
 * Who and where, above a menu's actions: an avatar or mark, a name, and a
 * detail line such as the server it belongs to.
 */
@Composable
fun SiloMenuHeader(
    title: String,
    modifier: Modifier = Modifier,
    detail: String? = null,
    leading: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = MenuDimens.rowHorizontalPadding, end = MenuDimens.rowHorizontalPadding, top = 8.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) {
            leading()
            Box(Modifier.size(12.dp))
        }
        Column(modifier = Modifier.weight(1f, fill = false)) {
            Text(
                text = title,
                style = SettingsTextStyles.menuLabel.copy(fontSize = 15.sp),
                color = SiloForeground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!detail.isNullOrBlank()) {
                Text(
                    text = detail,
                    color = SiloMenuColors.Detail,
                    fontSize = 12.5.sp,
                    lineHeight = 16.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
