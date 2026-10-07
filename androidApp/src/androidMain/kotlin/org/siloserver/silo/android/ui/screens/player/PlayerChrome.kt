package org.siloserver.silo.android.ui.screens.player

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import org.siloserver.silo.android.ui.screens.settings.settingsSwitchColors
import org.siloserver.silo.android.ui.theme.SiloDestructive
import org.siloserver.silo.android.ui.theme.SiloInterFamily

/**
 * The video player's visual language, shared by the controls and every player
 * menu. Monochrome like the Apple clients: smoked panels over the picture,
 * Paper for whatever is selected or focused, Inter with tabular figures for
 * anything that counts time. There is no live blur on Android, so "glass" is a
 * dark wash at a fixed opacity — the same fallback iOS uses over video on
 * devices that cannot afford to re-blur every frame.
 */
internal object PlayerChrome {
    val Paper = Color(0xFFEDEDED)
    val Ink = Color(0xFF0B0B0C)
    val Graphite = Paper.copy(alpha = 0.60f)
    val Faint = Paper.copy(alpha = 0.38f)

    /** Docked panels and sheets over the picture. */
    val Smoke = Color(0xFF131417).copy(alpha = 0.955f)

    /** Resting fill of a disc or pill floating directly on the video. */
    val Disc = Color(0xFF16171A).copy(alpha = 0.55f)
    val DiscStroke = Color.White.copy(alpha = 0.13f)
    val PanelStroke = Color.White.copy(alpha = 0.09f)

    /** Wash behind a selected row, and the resting fill of in-panel controls. */
    val Raised = Color.White.copy(alpha = 0.075f)
    val Group = Color.White.copy(alpha = 0.05f)
    val GroupSeparator = Color.White.copy(alpha = 0.06f)
    val Chip = Color.White.copy(alpha = 0.10f)

    /** The one destructive tint, shared with the rest of the app. */
    val Stop = SiloDestructive

    val DiscSize = 40.dp
    val TouchSize = 48.dp
    val PanelRadius = 24.dp
    val PanelWidth = 372.dp
}

/** Type for the player chrome. Inter throughout; timecodes use tabular figures. */
internal object PlayerType {
    private val base = TextStyle(fontFamily = SiloInterFamily, color = PlayerChrome.Paper)

    val Eyebrow = base.copy(
        fontSize = 10.5.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.09.em,
        color = PlayerChrome.Graphite,
    )
    val HudTitle = base.copy(fontSize = 15.5.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.01).em)
    val Time = base.copy(fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum")
    val PillLabel = base.copy(fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    val PillValue = base.copy(fontSize = 13.sp, fontWeight = FontWeight.Medium, color = PlayerChrome.Graphite)
    val PanelTitle = base.copy(fontSize = 17.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.01).em)
    val RowTitle = base.copy(fontSize = 14.5.sp, fontWeight = FontWeight.Medium, lineHeight = 19.sp)
    val RowDetail = base.copy(fontSize = 11.5.sp, color = PlayerChrome.Graphite, lineHeight = 15.sp)
    val Value = base.copy(fontSize = 13.sp, color = PlayerChrome.Graphite, fontFeatureSettings = "tnum")
    val Section = base.copy(
        fontSize = 10.5.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.1.em,
        color = PlayerChrome.Graphite,
    )
    val Segment = base.copy(fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    val SmallButton = base.copy(fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
    val Chip = base.copy(
        fontSize = 9.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 0.07.em,
        color = PlayerChrome.Paper.copy(alpha = 0.78f),
    )
    val FieldLabel = base.copy(fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = PlayerChrome.Graphite)
    val Footnote = base.copy(fontSize = 12.sp, color = PlayerChrome.Graphite, lineHeight = 16.sp)
    val BubbleTime = base.copy(
        fontSize = 24.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = (-0.01).em,
        fontFeatureSettings = "tnum",
    )
    val BubbleDetail = base.copy(
        fontSize = 11.5.sp,
        fontWeight = FontWeight.SemiBold,
        color = PlayerChrome.Paper.copy(alpha = 0.62f),
        fontFeatureSettings = "tnum",
    )
}

// ---------------------------------------------------------------------------
// Controls that sit directly on the picture
// ---------------------------------------------------------------------------

/**
 * A smoked disc on the video. The visual disc is [size]; the touch target is
 * never smaller than 48dp, so a row of discs keeps Android's minimum target
 * without the discs themselves growing.
 */
@Composable
internal fun PlayerDisc(
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = PlayerChrome.DiscSize,
    enabled: Boolean = true,
    fill: Color = PlayerChrome.Disc,
    contentColor: Color = PlayerChrome.Paper,
    stroke: Color = PlayerChrome.DiscStroke,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = modifier
            .minimumInteractiveComponentSize()
            .size(size)
            .clip(CircleShape)
            .background(fill)
            .border(1.dp, stroke, CircleShape)
            // The disc is the control a screen reader names; its glyph says nothing.
            .semantics { this.contentDescription = contentDescription }
            .clickable(
                enabled = enabled,
                role = Role.Button,
                onClickLabel = contentDescription,
                onClick = onClick,
            )
            .alpha(if (enabled) 1f else 0.32f),
        contentAlignment = Alignment.Center,
    ) {
        Box(modifier = Modifier.clearAndSetSemantics {}, contentAlignment = Alignment.Center) {
            CompositionLocalProvider(LocalContentColor provides contentColor) {
                content()
            }
        }
    }
}

@Composable
internal fun PlayerIconDisc(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = PlayerChrome.DiscSize,
    iconSize: Dp = 22.dp,
    enabled: Boolean = true,
) {
    PlayerDisc(
        contentDescription = contentDescription,
        onClick = onClick,
        modifier = modifier,
        size = size,
        enabled = enabled,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = PlayerChrome.Paper,
            modifier = Modifier.size(iconSize),
        )
    }
}

