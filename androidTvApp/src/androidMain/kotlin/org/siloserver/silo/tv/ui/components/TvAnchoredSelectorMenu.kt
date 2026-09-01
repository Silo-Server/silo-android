package org.siloserver.silo.tv.ui.components

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.DropdownMenu
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
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
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

/** Trigger chrome used by the selector without changing its anchored menu. */
internal enum class TvSelectorTriggerStyle {
    SquaredPill,
    ConnectedSegment,
    CircularAction,
}

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
internal fun TvAnchoredSelectorMenu(
    icon: ImageVector,
    label: String,
    value: String,
    options: List<TvSelectorOption>,
    modifier: Modifier = Modifier,
    triggerFocusRequester: FocusRequester? = null,
    interactive: Boolean = true,
    triggerStyle: TvSelectorTriggerStyle = TvSelectorTriggerStyle.SquaredPill,
    compactValue: String = value,
    groupExpanded: Boolean = false,
    /** Fixed selector bands make the connected trigger occupy its equal share. */
    connectedFillWidth: Boolean = false,
    /** Compact fixed bands keep their value stable instead of growing on focus. */
    connectedExpandValue: Boolean = true,
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
        when (triggerStyle) {
            TvSelectorTriggerStyle.CircularAction -> TvSquareToggleButton(
                icon = icon,
                iconActive = icon,
                isActive = false,
                contentDescription = buildString {
                    append(label)
                    value.takeIf { it.isNotBlank() }?.let { append(", ").append(it) }
                },
                onClick = { if (interactive) expansionRequested = true },
                focusRequester = triggerFr,
            )
            TvSelectorTriggerStyle.ConnectedSegment -> ConnectedSelectorTrigger(
                icon = icon,
                label = label,
                value = value,
                compactValue = compactValue,
                expanded = groupExpanded || expanded,
                interactive = interactive,
                focusRequester = triggerFr,
                fillWidth = connectedFillWidth,
                expandValue = connectedExpandValue,
                onClick = { if (interactive) expansionRequested = true },
            )
            TvSelectorTriggerStyle.SquaredPill -> SquaredPillSurface(
                kind = PillKind.Secondary,
                onClick = { if (interactive) expansionRequested = true },
                modifier = Modifier,
                focusRequester = triggerFr,
                // Deliberately NOT `enabled = interactive`. A single-choice
                // value remains focusable and simply does nothing on Select.
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
        }

        // The Material3 DropdownMenu is kept only as the anchored popup host
        // (positioning under the trigger, focus capture, dismiss-on-Back); its
        // own surface is made transparent and the content draws the same
        // Skyline glass panel, dim uppercase header, inverted-capsule rows and
        // hint footer as the top-bar cascade / For You selector, so every
        // dropdown in the app reads as one component.
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = {
                expansionRequested = false
                // Guard: the trigger may have left composition (selector row
                // reloaded on selection) — requesting focus then throws.
                runCatching { triggerFr.requestFocus() }
            },
            offset = DpOffset(0.dp, SelectorMenuGap),
            containerColor = Color.Transparent,
            tonalElevation = 0.dp,
            shadowElevation = 0.dp,
            shape = RectangleShape,
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
                    .widthIn(min = CascadeLibraryColumnWidth, max = TvCascadeSelectorMaxPanelWidth)
                    .tvSkylinePanelChrome()
                    .padding(CascadePanelPadding)
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
                CascadePanelHeader(label.uppercase())
                // The rows scroll inside a capped list while the header and
                // footer stay pinned — a long subtitle list would otherwise
                // grow the panel past the bottom of the screen.
                Box {
                Column(
                    modifier = Modifier
                        .heightIn(max = SelectorMenuMaxListHeight)
                        .selectorMenuEdgeFade(
                            fadeTop = menuScrollState.canScrollBackward,
                            fadeBottom = menuScrollState.canScrollForward,
                        )
                        .verticalScroll(menuScrollState),
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
                        SelectorMenuRow(
                            option = option,
                            focused = focused,
                            interactionSource = interactionSource,
                            modifier = Modifier
                                .focusRequester(rowFocusRequesters[index])
                                .onGloballyPositioned { coords ->
                                    // positionInParent is content-space: it does not
                                    // move when the menu scrolls, so it is a stable
                                    // scroll target.
                                    rowTops[index] = coords.positionInParent().y.toInt()
                                    rowHeights[index] = coords.size.height
                                },
                            onClick = {
                                option.onSelect()
                                expansionRequested = false
                                runCatching { triggerFr.requestFocus() }
                            },
                        )
                    }
                }
                    // Make the overflow obvious: a fade plus chevron on whichever
                    // edge still has rows beyond it.
                    SelectorMenuScrollEdge(
                        visible = menuScrollState.canScrollBackward,
                        top = true,
                        modifier = Modifier.align(Alignment.TopCenter),
                    )
                    SelectorMenuScrollEdge(
                        visible = menuScrollState.canScrollForward,
                        top = false,
                        modifier = Modifier.align(Alignment.BottomCenter),
                    )
                }
                CascadePanelFooter(caption = "Press selects · Back closes")
            }
        }
    }
}

