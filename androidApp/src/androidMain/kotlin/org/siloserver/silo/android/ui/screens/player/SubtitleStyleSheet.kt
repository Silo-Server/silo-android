package org.siloserver.silo.android.ui.screens.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.siloserver.silo.common.R as CommonR
import org.siloserver.silo.model.settings.SubtitleAppearance
import org.siloserver.silo.model.settings.SubtitleBackgroundStylePreset
import org.siloserver.silo.model.settings.SubtitleFontSizePreset
import org.siloserver.silo.model.settings.SubtitlePositionPreset

/**
 * Editor for the user's [SubtitleAppearance]: size, font, colors, background,
 * outline and position. Docked beside the picture, the cue on screen is the
 * preview, so there is no sample box of its own.
 *
 * Each control writes back a transform via [onUpdate] rather than a
 * precomputed value built from [appearance]: that parameter is a
 * composable-captured snapshot that can go stale between when a control's
 * closure is built and when it actually runs (e.g. two opacity fields
 * committing independently as the menu is dismissed), so the caller applies
 * the transform against the freshest value it can read instead. The
 * consuming layer (`PlayerViewModel` / `PlayerSettingsStore`) is responsible
 * for persistence and propagating to [org.siloserver.silo.common.player.SubtitleManager].
 */
@Composable
fun SubtitleStyleSheet(
    isVisible: Boolean,
    appearance: SubtitleAppearance,
    onUpdate: ((SubtitleAppearance) -> SubtitleAppearance) -> Unit,
    onDismiss: () -> Unit,
    // False when the server is known to discard text opacity; the row is
    // hidden rather than offering a value that will not be kept.
    showTextOpacity: Boolean = true,
    // Back affordance: hands over to the settings menu (wired in PlayerOverlay).
    onBack: (() -> Unit)? = null,
    tabletopPaneHeight: Dp? = null,
) {
    if (!isVisible) return

    val controller = rememberPlayerMenuController(onDismiss)

    PlayerMenu(controller = controller, tabletopPaneHeight = tabletopPaneHeight) {
        PlayerPanelHeader(
            title = stringResource(CommonR.string.player_subtitles_style_title),
            onClose = { controller.dismiss() },
            onBack = onBack?.let { back -> { controller.dismiss(then = back) } },
            backDescription = stringResource(CommonR.string.player_menu_backToSettings),
        )
        PlayerMenuScrollColumn(horizontalPadding = 18.dp) {
            val sizeIndex = FONT_SIZES.indexOfFirst { it.first == appearance.fontSize }
            PlayerFieldLabel(
                stringResource(CommonR.string.player_subtitles_style_size),
                FONT_SIZES.getOrNull(sizeIndex)?.let { stringResource(it.third) },
            )
            PlayerChoiceSegments(
                options = FONT_SIZES.map { stringResource(it.second) },
                selectedIndex = sizeIndex,
                onSelect = { index -> onUpdate { it.copy(fontSize = FONT_SIZES[index].first).sanitized() } },
            )

            val familyIndex = FONT_FAMILIES.indexOfFirst { it.first == appearance.fontFamily }
            PlayerFieldLabel(
                stringResource(CommonR.string.player_subtitles_style_font),
                FONT_FAMILIES.getOrNull(familyIndex)?.let { stringResource(it.second) },
            )
            PlayerChoiceSegments(
                options = FONT_FAMILIES.map { stringResource(it.second) },
                selectedIndex = familyIndex,
                onSelect = { index -> onUpdate { it.copy(fontFamily = FONT_FAMILIES[index].first).sanitized() } },
            )

            val textColorIndex = TEXT_COLORS.indexOfFirst { colorsEqual(it.first, appearance.fontColor) }
            PlayerFieldLabel(
                stringResource(CommonR.string.player_subtitles_style_textColor),
                TEXT_COLORS.getOrNull(textColorIndex)?.let { stringResource(it.second) },
            )
            PlayerSwatchRow(
                colors = TEXT_COLORS.map { hexToComposeColor(it.first) to stringResource(it.second) },
                selectedIndex = textColorIndex,
                onSelect = { index -> onUpdate { it.copy(fontColor = TEXT_COLORS[index].first).sanitized() } },
            )

            if (showTextOpacity) {
                PercentField(
                    label = stringResource(CommonR.string.player_subtitles_style_textOpacity),
                    value = appearance.textOpacity,
                    min = 1,
                    onChange = { value -> onUpdate { it.copy(textOpacity = value).sanitized() } },
                )
            }

            val backgroundIndex = BACKGROUND_STYLES.indexOfFirst { it.first == appearance.backgroundStyle }
            PlayerFieldLabel(
                stringResource(CommonR.string.player_subtitles_style_background),
                BACKGROUND_STYLES.getOrNull(backgroundIndex)?.let { stringResource(it.second) },
            )
            PlayerChoiceSegments(
                options = BACKGROUND_STYLES.map { stringResource(it.second) },
                selectedIndex = backgroundIndex,
                onSelect = { index ->
                    onUpdate { it.copy(backgroundStyle = BACKGROUND_STYLES[index].first).sanitized() }
                },
            )

            val backgroundColorIndex = BACKGROUND_COLORS.indexOfFirst {
                colorsEqual(it.first, appearance.backgroundColor)
            }
            PlayerFieldLabel(
                stringResource(CommonR.string.player_subtitles_style_backgroundColor),
                BACKGROUND_COLORS.getOrNull(backgroundColorIndex)?.let { stringResource(it.second) },
            )
            PlayerSwatchRow(
                colors = BACKGROUND_COLORS.map { hexToComposeColor(it.first) to stringResource(it.second) },
                selectedIndex = backgroundColorIndex,
                onSelect = { index ->
                    onUpdate { it.copy(backgroundColor = BACKGROUND_COLORS[index].first).sanitized() }
                },
            )

            PercentField(
                label = stringResource(CommonR.string.player_subtitles_style_backgroundOpacity),
                value = appearance.backgroundOpacity,
                min = 0,
                onChange = { value -> onUpdate { it.copy(backgroundOpacity = value).sanitized() } },
            )

            Spacer(Modifier.height(14.dp))
            PlayerGroup {
                PlayerSwitchRow(
                    label = stringResource(CommonR.string.player_subtitles_style_textOutline),
                    checked = appearance.textOutline,
                    onCheckedChange = { value -> onUpdate { it.copy(textOutline = value).sanitized() } },
                )
            }
            if (appearance.textOutline) {
                val outlineIndex = BACKGROUND_COLORS.indexOfFirst {
                    colorsEqual(it.first, appearance.textOutlineColor)
                }
                PlayerFieldLabel(
                    stringResource(CommonR.string.player_subtitles_style_outlineColor),
                    BACKGROUND_COLORS.getOrNull(outlineIndex)?.let { stringResource(it.second) },
                )
                PlayerSwatchRow(
                    colors = BACKGROUND_COLORS.map { hexToComposeColor(it.first) to stringResource(it.second) },
                    selectedIndex = outlineIndex,
                    onSelect = { index ->
                        onUpdate { it.copy(textOutlineColor = BACKGROUND_COLORS[index].first).sanitized() }
                    },
                )
            }

            val positionIndex = POSITIONS.indexOfFirst { it.first == appearance.position }
            PlayerFieldLabel(
                stringResource(CommonR.string.player_subtitles_style_position),
                POSITIONS.getOrNull(positionIndex)?.let { stringResource(it.second) },
            )
            PlayerChoiceSegments(
                options = POSITIONS.map { stringResource(it.second) },
                selectedIndex = positionIndex,
                onSelect = { index -> onUpdate { it.copy(position = POSITIONS[index].first).sanitized() } },
            )
            Spacer(Modifier.height(16.dp))
        }
    }
}

