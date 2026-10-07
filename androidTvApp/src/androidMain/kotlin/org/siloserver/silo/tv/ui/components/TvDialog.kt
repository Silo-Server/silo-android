package org.siloserver.silo.tv.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.Text
import org.siloserver.silo.tv.ui.focus.TvContentInitialFocusMaxAttempts
import org.siloserver.silo.tv.ui.focus.requestFocusUntilObserved
import org.siloserver.silo.tv.ui.focus.tvModalFocusBoundary
import org.siloserver.silo.tv.ui.screens.player.TvPlayerChrome
import org.siloserver.silo.tv.ui.screens.player.TvPlayerType

/** How a dialog button reads: the safe way out, the recommended action, or a destructive one. */
enum class TvDialogActionStyle { Default, Primary, Destructive }

data class TvDialogAction(
    val label: String,
    val onClick: () -> Unit,
    val style: TvDialogActionStyle = TvDialogActionStyle.Default,
)

/** The one TV dialog surface: an opaque card with a hairline over a deep scrim. */
internal object TvDialogDefaults {
    val Scrim = Color.Black.copy(alpha = 0.78f)
    val Shape: Shape = RoundedCornerShape(22.dp)
    val Width = 470.dp
}

/** The card every TV dialog and content modal is drawn on. */
internal fun Modifier.tvDialogSurface(shape: Shape = TvDialogDefaults.Shape): Modifier = this
    .shadow(elevation = 30.dp, shape = shape, ambientColor = Color.Black, spotColor = Color.Black)
    .clip(shape)
    .background(TvPlayerChrome.Card)
    .border(1.dp, TvPlayerChrome.PanelStroke, shape)

/**
 * The one TV confirmation and alert: a title, what happens, and a row of
 * buttons in the order given. Focus starts on the first [TvDialogActionStyle.Default]
 * action — the safe way out — so a stray Select never runs the destructive
 * one; pass [initialFocusIndex] to choose another.
 *
 * Drawn in its own window. Callers already inside a Popup or an in-window
 * overlay use [TvDialogCard] directly so they keep their own window.
 */
@Composable
fun TvDialog(
    title: String,
    onDismissRequest: () -> Unit,
    actions: List<TvDialogAction>,
    modifier: Modifier = Modifier,
    message: String? = null,
    initialFocusIndex: Int = defaultTvDialogFocusIndex(actions),
    width: Dp = TvDialogDefaults.Width,
    content: (@Composable ColumnScope.() -> Unit)? = null,
) {
    BackHandler(onBack = onDismissRequest)
    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(TvDialogDefaults.Scrim),
            contentAlignment = Alignment.Center,
        ) {
            TvDialogCard(
                title = title,
                actions = actions,
                modifier = modifier,
                message = message,
                initialFocusIndex = initialFocusIndex,
                width = width,
                content = content,
            )
        }
    }
}

/** The safe way out: the first Default-style action, else the first action. */
fun defaultTvDialogFocusIndex(actions: List<TvDialogAction>): Int =
    actions.indexOfFirst { it.style == TvDialogActionStyle.Default }.coerceAtLeast(0)

/**
 * [TvDialog]'s card without a window: title, message, optional [content],
 * and the buttons, with focus contained and placed on [initialFocusIndex].
 */
@Composable
fun TvDialogCard(
    title: String,
    actions: List<TvDialogAction>,
    modifier: Modifier = Modifier,
    message: String? = null,
    initialFocusIndex: Int = defaultTvDialogFocusIndex(actions),
    width: Dp = TvDialogDefaults.Width,
    content: (@Composable ColumnScope.() -> Unit)? = null,
) {
    val initialFocus = remember { FocusRequester() }
    var focusedIndex by remember { mutableIntStateOf(-1) }
    // A dialog window or focusable popup hands focus to its first control on
    // its own, so ask for the intended button until that button holds it.
    LaunchedEffect(initialFocusIndex) {
        requestFocusUntilObserved(
            maxAttempts = TvContentInitialFocusMaxAttempts,
            awaitAttempt = { withFrameNanos { } },
            requestFocus = initialFocus::requestFocus,
            isFocused = { focusedIndex == initialFocusIndex },
        )
    }

    Column(
        modifier = modifier
            .width(width)
            .tvDialogSurface()
            .tvModalFocusBoundary()
            .padding(start = 32.dp, end = 32.dp, top = 30.dp, bottom = 28.dp),
    ) {
        Text(text = title, style = TvPlayerType.ConfirmTitle)
        if (!message.isNullOrBlank()) {
            Text(text = message, style = TvPlayerType.Body, modifier = Modifier.padding(top = 8.dp))
        }
        if (content != null) {
            Column(
                modifier = Modifier.padding(top = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                content = content,
            )
        }
        if (actions.isNotEmpty()) {
            Row(
                modifier = Modifier.padding(top = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                actions.forEachIndexed { index, action ->
                    TvDialogButton(
                        label = action.label,
                        onClick = action.onClick,
                        style = action.style,
                        modifier = Modifier
                            .then(if (index == initialFocusIndex) Modifier.focusRequester(initialFocus) else Modifier)
                            .onFocusChanged { state ->
                                if (state.isFocused) {
                                    focusedIndex = index
                                } else if (focusedIndex == index) {
                                    focusedIndex = -1
                                }
                            },
                    )
                }
            }
        }
    }
}

/**
 * A dialog button. At rest it is a quiet capsule (destructive: bright red on
 * a red tint, legible on the dark card); focused, it fills Paper, or red for
 * a destructive action, so the button you are on is never in doubt.
 */
@Composable
fun TvDialogButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: TvDialogActionStyle = TvDialogActionStyle.Default,
    enabled: Boolean = true,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val destructive = style == TvDialogActionStyle.Destructive
    val (fill, textColor) = when {
        isFocused && destructive -> TvPlayerChrome.Stop to Color.White
        isFocused -> TvPlayerChrome.Paper to TvPlayerChrome.Ink
        destructive -> TvPlayerChrome.StopRest to TvPlayerChrome.StopText
        else -> Color.White.copy(alpha = 0.08f) to TvPlayerChrome.Paper
    }
    val shape = RoundedCornerShape(22.dp)
    Box(
        modifier = modifier
            .height(44.dp)
            .then(
                if (isFocused) {
                    Modifier.shadow(elevation = 12.dp, shape = shape, ambientColor = Color.Black, spotColor = Color.Black)
                } else {
                    Modifier
                },
            )
            .clip(shape)
            .background(fill)
            .clickable(
                enabled = enabled,
                interactionSource = interactionSource,
                indication = null,
                role = Role.Button,
                onClick = onClick,
            )
            .alpha(if (enabled) 1f else 0.4f)
            .padding(horizontal = 26.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = label, style = TvPlayerType.Button, color = textColor, maxLines = 1)
    }
}