/** One focusable segment inside the approved connected selector capsule. */
@Composable
private fun ConnectedSelectorTrigger(
    icon: ImageVector,
    label: String,
    value: String,
    compactValue: String,
    expanded: Boolean,
    interactive: Boolean,
    focusRequester: FocusRequester,
    fillWidth: Boolean,
    expandValue: Boolean,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    val foreground by animateColorAsState(
        targetValue = if (focused) Color.Black else Color.White,
        animationSpec = tween(120),
        label = "selectorSegmentForeground",
    )
    val fill by animateColorAsState(
        targetValue = if (focused) Color.White else Color.Transparent,
        animationSpec = tween(120),
        label = "selectorSegmentFill",
    )
    val showsFullValue = expandValue && (expanded || focused)

    Row(
        modifier = (if (fillWidth) {
            Modifier.fillMaxWidth()
        } else {
            Modifier.animateContentSize(animationSpec = tween(durationMillis = 280))
        })
            .height(23.dp)
            .clip(CircleShape)
            .background(fill)
            .border(
                width = if (focused) 1.25.dp else 0.dp,
                color = if (focused) Color.White.copy(alpha = 0.95f) else Color.Transparent,
                shape = CircleShape,
            )
            .focusRequester(focusRequester)
            .semantics { contentDescription = "$label, $value" }
            // Keep single-choice values focusable, matching tvOS
            // TVSelectorValue; interactivity only controls menu expansion.
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = foreground,
            modifier = Modifier.size(15.dp),
        )
        Text(
            text = if (showsFullValue) value else compactValue,
            color = foreground,
            fontWeight = FontWeight.Medium,
            fontSize = 14.sp,
            lineHeight = 17.sp,
            maxLines = 1,
        )
        if (interactive && showsFullValue) {
            Icon(
                imageVector = Icons.Filled.KeyboardArrowDown,
                contentDescription = null,
                tint = foreground.copy(alpha = 0.78f),
                modifier = Modifier.size(8.dp),
            )
        }
    }
}

private val SelectorMenuGap = 6.dp

/** Six rows of options; anything longer scrolls within the panel. */
private val SelectorMenuMaxListHeight = 230.dp
private val SelectorMenuScrollEdgeHeight = 26.dp

/**
 * Fades the rows out toward whichever edge still has more of them, by masking
 * the list's own pixels (DstIn) rather than painting a colour over it — a
 * painted fade can never quite match the panel's translucent gradient and
 * shows up as a band.
 */
private fun Modifier.selectorMenuEdgeFade(fadeTop: Boolean, fadeBottom: Boolean): Modifier {
    if (!fadeTop && !fadeBottom) return this
    return this
        .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
        .drawWithContent {
            drawContent()
            val fade = SelectorMenuScrollEdgeHeight.toPx()
            if (fadeTop) {
                drawRect(
                    brush = Brush.verticalGradient(
                        colors = listOf(Color.Transparent, Color.Black),
                        startY = 0f,
                        endY = fade,
                    ),
                    size = Size(size.width, fade),
                    blendMode = BlendMode.DstIn,
                )
            }
            if (fadeBottom) {
                drawRect(
                    brush = Brush.verticalGradient(
                        colors = listOf(Color.Black, Color.Transparent),
                        startY = size.height - fade,
                        endY = size.height,
                    ),
                    topLeft = Offset(0f, size.height - fade),
                    size = Size(size.width, fade),
                    blendMode = BlendMode.DstIn,
                )
            }
        }
}

/** Chevron over the list edge that still has rows beyond it. */
@Composable
private fun SelectorMenuScrollEdge(visible: Boolean, top: Boolean, modifier: Modifier = Modifier) {
    if (!visible) return
    Icon(
        imageVector = if (top) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
        contentDescription = null,
        tint = SiloOnSurface.copy(alpha = 0.7f),
        modifier = modifier.size(14.dp),
    )
}

/**
 * One option row, drawn with the cascade's row chrome (see `CascadeRowChrome`):
 * a leading check slot (kept even when unselected so titles stay aligned, the
 * way the cascade's leading icon does), the title in semibold and the detail
 * dimmed, inverting to a solid [SiloOnSurface] capsule on focus. Disabled rows
 * are dimmed and skipped by focus.
 */
@Composable
private fun SelectorMenuRow(
    option: TvSelectorOption,
    focused: Boolean,
    interactionSource: MutableInteractionSource,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val visual = tvSelectorRowVisualState(focused, option.selected, option.enabled)
    val shape = RoundedCornerShape(CascadeRowCornerRadius)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .clip(shape)
            .background(visual.container)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = option.enabled,
                onClick = onClick,
            )
            .semantics { this.selected = option.selected }
            .padding(horizontal = CascadeRowPaddingHorizontal, vertical = CascadeRowPaddingVertical),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Icon(
            imageVector = Icons.Filled.Check,
            contentDescription = null,
            tint = if (option.selected) visual.content else Color.Transparent,
            modifier = Modifier.size(CascadeRowIconSize),
        )
        Text(
            text = option.title,
            color = visual.content,
            fontWeight = FontWeight.SemiBold,
            fontSize = CascadeRowTextSize,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (option.detail.isNotBlank()) {
            Text(
                text = option.detail,
                color = visual.content.copy(alpha = 0.6f),
                fontWeight = FontWeight.Medium,
                fontSize = CascadeRowTextSize,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
        }
    }
}
