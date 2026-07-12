package org.siloserver.silo.tv.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MenuDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import org.siloserver.silo.tv.ui.theme.DarkSurfaceElevated
import org.siloserver.silo.tv.ui.theme.SiloOnSurface

// ---------------------------------------------------------------------------
// Anchored selector popover — Compose-for-TV port of the silo-apple tvOS
// `TVSelectorButton` + `selectorMenuItem` (TVPlaybackSelectorRow.swift).
//
// Apple renders a SwiftUI `Menu` whose label is a secondary `.compact` squared
// pill (`[icon] LABEL  value  ⌄`) and whose items are `"Title — Detail"` rows
// with a leading `checkmark` when selected. We reproduce that with the shared
// `SquaredPillSurface(kind = .Secondary)` trigger and a Material3 `DropdownMenu`
// anchored under the trigger (NOT the centered `TvOptionDialog`). The menu
// captures d-pad focus while open; on dismiss focus returns to the trigger.
//
// This component is stateless with respect to selection — the caller owns the
// selected flag + onSelect per option. The only internal state is open/closed.
// ---------------------------------------------------------------------------

/** One row of the selector menu. Mirrors Apple's `selectorMenuItem` arguments.
 *  [enabled] = false renders a non-selectable row (Apple's disabled "Unknown"
 *  audio fallback / unavailable subtitle entries). */
data class TvSelectorOption(
    val title: String,
    val detail: String,
    val selected: Boolean,
    val onSelect: () -> Unit,
    val enabled: Boolean = true,
)

/**
 * A secondary `.compact` squared pill that opens an anchored dropdown of
 * [options]. Trigger layout mirrors tvOS `TVSelectorButton` at tvOS÷2 scale
 * (`[icon] LABEL  value  ⌄`).
 *
 * Each row renders `"Title — Detail"` (the " — Detail" suffix is dropped when
 * [TvSelectorOption.detail] is blank) with a leading check when selected, like
 * `selectorMenuItem`. Selecting a row invokes its `onSelect` then closes.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun TvAnchoredSelectorMenu(
    icon: ImageVector,
    label: String,
    value: String,
    options: List<TvSelectorOption>,
    modifier: Modifier = Modifier,
    triggerFocusRequester: FocusRequester? = null,
) {
    var expanded by remember { mutableStateOf(false) }
    // Use the caller's requester when provided (Task 4 directs selector-row
    // focus to a specific trigger); otherwise a private one for focus-restore.
    val triggerFr = triggerFocusRequester ?: remember { FocusRequester() }

    // Wrapping the trigger and the DropdownMenu in the same Box anchors the
    // popup at the trigger's layout position (the menu inherits the anchor's
    // top-start), so it opens at/under the pill rather than as a centered modal.
    Box(modifier = modifier) {
        SquaredPillSurface(
            kind = PillKind.Secondary,
            onClick = { expanded = true },
            modifier = Modifier,
            focusRequester = triggerFr,
            // Secondary .compact pill body padding, tvOS 40×22pt → 20×11dp,
            // +2/+1 per design review.
            contentPadding = PaddingValues(horizontal = 22.dp, vertical = 12.dp),
        ) { fg ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = fg,
                    modifier = Modifier.size(13.dp),
                )
                Spacer(Modifier.width(7.dp))
                // tvOS `TVSelectorButton`: label 18pt bold tracking 1.0 @0.6,
                // value 22pt semibold — half scale +1 per design review.
                Text(
                    text = label.uppercase(),
                    style = MaterialTheme.typography.labelLarge.copy(
                        fontSize = 10.sp,
                        lineHeight = 12.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.5.sp,
                    ),
                    color = fg.copy(alpha = 0.6f),
                    maxLines = 1,
                )
                Spacer(Modifier.width(7.dp))
                Text(
                    text = value,
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontSize = 12.sp,
                        lineHeight = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                    ),
                    color = fg,
                    maxLines = 1,
                )
                Spacer(Modifier.width(7.dp))
                Icon(
                    imageVector = Icons.Filled.KeyboardArrowDown,
                    contentDescription = null,
                    tint = fg.copy(alpha = 0.6f),
                    modifier = Modifier.size(9.5.dp),
                )
            }
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = {
                expanded = false
                // Guard: the trigger may have left composition (selector row
                // reloaded on selection) — requesting focus then throws.
                runCatching { triggerFr.requestFocus() }
            },
            containerColor = DarkSurfaceElevated,
            tonalElevation = 0.dp,
            shadowElevation = 18.dp,
        ) {
            options.forEach { option ->
                val labelText = if (option.detail.isBlank()) {
                    option.title
                } else {
                    "${option.title} — ${option.detail}"
                }
                DropdownMenuItem(
                    enabled = option.enabled,
                    text = {
                        androidx.compose.material3.Text(
                            text = labelText,
                            style = androidx.compose.material3.MaterialTheme.typography.bodyLarge.copy(
                                fontSize = 14.sp,
                                lineHeight = 18.sp,
                                fontWeight = FontWeight.Medium,
                            ),
                        )
                    },
                    leadingIcon = if (option.selected) {
                        {
                            androidx.compose.material3.Icon(
                                imageVector = Icons.Filled.Check,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    } else {
                        null
                    },
                    colors = MenuDefaults.itemColors(
                        textColor = SiloOnSurface,
                        leadingIconColor = SiloOnSurface,
                        disabledTextColor = SiloOnSurface.copy(alpha = 0.38f),
                        disabledLeadingIconColor = SiloOnSurface.copy(alpha = 0.38f),
                    ),
                    onClick = {
                        option.onSelect()
                        expanded = false
                        runCatching { triggerFr.requestFocus() }
                    },
                )
            }
        }
    }
}
