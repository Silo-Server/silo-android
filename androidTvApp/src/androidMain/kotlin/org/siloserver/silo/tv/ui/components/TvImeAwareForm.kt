package org.siloserver.silo.tv.ui.components

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusEvent
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import org.siloserver.silo.tv.ui.focus.TvFocusLog
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp

internal data class TvImeRelocationKey(
    val imeBottomPx: Int,
    val fieldWidthPx: Int,
    val fieldHeightPx: Int,
)

internal fun tvImeRelocationKey(
    hasFocus: Boolean,
    imeBottomPx: Int,
    fieldWidthPx: Int,
    fieldHeightPx: Int,
): TvImeRelocationKey? =
    if (hasFocus && imeBottomPx > 0 && fieldWidthPx > 0 && fieldHeightPx > 0) {
        TvImeRelocationKey(
            imeBottomPx = imeBottomPx,
            fieldWidthPx = fieldWidthPx,
            fieldHeightPx = fieldHeightPx,
        )
    } else {
        null
    }

internal fun shouldRestoreTvImeFormScroll(
    previousImeBottomPx: Int,
    currentImeBottomPx: Int,
): Boolean = previousImeBottomPx > 0 && currentImeBottomPx == 0

/**
 * Keeps a focused TV text-field context clear of the stock platform IME.
 *
 * Apply this to the smallest container that includes the field's visible
 * label. [focusGroup] lets the container observe focus held by its child text
 * field without becoming an extra D-pad destination.
 */
@Composable
internal fun Modifier.tvImeAwareFieldContext(
    bottomClearance: Dp = TvImeFieldBottomClearance,
): Modifier {
    val bringIntoViewRequester = remember { BringIntoViewRequester() }
    val density = LocalDensity.current
    val imeBottomPx = WindowInsets.ime.getBottom(density)
    val bottomClearancePx = with(density) { bottomClearance.toPx() }
    var hasFocus by remember { mutableStateOf(false) }
    var fieldSize by remember { mutableStateOf(IntSize.Zero) }
    val relocationKey = tvImeRelocationKey(
        hasFocus = hasFocus,
        imeBottomPx = imeBottomPx,
        fieldWidthPx = fieldSize.width,
        fieldHeightPx = fieldSize.height,
    )

    LaunchedEffect(relocationKey, bottomClearancePx) {
        val key = relocationKey ?: return@LaunchedEffect
        withFrameNanos { }
        runCatching {
            bringIntoViewRequester.bringIntoView(
                Rect(
                    left = 0f,
                    top = 0f,
                    right = key.fieldWidthPx.toFloat(),
                    bottom = key.fieldHeightPx + bottomClearancePx,
                ),
            )
        }
    }

    return this
        .bringIntoViewRequester(bringIntoViewRequester)
        .onSizeChanged { fieldSize = it }
        .onFocusEvent { hasFocus = it.hasFocus }
        .focusGroup()
}

/**
 * Owns scrolling for a TV form and returns it to its normal top position when
 * the stock IME closes. Initial composition with a hidden IME is a no-op.
 */
@Composable
internal fun rememberTvImeAwareFormScrollState(): ScrollState {
    val scrollState = rememberScrollState()
    val density = LocalDensity.current
    val imeBottomPx = WindowInsets.ime.getBottom(density)
    var previousImeBottomPx by remember { mutableIntStateOf(imeBottomPx) }

    LaunchedEffect(imeBottomPx) {
        if (shouldRestoreTvImeFormScroll(previousImeBottomPx, imeBottomPx)) {
            scrollState.scrollTo(0)
        }
        previousImeBottomPx = imeBottomPx
    }

    return scrollState
}

private val TvImeFieldBottomClearance = 32.dp

/**
 * Summons the stock IME on SELECT/ENTER instead of on focus. Pair with
 * `KeyboardOptions(showKeyboardOnFocus = false)` so D-pad focus can rest on a
 * field without the keyboard covering half the form; a click/tap still opens
 * it natively. Auth-flow field idiom (product call 2026-08-14).
 */
@Composable
internal fun Modifier.tvShowImeOnSelect(): Modifier {
    val keyboardController = LocalSoftwareKeyboardController.current
    // A select must start AND end on this field to summon the IME. Acting on
    // KeyUp alone leaks: activating a button whose click moves focus into the
    // field (e.g. "Sign in with a password") delivers the tail KeyUp of that
    // same press here and pops the keyboard uninvited — after which the D-pad
    // drives the keyboard instead of the form.
    val sawKeyDown = remember { java.util.concurrent.atomic.AtomicBoolean(false) }
    return this.onPreviewKeyEvent { event ->
        val selectKey = event.key == Key.DirectionCenter ||
            event.key == Key.Enter ||
            event.key == Key.NumPadEnter
        when {
            selectKey && event.type == KeyEventType.KeyDown -> {
                sawKeyDown.set(true)
                false
            }
            selectKey && event.type == KeyEventType.KeyUp -> {
                if (sawKeyDown.compareAndSet(true, false)) {
                    TvFocusLog.d { "field: select completed on field -> showing IME" }
                    keyboardController?.show()
                    true
                } else {
                    TvFocusLog.d { "field: stray select KeyUp suppressed (no matching KeyDown)" }
                    false
                }
            }
            else -> false
        }
    }
}
