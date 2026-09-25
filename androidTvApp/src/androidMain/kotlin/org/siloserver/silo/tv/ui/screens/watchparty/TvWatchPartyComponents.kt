package org.siloserver.silo.tv.ui.screens.watchparty

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Glow
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import kotlinx.coroutines.delay
import org.siloserver.silo.model.watchtogether.RoomMember
import org.siloserver.silo.model.watchtogether.RoomSnapshot
import org.siloserver.silo.repository.WatchTogetherConnectionState
import org.siloserver.silo.tv.ui.components.rememberTvDialogInitialFocus
import org.siloserver.silo.tv.ui.focus.TvControlState
import org.siloserver.silo.tv.ui.focus.tvControlSemantics
import org.siloserver.silo.tv.ui.focus.requestFocusUntilObserved
import org.siloserver.silo.tv.ui.focus.tvModalFocusBoundary
import org.siloserver.silo.tv.ui.screens.auth.QrCodePanel
import org.siloserver.silo.tv.ui.theme.DarkBackground
import org.siloserver.silo.tv.ui.theme.FocusedContainer
import org.siloserver.silo.tv.ui.theme.FocusedContent

private val DestructiveContainer = Color(0xFF7A2620).copy(alpha = 0.42f)
private val DestructiveContent = Color(0xFFFFB4A9)
private val DestructiveFocused = Color(0xFFB3261E)

/**
 * A full-width Watch Party action. A [state] gated by work in flight keeps the
 * row focusable (see [TvControlState.transient]) so live updates and pending
 * requests never strand focus; a structurally unavailable row leaves the
 * focus graph.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun TvPartyActionRow(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    state: TvControlState = TvControlState.structural(true),
    subtitle: String? = null,
    destructive: Boolean = false,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val shape = RoundedCornerShape(14.dp)
    val actionable = state.actionable
    val restContent = when {
        !actionable -> Color.White.copy(alpha = 0.42f)
        destructive -> DestructiveContent
        else -> Color.White
    }
    Surface(
        onClick = { state.perform(onClick) },
        enabled = state.focusable,
        interactionSource = interactionSource,
        shape = ClickableSurfaceDefaults.shape(shape = shape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (destructive && actionable) DestructiveContainer else Color.White.copy(alpha = 0.08f),
            contentColor = restContent,
            focusedContainerColor = if (destructive && actionable) DestructiveFocused else FocusedContainer,
            focusedContentColor = if (destructive && actionable) Color.White else FocusedContent,
            pressedContainerColor = if (destructive && actionable) DestructiveFocused else FocusedContainer,
            pressedContentColor = if (destructive && actionable) Color.White else FocusedContent,
            disabledContainerColor = Color.White.copy(alpha = 0.03f),
            disabledContentColor = Color.White.copy(alpha = 0.38f),
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.02f),
        border = ClickableSurfaceDefaults.border(
            focusedBorder = Border(
                border = BorderStroke(2.dp, DarkBackground.copy(alpha = 0.82f)),
                shape = shape,
            ),
        ),
        glow = ClickableSurfaceDefaults.glow(
            focusedGlow = Glow(elevationColor = Color.White.copy(alpha = 0.18f), elevation = 14.dp),
        ),
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .then(if (isFocused) Modifier.border(2.dp, Color.White.copy(alpha = 0.98f), shape) else Modifier)
            .tvControlSemantics(state),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            val focusedContent = if (destructive && actionable) Color.White else FocusedContent
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium.copy(fontSize = 17.sp, fontWeight = FontWeight.SemiBold),
                color = if (isFocused) focusedContent else restContent,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 14.sp, lineHeight = 18.sp),
                    color = (if (isFocused) focusedContent else Color.White).copy(alpha = 0.72f),
                )
            }
        }
    }
}

/** A compact action inside a list row (Vote, Queue, Remove). Same focus rules as [TvPartyActionRow]. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun TvPartyChip(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    state: TvControlState = TvControlState.structural(true),
    selected: Boolean = false,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val shape = RoundedCornerShape(10.dp)
    val rest = if (state.actionable) Color.White else Color.White.copy(alpha = 0.42f)
    Surface(
        onClick = { state.perform(onClick) },
        enabled = state.focusable,
        interactionSource = interactionSource,
        shape = ClickableSurfaceDefaults.shape(shape = shape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (selected) Color.White.copy(alpha = 0.22f) else Color.White.copy(alpha = 0.10f),
            contentColor = rest,
            focusedContainerColor = FocusedContainer,
            focusedContentColor = FocusedContent,
            pressedContainerColor = FocusedContainer,
            pressedContentColor = FocusedContent,
            disabledContainerColor = Color.White.copy(alpha = 0.03f),
            disabledContentColor = Color.White.copy(alpha = 0.38f),
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.04f),
        modifier = modifier
            .then(if (isFocused) Modifier.border(2.dp, Color.White.copy(alpha = 0.98f), shape) else Modifier)
            .tvControlSemantics(state),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge.copy(fontSize = 15.sp, fontWeight = FontWeight.SemiBold),
            color = if (isFocused) FocusedContent else rest,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
        )
    }
}

/** Small uppercase section label. */
@Composable
internal fun TvPartySectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelLarge.copy(
            fontSize = 14.sp,
            letterSpacing = 1.5.sp,
            fontWeight = FontWeight.SemiBold,
        ),
        color = Color.White.copy(alpha = 0.56f),
        modifier = modifier,
    )
}

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
 * The room code, large, plus a QR code of the invitation link when there is
 * one (the host). Guests get the code only.
 */