/**
 * A labeled control on the bottom row: icon, what it opens, and what it is set
 * to now ("Chapters · Scene 10"). Compact mode keeps only the icon for narrow
 * windows, the way iOS falls back through `ViewThatFits`.
 */
@Composable
internal fun PlayerActionPill(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    value: String? = null,
    compact: Boolean = false,
    enabled: Boolean = true,
) {
    val shape = RoundedCornerShape(18.dp)
    Row(
        modifier = modifier
            .minimumInteractiveComponentSize()
            .height(36.dp)
            .clip(shape)
            .background(PlayerChrome.Disc)
            .border(1.dp, PlayerChrome.DiscStroke, shape)
            .clickable(enabled = enabled, role = Role.Button, onClickLabel = label, onClick = onClick)
            .alpha(if (enabled) 1f else 0.32f)
            .padding(start = if (compact) 8.5.dp else 11.dp, end = if (compact) 8.5.dp else 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = if (compact) label else null,
            tint = PlayerChrome.Paper,
            modifier = Modifier.size(19.dp),
        )
        if (!compact) {
            Text(text = label, style = PlayerType.PillLabel, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!value.isNullOrBlank()) {
                // The value gives way first when the pill is squeezed.
                Text(
                    text = value,
                    style = PlayerType.PillValue,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .widthIn(max = 160.dp),
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Panel content: shared by the docked landscape panel and the portrait sheet
// ---------------------------------------------------------------------------

/** Title row of a player menu, with an optional back chevron and a close disc. */
@Composable
internal fun PlayerPanelHeader(
    title: String,
    onClose: () -> Unit,
    onBack: (() -> Unit)? = null,
    backDescription: String = "Back",
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = if (onBack != null) 4.dp else 18.dp, end = 6.dp, top = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            Box(
                modifier = Modifier
                    .size(PlayerChrome.TouchSize)
                    .clip(CircleShape)
                    .clickable(onClickLabel = backDescription, role = Role.Button, onClick = onBack),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowLeft,
                    contentDescription = backDescription,
                    tint = PlayerChrome.Paper,
                    modifier = Modifier.size(26.dp),
                )
            }
        }
        Text(
            text = title,
            style = PlayerType.PanelTitle,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        PlayerDisc(
            contentDescription = "Close",
            onClick = onClose,
            size = 32.dp,
            fill = Color.White.copy(alpha = 0.08f),
            stroke = Color.Transparent,
        ) {
            Icon(
                imageVector = Icons.Rounded.Close,
                contentDescription = null,
                tint = PlayerChrome.Paper,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

internal data class PlayerSegment(val label: String, val count: Int? = null)

/**
 * Pill segmented control. The selected segment inverts to Paper, the same
 * rule the TV uses for focus.
 */
@Composable
internal fun PlayerSegmentedControl(
    segments: List<PlayerSegment>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(19.dp))
            .background(PlayerChrome.Raised)
            .padding(3.dp),
    ) {
        segments.forEachIndexed { index, segment ->
            val selected = index == selectedIndex
            Row(
                modifier = Modifier
                    .weight(1f)
                    .height(32.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(if (selected) PlayerChrome.Paper else Color.Transparent)
                    .selectable(selected = selected, role = Role.Tab, onClick = { onSelect(index) }),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val color = if (selected) PlayerChrome.Ink else PlayerChrome.Graphite
                Text(text = segment.label, style = PlayerType.Segment.copy(color = color), maxLines = 1)
                segment.count?.let { count ->
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = count.toString(),
                        style = PlayerType.Segment.copy(
                            color = color.copy(alpha = color.alpha * 0.55f),
                            fontWeight = FontWeight.Medium,
                            fontSize = 12.sp,
                        ),
                    )
                }
            }
        }
    }
}

/**
 * Compact segmented control for value choices inside a panel (subtitle size,
 * background, position). Selection is a raised wash rather than an inversion
 * so a page of them does not turn into a wall of white.
 */
@Composable
internal fun PlayerChoiceSegments(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(PlayerChrome.Raised)
            .padding(3.dp),
    ) {
        val segmentShape = RoundedCornerShape(9.dp)
        options.forEachIndexed { index, label ->
            val selected = index == selectedIndex
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(32.dp)
                    .clip(segmentShape)
                    .then(
                        if (selected) {
                            Modifier
                                .background(Color.White.copy(alpha = 0.16f))
                                .border(1.dp, Color.White.copy(alpha = 0.12f), segmentShape)
                        } else {
                            Modifier
                        },
                    )
                    .selectable(selected = selected, role = Role.RadioButton, onClick = { onSelect(index) }),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label,
                    style = PlayerType.SmallButton.copy(
                        color = if (selected) PlayerChrome.Paper else PlayerChrome.Graphite,
                    ),
                    maxLines = 1,
                )
            }
        }
    }
}

/** "SDH", "FORCED": short facts about a track that belong next to its name. */
@Composable
internal fun PlayerChip(text: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(4.dp))
            .background(PlayerChrome.Chip)
            .padding(horizontal = 5.dp, vertical = 1.5.dp),
    ) {
        Text(text = text.uppercase(), style = PlayerType.Chip, maxLines = 1)
    }
}

