package org.siloserver.silo.tv.ui.screens.watchparty

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import kotlinx.coroutines.delay
import org.siloserver.silo.repository.WatchTogetherConnectionState
import org.siloserver.silo.tv.ui.components.TvDialogAction
import org.siloserver.silo.tv.ui.components.TvDialogActionStyle
import org.siloserver.silo.tv.ui.components.TvDialogCard
import org.siloserver.silo.tv.ui.components.TvDialogDefaults

/** A non-focusable one- or two-line notice (reconnecting, host away, host stopped). */
@Composable
internal fun TvPartyNotice(title: String, modifier: Modifier = Modifier, detail: String? = null) {
    Column(
        modifier = modifier
            .background(Color.Black.copy(alpha = 0.72f), RoundedCornerShape(14.dp))
            .border(1.dp, Color.White.copy(alpha = 0.14f), RoundedCornerShape(14.dp))
            .padding(horizontal = 18.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium.copy(fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
            color = Color.White,
        )
        if (detail != null) {
            Text(
                text = detail,
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 14.sp, lineHeight = 18.sp),
                color = Color.White.copy(alpha = 0.78f),
            )
        }
    }
}

/**
 * Two-button confirmation on the party's card. Initial focus goes to Cancel
 * unless [focusConfirm], so a stray Select never runs a destructive action.
 */
@Composable
internal fun TvWatchPartyConfirmDialog(
    title: String,
    message: String?,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    cancelLabel: String = "Cancel",
    destructive: Boolean = false,
    focusConfirm: Boolean = false,
) {
    Popup(
        alignment = Alignment.Center,
        onDismissRequest = onDismiss,
        properties = PopupProperties(
            focusable = true,
            dismissOnBackPress = true,
            dismissOnClickOutside = false,
            clippingEnabled = false,
        ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(TvDialogDefaults.Scrim),
            contentAlignment = Alignment.Center,
        ) {
            TvDialogCard(
                title = title,
                message = message,
                actions = listOf(
                    TvDialogAction(label = cancelLabel, onClick = onDismiss),
                    TvDialogAction(
                        label = confirmLabel,
                        onClick = onConfirm,
                        style = if (destructive) TvDialogActionStyle.Destructive else TvDialogActionStyle.Primary,
                    ),
                ),
                initialFocusIndex = if (focusConfirm) 1 else 0,
            )
        }
    }
}

/**
 * The reconnect notice text, or null. It appears only after the room socket
 * has been down for [delayMs], timed from when this screen first saw it down,
 * and clears as soon as the socket is writable again.
 */
@Composable
internal fun rememberTvWatchPartyReconnectNotice(
    connection: WatchTogetherConnectionState,
    inRoom: Boolean,
    delayMs: Long = TV_WATCH_PARTY_RECONNECT_NOTICE_DELAY_MS,
): String? {
    var shown by remember { mutableStateOf(false) }
    val down = inRoom && !connection.writable && connection.disconnectedAtMs != null
    LaunchedEffect(down, connection.disconnectedAtMs) {
        shown = false
        if (down) {
            delay(delayMs)
            shown = true
        }
    }
    if (!down || !shown) return null
    return if (connection.epoch == 0L) "Connecting to the party…" else "Reconnecting to the party…"
}

internal const val TV_WATCH_PARTY_RECONNECT_NOTICE_DELAY_MS = 2_000L
