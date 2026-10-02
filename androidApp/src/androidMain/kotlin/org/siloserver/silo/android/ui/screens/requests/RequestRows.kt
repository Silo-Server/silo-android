package org.siloserver.silo.android.ui.screens.requests

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.siloserver.silo.android.ui.theme.SiloBorder
import org.siloserver.silo.android.ui.theme.SiloOnSurface
import org.siloserver.silo.android.ui.theme.SiloSecondaryText
import org.siloserver.silo.android.ui.theme.SiloSurfaceContainer
import org.siloserver.silo.android.ui.theme.SiloSurfaceElevated
import org.siloserver.silo.common.requests.RequestColors
import org.siloserver.silo.common.requests.RequestRowCopy
import org.siloserver.silo.common.requests.RequestStageTrack
import org.siloserver.silo.common.ui.components.ThumbhashImage
import org.siloserver.silo.model.request.AdminRequestAction
import org.siloserver.silo.model.request.MediaRequest
import org.siloserver.silo.model.request.RequestDisplayState
import org.siloserver.silo.model.request.RequestProgress
import org.siloserver.silo.model.request.RequestRowActionPhase
import org.siloserver.silo.model.request.requestPosterUrl

/**
 * One request in a grouped list: poster, title, meta line, the stage track,
 * and a status line. The trailing slot is Open (in the library), Retry (admin,
 * failed), or a disclosure chevron. Swipe left or long-press to cancel while
 * the request waits for approval.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MyRequestRow(
    record: MediaRequest,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
    isBusy: Boolean = false,
    onOpenInLibrary: (() -> Unit)? = null,
    onCancel: (() -> Unit)? = null,
    onRetry: (() -> Unit)? = null,
    actionPhase: RequestRowActionPhase? = null,
    actionError: String? = null,
    shakeTrigger: Int = 0,
    showDivider: Boolean = false,
) {
    val progress = RequestProgress.of(record)
    val latestCancel by rememberUpdatedState(onCancel)
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value == SwipeToDismissBoxValue.EndToStart) latestCancel?.invoke()
            // The row leaves when the list re-reads; it never dismisses itself.
            false
        },
    )
    var menuOpen by remember { mutableStateOf(false) }
    SwipeToDismissBox(
        state = dismissState,
        modifier = modifier,
        enableDismissFromStartToEnd = false,
        enableDismissFromEndToStart = onCancel != null && !isBusy,
        backgroundContent = {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(RequestColors.Rose)
                    .padding(horizontal = 20.dp),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(Icons.Filled.Close, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                    Text("Cancel", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                }
            }
        },
    ) {
        Column(modifier = Modifier.background(SiloSurfaceContainer)) {
            if (showDivider) HorizontalDivider(modifier = Modifier.padding(start = 74.dp), color = SiloBorder.copy(alpha = 0.55f))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .alpha(if (isBusy || actionPhase != null) 0.45f else 1f)
                        .combinedClickable(
                            enabled = !isBusy,
                            onClick = onOpen,
                            onLongClick = if (onCancel != null) ({ menuOpen = true }) else null,
                        ),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RequestRowPoster(record.posterPath)
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = record.title,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = SiloOnSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = RequestRowCopy.meta(record, progress),
                            fontSize = 12.sp,
                            color = SiloSecondaryText,
                            maxLines = 1,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                        RequestStageTrack(progress = progress, modifier = Modifier.padding(top = 9.dp))
                        AnimatedContent(
                            targetState = actionError,
                            transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(200)) },
                            label = "rowStatus",
                            modifier = Modifier.padding(top = 6.dp),
                        ) { error ->
                            if (error != null) {
                                RequestRowErrorLine(text = error)
                            } else {
                                RequestStatusLabel(
                                    progress = progress,
                                    text = RequestRowCopy.status(record, progress),
                                    fontWeight = FontWeight.SemiBold,
                                    color = SiloOnSurface,
                                    maxLines = 2,
                                )
                            }
                        }
                    }
                }
                when {
                    onRetry != null -> RequestRowActionButton(
                        action = AdminRequestAction.Retry,
                        phase = actionPhase,
                        shakeTrigger = shakeTrigger,
                        enabled = !isBusy,
                        onClick = onRetry,
                    )
                    onOpenInLibrary != null && progress.display == RequestDisplayState.InLibrary ->
                        RequestCapsuleButton(title = "Open", icon = Icons.AutoMirrored.Filled.OpenInNew, onClick = onOpenInLibrary)
                    else -> Text(text = "›", fontSize = 20.sp, color = SiloSecondaryText.copy(alpha = 0.7f))
                }
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text("Cancel Request", color = RequestColors.Rose) },
                    leadingIcon = { Icon(Icons.Filled.Close, contentDescription = null, tint = RequestColors.Rose) },
                    onClick = {
                        menuOpen = false
                        onCancel?.invoke()
                    },
                )
            }
        }
    }
}

/** An admin's view of someone's pending request, with inline decisions. */
@Composable
internal fun RequestApprovalRow(
    record: MediaRequest,
    onOpen: () -> Unit,
    onApprove: () -> Unit,
    onDecline: () -> Unit,
    modifier: Modifier = Modifier,
    isBusy: Boolean = false,
    phase: RequestRowActionPhase? = null,
    actionError: String? = null,
    shakeTrigger: Int = 0,
    showDivider: Boolean = false,
) {
    Column(modifier = modifier.animateContentSize()) {
        if (showDivider) HorizontalDivider(modifier = Modifier.padding(start = 74.dp), color = SiloBorder.copy(alpha = 0.55f))
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .alpha(if (phase != null) 0.45f else 1f)
                    .rowClickable(enabled = !isBusy, onClick = onOpen),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                RequestRowPoster(record.posterPath)
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(
                        text = record.title + (record.year?.takeIf { it > 0 }?.let { " ($it)" } ?: ""),
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = SiloOnSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    RequestRowCopy.day(record.createdAt)?.let {
                        Text(text = "Requested $it", fontSize = 12.sp, color = SiloSecondaryText)
                    }
                    Text(text = RequestRowCopy.kindAndQuality(record), fontSize = 12.sp, color = SiloSecondaryText)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                RequestRowActionButton(
                    action = AdminRequestAction.Approve,
                    phase = phase,
                    shakeTrigger = shakeTrigger,
                    style = RequestRowButtonStyle.Prominent,
                    enabled = !isBusy,
                    onClick = onApprove,
                )
                RequestRowActionButton(
                    action = AdminRequestAction.Decline,
                    phase = phase,
                    shakeTrigger = shakeTrigger,
                    style = RequestRowButtonStyle.Wide,
                    enabled = !isBusy,
                    onClick = onDecline,
                )
            }
            if (actionError != null) RequestRowErrorLine(text = actionError)
        }
    }
}