/**
 * One selectable track or option: title (with chips), an optional detail line,
 * and a trailing check when selected. The selected row also gets a soft wash.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun PlayerTrackRow(
    title: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    detail: String? = null,
    chips: List<String> = emptyList(),
    enabled: Boolean = true,
    leading: (@Composable () -> Unit)? = null,
) {
    // A long list opens with the current choice on screen, not row one, and
    // with the rows after it: revealing the row alone parked it under the
    // list's bottom fade.
    val revealRequester = remember { BringIntoViewRequester() }
    var rowSize by remember { mutableStateOf(IntSize.Zero) }
    val revealContextPx = with(LocalDensity.current) { 96.dp.toPx() }
    if (selected && rowSize != IntSize.Zero) {
        LaunchedEffect(Unit) {
            revealRequester.bringIntoView(
                Rect(0f, 0f, rowSize.width.toFloat(), rowSize.height + revealContextPx),
            )
        }
    }
    Row(
        modifier = modifier
            .onSizeChanged { rowSize = it }
            .bringIntoViewRequester(revealRequester)
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) PlayerChrome.Raised else Color.Transparent)
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .alpha(if (enabled) 1f else 0.4f)
            .heightIn(min = 46.dp)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        leading?.invoke()
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = title,
                    style = PlayerType.RowTitle.copy(
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                    ),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                chips.forEach { chip ->
                    Spacer(Modifier.width(6.dp))
                    PlayerChip(chip)
                }
            }
            if (!detail.isNullOrBlank()) {
                Text(
                    text = detail,
                    style = PlayerType.RowDetail,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
        if (selected) {
            Icon(
                imageVector = Icons.Rounded.Check,
                contentDescription = "Selected",
                tint = PlayerChrome.Paper,
                modifier = Modifier.size(21.dp),
            )
        }
    }
}

/** Small caps header above a group of rows. */
@Composable
internal fun PlayerSectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text.uppercase(),
        style = PlayerType.Section,
        modifier = modifier.padding(start = 12.dp, end = 12.dp, top = 14.dp, bottom = 6.dp),
    )
}

