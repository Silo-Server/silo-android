package org.siloserver.silo.tv.ui.screens.audiobook

import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.background
import androidx.compose.ui.focus.focusProperties
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import kotlinx.coroutines.delay
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import org.siloserver.silo.tv.ui.focus.TvFocusAcquisitionBudgetMillis
import org.siloserver.silo.tv.ui.focus.TvFocusRelocationBudgetMillis
import org.siloserver.silo.tv.ui.focus.TvObservedFocusResult
import org.siloserver.silo.tv.ui.focus.requestFocusUntilObserved

private const val TvAudiobookPanelFocusRetryDelayMillis = 60L

private val TvAudiobookPanelFocusMaxAttempts =
    (TvFocusAcquisitionBudgetMillis / TvAudiobookPanelFocusRetryDelayMillis).toInt()

/**
 * Confirming that a traversal landed is a relocation, not an acquisition: focus
 * has already moved and we are only waiting to see where. The short budget is
 * the right one — a long wait here just delays the fail-open.
 */
private val TvAudiobookPanelFocusConfirmAttempts =
    (TvFocusRelocationBudgetMillis / TvAudiobookPanelFocusRetryDelayMillis).toInt()

/**
 * Right-aligned full-screen overlay panel over a dimming scrim, shared by the
 * chapters / speed / sleep audiobook panels (spec §4.9 — focusable overlays,
 * not phone bottom sheets). Back is handled by the host screen's BackHandler;
 * the scaffold only makes sure the key reaches it (see the key handler below).
 *
 * Acquisition lives here rather than in each panel because every panel needs it
 * and the ones that hand-rolled it got it wrong in the same way: a single
 * unobserved `requestFocus()`, some of them fired before the target row had
 * been laid out. A rejected request was indistinguishable from a successful
 * one, so the panel opened with focus still on the transport pill behind the
 * scrim — and the containment below cannot help with focus that never entered.
 *
 * [initialFocus] is required, not optional: a panel with nothing focusable in
 * it cannot own focus, and while it is open the covered player is suppressed,
 * so "no focus target" means a dead D-pad rather than a cosmetic problem.
 *
 * [onAcquisitionFailed] is the escape hatch for when that still does not work.
 * Acquisition is retried until observed, but "retried until observed" is not
 * the same as "guaranteed", and the shared dialog adapter's last resort — ask
 * the focus system to enter the overlay by traversal — returns a Boolean that
 * can be false, with nothing left to try. Suppressing the player behind an
 * overlay that then fails to take focus is strictly worse than the escape bug
 * this replaces, so the failure is reported instead of swallowed and the host
 * hands the player back.
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
internal fun TvAudiobookOverlayScaffold(
    title: String,
    initialFocus: FocusRequester,
    modifier: Modifier = Modifier,
    onAcquisitionFailed: () -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    var panelHasFocus by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    val backDispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher

    LaunchedEffect(initialFocus) {
        val result = requestFocusUntilObserved(
            maxAttempts = TvAudiobookPanelFocusMaxAttempts,
            awaitAttempt = { delay(TvAudiobookPanelFocusRetryDelayMillis) },
            requestFocus = initialFocus::requestFocus,
            isFocused = { panelHasFocus },
        )
        if (result == TvObservedFocusResult.Focused || panelHasFocus) return@LaunchedEffect

        // Traversal, in case the named row never became focusable but something
        // else in the panel did. `exit` cancels searches *leaving* the group and
        // does not block entering it.
        //
        // A true return is not success. `moveFocus` is a global directional
        // move: it reports that focus moved, not that it moved into this panel.
        // Observed panel focus is the criterion for the request above, and
        // abandoning it here would be how a "focus went somewhere else entirely"
        // outcome gets recorded as a win while the player stays suppressed. So
        // the move is confirmed the same way, and an unconfirmed move is a
        // failure like any other.
        val entered = runCatching { focusManager.moveFocus(FocusDirection.Enter) }.getOrDefault(false)
        if (entered) {
            repeat(TvAudiobookPanelFocusConfirmAttempts) {
                delay(TvAudiobookPanelFocusRetryDelayMillis)
                if (panelHasFocus) return@LaunchedEffect
            }
        }
        if (!panelHasFocus) onAcquisitionFailed()
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.55f)),
        contentAlignment = Alignment.CenterEnd,
    ) {
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .width(320.dp)
                .background(Color(0xFF101010))
                // Focus trap: the panel is an in-window overlay, so without
                // containment D-pad Left/Up escapes to the transport pills
                // behind the scrim and Select activates them while the panel
                // stays open. Same recipe as the player HUD picker.
                .focusGroup()
                .focusProperties { exit = { FocusRequester.Cancel } }
                .onFocusChanged { panelHasFocus = it.hasFocus }
                // Before Android 16, Back reaches the app as a key event, and
                // Compose turns an unconsumed Back KeyDown into a
                // FocusDirection.Exit search. When the focused row sits directly
                // in this column (Speed, for one), the exit = Cancel above
                // cancels that search, which consumes the KeyDown, so the
                // activity never tracks the press and the KeyUp never reaches
                // the host's BackHandler: Back could not close the panel. Take
                // both halves here and hand the press to the back dispatcher. A
                // canceled KeyUp is Android 16 forwarding a press its back
                // callback has already handled, so it must not close a second
                // layer.
                .onPreviewKeyEvent { event ->
                    if (event.key != Key.Back && event.key != Key.Escape) return@onPreviewKeyEvent false
                    when (event.type) {
                        KeyEventType.KeyDown -> true
                        KeyEventType.KeyUp -> {
                            if (!event.nativeKeyEvent.isCanceled) backDispatcher?.onBackPressed()
                            true
                        }
                        else -> false
                    }
                }
                .padding(horizontal = 18.dp, vertical = 22.dp),
        ) {
            Text(text = title, style = MaterialTheme.typography.titleLarge, color = Color.White)
            content()
        }
    }
}

/**
 * Focusable D-pad row used inside the audiobook overlay panels. Select fires
 * [onSelect]; the current selection is tinted, the focused row inverts. An
 * optional [trailing] string (e.g. a chapter timestamp) is right-aligned.
 */
@Composable
internal fun TvAudiobookOverlayRow(
    label: String,
    isCurrent: Boolean,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier,
    trailing: String? = null,
    focusRequester: FocusRequester? = null,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val bg = when {
        isFocused -> Color.White
        isCurrent -> Color.White.copy(alpha = 0.12f)
        else -> Color.Transparent
    }
    val fg = if (isFocused) Color.Black else Color.White
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(bg)
            .let { mod -> if (focusRequester != null) mod.focusRequester(focusRequester) else mod }
            .focusable(interactionSource = interactionSource)
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyUp) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> { onSelect(); true }
                    else -> false
                }
            }
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = fg,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (trailing != null) {
            Spacer(Modifier.width(12.dp))
            Text(
                text = trailing,
                style = MaterialTheme.typography.bodyMedium,
                color = fg.copy(alpha = 0.7f),
            )
        }
    }
}

/** H:MM:SS / M:SS clock used across the audiobook TV surfaces. */
internal fun formatAudiobookTime(seconds: Double): String {
    if (seconds.isNaN() || seconds < 0.0) return "0:00"
    val total = seconds.toLong()
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
