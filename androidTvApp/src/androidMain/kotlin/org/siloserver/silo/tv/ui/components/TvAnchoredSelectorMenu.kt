package org.siloserver.silo.tv.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MenuDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
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
    val key: String,
    val title: String,
    val detail: String,
    val selected: Boolean,
    val onSelect: () -> Unit,
    val enabled: Boolean = true,
)

internal fun selectorExpansionAfterInteractivityChange(
    expanded: Boolean,
    interactive: Boolean,
): Boolean = expanded && interactive

/**
 * The next selectable row [from] the current one, or null at the list boundary.
 *
 * The menu drives its own d-pad walk rather than leaving it to Compose's focus
 * search: the search would leave the popup once the next row was off-screen,
 * stranding every option below the fold and — because each row only scrolls
 * itself into view when it gains focus — never scrolling the list at all.
 * Disabled rows (the "Unknown" audio fallback) are stepped over, not landed on.
 */
internal fun nextSelectorMenuIndex(
    options: List<TvSelectorOption>,
    from: Int,
    forward: Boolean,
): Int? {
    val step = if (forward) 1 else -1
    var candidate = from + step
    while (candidate in options.indices) {
        if (options[candidate].enabled) return candidate
        candidate += step
    }
    return null
}

/**
 * Where the menu must scroll to so the row at [rowTop]..[rowTop] + [rowHeight]
 * is fully on screen, given the current [scroll] offset and [viewport] height.
 *
 * The menu scrolls itself rather than leaving it to `bringIntoView`: measured on
 * a Google TV Streamer, focus moved onto the below-fold rows correctly while the
 * requester scrolled nothing at all, so every row past the tenth stayed off
 * screen even though it held focus. Returns [scroll] unchanged when the row is
 * already visible, so an ordinary d-pad step does not jitter the list.
 */
internal fun selectorMenuScrollTarget(
    scroll: Int,
    rowTop: Int,
    rowHeight: Int,
    viewport: Int,
    maxValue: Int,
): Int {
    if (viewport <= 0 || rowHeight <= 0) return scroll
    val target = when {
        rowTop < scroll -> rowTop
        rowTop + rowHeight > scroll + viewport -> rowTop + rowHeight - viewport
        else -> scroll
    }
    return target.coerceIn(0, maxOf(0, maxValue))
}

/**
 * Index the menu should focus when it opens: the selected row, else the first
 * selectable one, or -1 when there is nothing selectable at all.
 *
 * Returning 0 for a list with no enabled rows would aim focus at a disabled
 * one, which cannot take it — the request fails silently and the menu opens
 * with focus nowhere.
 */
