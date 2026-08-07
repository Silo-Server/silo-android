package org.siloserver.silo.android.ui.screens.player

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.siloserver.silo.model.settings.SubtitleAppearance
import org.siloserver.silo.model.settings.SubtitleBackgroundStylePreset
import org.siloserver.silo.model.settings.SubtitleFontSizePreset
import org.siloserver.silo.model.settings.SubtitlePositionPreset

/**
 * Glass-style bottom sheet for editing the user's [SubtitleAppearance]:
 * font size/family/color, background style/color/opacity, optional outline,
 * and on-screen position.
 *
 * Each control writes back the full sanitized appearance via [onUpdate];
 * the consuming layer (`PlayerViewModel` / `PlayerSettingsStore`) is
 * responsible for persistence and propagating to [org.siloserver.silo.common.player.SubtitleManager].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SubtitleStyleSheet(
    isVisible: Boolean,
    appearance: SubtitleAppearance,
    onUpdate: (SubtitleAppearance) -> Unit,
    onDismiss: () -> Unit,
    // Gear-submenu back affordance: dismisses this sheet and reopens the
    // parent settings sheet (wired in PlayerOverlay).
    onBack: (() -> Unit)? = null,
    tabletopPaneHeight: Dp? = null,
) {
    if (!isVisible) return

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val scrollState = rememberScrollState()
    val dismissSheet = { scope.dismissPlayerSheet(sheetState, onDismiss) }

    LaunchedEffect(isVisible) {
        if (isVisible) sheetState.show()
    }

    PlayerModalBottomSheet(
        onDismissRequest = dismissSheet,
        sheetState = sheetState,
        tabletopPaneHeight = tabletopPaneHeight,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                // Cap below the top edge + keep content flings from
                // dismissing the sheet — see PlayerSheetSupport.
                .playerSheetContent(tabletopPaneHeight)
                .nestedScroll(PlayerSheetFlingGuard)
                .background(
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            Color(0xFF1F2937).copy(alpha = 0.95f),
                            Color.Black.copy(alpha = 0.92f),
                        ),
                    ),
                ),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(scrollState),
            ) {
                PlayerSheetHeader(
                    title = "Subtitle Style",
                    onBack = onBack?.let { back ->
                        { scope.dismissPlayerSheet(sheetState, back) }
                    },
                    onDismiss = dismissSheet,
                )

                // ---- Text section ------------------------------------------------
                SectionHeader("Text")

                FontSizeRow(
                    selected = appearance.fontSize,
                    onSelect = { value ->
                        onUpdate(appearance.copy(fontSize = value).sanitized())
                    },
                )

                FontFamilyRow(
                    selected = appearance.fontFamily,
                    onSelect = { value ->
                        onUpdate(appearance.copy(fontFamily = value).sanitized())
                    },
                )

                ColorSwatchRow(
                    label = "Text Color",
                    swatches = TEXT_COLOR_SWATCHES,
                    selectedHex = appearance.fontColor,
                    onSelect = { hex ->
                        onUpdate(appearance.copy(fontColor = hex).sanitized())
                    },
                )

                // ---- Background section -----------------------------------------
                SectionHeader("Background")

                BackgroundStyleRow(
                    selected = appearance.backgroundStyle,
                    onSelect = { value ->
                        onUpdate(appearance.copy(backgroundStyle = value).sanitized())
                    },
                )

                ColorSwatchRow(
                    label = "Background Color",
                    swatches = BACKGROUND_COLOR_SWATCHES,
                    selectedHex = appearance.backgroundColor,
                    onSelect = { hex ->
                        onUpdate(appearance.copy(backgroundColor = hex).sanitized())
                    },
                )

                OpacityRow(
                    opacity = appearance.backgroundOpacity,
                    onChange = { value ->
                        onUpdate(appearance.copy(backgroundOpacity = value).sanitized())
                    },
                )

                // ---- Outline section --------------------------------------------
                SectionHeader("Outline")

                ToggleRow(
                    label = "Text Outline",
                    subtitle = null,
                    checked = appearance.textOutline,
                    onCheckedChange = { value ->
                        onUpdate(appearance.copy(textOutline = value).sanitized())
                    },
                )

                if (appearance.textOutline) {
                    ColorSwatchRow(
                        label = "Outline Color",
                        swatches = BACKGROUND_COLOR_SWATCHES,
                        selectedHex = appearance.textOutlineColor,
                        onSelect = { hex ->
                            onUpdate(appearance.copy(textOutlineColor = hex).sanitized())
                        },
                    )
                }

                // ---- Position section -------------------------------------------
                SectionHeader("Position")

                PositionRow(
                    selected = appearance.position,
                    onSelect = { value ->
                        onUpdate(appearance.copy(position = value).sanitized())
                    },
                )

                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text.uppercase(),
        color = Color.White.copy(alpha = 0.6f),
        fontSize = 12.sp,
        fontWeight = FontWeight.Medium,
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 4.dp),
    )
}

@Composable
private fun FontSizeRow(
    selected: SubtitleFontSizePreset,
    onSelect: (SubtitleFontSizePreset) -> Unit,
) {
    val options = listOf(
        SubtitleFontSizePreset.Small to "Small",
        SubtitleFontSizePreset.Medium to "Medium",
        SubtitleFontSizePreset.Large to "Large",
        SubtitleFontSizePreset.XLarge to "X-Large",
        SubtitleFontSizePreset.XXLarge to "XX-Large",
    )
    Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
        Text(
            text = "Font Size",
            color = Color.White,
            fontSize = 16.sp,
        )
        Spacer(modifier = Modifier.height(8.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(options) { (value, label) ->
                PillButton(
                    label = label,
                    isSelected = selected == value,
                    onClick = { onSelect(value) },
                )
            }
        }
    }
}

@Composable
private fun FontFamilyRow(
    selected: String,
    onSelect: (String) -> Unit,
) {
    val options = listOf(
        SubtitleAppearance.SANS_SERIF to "Sans-serif",
        SubtitleAppearance.SERIF to "Serif",
        SubtitleAppearance.MONOSPACE to "Monospace",
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "Font Family",
            color = Color.White,
            fontSize = 16.sp,
            modifier = Modifier.weight(1f),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            options.forEach { (value, label) ->
                PillButton(
                    label = label,
                    isSelected = selected == value,
                    onClick = { onSelect(value) },
                )
            }
        }
    }
}

@Composable
private fun BackgroundStyleRow(
    selected: SubtitleBackgroundStylePreset,
    onSelect: (SubtitleBackgroundStylePreset) -> Unit,
) {
    val options = listOf(
        SubtitleBackgroundStylePreset.Box to "Box",
        SubtitleBackgroundStylePreset.Shadow to "Shadow",
        SubtitleBackgroundStylePreset.Outline to "Outline",
        SubtitleBackgroundStylePreset.None to "None",
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "Background Style",
            color = Color.White,
            fontSize = 16.sp,
            modifier = Modifier.weight(1f),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            options.forEach { (value, label) ->
                PillButton(
                    label = label,
                    isSelected = selected == value,
                    onClick = { onSelect(value) },
                )
            }
        }
    }
}

@Composable
private fun PositionRow(
    selected: SubtitlePositionPreset,
    onSelect: (SubtitlePositionPreset) -> Unit,
) {
    val options = listOf(
        SubtitlePositionPreset.Bottom to "Bottom",
        SubtitlePositionPreset.LowerThird to "Lower Third",
        SubtitlePositionPreset.Top to "Top",
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "Position",
            color = Color.White,
            fontSize = 16.sp,
            modifier = Modifier.weight(1f),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            options.forEach { (value, label) ->
                PillButton(
                    label = label,
                    isSelected = selected == value,
                    onClick = { onSelect(value) },
                )
            }
        }
    }
}

@Composable
private fun PillButton(
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(14.dp)
    val baseModifier = Modifier.clickable(onClick = onClick)
    Box(
        modifier = if (isSelected) {
            baseModifier.background(color = Color.White, shape = shape)
        } else {
            baseModifier
                .background(color = Color.Transparent, shape = shape)
                .border(width = 1.dp, color = Color.White, shape = shape)
        },
    ) {
        Text(
            text = label,
            color = if (isSelected) Color.Black else Color.White,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
}

@Composable
private fun ColorSwatchRow(
    label: String,
    swatches: List<String>,
    selectedHex: String,
    onSelect: (String) -> Unit,
) {
    Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
        Text(
            text = label,
            color = Color.White,
            fontSize = 16.sp,
        )
        Spacer(modifier = Modifier.height(8.dp))
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(horizontal = 0.dp),
        ) {
            items(swatches) { hex ->
                ColorSwatch(
                    hex = hex,
                    isSelected = colorsEqual(hex, selectedHex),
                    onClick = { onSelect(hex) },
                )
            }
        }
    }
}

@Composable
private fun ColorSwatch(
    hex: String,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    val color = hexToComposeColor(hex)
    val baseModifier = Modifier
        .size(24.dp)
        .clickable(onClick = onClick)
        .background(color = color, shape = CircleShape)
    Box(
        modifier = if (isSelected) {
            baseModifier.border(width = 2.dp, color = Color.White, shape = CircleShape)
        } else {
            baseModifier.border(width = 1.dp, color = Color.White.copy(alpha = 0.4f), shape = CircleShape)
        },
    )
}

@Composable
private fun OpacityRow(
    opacity: Int,
    onChange: (Int) -> Unit,
) {
    Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Opacity ${opacity.coerceIn(0, 100)}%",
                color = Color.White,
                fontSize = 16.sp,
                modifier = Modifier.weight(1f),
            )
        }
        Slider(
            value = opacity.coerceIn(0, 100).toFloat(),
            onValueChange = { value -> onChange(value.toInt().coerceIn(0, 100)) },
            valueRange = 0f..100f,
            steps = 0,
            colors = SliderDefaults.colors(
                thumbColor = Color.White,
                activeTrackColor = Color(0xFF06B6D4),
                inactiveTrackColor = Color.White.copy(alpha = 0.25f),
            ),
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
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                color = Color.White,
                fontSize = 16.sp,
            )
            if (subtitle != null) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    color = Color.White.copy(alpha = 0.6f),
                    fontSize = 12.sp,
                )
            }
        }
        Spacer(modifier = Modifier.width(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = Color(0xFF06B6D4),
            ),
        )
    }
}

private val TEXT_COLOR_SWATCHES = listOf(
    "#ffffff", // White
    "#facc15", // Yellow
    "#22c55e", // Green
    "#06b6d4", // Cyan
    "#d946ef", // Magenta
    "#ef4444", // Red
    "#3b82f6", // Blue
    "#000000", // Black
)

private val BACKGROUND_COLOR_SWATCHES = listOf(
    "#000000", // Black
    "#374151", // Dark Gray
    "#1e3a5f", // Navy
    "#7f1d1d", // Dark Red
    "#14532d", // Dark Green
)

/** Compose `Color` from a `#rrggbb` string. Falls back to white on garbage input. */
private fun hexToComposeColor(hex: String): Color {
    return try {
        val cleaned = if (hex.startsWith("#")) hex.drop(1) else hex
        val rgb = cleaned.toLong(16).toInt() and 0x00FFFFFF
        Color(0xFF000000.toInt() or rgb)
    } catch (_: NumberFormatException) {
        Color.White
    }
}

/** Case-insensitive hex comparison so "#FFFFFF" and "#ffffff" both match. */
private fun colorsEqual(a: String, b: String): Boolean {
    return a.equals(b, ignoreCase = true)
}