private fun Modifier.rowClickable(enabled: Boolean, onClick: () -> Unit): Modifier =
    clip(RoundedCornerShape(8.dp)).combinedClickable(enabled = enabled, onClick = onClick)

@Composable
internal fun RequestRowPoster(path: String?) {
    Box(
        modifier = Modifier
            .size(width = 46.dp, height = 69.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(SiloSurfaceElevated),
    ) {
        requestPosterUrl(path)?.let { url ->
            ThumbhashImage(
                url = url,
                thumbhash = null,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize(),
            )
        }
    }
}

@Composable
internal fun RequestCapsuleButton(title: String, icon: ImageVector, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(RequestChromeSelectedFill)
            .rowClickable(enabled = true, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(icon, contentDescription = null, tint = SiloOnSurface, modifier = Modifier.size(14.dp))
        Text(title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = SiloOnSurface)
    }
}

internal enum class RequestRowButtonStyle {
    /** Compact capsule at the row's trailing edge (Retry). */
    Trailing,
    /** Full-width white capsule (Approve). */
    Prominent,
    /** Full-width quiet capsule (Decline). */
    Wide,
}

/**
 * An admin action button that shows its own progress: Retry's arrow spins
 * while the request is in flight (the others swap their icon for a spinner),
 * the icon turns into a check when the server accepts it, and the button
 * shakes when it fails. Buttons for the other actions on the row step back.
 */
@Composable
internal fun RowScope.RequestRowActionButton(
    action: AdminRequestAction,
    phase: RequestRowActionPhase?,
    onClick: () -> Unit,
    shakeTrigger: Int = 0,
    style: RequestRowButtonStyle = RequestRowButtonStyle.Trailing,
    enabled: Boolean = true,
) {
    val isWorking = phase == RequestRowActionPhase.Working(action)
    val isDone = phase == RequestRowActionPhase.Succeeded(action)
    val isSidelined = phase != null && !isWorking && !isDone
    val shake = remember { Animatable(0f) }
    LaunchedEffect(shakeTrigger) {
        if (shakeTrigger == 0) return@LaunchedEffect
        for (offset in listOf(-7f, 6f, -4f, 0f)) shake.animateTo(offset, tween(70))
    }
    val sidelineAlpha by animateFloatAsState(if (isSidelined) 0.35f else 1f, tween(250), label = "sideline")
    val foreground = if (style == RequestRowButtonStyle.Prominent) Color.Black else SiloOnSurface
    val background = if (style == RequestRowButtonStyle.Prominent) Color.White else RequestChromeSelectedFill
    val title = when (action) {
        AdminRequestAction.Approve -> if (isWorking) "Approving…" else if (isDone) "Approved" else "Approve"
        AdminRequestAction.Decline -> if (isWorking) "Declining…" else if (isDone) "Declined" else "Decline"
        AdminRequestAction.Retry -> if (isWorking) "Retrying…" else if (isDone) "Retried" else "Retry"
    }
    val idleIcon = when (action) {
        AdminRequestAction.Approve -> Icons.Filled.Check
        AdminRequestAction.Decline -> Icons.Filled.Close
        AdminRequestAction.Retry -> Icons.Filled.Refresh
    }
    val wide = style != RequestRowButtonStyle.Trailing
    Row(
        modifier = (if (wide) Modifier.weight(1f).heightIn(min = 38.dp) else Modifier)
            .graphicsLayer {
                translationX = shake.value * density
                alpha = sidelineAlpha
            }
            .clip(CircleShape)
            .background(background)
            .rowClickable(enabled = enabled && phase == null, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
    ) {
        Box(modifier = Modifier.size(16.dp), contentAlignment = Alignment.Center) {
            when {
                isDone -> Icon(
                    Icons.Filled.Check,
                    contentDescription = null,
                    tint = if (style == RequestRowButtonStyle.Prominent) Color.Black else RequestColors.Emerald,
                    modifier = Modifier.size(16.dp),
                )
                isWorking && action == AdminRequestAction.Retry -> {
                    val spin = rememberInfiniteTransition(label = "retrySpin")
                    val angle by spin.animateFloat(0f, 360f, infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Restart), label = "angle")
                    Icon(idleIcon, contentDescription = null, tint = foreground, modifier = Modifier.size(16.dp).rotate(angle))
                }
                isWorking -> CircularProgressIndicator(color = foreground, strokeWidth = 2.dp, modifier = Modifier.size(14.dp))
                else -> Icon(idleIcon, contentDescription = null, tint = foreground, modifier = Modifier.size(16.dp))
            }
        }
        Text(
            text = title,
            fontSize = if (wide) 14.sp else 13.sp,
            fontWeight = if (style == RequestRowButtonStyle.Prominent) FontWeight.Bold else FontWeight.SemiBold,
            color = foreground,
            maxLines = 1,
        )
    }
}
