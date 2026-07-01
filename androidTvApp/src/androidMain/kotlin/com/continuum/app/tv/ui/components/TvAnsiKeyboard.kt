package com.continuum.app.tv.ui.components

import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text

internal sealed interface TvAnsiKeyboardAction {
    data class Insert(val text: String) : TvAnsiKeyboardAction
    data object Backspace : TvAnsiKeyboardAction
    data object Clear : TvAnsiKeyboardAction
}

internal data class TvAnsiShortcutKey(
    val label: String,
    val text: String = label,
    val weight: Float = if (label.length > 4) 1.45f else 1f,
)

internal data class TvAnsiMainKey(
    val label: String,
    val text: String = label,
    val weight: Float = 1f,
)

internal fun applyTvAnsiKeyboardAction(
    value: String,
    action: TvAnsiKeyboardAction,
    maxLength: Int,
): String =
    when (action) {
        is TvAnsiKeyboardAction.Insert -> (value + action.text).take(maxLength)
        TvAnsiKeyboardAction.Backspace -> value.dropLast(1)
        TvAnsiKeyboardAction.Clear -> ""
    }

@Composable
internal fun TvAnsiKeyboard(
    primaryLabel: String,
    primaryEnabled: Boolean,
    enabled: Boolean,
    firstKeyFocusRequester: FocusRequester,
    onAction: (TvAnsiKeyboardAction) -> Unit,
    onPrimary: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    shortcuts: List<TvAnsiShortcutKey> = emptyList(),
    mainKeys: List<TvAnsiMainKey> = emptyList(),
    showPasswordVisibilityKey: Boolean = false,
    passwordVisible: Boolean = false,
    onTogglePasswordVisibility: () -> Unit = {},
) {
    var shifted by remember { mutableStateOf(false) }
    val rows = remember(shifted, shortcuts, mainKeys, showPasswordVisibilityKey) {
        buildTvAnsiRows(
            shifted = shifted,
            shortcuts = shortcuts,
            mainKeys = mainKeys,
            showPasswordVisibilityKey = showPasswordVisibilityKey,
        )
    }
    val keyFocusRequesters = remember(rows, firstKeyFocusRequester) {
        var firstFocusableAssigned = false
        rows.map { row ->
            row.map { key ->
                if (key.type == TvAnsiKeyType.Spacer) {
                    null
                } else if (!firstFocusableAssigned) {
                    firstFocusableAssigned = true
                    firstKeyFocusRequester
                } else {
                    FocusRequester()
                }
            }
        }
    }
    val panelShape = RoundedCornerShape(18.dp)

    fun moveKeyboardFocus(rowIndex: Int, keyIndex: Int, direction: Key): Boolean {
        val target = findTvAnsiFocusTarget(rows, rowIndex, keyIndex, direction)
        val requester = target?.let { (targetRow, targetKey) ->
            keyFocusRequesters.getOrNull(targetRow)?.getOrNull(targetKey)
        }
        requester?.let { runCatching { it.requestFocus() } }
        return true
    }

    Column(
        modifier = modifier
            .widthIn(max = TvAnsiKeyboardMaxWidth)
            .clip(panelShape)
            .background(Color(0xFF17121A).copy(alpha = 0.96f))
            .auroraGlass(cornerRadius = 18.dp, emphasized = true)
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyUp) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.Back, Key.Escape -> {
                        onDismiss()
                        true
                    }
                    else -> false
                }
            }
            .padding(horizontal = TvAnsiKeyboardHorizontalPadding, vertical = TvAnsiKeyboardVerticalPadding),
        verticalArrangement = Arrangement.spacedBy(TvAnsiKeyboardRowGap),
    ) {
        rows.forEachIndexed { rowIndex, row ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(TvAnsiKeyboardKeyGap),
                modifier = Modifier.fillMaxWidth(),
            ) {
                row.forEachIndexed { keyIndex, key ->
                    if (key.type == TvAnsiKeyType.Spacer) {
                        Spacer(modifier = Modifier.weight(key.weight))
                        return@forEachIndexed
                    }

                    val focusRequester = keyFocusRequesters
                        .getOrNull(rowIndex)
                        ?.getOrNull(keyIndex)
                    val isPrimary = key.type == TvAnsiKeyType.Primary
                    val isShift = key.type == TvAnsiKeyType.Shift
                    val label = key.displayLabel(
                        shifted = shifted,
                        primaryLabel = primaryLabel,
                        passwordVisible = passwordVisible,
                    )

                    TvAnsiKeyButton(
                        label = label,
                        enabled = enabled && (!isPrimary || primaryEnabled),
                        isPrimary = isPrimary,
                        isShifted = isShift && shifted,
                        focusRequester = focusRequester,
                        leftFocusRequester = keyboardFocusRequesterFor(rows, keyFocusRequesters, rowIndex, keyIndex, Key.DirectionLeft),
                        rightFocusRequester = keyboardFocusRequesterFor(rows, keyFocusRequesters, rowIndex, keyIndex, Key.DirectionRight),
                        upFocusRequester = keyboardFocusRequesterFor(rows, keyFocusRequesters, rowIndex, keyIndex, Key.DirectionUp),
                        downFocusRequester = keyboardFocusRequesterFor(rows, keyFocusRequesters, rowIndex, keyIndex, Key.DirectionDown),
                        onDirection = { direction -> moveKeyboardFocus(rowIndex, keyIndex, direction) },
                        onClick = {
                            when (key.type) {
                                TvAnsiKeyType.Insert -> {
                                    onAction(TvAnsiKeyboardAction.Insert(key.insertText(shifted)))
                                    if (shifted && key.type == TvAnsiKeyType.Insert) shifted = false
                                }
                                TvAnsiKeyType.Shortcut -> {
                                    onAction(TvAnsiKeyboardAction.Insert(key.text.orEmpty()))
                                    shifted = false
                                }
                                TvAnsiKeyType.Backspace -> onAction(TvAnsiKeyboardAction.Backspace)
                                TvAnsiKeyType.Clear -> onAction(TvAnsiKeyboardAction.Clear)
                                TvAnsiKeyType.Space -> onAction(TvAnsiKeyboardAction.Insert(" "))
                                TvAnsiKeyType.Shift -> shifted = !shifted
                                TvAnsiKeyType.Visibility -> onTogglePasswordVisibility()
                                TvAnsiKeyType.Primary -> onPrimary()
                                TvAnsiKeyType.Spacer -> Unit
                            }
                        },
                        modifier = Modifier
                            .weight(key.weight)
                            .height(TvAnsiKeyboardKeyHeight),
                    )
                }
            }
        }
    }
}

