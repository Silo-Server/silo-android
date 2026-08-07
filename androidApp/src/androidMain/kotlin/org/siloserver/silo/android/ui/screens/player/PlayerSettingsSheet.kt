package org.siloserver.silo.android.ui.screens.player

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.siloserver.silo.common.player.PlayerStatsSnapshot
import org.siloserver.silo.common.player.SleepTimerState

private enum class SettingsCategory(
    val label: String,
    val description: String,
    val icon: ImageVector,
) {
    Playback("Playback", "Speed and picture sizing", Icons.Filled.PlayArrow),
    Episodes("Episodes", "Automatic episode behavior", Icons.Filled.SkipNext),
    Sync("Sync", "Audio and subtitle timing", Icons.Filled.Sync),
    More("More", "Subtitles, timer, and video", Icons.Filled.MoreHoriz),
}

/** Adaptive playback settings menu shared by regular phones and tabletop mode. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun PlayerSettingsSheet(
    isVisible: Boolean,
    onDismiss: () -> Unit,
    playbackSpeed: Double,
    onSetPlaybackSpeed: (Double) -> Unit,
    videoGravity: String,
    onSetVideoGravity: (String) -> Unit,
    autoSkipIntroEnabled: Boolean,
    onSetAutoSkipIntro: (Boolean) -> Unit,
    autoPlayNextEnabled: Boolean,
    onSetAutoPlayNext: (Boolean) -> Unit,
    hdrEnabled: Boolean,
    onSetHdrEnabled: (Boolean) -> Unit,
    dolbyVisionEnabled: Boolean,
    onSetDolbyVisionEnabled: (Boolean) -> Unit,
    onOpenSubtitleStyle: () -> Unit = {},
    onOpenSleepTimer: () -> Unit = {},
    stats: PlayerStatsSnapshot = PlayerStatsSnapshot(),
    onOpenPlaybackStats: () -> Unit = {},
    audioDelayMs: Int = 0,
    audioDelayEnabled: Boolean = true,
    onSetAudioDelay: (Int) -> Unit = {},
    subtitleDelayMs: Int = 0,
    onSetSubtitleDelay: (Int) -> Unit = {},
    sleepTimerState: SleepTimerState = SleepTimerState.Idle,
    tabletopPaneHeight: Dp? = null,
) {
    if (!isVisible) return

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    var selectedCategoryIndex by rememberSaveable { mutableIntStateOf(0) }
    val selectedCategory = SettingsCategory.entries[selectedCategoryIndex]
    val useSideRail = LocalConfiguration.current.screenWidthDp >= 600
    val dismissSheet = { scope.dismissPlayerSheet(sheetState, onDismiss) }
    val openSubtitleStyle = {
        scope.dismissPlayerSheet(sheetState, onDismiss, onOpenSubtitleStyle)
    }
    val openSleepTimer = {
        scope.dismissPlayerSheet(sheetState, onDismiss, onOpenSleepTimer)
    }
    val openPlaybackStats = {
        scope.dismissPlayerSheet(sheetState, onDismiss, onOpenPlaybackStats)
    }

    LaunchedEffect(isVisible) {
        if (isVisible) sheetState.show()
    }

    val categoryContent: @Composable (Modifier, Boolean) -> Unit = { modifier, showHeader ->
        SettingsCategoryContent(
            category = selectedCategory,
            playbackSpeed = playbackSpeed,
            onSetPlaybackSpeed = onSetPlaybackSpeed,
            videoGravity = videoGravity,
            onSetVideoGravity = onSetVideoGravity,
            autoSkipIntroEnabled = autoSkipIntroEnabled,
            onSetAutoSkipIntro = onSetAutoSkipIntro,
            autoPlayNextEnabled = autoPlayNextEnabled,
            onSetAutoPlayNext = onSetAutoPlayNext,
            audioDelayMs = audioDelayMs,
            audioDelayEnabled = audioDelayEnabled,
            onSetAudioDelay = onSetAudioDelay,
            subtitleDelayMs = subtitleDelayMs,
            onSetSubtitleDelay = onSetSubtitleDelay,
            onOpenSubtitleStyle = openSubtitleStyle,
            sleepTimerState = sleepTimerState,
            onOpenSleepTimer = openSleepTimer,
            hdrEnabled = hdrEnabled,
            onSetHdrEnabled = onSetHdrEnabled,
            dolbyVisionEnabled = dolbyVisionEnabled,
            onSetDolbyVisionEnabled = onSetDolbyVisionEnabled,
            stats = stats,
            onOpenPlaybackStats = openPlaybackStats,
            showHeader = showHeader,
            modifier = modifier,
        )
    }

    PlayerModalBottomSheet(
        onDismissRequest = dismissSheet,
        sheetState = sheetState,
        tabletopPaneHeight = tabletopPaneHeight,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .playerSheetContent(tabletopPaneHeight)
                .nestedScroll(PlayerSheetFlingGuard),
        ) {
            PlayerSheetHeader(
                title = "Playback Settings",
                subtitle = selectedCategory.description,
                onDismiss = dismissSheet,
            )

            if (useSideRail) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 24.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    SettingsCategoryRail(
                        selected = selectedCategory,
                        onSelect = { selectedCategoryIndex = it.ordinal },
                        modifier = Modifier
                            .width(212.dp)
                            .fillMaxHeight(),
                    )
                    categoryContent(
                        Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                        false,
                    )
                }
            } else {
                SettingsCategoryTabs(
                    selected = selectedCategory,
                    onSelect = { selectedCategoryIndex = it.ordinal },
                )
                categoryContent(
                    Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 24.dp),
                    true,
                )
            }
        }
    }
}

@Composable
private fun SettingsCategoryRail(
    selected: SettingsCategory,
    onSelect: (SettingsCategory) -> Unit,
    modifier: Modifier = Modifier,
) {
    PlayerSheetCard(modifier = modifier) {
        PlayerSheetSectionLabel("Settings")
        SettingsCategory.entries.forEach { category ->
            CategoryButton(
                category = category,
                isSelected = category == selected,
                onClick = { onSelect(category) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 3.dp),
            )
        }
    }
}

@Composable
private fun SettingsCategoryTabs(
    selected: SettingsCategory,
    onSelect: (SettingsCategory) -> Unit,
) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(SettingsCategory.entries) { category ->
            CategoryButton(
                category = category,
                isSelected = category == selected,
                onClick = { onSelect(category) },
            )
        }
    }
}

@Composable
private fun CategoryButton(
    category: SettingsCategory,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(if (isSelected) PlayerSheetSelectedColor else Color.Transparent)
            .clickable(onClick = onClick)
            .heightIn(min = 48.dp)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(
            imageVector = category.icon,
            contentDescription = null,
            tint = if (isSelected) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.58f),
            modifier = Modifier.size(20.dp),
        )
        Text(
            text = category.label,
            color = if (isSelected) Color.White else Color.White.copy(alpha = 0.72f),
            fontSize = 14.sp,
            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
            maxLines = 1,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SettingsCategoryContent(
    category: SettingsCategory,
    playbackSpeed: Double,
    onSetPlaybackSpeed: (Double) -> Unit,
    videoGravity: String,
    onSetVideoGravity: (String) -> Unit,
    autoSkipIntroEnabled: Boolean,
    onSetAutoSkipIntro: (Boolean) -> Unit,
    autoPlayNextEnabled: Boolean,
    onSetAutoPlayNext: (Boolean) -> Unit,
    audioDelayMs: Int,
    audioDelayEnabled: Boolean,
    onSetAudioDelay: (Int) -> Unit,
    subtitleDelayMs: Int,
    onSetSubtitleDelay: (Int) -> Unit,
    onOpenSubtitleStyle: () -> Unit,
    sleepTimerState: SleepTimerState,
    onOpenSleepTimer: () -> Unit,
    hdrEnabled: Boolean,
    onSetHdrEnabled: (Boolean) -> Unit,
    dolbyVisionEnabled: Boolean,
    onSetDolbyVisionEnabled: (Boolean) -> Unit,
    stats: PlayerStatsSnapshot,
    onOpenPlaybackStats: () -> Unit,
    showHeader: Boolean,
    modifier: Modifier = Modifier,
) {
    PlayerSheetCard(modifier = modifier) {
        if (showHeader) {
            CategoryContentHeader(category)
            PlayerSheetDivider()
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(vertical = 8.dp),
        ) {
            when (category) {
                SettingsCategory.Playback -> {
                    SpeedSetting(
                        selected = playbackSpeed,
                        onSelect = onSetPlaybackSpeed,
                    )
                    PlayerSheetDivider(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                    AspectSetting(
                        selected = videoGravity,
                        onSelect = onSetVideoGravity,
                    )
                }

                SettingsCategory.Episodes -> {
                    ToggleRow(
                        label = "Auto-skip intro",
                        subtitle = "Skip after the five-second countdown",
                        checked = autoSkipIntroEnabled,
                        onCheckedChange = onSetAutoSkipIntro,
                    )
                    PlayerSheetDivider(modifier = Modifier.padding(horizontal = 16.dp))
                    ToggleRow(
                        label = "Auto-play next episode",
                        subtitle = "Continue without returning to the series page",
                        checked = autoPlayNextEnabled,
                        onCheckedChange = onSetAutoPlayNext,
                    )
                }

                SettingsCategory.Sync -> {
                    DelaySpinnerRow(
                        label = "Audio delay",
                        subtitle = "PCM audio only",
                        valueMs = audioDelayMs,
                        enabled = audioDelayEnabled,
                        stepMs = 50,
                        minMs = -5000,
                        maxMs = 5000,
                        onChange = onSetAudioDelay,
                    )
                    PlayerSheetDivider(modifier = Modifier.padding(horizontal = 16.dp))
                    DelaySpinnerRow(
                        label = "Subtitle delay",
                        subtitle = "Move captions earlier or later",
                        valueMs = subtitleDelayMs,
                        stepMs = 50,
                        minMs = -10000,
                        maxMs = 10000,
                        onChange = onSetSubtitleDelay,
                    )
                }

                SettingsCategory.More -> {
                    TapRow(
                        label = "Subtitle style",
                        subtitle = "Font, color, background, and position",
                        onClick = onOpenSubtitleStyle,
                    )
                    PlayerSheetDivider(modifier = Modifier.padding(horizontal = 16.dp))
                    TapRow(
                        label = "Sleep timer",
                        subtitle = formatSleepTimerSubtitle(sleepTimerState),
                        onClick = onOpenSleepTimer,
                    )
                    PlayerSheetDivider(modifier = Modifier.padding(horizontal = 16.dp))
                    ToggleRow(
                        label = "HDR",
                        subtitle = "Allow high dynamic range playback",
                        checked = hdrEnabled,
                        onCheckedChange = onSetHdrEnabled,
                    )
                    PlayerSheetDivider(modifier = Modifier.padding(horizontal = 16.dp))
                    ToggleRow(
                        label = "Dolby Vision",
                        subtitle = "Turn off to use the HDR10 base layer",
                        checked = dolbyVisionEnabled,
                        onCheckedChange = onSetDolbyVisionEnabled,
                    )
                    PlayerSheetDivider(modifier = Modifier.padding(horizontal = 16.dp))
                    TapRow(
                        label = "Playback stats",
                        subtitle = stats.summaryLabel(),
                        onClick = onOpenPlaybackStats,
                    )
                }
            }
        }
    }
}

@Composable
private fun CategoryContentHeader(category: SettingsCategory) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
            modifier = Modifier.size(38.dp),
        ) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = category.icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(21.dp),
                )
            }
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = category.label,
                color = Color.White,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = category.description,
                color = Color.White.copy(alpha = 0.48f),
                fontSize = 12.sp,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SpeedSetting(
    selected: Double,
    onSelect: (Double) -> Unit,
) {
    val options = listOf(0.5, 0.75, 1.0, 1.25, 1.5, 1.75, 2.0, 2.5, 3.0)
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
        SettingTitle(
            title = "Speed",
            value = "${formatPlaybackSpeed(selected)}×",
        )
        Spacer(modifier = Modifier.height(12.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            options.forEach { value ->
                SelectionPill(
                    label = "${formatPlaybackSpeed(value)}×",
                    isSelected = isSameSpeed(value, selected),
                    onClick = { onSelect(value) },
                )
            }
        }
    }
}

@Composable
private fun AspectSetting(
    selected: String,
    onSelect: (String) -> Unit,
) {
    val options = listOf("fit" to "Fit", "fill" to "Fill", "stretch" to "Stretch")
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
        SettingTitle(title = "Picture size")
        Spacer(modifier = Modifier.height(12.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(Color.Black.copy(alpha = 0.22f))
                .padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            options.forEach { (value, label) ->
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(11.dp))
                        .background(
                            if (selected == value) MaterialTheme.colorScheme.primary else Color.Transparent,
                        )
                        .clickable { onSelect(value) }
                        .heightIn(min = 42.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = label,
                        color = if (selected == value) Color.Black else Color.White.copy(alpha = 0.72f),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingTitle(title: String, value: String? = null) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            color = Color.White,
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1f),
        )
        if (value != null) {
            Text(
                text = value,
                color = MaterialTheme.colorScheme.primary,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun SelectionPill(
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(13.dp))
            .background(
                if (isSelected) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.06f),
            )
            .then(
                if (isSelected) Modifier else Modifier.border(
                    width = 1.dp,
                    color = Color.White.copy(alpha = 0.10f),
                    shape = RoundedCornerShape(13.dp),
                ),
            )
            .clickable(onClick = onClick)
            .heightIn(min = 40.dp)
            .padding(horizontal = 13.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (isSelected) {
            Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = null,
                tint = Color.Black,
                modifier = Modifier.size(16.dp),
            )
        }
        Text(
            text = label,
            color = if (isSelected) Color.Black else Color.White.copy(alpha = 0.78f),
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun TapRow(
    label: String,
    subtitle: String?,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .heightIn(min = 68.dp)
            .padding(horizontal = 16.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        RowLabel(label = label, subtitle = subtitle, modifier = Modifier.weight(1f))
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = Color.White.copy(alpha = 0.38f),
            modifier = Modifier.size(22.dp),
        )
    }
}

@Composable
private fun ToggleRow(
    label: String,
    subtitle: String?,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(
                value = checked,
                onValueChange = onCheckedChange,
                role = Role.Switch,
            )
            .heightIn(min = 68.dp)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        RowLabel(label = label, subtitle = subtitle, modifier = Modifier.weight(1f))
        Switch(
            checked = checked,
            onCheckedChange = null,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = MaterialTheme.colorScheme.primary,
                uncheckedThumbColor = Color.White.copy(alpha = 0.72f),
                uncheckedTrackColor = Color.White.copy(alpha = 0.15f),
                uncheckedBorderColor = Color.Transparent,
            ),
        )
    }
}

@Composable
private fun RowLabel(
    label: String,
    subtitle: String?,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Text(
            text = label,
            color = Color.White,
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (subtitle != null) {
            Text(
                text = subtitle,
                color = Color.White.copy(alpha = 0.48f),
                fontSize = 12.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun DelaySpinnerRow(
    label: String,
    subtitle: String,
    valueMs: Int,
    stepMs: Int,
    minMs: Int,
    maxMs: Int,
    enabled: Boolean = true,
    onChange: (Int) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        RowLabel(label = label, subtitle = subtitle)
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.End,
        ) {
            SpinnerButton(
                label = "−",
                enabled = enabled,
                onClick = { onChange((valueMs - stepMs).coerceIn(minMs, maxMs)) },
            )
            Text(
                text = formatDelayMs(valueMs),
                color = if (enabled) Color.White else Color.White.copy(alpha = 0.38f),
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                modifier = Modifier.width(96.dp),
            )
            SpinnerButton(
                label = "+",
                enabled = enabled,
                onClick = { onChange((valueMs + stepMs).coerceIn(minMs, maxMs)) },
            )
        }
    }
}

@Composable
private fun SpinnerButton(
    label: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = if (enabled) 0.10f else 0.04f))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = if (enabled) Color.White else Color.White.copy(alpha = 0.30f),
            fontSize = 20.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

private fun formatSleepTimerSubtitle(state: SleepTimerState): String = when (state) {
    is SleepTimerState.Idle -> "Off"
    is SleepTimerState.Active -> "Pausing in ${formatRemaining(state.remainingSeconds)}"
}

private fun PlayerStatsSnapshot.summaryLabel(): String {
    val route = backendRoute ?: backendDisplayName
    return listOfNotNull(resolution, route, bitrateBps?.let(::formatStatsBitrate))
        .take(2)
        .joinToString(" - ")
        .ifBlank { "Waiting for player data" }
}

private fun isSameSpeed(a: Double, b: Double): Boolean = kotlin.math.abs(a - b) < 0.001

private fun formatDelayMs(ms: Int): String = when {
    ms == 0 -> "0 ms"
    ms > 0 -> "+$ms ms"
    else -> "−${-ms} ms"
}
