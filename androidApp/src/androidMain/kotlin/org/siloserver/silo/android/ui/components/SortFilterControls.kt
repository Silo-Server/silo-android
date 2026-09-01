package org.siloserver.silo.android.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.siloserver.silo.android.ui.theme.SiloOnOpaqueControl
import org.siloserver.silo.android.ui.theme.SiloOpaqueControl
import org.siloserver.silo.android.ui.theme.SiloOpaqueControlBorder
import org.siloserver.silo.android.ui.theme.SiloOpaqueControlSelected

/** One entry in the sort dropdown. [selectedLabel] (e.g. "Title · A–Z") shows while active. */
data class SortMenuOption(
    val id: String,
    val label: String,
    val selectedLabel: String = label,
    /** Re-picking an active option with a direction keeps the menu open to show the flip. */
    val flipsOnReselect: Boolean = false,
    /** A divider is drawn above this entry (separates the default from the rest). */
    val dividerAbove: Boolean = false,
)

/**
 * The shared "Sort ▾ · Filter (n) · × Reset" control row used by every grid
 * that sorts and filters (Browse, Watchlist, Favorites). Sits in the grid's
 * spanning header so it scrolls with the content and stays reachable when
 * the list is empty. [trailing] renders at the end (e.g. an item count).
 */
@Composable
fun SortFilterControlsRow(
    sortLabel: String,
    sortActive: Boolean,
    sortOptions: List<SortMenuOption>,
    selectedSortId: String,
    onSelectSort: (String) -> Unit,
    filterCount: Int,
    onOpenFilters: () -> Unit,
    showReset: Boolean,
    onReset: () -> Unit,
    modifier: Modifier = Modifier,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    var sortMenuOpen by remember { mutableStateOf(false) }

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box {
            ControlPill(
                icon = Icons.AutoMirrored.Filled.Sort,
                label = sortLabel,
                active = sortActive,
                trailingChevron = true,
                onClick = { sortMenuOpen = true },
            )
            DropdownMenu(
                expanded = sortMenuOpen,
                onDismissRequest = { sortMenuOpen = false },
            ) {
                sortOptions.forEach { option ->
                    val selected = option.id == selectedSortId
                    if (option.dividerAbove) HorizontalDivider()
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = if (selected) option.selectedLabel else option.label,
                                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                            )
                        },
                        trailingIcon = if (selected) {
                            { Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(18.dp)) }
                        } else {
                            null
                        },
                        onClick = {
                            onSelectSort(option.id)
                            if (!(selected && option.flipsOnReselect)) sortMenuOpen = false
                        },
                    )
                }
            }
        }

        ControlPill(
            icon = Icons.Filled.FilterList,
            label = if (filterCount > 0) "Filter · $filterCount" else "Filter",
            active = filterCount > 0,
            onClick = onOpenFilters,
        )

        // Reset appears only once something is customised — one tap back to
        // the defaults.
        if (showReset) {
            TextButton(
                onClick = onReset,
                contentPadding = PaddingValues(horizontal = 8.dp),
                modifier = Modifier.height(34.dp),
            ) {
                Icon(Icons.Filled.Close, contentDescription = null, modifier = Modifier.size(14.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("Reset", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }
        }

        Spacer(modifier = Modifier.weight(1f))
        trailing()
    }
}

/** Same capsule as the For You saved-list pills, brightened when active. */
@Composable
private fun ControlPill(
    icon: ImageVector,
    label: String,
    active: Boolean,
    onClick: () -> Unit,
    trailingChevron: Boolean = false,
) {
    OutlinedButton(
        onClick = onClick,
        shape = CircleShape,
        contentPadding = PaddingValues(horizontal = 12.dp),
        border = BorderStroke(1.dp, SiloOpaqueControlBorder),
        colors = ButtonDefaults.outlinedButtonColors(
            contentColor = SiloOnOpaqueControl,
            containerColor = if (active) SiloOpaqueControlSelected else SiloOpaqueControl,
        ),
        modifier = Modifier.height(34.dp),
    ) {
        Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(14.dp))
        Spacer(modifier = Modifier.width(6.dp))
        Text(text = label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
        if (trailingChevron) {
            Spacer(modifier = Modifier.width(2.dp))
            Icon(
                imageVector = Icons.Filled.KeyboardArrowDown,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}