@Composable
private fun TvAnsiKeyButton(
    label: String,
    enabled: Boolean,
    isPrimary: Boolean,
    isShifted: Boolean,
    onDirection: (Key) -> Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
    leftFocusRequester: FocusRequester? = null,
    rightFocusRequester: FocusRequester? = null,
    upFocusRequester: FocusRequester? = null,
    downFocusRequester: FocusRequester? = null,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val shape = RoundedCornerShape(8.dp)
    val fill = when {
        !enabled -> Color.White.copy(alpha = 0.03f)
        isPrimary && isFocused -> TvKeyboardCream
        isFocused -> Color.White.copy(alpha = 0.90f)
        isPrimary -> TvKeyboardGold.copy(alpha = 0.24f)
        isShifted -> TvKeyboardGold.copy(alpha = 0.16f)
        else -> Color.White.copy(alpha = 0.065f)
    }
    val content = when {
        !enabled -> Color.White.copy(alpha = 0.30f)
        isPrimary && isFocused -> TvKeyboardCreamInk
        isFocused -> Color.Black.copy(alpha = 0.88f)
        isPrimary -> Color.White
        else -> Color.White.copy(alpha = 0.88f)
    }

    Box(
        modifier = modifier
            .clip(shape)
            .background(fill)
            .border(
                width = if (isFocused) 2.dp else 1.dp,
                color = when {
                    isFocused -> Color.White
                    isPrimary || isShifted -> TvKeyboardGold.copy(alpha = 0.58f)
                    else -> Color.White.copy(alpha = 0.12f)
                },
                shape = shape,
            )
            .then(focusRequester?.let { Modifier.focusRequester(it) } ?: Modifier)
            .focusProperties {
                left = leftFocusRequester ?: FocusRequester.Cancel
                right = rightFocusRequester ?: FocusRequester.Cancel
                up = upFocusRequester ?: FocusRequester.Cancel
                down = downFocusRequester ?: FocusRequester.Cancel
            }
            .focusable(enabled = enabled, interactionSource = interactionSource)
            .onPreviewKeyEvent { event ->
                if (event.key in TvAnsiDirectionalKeys) {
                    return@onPreviewKeyEvent when (event.type) {
                        KeyEventType.KeyDown -> onDirection(event.key)
                        KeyEventType.KeyUp -> true
                        else -> false
                    }
                }
                if (event.type != KeyEventType.KeyUp) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                        onClick()
                        true
                    }
                    else -> false
                }
            }
            .clickable(
                enabled = enabled,
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = content,
            fontSize = when {
                label.length > 9 -> 10.sp
                label.length > 5 -> 11.sp
                label.length > 2 -> 12.sp
                else -> 16.sp
            },
            lineHeight = 16.sp,
            fontWeight = if (isPrimary) FontWeight.SemiBold else FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Clip,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
    }
}