/** An inset group of rows with hairlines between them. */
@Composable
internal fun PlayerGroup(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(PlayerChrome.Group),
        content = content,
    )
}

/** Draws the group hairline above every row except the first. */
internal fun Modifier.playerGroupSeparator(show: Boolean): Modifier =
    if (show) {
        drawBehind {
            drawLine(
                color = PlayerChrome.GroupSeparator,
                start = Offset(0f, 0f),
                end = Offset(size.width, 0f),
                strokeWidth = 1.dp.toPx(),
            )
        }
    } else {
        this
    }

@Composable
private fun PlayerGroupRow(
    modifier: Modifier,
    separator: Boolean,
    content: @Composable RowScope.() -> Unit,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .playerGroupSeparator(separator)
            .heightIn(min = 44.dp)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        content = content,
    )
}

/** Label · value · chevron. Values line up in one right-aligned column. */
@Composable
internal fun PlayerValueRow(
    label: String,
    value: String?,
    onClick: () -> Unit,
    separator: Boolean = false,
    enabled: Boolean = true,
    detail: String? = null,
) {
    PlayerGroupRow(
        modifier = Modifier
            .clickable(enabled = enabled, onClick = onClick)
            .alpha(if (enabled) 1f else 0.4f),
        separator = separator,
    ) {
        // The label keeps its natural width; the value takes the rest of the
        // row and ends at the chevron, so every value lines up on one edge.
        Column(modifier = if (value.isNullOrBlank()) Modifier.weight(1f) else Modifier) {
            Text(text = label, style = PlayerType.RowTitle, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!detail.isNullOrBlank()) {
                Text(text = detail, style = PlayerType.RowDetail, modifier = Modifier.padding(top = 1.dp))
            }
        }
        if (!value.isNullOrBlank()) {
            Text(
                text = value,
                style = PlayerType.Value,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.End,
                modifier = Modifier.weight(1f),
            )
        }
        PlayerRowChevron()
    }
}

@Composable
private fun PlayerRowChevron() {
    Icon(
        imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
        contentDescription = null,
        tint = PlayerChrome.Faint,
        modifier = Modifier
            .size(20.dp)
            .offset(x = 4.dp),
    )
}

/** A switch row matching the app's Settings switch (green track, white thumb). */
@Composable
internal fun PlayerSwitchRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    separator: Boolean = false,
    enabled: Boolean = true,
    detail: String? = null,
) {
    PlayerGroupRow(
        modifier = Modifier
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange)
            .alpha(if (enabled) 1f else 0.4f),
        separator = separator,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = label, style = PlayerType.RowTitle, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!detail.isNullOrBlank()) {
                Text(text = detail, style = PlayerType.RowDetail, modifier = Modifier.padding(top = 1.dp))
            }
        }
        Switch(
            checked = checked,
            onCheckedChange = null,
            enabled = enabled,
            thumbContent = {},
            colors = settingsSwitchColors(),
        )
    }
}