internal fun initialSelectorMenuIndex(options: List<TvSelectorOption>): Int {
    val selected = options.indexOfFirst { it.selected && it.enabled }
    if (selected >= 0) return selected
    return options.indexOfFirst { it.enabled }
}

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
    interactive: Boolean = true,
) {
    var expansionRequested by remember { mutableStateOf(false) }
    // Derived, not deferred: a LaunchedEffect would leave the dropdown drawn
    // over a trigger that has already stopped being interactive for the frame
    // it takes the effect to run. The effect below still clears the stored bit
    // so interactivity returning does not re-open a menu the viewer never
    // asked for a second time.
    val expanded = selectorExpansionAfterInteractivityChange(expansionRequested, interactive)
    LaunchedEffect(interactive) {
        expansionRequested = selectorExpansionAfterInteractivityChange(expansionRequested, interactive)
    }
    // Use the caller's requester when provided (Task 4 directs selector-row
    // focus to a specific trigger); otherwise a private one for focus-restore.
    val triggerFr = triggerFocusRequester ?: remember { FocusRequester() }
    val menuScrollState = rememberScrollState()

    // Wrapping the trigger and the DropdownMenu in the same Box anchors the
    // popup at the trigger's layout position (the menu inherits the anchor's
    // top-start), so it opens at/under the pill rather than as a centered modal.
    Box(modifier = modifier) {
        SquaredPillSurface(
            kind = PillKind.Secondary,
            onClick = { if (interactive) expansionRequested = true },
            modifier = Modifier,
            focusRequester = triggerFr,
            enabled = interactive,
            // Secondary .compact pill body padding, tvOS 40×22pt → 20×11dp,
            // +2/+1 per design review.
            contentPadding = PaddingValues(horizontal = 22.dp, vertical = 12.dp),
        ) { fg ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = fg,
                    modifier = Modifier.size(13.dp),
                )
                Spacer(Modifier.width(7.dp))
                // tvOS `TVSelectorButton`: label 18pt bold tracking 1.0 @0.6,
                // value 22pt semibold — floored at 14sp for 10-ft legibility
                // (audit 2026-07-20).
                Text(
                    text = label.uppercase(),
                    style = MaterialTheme.typography.labelLarge.copy(
                        fontSize = 14.sp,
                        lineHeight = 18.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.5.sp,
                    ),
                    color = fg.copy(alpha = 0.75f),
                    maxLines = 1,
                )
                Spacer(Modifier.width(7.dp))
                Text(
                    text = value,
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontSize = 14.sp,
                        lineHeight = 18.sp,
                        fontWeight = FontWeight.SemiBold,
                    ),
                    color = fg,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (interactive) {
                    Spacer(Modifier.width(7.dp))
                    Icon(
                        imageVector = Icons.Filled.KeyboardArrowDown,
                        contentDescription = null,
                        tint = fg.copy(alpha = 0.6f),
                        modifier = Modifier.size(9.5.dp),
                    )
                }
            }
        }

        // Known limitation: this still uses the phone Material3 DropdownMenu
        // rather than a TV-native popup. Rows provide explicit TV focus colors
        // and borders below, while a fully TV-native anchored popup (including
        // scale behavior) would require a bespoke Popup.
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = {
                expansionRequested = false
                // Guard: the trigger may have left composition (selector row
                // reloaded on selection) — requesting focus then throws.
                runCatching { triggerFr.requestFocus() }
            },
            scrollState = menuScrollState,
            containerColor = DarkSurfaceElevated,
            tonalElevation = 0.dp,
            shadowElevation = 18.dp,
        ) {
            // Own both halves of the walk: which row takes focus, and where the
            // list has to scroll for it to be visible. Compose's own focus
            // search leaves the popup once the next row is off-screen, and
            // BringIntoViewRequester was measured on a Google TV Streamer to
            // scroll this menu not at all — so a row could hold focus while
            // staying below the fold, which is what stranded every option past
            // the tenth for two release candidates.
            val rowTops = remember(options) { mutableStateMapOf<Int, Int>() }
            val rowHeights = remember(options) { mutableStateMapOf<Int, Int>() }
            val rowFocusRequesters = remember(options) { List(options.size) { FocusRequester() } }
            var focusedIndex by remember(options) { mutableStateOf(initialSelectorMenuIndex(options)) }
            LaunchedEffect(options) {
                if (focusedIndex < 0) return@LaunchedEffect
                rowFocusRequesters.getOrNull(focusedIndex)?.let { requester ->
                    runCatching { requester.requestFocus() }
                }
            }
            Column(
                // focusGroup is load-bearing, not decoration: key modifiers only
                // see events on nodes that sit in the focus hierarchy, so without
                // it the handler below is never called and the d-pad falls
                // straight through to Compose's own focus search.
                modifier = Modifier
                    .focusGroup()
                    .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    val forward = when (event.key) {
                        Key.DirectionDown -> true
                        Key.DirectionUp -> false
                        else -> return@onPreviewKeyEvent false
                    }
                    val next = nextSelectorMenuIndex(options, focusedIndex, forward)
                    if (next != null) {
                        rowFocusRequesters.getOrNull(next)?.let { requester ->
                            runCatching { requester.requestFocus() }
                        }
                    }
                    // Consume at the boundary too: a d-pad press that runs off
                    // the end must stay put rather than leak to the screen the
                    // menu is covering.
                    true
                },
            ) {
                options.forEachIndexed { index, option ->
                    val interactionSource = remember(option.key) { MutableInteractionSource() }
                    val focused by interactionSource.collectIsFocusedAsState()
                    LaunchedEffect(focused, rowTops[index], rowHeights[index]) {
                        if (!focused) return@LaunchedEffect
                        focusedIndex = index
                        val target = selectorMenuScrollTarget(
                            scroll = menuScrollState.value,
                            rowTop = rowTops[index] ?: return@LaunchedEffect,
                            rowHeight = rowHeights[index] ?: return@LaunchedEffect,
                            viewport = menuScrollState.viewportSize,
                            maxValue = menuScrollState.maxValue,
                        )
                        if (target != menuScrollState.value) menuScrollState.animateScrollTo(target)
                    }
                    val visual = tvSelectorRowVisualState(focused, option.selected, option.enabled)
                    val labelText = if (option.detail.isBlank()) {
                        option.title
                    } else {
                        "${option.title} — ${option.detail}"
                    }
                    DropdownMenuItem(
                        interactionSource = interactionSource,
                        modifier = Modifier
                            .focusRequester(rowFocusRequesters[index])
                            .onGloballyPositioned { coords ->
                                // positionInParent is content-space: it does not
                                // move when the menu scrolls, so it is a stable
                                // scroll target.
                                rowTops[index] = coords.positionInParent().y.toInt()
                                rowHeights[index] = coords.size.height
                            }
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(visual.container)
                            .border(1.dp, visual.border, RoundedCornerShape(8.dp))
                            .semantics { this.selected = option.selected },
                        enabled = option.enabled,
                        text = {
                            androidx.compose.material3.Text(
                                text = labelText,
                                style = androidx.compose.material3.MaterialTheme.typography.bodyLarge.copy(
                                    fontSize = 14.sp,
                                    lineHeight = 18.sp,
                                    fontWeight = FontWeight.Medium,
                                ),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
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
                            textColor = visual.content,
                            leadingIconColor = visual.content,
                            disabledTextColor = visual.content,
                            disabledLeadingIconColor = visual.content,
                        ),
                        onClick = {
                            option.onSelect()
                            expansionRequested = false
                            runCatching { triggerFr.requestFocus() }
                        },
                    )
                }
            }
        }
    }
}
