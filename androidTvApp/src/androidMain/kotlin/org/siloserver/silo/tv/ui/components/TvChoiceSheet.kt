package org.siloserver.silo.tv.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import org.siloserver.silo.common.R as CommonR
import org.siloserver.silo.tv.ui.focus.TvContentInitialFocusMaxAttempts
import org.siloserver.silo.tv.ui.focus.requestFocusUntilObserved
import org.siloserver.silo.tv.ui.focus.tvModalFocusBoundary
import org.siloserver.silo.tv.ui.screens.player.TvPlayerChrome
import org.siloserver.silo.tv.ui.screens.player.TvPlayerType

/** One row of a [TvChoiceSheet]: an icon, a title and the line under it. */
data class TvChoice(
    val key: String,
    val title: String,
    val detail: String,
    val icon: ImageVector,
    val onClick: () -> Unit,
)

private val SheetWidth = 420.dp
private val RowHeight = 58.dp
private val IconTile = 36.dp
private val IconGlyph = 20.dp
private val RowShape = RoundedCornerShape(12.dp)

/**
 * A short list of choices, each with an icon and a line saying what it does,
 * then Cancel (Roku `ChoiceSheet`): the picker's Add. Drawn on the TV dialog
 * card over its scrim, in its own window, with focus on the first choice.
 * Back is Cancel.
 */
@Composable
fun TvChoiceSheet(
    title: String,
    choices: List<TvChoice>,
    onDismiss: () -> Unit,
) {
    BackHandler(onBack = onDismiss)
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(
            modifier = Modifier.fillMaxSize().background(TvDialogDefaults.Scrim),
            contentAlignment = Alignment.Center,
        ) {
            val first = remember { FocusRequester() }
            var firstFocused by remember { mutableStateOf(false) }
            LaunchedEffect(choices.firstOrNull()?.key) {
                requestFocusUntilObserved(
                    maxAttempts = TvContentInitialFocusMaxAttempts,
                    awaitAttempt = { withFrameNanos { } },
                    requestFocus = first::requestFocus,
                    isFocused = { firstFocused },
                )
            }
            val firstModifier = Modifier.focusRequester(first).onFocusChanged { firstFocused = it.isFocused }
            Column(
                modifier = Modifier
                    .width(SheetWidth)
                    .tvDialogSurface()
                    .tvModalFocusBoundary()
                    .padding(start = 24.dp, end = 24.dp, top = 24.dp, bottom = 20.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(text = title, style = TvPlayerType.ConfirmTitle, modifier = Modifier.padding(bottom = 10.dp))
                choices.forEachIndexed { index, choice ->
                    ChoiceRow(
                        title = choice.title,
                        detail = choice.detail,
                        icon = choice.icon,
                        onClick = choice.onClick,
                        modifier = if (index == 0) firstModifier else Modifier,
                    )
                }
                Spacer(Modifier.height(4.dp))
                ChoiceRow(
                    title = stringResource(CommonR.string.common_cancel),
                    detail = null,
                    icon = null,
                    onClick = onDismiss,
                    modifier = if (choices.isEmpty()) firstModifier else Modifier,
                )
            }
        }
    }
}

/** The focused row fills Paper with dark text; the others rest on white 7%. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ChoiceRow(
    title: String,
    detail: String?,
    icon: ImageVector?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    val ink = if (focused) TvPlayerChrome.Ink else TvPlayerChrome.Paper
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(if (detail == null) 44.dp else RowHeight)
            .clip(RowShape)
            .background(if (focused) TvPlayerChrome.Paper else Color.White.copy(alpha = 0.07f))
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                role = Role.Button,
                onClick = onClick,
            )
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = if (icon == null) Arrangement.Center else Arrangement.Start,
    ) {
        if (icon != null) {
            Box(
                modifier = Modifier
                    .size(IconTile)
                    .clip(RoundedCornerShape(9.dp))
                    .background(if (focused) Color.Black.copy(alpha = 0.08f) else Color(0xFF3A3A3C)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, tint = ink, modifier = Modifier.size(IconGlyph))
            }
            Spacer(Modifier.width(14.dp))
        }
        Column(horizontalAlignment = if (icon == null) Alignment.CenterHorizontally else Alignment.Start) {
            Text(text = title, style = TvPlayerType.Row, color = ink, maxLines = 1)
            if (detail != null) {
                Text(
                    text = detail,
                    style = TvPlayerType.RowDetail,
                    color = if (focused) TvPlayerChrome.InkMuted else TvPlayerChrome.Graphite,
                    maxLines = 1,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }
}
