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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
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
 * Each control writes back a transform via [onUpdate] rather than a
 * precomputed value built from [appearance]: that parameter is a
 * composable-captured snapshot that can go stale between when a control's
 * closure is built and when it actually runs (e.g. two opacity fields
 * committing independently as the sheet is dismissed), so the caller applies
 * the transform against the freshest value it can read instead. The
 * consuming layer (`PlayerViewModel` / `PlayerSettingsStore`) is responsible
 * for persistence and propagating to [org.siloserver.silo.common.player.SubtitleManager].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SubtitleStyleSheet(
    isVisible: Boolean,
    appearance: SubtitleAppearance,
    onUpdate: ((SubtitleAppearance) -> SubtitleAppearance) -> Unit,
    onDismiss: () -> Unit,
    // False when the server is known to discard text opacity; the row is
    // hidden rather than offering a value that will not be kept.
    showTextOpacity: Boolean = true,
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
                        onUpdate { it.copy(fontSize = value).sanitized() }
                    },
                )

                FontFamilyRow(
                    selected = appearance.fontFamily,
                    onSelect = { value ->
                        onUpdate { it.copy(fontFamily = value).sanitized() }
                    },
                )

                ColorSwatchRow(
                    label = "Text Color",
                    swatches = TEXT_COLOR_SWATCHES,
                    selectedHex = appearance.fontColor,
                    onSelect = { hex ->
                        onUpdate { it.copy(fontColor = hex).sanitized() }
                    },
                )

                if (showTextOpacity) {
                    PercentInputRow(
                        label = "Text Opacity",
                        value = appearance.textOpacity,
                        min = 1,
                        onChange = { value ->
                            onUpdate { it.copy(textOpacity = value).sanitized() }
                        },
                    )
                }

                // ---- Background section -----------------------------------------
                SectionHeader("Background")

                BackgroundStyleRow(
                    selected = appearance.backgroundStyle,
                    onSelect = { value ->
                        onUpdate { it.copy(backgroundStyle = value).sanitized() }
                    },
                )

                ColorSwatchRow(
                    label = "Background Color",
                    swatches = BACKGROUND_COLOR_SWATCHES,
                    selectedHex = appearance.backgroundColor,
                    onSelect = { hex ->
                        onUpdate { it.copy(backgroundColor = hex).sanitized() }
                    },
                )

                PercentInputRow(
                    label = "Background Opacity",
                    value = appearance.backgroundOpacity,
                    min = 0,
                    onChange = { value ->
                        onUpdate { it.copy(backgroundOpacity = value).sanitized() }
                    },
                )

                // ---- Outline section --------------------------------------------
                SectionHeader("Outline")

                ToggleRow(
                    label = "Text Outline",
                    subtitle = null,
                    checked = appearance.textOutline,
                    onCheckedChange = { value ->
                        onUpdate { it.copy(textOutline = value).sanitized() }
                    },
                )

                if (appearance.textOutline) {
                    ColorSwatchRow(
                        label = "Outline Color",
                        swatches = BACKGROUND_COLOR_SWATCHES,
                        selectedHex = appearance.textOutlineColor,
                        onSelect = { hex ->
                            onUpdate { it.copy(textOutlineColor = hex).sanitized() }
                        },
                    )
                }

                // ---- Position section -------------------------------------------
                SectionHeader("Position")

                PositionRow(
                    selected = appearance.position,
                    onSelect = { value ->
                        onUpdate { it.copy(position = value).sanitized() }
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

/**
 * A typed percentage value (`min`-100), for controls where dragging a slider
 * is more fiddly than just typing the number — opacity wants precision at the
 * low end where a few percent is the difference between legible and not.
 */
@Composable
private fun PercentInputRow(
    label: String,
    value: Int,
    min: Int,
    onChange: (Int) -> Unit,
) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    // The last value handed to onChange that `value` has not caught up with
    // yet. Done commits and then clears focus, which commits again on blur;
    // without this the second commit would repeat the same write.
    var sent by remember(value) { mutableStateOf<Int?>(null) }
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current

    // Committing on every keystroke fights the clamp: typing "0" below the
    // floor calls onChange(min), which can equal the value already in effect,
    // so `value` never changes and `remember(value)` never re-keys the draft
    // back to a valid display. Committing once, on blur/Done, avoids that
    // entirely and lets the field hold invalid intermediate text (including
    // empty) while the user is still typing.
    fun commit() {
        val clamped = text.toIntOrNull()?.coerceIn(min, 100)
        if (clamped != null && clamped != value && clamped != sent) {
            sent = clamped
            onChange(clamped)
        }
        text = (clamped ?: value).toString()
    }

    // Dismissing the sheet while the field is still focused (swipe-away, back
    // press) tears down this composable without firing onFocusChanged(false),
    // so a typed-but-uncommitted percentage would otherwise be silently lost.
    // rememberUpdatedState keeps the lambda pointed at the latest commit
    // closure across recompositions, so onDispose always commits current text.
    val latestCommit = rememberUpdatedState(::commit)
    DisposableEffect(Unit) {
        onDispose { latestCommit.value() }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            color = Color.White,
            fontSize = 16.sp,
            modifier = Modifier.weight(1f),
        )
        BasicTextField(
            value = text,
            onValueChange = { input -> text = input.filter { it.isDigit() }.take(3) },
            singleLine = true,
            textStyle = TextStyle(color = Color.White, fontSize = 16.sp, textAlign = TextAlign.End),
            cursorBrush = SolidColor(Color.White),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(
                onDone = {
                    commit()
                    focusManager.clearFocus()
                    keyboardController?.hide()
                },
            ),
            modifier = Modifier
                .width(44.dp)
                .border(
                    width = 1.dp,
                    color = Color.White.copy(alpha = 0.4f),
                    shape = RoundedCornerShape(6.dp),
                )
                .padding(horizontal = 8.dp, vertical = 6.dp)
                .onFocusChanged { focusState -> if (!focusState.isFocused) commit() },
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(text = "%", color = Color.White, fontSize = 16.sp)
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
    "#9ca3af", // Gray
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