@Composable
internal fun TvPartyInvitation(
    code: String,
    inviteUrl: String?,
    modifier: Modifier = Modifier,
    qrSize: Dp = 132.dp,
) {
    if (code.isBlank()) return
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            TvPartySectionLabel("Party code")
            Text(
                text = tvWatchPartyDisplayCode(code),
                style = MaterialTheme.typography.displaySmall.copy(
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 4.sp,
                ),
                color = Color.White,
            )
            Text(
                text = if (inviteUrl != null) {
                    "Enter the code in Silo, or scan to join."
                } else {
                    "Enter this code in Silo to join."
                },
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 14.sp, lineHeight = 18.sp),
                color = Color.White.copy(alpha = 0.64f),
            )
        }
        if (inviteUrl != null) {
            QrCodePanel(content = inviteUrl, size = qrSize)
        }
    }
}

/** One member: name, host badge, this viewer, and status. Not focusable. */
@Composable
internal fun TvPartyMemberRow(member: RoomMember, room: RoomSnapshot, modifier: Modifier = Modifier) {
    val status = tvWatchPartyMemberStatus(member, room)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(Color.White.copy(alpha = 0.04f), RoundedCornerShape(10.dp))
            .padding(horizontal = 14.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = tvWatchPartyMemberName(member) + if (member.isSelf) " (you)" else "",
            style = MaterialTheme.typography.titleMedium.copy(fontSize = 16.sp),
            color = Color.White.copy(alpha = if (member.connected) 1f else 0.5f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (member.isHost) {
            Text(
                text = "HOST",
                style = MaterialTheme.typography.labelMedium.copy(
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp,
                ),
                color = DarkBackground,
                modifier = Modifier
                    .background(Color.White.copy(alpha = 0.88f), RoundedCornerShape(6.dp))
                    .padding(horizontal = 8.dp, vertical = 2.dp),
            )
        }
        if (status != null) {
            Text(
                text = status,
                style = MaterialTheme.typography.labelLarge.copy(fontSize = 15.sp),
                color = Color.White.copy(alpha = 0.72f),
            )
        }
    }
}

/** Frames to keep asking for the confirmation's initial focus. */
private const val CONFIRM_FOCUS_ATTEMPTS = 6

/**
 * Two-button confirmation. Initial focus goes to Cancel unless
 * [focusConfirm], so a stray Select never runs a destructive action.
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
    // The focusable popup hands focus to its first row (the confirm action)
    // on its own, and the shared initial-focus helper then stands down, so
    // move focus to the intended row once the rows exist.
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
            Column(
                modifier = Modifier
                    .width(420.dp)
                    .background(DarkBackground.copy(alpha = 0.94f), RoundedCornerShape(20.dp))
                    .border(0.6.dp, Color.White.copy(alpha = 0.20f), RoundedCornerShape(20.dp))
                    .padding(28.dp)
                    .tvModalFocusBoundary()
                    .then(rememberTvDialogInitialFocus(if (focusConfirm) confirmFocus else cancelFocus)),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    color = Color.White,
                    textAlign = TextAlign.Center,
                )
                if (message != null) {
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White.copy(alpha = 0.80f),
                        textAlign = TextAlign.Center,
                    )
                }
                TvPartyActionRow(
                    title = confirmLabel,
                    onClick = onConfirm,
                    destructive = destructive,
                    modifier = Modifier
                        .focusRequester(confirmFocus)
                        .onFocusChanged { confirmFocused = it.isFocused },
                )
                TvPartyActionRow(
                    title = cancelLabel,
                    onClick = onDismiss,
                    modifier = Modifier
                        .focusRequester(cancelFocus)
                        .onFocusChanged { cancelFocused = it.isFocused },
                )
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
