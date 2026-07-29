package org.siloserver.silo.tv.ui.screens.watchtogether

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import org.siloserver.silo.tv.ui.components.rememberTvDialogInitialFocus
import org.siloserver.silo.tv.ui.screens.player.TvDialogActionRow
import org.siloserver.silo.tv.ui.theme.DarkBackground
import org.siloserver.silo.watchtogether.canDismissRoomEntry

enum class TvWatchTogetherMenuInitialAction {
    Resume,
    Host,
}

fun tvWatchTogetherMenuInitialAction(
    canResume: Boolean,
): TvWatchTogetherMenuInitialAction =
    if (canResume) {
        TvWatchTogetherMenuInitialAction.Resume
    } else {
        TvWatchTogetherMenuInitialAction.Host
    }

/**
 * Focus-owning Watch Together entry popup launched from the authenticated
 * profile dropdown. Resume is initially focused when a current room exists;
 * otherwise Host owns initial focus.
 */
@Composable
fun TvWatchTogetherMenuEntryDialog(
    canResume: Boolean,
    isBusy: Boolean,
    error: String?,
    onResume: () -> Unit,
    onHost: () -> Unit,
    onJoin: () -> Unit,
    onDismiss: () -> Unit,
) {
    val resumeFocus = remember { FocusRequester() }
    val hostFocus = remember { FocusRequester() }
    val initialAction = tvWatchTogetherMenuInitialAction(canResume)
    val initialFocus = when (initialAction) {
        TvWatchTogetherMenuInitialAction.Resume -> resumeFocus
        TvWatchTogetherMenuInitialAction.Host -> hostFocus
    }

    Popup(
        alignment = Alignment.Center,
        onDismissRequest = {
            if (canDismissRoomEntry(isBusy)) onDismiss()
        },
        properties = PopupProperties(
            focusable = true,
            dismissOnBackPress = canDismissRoomEntry(isBusy),
            dismissOnClickOutside = canDismissRoomEntry(isBusy),
            clippingEnabled = false,
        ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(start = 36.dp, top = 50.dp, end = 36.dp, bottom = 42.dp),
            contentAlignment = Alignment.Center,
        ) {
            val panelShape = RoundedCornerShape(14.dp)
            Column(
                modifier = Modifier
                    .width(340.dp)
                    .background(color = DarkBackground.copy(alpha = 0.68f), shape = panelShape)
                    .border(0.6.dp, Color.White.copy(alpha = 0.20f), panelShape)
                    .padding(horizontal = 14.dp, vertical = 14.dp)
                    .then(rememberTvDialogInitialFocus(initialFocus)),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = "WATCH TOGETHER",
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontSize = 16.sp,
                        letterSpacing = 1.1.sp,
                        fontWeight = FontWeight.Bold,
                    ),
                    color = Color.White.copy(alpha = 0.58f),
                    modifier = Modifier.padding(horizontal = 8.dp),
                )

                if (canResume) {
                    TvDialogActionRow(
                        title = "Resume current room",
                        enabled = !isBusy,
                        onClick = onResume,
                        modifier = Modifier.focusRequester(resumeFocus),
                    )
                }

                TvDialogActionRow(
                    title = if (isBusy) "Working…" else "Host a room",
                    enabled = !isBusy,
                    onClick = onHost,
                    modifier = Modifier.focusRequester(hostFocus),
                )

                TvDialogActionRow(
                    title = "Join by code",
                    enabled = !isBusy,
                    onClick = onJoin,
                )

                error?.let { message ->
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFFEF4444),
                        modifier = Modifier.padding(horizontal = 8.dp),
                    )
                }
            }
        }
    }
}
