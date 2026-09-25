package org.siloserver.silo.tv.ui.screens.watchparty

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import kotlinx.coroutines.delay
import org.siloserver.silo.repository.WatchTogetherConnectionState
import org.siloserver.silo.tv.ui.components.rememberTvDialogInitialFocus
import org.siloserver.silo.tv.ui.focus.requestFocusUntilObserved
import org.siloserver.silo.tv.ui.focus.tvModalFocusBoundary
import org.siloserver.silo.tv.ui.theme.DarkSurfaceElevated
import org.siloserver.silo.tv.ui.theme.SiloOnSurface
import org.siloserver.silo.tv.ui.theme.SiloSecondaryText

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

/** Frames to keep asking for the confirmation's initial focus. */
private const val CONFIRM_FOCUS_ATTEMPTS = 6

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
    val confirmFocus = remember { FocusRequester() }
    val cancelFocus = remember { FocusRequester() }
    var confirmFocused by remember { mutableStateOf(false) }
    var cancelFocused by remember { mutableStateOf(false) }
    // The focusable popup hands focus to its first control on its own, and
    // the shared initial-focus helper then stands down, so move focus to the
    // intended button once the buttons exist.
    LaunchedEffect(focusConfirm) {
        requestFocusUntilObserved(
            maxAttempts = CONFIRM_FOCUS_ATTEMPTS,
            awaitAttempt = { withFrameNanos { } },
            requestFocus = (if (focusConfirm) confirmFocus else cancelFocus)::requestFocus,
            isFocused = { if (focusConfirm) confirmFocused else cancelFocused },
        )
    }
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
                .background(Color.Black.copy(alpha = 0.72f)),
            contentAlignment = Alignment.Center,
        ) {
            val shape = RoundedCornerShape(15.dp)
            Column(
                modifier = Modifier
                    .width(440.dp)
                    .background(DarkSurfaceElevated, shape)
                    .border(1.dp, PartyChromeBorder, shape)
                    .padding(28.dp)
                    .tvModalFocusBoundary()
                    .then(rememberTvDialogInitialFocus(if (focusConfirm) confirmFocus else cancelFocus)),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = title,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = SiloOnSurface,
                    textAlign = TextAlign.Center,
                )
                if (message != null) {
                    Text(
                        text = message,
                        fontSize = TvPartyMetrics.body,
                        color = SiloSecondaryText,
                        textAlign = TextAlign.Center,
                    )
                }
                Row(
                    modifier = Modifier.padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    TvPartyButton(
                        label = cancelLabel,
                        kind = TvPartyButtonKind.Secondary,
                        onClick = onDismiss,
                        modifier = Modifier
                            .focusRequester(cancelFocus)
                            .onFocusChanged { cancelFocused = it.isFocused },
                    )
                    TvPartyButton(
                        label = confirmLabel,
                        kind = if (destructive) TvPartyButtonKind.Secondary else TvPartyButtonKind.Primary,
                        onClick = onConfirm,
                        modifier = Modifier
                            .focusRequester(confirmFocus)
                            .onFocusChanged { confirmFocused = it.isFocused },
                    )
                }
            }
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