/** A choice inside a group: label, optional detail, trailing check when chosen. */
@Composable
internal fun PlayerCheckRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    separator: Boolean = false,
    detail: String? = null,
    labelColor: Color = PlayerChrome.Paper,
) {
    PlayerGroupRow(
        modifier = Modifier.selectable(selected = selected, role = Role.RadioButton, onClick = onClick),
        separator = separator,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = PlayerType.RowTitle.copy(
                    color = labelColor,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                    fontFeatureSettings = "tnum",
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!detail.isNullOrBlank()) {
                Text(text = detail, style = PlayerType.RowDetail, modifier = Modifier.padding(top = 1.dp))
            }
        }
        if (selected) {
            Icon(
                imageVector = Icons.Rounded.Check,
                contentDescription = "Selected",
                tint = PlayerChrome.Paper,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/** Flat button for a panel footer: "Find more", "Translate", "Timing". */
@Composable
internal fun PlayerFooterButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Row(
        modifier = modifier
            .heightIn(min = 40.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(PlayerChrome.Raised)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .alpha(if (enabled) 1f else 0.4f)
            .padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(imageVector = icon, contentDescription = null, tint = PlayerChrome.Paper, modifier = Modifier.size(18.dp))
        Text(text = label, style = PlayerType.SmallButton, maxLines = 1)
    }
}

/** "Size ........ Large": the label over a control, with its current value. */
@Composable
internal fun PlayerFieldLabel(label: String, value: String?, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 4.dp, end = 4.dp, top = 14.dp, bottom = 7.dp),
    ) {
        Text(text = label, style = PlayerType.FieldLabel, modifier = Modifier.weight(1f))
        if (!value.isNullOrBlank()) {
            Text(text = value, style = PlayerType.FieldLabel.copy(color = PlayerChrome.Paper))
        }
    }
}

/** Color choices as swatches; the chosen one gets a Paper ring. */
@Composable
internal fun PlayerSwatchRow(
    colors: List<Pair<Color, String>>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Nine swatches need about 330dp; a narrow docked panel has less, so the
    // row scrolls rather than clip the last colors.
    Row(
        modifier = modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        colors.forEachIndexed { index, (color, name) ->
            val selected = index == selectedIndex
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .selectable(selected = selected, role = Role.RadioButton, onClick = { onSelect(index) })
                    .then(
                        if (selected) Modifier.border(2.dp, PlayerChrome.Paper, CircleShape) else Modifier,
                    )
                    .padding(4.dp)
                    .clip(CircleShape)
                    .background(color)
                    .border(1.dp, Color.White.copy(alpha = 0.18f), CircleShape)
                    .semantics { contentDescription = name },
            )
        }
    }
}

/** Explanatory text under a group. */
@Composable
internal fun PlayerFootnote(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = PlayerType.Footnote,
        modifier = modifier.padding(start = 12.dp, end = 12.dp, top = 10.dp),
    )
}

/** "−   +0.25 s   +" stepper used for delays. */
@Composable
internal fun PlayerStepperRow(
    label: String,
    value: String,
    onDecrease: () -> Unit,
    onIncrease: () -> Unit,
    separator: Boolean = false,
    detail: String? = null,
    enabled: Boolean = true,
) {
    PlayerGroupRow(modifier = Modifier.alpha(if (enabled) 1f else 0.4f), separator = separator) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = label, style = PlayerType.RowTitle, maxLines = 1)
            if (!detail.isNullOrBlank()) {
                Text(text = detail, style = PlayerType.RowDetail, modifier = Modifier.padding(top = 1.dp))
            }
        }
        StepperButton(symbol = "−", description = "Decrease $label", enabled = enabled, onClick = onDecrease)
        Text(
            text = value,
            style = PlayerType.Value.copy(color = PlayerChrome.Paper, fontWeight = FontWeight.SemiBold),
            textAlign = TextAlign.Center,
            modifier = Modifier.width(72.dp),
        )
        StepperButton(symbol = "+", description = "Increase $label", enabled = enabled, onClick = onIncrease)
    }
}

@Composable
private fun StepperButton(symbol: String, description: String, enabled: Boolean, onClick: () -> Unit) {
    PlayerDisc(
        contentDescription = description,
        onClick = onClick,
        size = 34.dp,
        enabled = enabled,
        fill = Color.White.copy(alpha = 0.09f),
        stroke = Color.Transparent,
    ) {
        Text(text = symbol, style = PlayerType.PanelTitle.copy(fontWeight = FontWeight.Medium))
    }
}

internal val PlayerPanelBorder = BorderStroke(1.dp, PlayerChrome.PanelStroke)

/** An action inside a group: leading icon and a label, no value. */
@Composable
internal fun PlayerActionRow(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    separator: Boolean = false,
    enabled: Boolean = true,
    tint: Color = PlayerChrome.Paper,
) {
    PlayerGroupRow(
        modifier = Modifier
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .alpha(if (enabled) 1f else 0.4f),
        separator = separator,
    ) {
        Icon(imageVector = icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
        Text(
            text = label,
            style = PlayerType.RowTitle.copy(color = tint),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        PlayerRowChevron()
    }
}