private data class TvAnsiKeySpec(
    val label: String = "",
    val shifted: String? = null,
    val text: String? = label,
    val type: TvAnsiKeyType = TvAnsiKeyType.Insert,
    val weight: Float = 1f,
    val isLetter: Boolean = false,
)

private enum class TvAnsiKeyType {
    Insert,
    Shortcut,
    Backspace,
    Shift,
    Clear,
    Space,
    Visibility,
    Primary,
    Spacer,
}

private val TvKeyboardCream = Color(0xFFFDF7EC)
private val TvKeyboardCreamInk = Color(0xFF20160A)
private val TvKeyboardGold = Color(0xFFD4B886)
private val TvAnsiKeyboardKeyHeight = 28.dp
private val TvAnsiKeyboardKeyGap = 5.dp
private val TvAnsiKeyboardRowGap = 4.dp
private val TvAnsiKeyboardHorizontalPadding = 12.dp
private val TvAnsiKeyboardVerticalPadding = 8.dp
private val TvAnsiKeyboardMaxWidth = 600.dp

private fun insertKey(
    label: String,
    shifted: String? = null,
    weight: Float = 1f,
    isLetter: Boolean = false,
): TvAnsiKeySpec =
    TvAnsiKeySpec(label = label, shifted = shifted, weight = weight, isLetter = isLetter)

private fun actionKey(type: TvAnsiKeyType, label: String, weight: Float): TvAnsiKeySpec =
    TvAnsiKeySpec(label = label, text = null, type = type, weight = weight)

private fun shortcutKey(shortcut: TvAnsiShortcutKey): TvAnsiKeySpec =
    TvAnsiKeySpec(
        label = shortcut.label,
        text = shortcut.text,
        type = TvAnsiKeyType.Shortcut,
        weight = shortcut.weight,
    )

private fun mainKey(key: TvAnsiMainKey): TvAnsiKeySpec =
    TvAnsiKeySpec(
        label = key.label,
        text = key.text,
        type = TvAnsiKeyType.Insert,
        weight = key.weight,
    )

private fun spacer(weight: Float): TvAnsiKeySpec =
    TvAnsiKeySpec(type = TvAnsiKeyType.Spacer, weight = weight)

private fun TvAnsiKeySpec.displayLabel(
    shifted: Boolean,
    primaryLabel: String,
    passwordVisible: Boolean,
): String =
    when (type) {
        TvAnsiKeyType.Insert -> when {
            shifted && this.shifted != null -> this.shifted
            shifted && isLetter -> label.uppercase()
            else -> label
        }
        TvAnsiKeyType.Shift -> if (shifted) "ABC" else "Shift"
        TvAnsiKeyType.Primary -> primaryLabel
        TvAnsiKeyType.Visibility -> if (passwordVisible) "Hide" else "Show"
        else -> label
    }