/**
 * A typed percentage (`min`-100), for controls where dragging a slider is more
 * fiddly than typing the number — opacity wants precision at the low end where
 * a few percent is the difference between legible and not.
 */
@Composable
private fun PercentField(
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

    // Dismissing the menu while the field is still focused (swipe-away, back
    // press) tears down this composable without firing onFocusChanged(false),
    // so a typed-but-uncommitted percentage would otherwise be silently lost.
    // rememberUpdatedState keeps the lambda pointed at the latest commit
    // closure across recompositions, so onDispose always commits current text.
    val latestCommit = rememberUpdatedState(::commit)
    DisposableEffect(Unit) {
        onDispose { latestCommit.value() }
    }

    Row(
        modifier = Modifier.padding(start = 4.dp, end = 4.dp, top = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = label, style = PlayerType.FieldLabel, modifier = Modifier.weight(1f))
        BasicTextField(
            value = text,
            onValueChange = { input -> text = input.filter { it.isDigit() }.take(3) },
            singleLine = true,
            textStyle = PlayerType.Value.copy(color = PlayerChrome.Paper, textAlign = TextAlign.End),
            cursorBrush = SolidColor(PlayerChrome.Paper),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(
                onDone = {
                    commit()
                    focusManager.clearFocus()
                    keyboardController?.hide()
                },
            ),
            modifier = Modifier
                .width(56.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(PlayerChrome.Raised)
                .padding(horizontal = 10.dp, vertical = 8.dp)
                .onFocusChanged { focusState -> if (!focusState.isFocused) commit() },
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(text = "%", style = PlayerType.FieldLabel)
    }
}

// Labels are string resource ids, resolved where the sheet draws them.
private val FONT_SIZES = listOf(
    Triple(SubtitleFontSizePreset.Small, CommonR.string.player_subtitles_sizeShort_small, CommonR.string.player_subtitles_size_small),
    Triple(SubtitleFontSizePreset.Medium, CommonR.string.player_subtitles_sizeShort_medium, CommonR.string.player_subtitles_size_medium),
    Triple(SubtitleFontSizePreset.Large, CommonR.string.player_subtitles_sizeShort_large, CommonR.string.player_subtitles_size_large),
    Triple(SubtitleFontSizePreset.XLarge, CommonR.string.player_subtitles_sizeShort_extraLarge, CommonR.string.player_subtitles_size_extraLarge),
    Triple(SubtitleFontSizePreset.XXLarge, CommonR.string.player_subtitles_sizeShort_largest, CommonR.string.player_subtitles_size_largest),
)

private val FONT_FAMILIES = listOf(
    SubtitleAppearance.SANS_SERIF to CommonR.string.player_subtitles_font_sans,
    SubtitleAppearance.SERIF to CommonR.string.player_subtitles_font_serif,
    SubtitleAppearance.MONOSPACE to CommonR.string.player_subtitles_font_mono,
)

private val BACKGROUND_STYLES = listOf(
    SubtitleBackgroundStylePreset.None to CommonR.string.player_subtitles_background_none,
    SubtitleBackgroundStylePreset.Shadow to CommonR.string.player_subtitles_background_shadow,
    SubtitleBackgroundStylePreset.Box to CommonR.string.player_subtitles_background_box,
    SubtitleBackgroundStylePreset.Outline to CommonR.string.player_subtitles_background_outline,
)

private val POSITIONS = listOf(
    SubtitlePositionPreset.Bottom to CommonR.string.player_subtitles_position_bottom,
    SubtitlePositionPreset.LowerThird to CommonR.string.player_subtitles_position_lowerThird,
    SubtitlePositionPreset.Top to CommonR.string.player_subtitles_position_top,
)

private val TEXT_COLORS = listOf(
    "#ffffff" to CommonR.string.player_subtitles_color_white,
    "#facc15" to CommonR.string.player_subtitles_color_yellow,
    "#22c55e" to CommonR.string.player_subtitles_color_green,
    "#06b6d4" to CommonR.string.player_subtitles_color_cyan,
    "#d946ef" to CommonR.string.player_subtitles_color_magenta,
    "#ef4444" to CommonR.string.player_subtitles_color_red,
    "#3b82f6" to CommonR.string.player_subtitles_color_blue,
    "#9ca3af" to CommonR.string.player_subtitles_color_gray,
    "#000000" to CommonR.string.player_subtitles_color_black,
)

private val BACKGROUND_COLORS = listOf(
    "#000000" to CommonR.string.player_subtitles_color_black,
    "#374151" to CommonR.string.player_subtitles_color_darkGray,
    "#1e3a5f" to CommonR.string.player_subtitles_color_navy,
    "#7f1d1d" to CommonR.string.player_subtitles_color_darkRed,
    "#14532d" to CommonR.string.player_subtitles_color_darkGreen,
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