private fun TvAnsiKeySpec.insertText(shifted: Boolean): String =
    when {
        shifted && this.shifted != null -> this.shifted
        shifted && isLetter -> label.uppercase()
        else -> text.orEmpty()
    }

private fun buildTvAnsiRows(
    shifted: Boolean,
    shortcuts: List<TvAnsiShortcutKey>,
    mainKeys: List<TvAnsiMainKey>,
    showPasswordVisibilityKey: Boolean,
): List<List<TvAnsiKeySpec>> =
    buildList {
        add(TvAnsiNumberRow)
        add(TvAnsiQwertyRow)
        add(TvAnsiHomeRow)
        add(buildTvAnsiBottomLetterRow(mainKeys))
        if (shortcuts.isNotEmpty()) {
            add(buildTvAnsiShortcutRow(shortcuts))
        }
        add(buildTvAnsiUtilityRow(shortcuts, showPasswordVisibilityKey))
    }

private val TvAnsiDirectionalKeys = setOf(
    Key.DirectionLeft,
    Key.DirectionRight,
    Key.DirectionUp,
    Key.DirectionDown,
)

private fun findTvAnsiFocusTarget(
    rows: List<List<TvAnsiKeySpec>>,
    rowIndex: Int,
    keyIndex: Int,
    direction: Key,
): Pair<Int, Int>? =
    when (direction) {
        Key.DirectionLeft -> findTvAnsiHorizontalTarget(rows, rowIndex, keyIndex, step = -1)
        Key.DirectionRight -> findTvAnsiHorizontalTarget(rows, rowIndex, keyIndex, step = 1)
        Key.DirectionUp -> findTvAnsiVerticalTarget(rows, rowIndex, keyIndex, step = -1)
        Key.DirectionDown -> findTvAnsiVerticalTarget(rows, rowIndex, keyIndex, step = 1)
        else -> null
    }

private fun keyboardFocusRequesterFor(
    rows: List<List<TvAnsiKeySpec>>,
    requesters: List<List<FocusRequester?>>,
    rowIndex: Int,
    keyIndex: Int,
    direction: Key,
): FocusRequester? {
    val (targetRow, targetKey) = findTvAnsiFocusTarget(rows, rowIndex, keyIndex, direction) ?: return null
    return requesters.getOrNull(targetRow)?.getOrNull(targetKey)
}

private fun findTvAnsiHorizontalTarget(
    rows: List<List<TvAnsiKeySpec>>,
    rowIndex: Int,
    keyIndex: Int,
    step: Int,
): Pair<Int, Int>? {
    val row = rows.getOrNull(rowIndex) ?: return null
    var candidate = keyIndex + step
    while (candidate in row.indices) {
        if (row[candidate].type != TvAnsiKeyType.Spacer) return rowIndex to candidate
        candidate += step
    }
    return rowIndex to keyIndex
}

private fun findTvAnsiVerticalTarget(
    rows: List<List<TvAnsiKeySpec>>,
    rowIndex: Int,
    keyIndex: Int,
    step: Int,
): Pair<Int, Int>? {
    var candidateRow = rowIndex + step
    while (candidateRow in rows.indices) {
        nearestFocusableKeyIndex(rows[candidateRow], keyIndex)?.let { return candidateRow to it }
        candidateRow += step
    }
    return rowIndex to keyIndex
}

private fun nearestFocusableKeyIndex(row: List<TvAnsiKeySpec>, keyIndex: Int): Int? =
    row.indices
        .filter { row[it].type != TvAnsiKeyType.Spacer }
        .minByOrNull { kotlin.math.abs(it - keyIndex) }

private val TvAnsiNumberRow = listOf(
    insertKey("1", shifted = "!"),
    insertKey("2", shifted = "@"),
    insertKey("3", shifted = "#"),
    insertKey("4", shifted = "$"),
    insertKey("5", shifted = "%"),
    insertKey("6", shifted = "^"),
    insertKey("7", shifted = "&"),
    insertKey("8", shifted = "*"),
    insertKey("9", shifted = "("),
    insertKey("0", shifted = ")"),
    insertKey("-", shifted = "_"),
    insertKey("=", shifted = "+"),
)

private val TvAnsiSymbolRow = listOf(
    insertKey("!"),
    insertKey("@"),
    insertKey("#"),
    insertKey("$"),
    insertKey("%"),
    insertKey("&"),
    insertKey("*"),
    insertKey("("),
    insertKey(")"),
    insertKey("-"),
    insertKey("_"),
    insertKey("+"),
    insertKey("/"),
)

private val TvAnsiQwertyRow = listOf(
    insertKey("q", isLetter = true),
    insertKey("w", isLetter = true),
    insertKey("e", isLetter = true),
    insertKey("r", isLetter = true),
    insertKey("t", isLetter = true),
    insertKey("y", isLetter = true),
    insertKey("u", isLetter = true),
    insertKey("i", isLetter = true),
    insertKey("o", isLetter = true),
    insertKey("p", isLetter = true),
    insertKey("[", shifted = "{"),
    insertKey("]", shifted = "}"),
    insertKey("\\", shifted = "|"),
)

private val TvAnsiHomeRow = listOf(
    spacer(0.45f),
    insertKey("a", isLetter = true),
    insertKey("s", isLetter = true),
    insertKey("d", isLetter = true),
    insertKey("f", isLetter = true),
    insertKey("g", isLetter = true),
    insertKey("h", isLetter = true),
    insertKey("j", isLetter = true),
    insertKey("k", isLetter = true),
    insertKey("l", isLetter = true),
    insertKey(";", shifted = ":"),
    insertKey("'", shifted = "\""),
    spacer(0.45f),
)

private fun buildTvAnsiBottomLetterRow(mainKeys: List<TvAnsiMainKey>): List<TvAnsiKeySpec> =
    buildList {
        add(spacer(1.1f))
        add(insertKey("z", isLetter = true))
        add(insertKey("x", isLetter = true))
        add(insertKey("c", isLetter = true))
        add(insertKey("v", isLetter = true))
        add(insertKey("b", isLetter = true))
        add(insertKey("n", isLetter = true))
        add(insertKey("m", isLetter = true))
        add(insertKey(",", shifted = "<"))
        add(insertKey(".", shifted = ">"))
        add(insertKey("/", shifted = "?"))
        mainKeys.forEach { add(mainKey(it)) }
        add(spacer(1.1f))
    }

private fun buildTvAnsiShortcutRow(shortcuts: List<TvAnsiShortcutKey>): List<TvAnsiKeySpec> =
    shortcuts.map(::shortcutKey)

private fun buildTvAnsiUtilityRow(
    shortcuts: List<TvAnsiShortcutKey>,
    showPasswordVisibilityKey: Boolean,
): List<TvAnsiKeySpec> =
    buildList {
        add(actionKey(TvAnsiKeyType.Shift, "Shift", weight = 1.35f))
        add(actionKey(TvAnsiKeyType.Clear, "Clear", weight = 1.1f))
        add(actionKey(TvAnsiKeyType.Space, "Space", weight = 3.6f))
        add(actionKey(TvAnsiKeyType.Backspace, "Delete", weight = 1.2f))
        if (showPasswordVisibilityKey) {
            add(actionKey(TvAnsiKeyType.Visibility, "Show", weight = 1.1f))
        }
        add(actionKey(TvAnsiKeyType.Primary, "Enter", weight = 1.35f))
    }

@Suppress("unused")
private val TvAnsiActivationKeyCodes = setOf(
    AndroidKeyEvent.KEYCODE_DPAD_CENTER,
    AndroidKeyEvent.KEYCODE_ENTER,
    AndroidKeyEvent.KEYCODE_NUMPAD_ENTER,
)
