package org.siloserver.silo.android.ui.screens.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Shared behavior for the player's modal bottom sheets.
 *
 * [playerSheetMaxHeight] caps sheet content so the sheet (and its drag
 * handle) never reaches the top edge of the screen. The player runs
 * immersive (system bars hidden), so Material 3 would otherwise let a tall
 * sheet grow flush with the display edge — where dragging the handle down
 * opens the system notification shade instead of moving the sheet.
 *
 * [PlayerSheetFlingGuard] sits between a sheet's scrollable content and the
 * sheet's own nested-scroll connection. Material 3 hands the fling velocity
 * left over after content scrolling to the sheet, so a downward fling that
 * runs into the top of the content hurls the sheet to Hidden — which is why
 * scrolling player menus back up kept dismissing them. Consuming the
 * post-fling velocity keeps flings inside the content; the sheet still
 * dismisses from a real drag (on the handle, or on content already at the
 * top) because drag deltas pass through untouched.
 */
@Composable
internal fun playerSheetMaxHeight(tabletopPaneHeight: Dp? = null): Dp =
    tabletopPaneHeight
        ?.let { (it - 32.dp).coerceAtLeast(160.dp) }
        ?: (LocalConfiguration.current.screenHeightDp * 0.8f).dp

/**
 * Material bottom sheets are window-level, so a sheet opened from the
 * lower-pane player overlay would otherwise expand back across the hinge and
 * dim the video. Cap the entire sheet to the measured controls pane and leave
 * the upper video pane visually untouched in tabletop posture.
 */
@Composable
internal fun Modifier.tabletopPlayerSheet(tabletopPaneHeight: Dp?): Modifier {
    if (tabletopPaneHeight == null) return this

    // ModalBottomSheet is hosted at the window level rather than inside the
    // lower-pane PlayerOverlay. Constraining its height alone leaves that host
    // at the top of the window on physical foldables, so translate the fixed
    // height region to the bottom pane explicitly. This placement is verified
    // on-device; Material's internal sheet anchor only moves content within
    // the constrained host and does not position the host below the hinge.
    val paneTop = (LocalConfiguration.current.screenHeightDp.dp - tabletopPaneHeight)
        .coerceAtLeast(0.dp)
    return height(tabletopPaneHeight).offset(y = paneTop)
}

@OptIn(ExperimentalMaterial3Api::class)
internal fun CoroutineScope.dismissPlayerSheet(
    sheetState: SheetState,
    onDismiss: () -> Unit,
    afterDismiss: (() -> Unit)? = null,
) {
    launch {
        sheetState.hide()
        onDismiss()
        afterDismiss?.invoke()
    }
}

@Composable
internal fun Modifier.playerSheetContent(tabletopPaneHeight: Dp?): Modifier =
    if (tabletopPaneHeight == null) {
        heightIn(max = playerSheetMaxHeight())
    } else {
        fillMaxHeight()
    }

internal fun playerSheetScrimColor(tabletopPaneHeight: Dp?): Color =
    if (tabletopPaneHeight == null) Color.Black.copy(alpha = 0.32f) else Color.Transparent

internal val PlayerSheetBackground = Color(0xFF090D12)
internal val PlayerSheetCardColor = Color(0xFF151B24)
internal val PlayerSheetSelectedColor = Color(0xFF10333D)
internal val PlayerSheetDividerColor = Color.White.copy(alpha = 0.08f)

internal fun playerSheetShape(tabletopPaneHeight: Dp?): Shape =
    if (tabletopPaneHeight == null) {
        RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
    } else {
        RectangleShape
    }

@Composable
@OptIn(ExperimentalMaterial3Api::class)
internal fun playerSheetDragHandle(tabletopPaneHeight: Dp?): (@Composable () -> Unit)? =
    if (tabletopPaneHeight == null) {
        {
            BottomSheetDefaults.DragHandle(
                color = Color.White.copy(alpha = 0.28f),
            )
        }
    } else {
        null
    }

internal fun playerSheetHorizontalPadding(tabletopPaneHeight: Dp?): Dp =
    if (tabletopPaneHeight == null) 20.dp else 24.dp

@Composable
@OptIn(ExperimentalMaterial3Api::class)
internal fun PlayerModalBottomSheet(
    onDismissRequest: () -> Unit,
    sheetState: SheetState,
    tabletopPaneHeight: Dp?,
    content: @Composable ColumnScope.() -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = sheetState,
        modifier = Modifier.tabletopPlayerSheet(tabletopPaneHeight),
        shape = playerSheetShape(tabletopPaneHeight),
        dragHandle = playerSheetDragHandle(tabletopPaneHeight),
        containerColor = PlayerSheetBackground,
        contentColor = Color.White,
        scrimColor = playerSheetScrimColor(tabletopPaneHeight),
    ) {
        KeepPlayerSheetImmersive()
        content()
    }
}

/**
 * Material hosts [ModalBottomSheet] in its own dialog window. Hiding system
 * bars on the player activity therefore does not cover the window that owns
 * an open sheet, which lets Samsung restore the status and navigation bars
 * while a player menu has focus. Apply the same immersive policy directly to
 * the sheet window so every shared player menu behaves like the player.
 */
@Composable
private fun KeepPlayerSheetImmersive() {
    val view = LocalView.current
    LaunchedEffect(view) {
        val sheetWindow = (view as? DialogWindowProvider)?.window
            ?: (view.parent as? DialogWindowProvider)?.window
            ?: return@LaunchedEffect
        WindowCompat.setDecorFitsSystemWindows(sheetWindow, false)
        WindowCompat.getInsetsController(sheetWindow, sheetWindow.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }
}

internal fun formatPlaybackSpeed(speed: Double): String {
    if (speed % 1.0 == 0.0) return speed.toInt().toString()
    return String.format(Locale.US, "%.2f", speed).trimEnd('0').trimEnd('.')
}

internal val PlayerSheetFlingGuard = object : NestedScrollConnection {
    override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity = available
}

/**
 * Header row for the glass-style sheets. When [onBack] is provided (gear
 * submenus), a leading chevron returns to the parent settings sheet — QA:
 * submenus previously had no way back.
 */
@Composable
internal fun PlayerSheetHeader(
    title: String,
    onBack: (() -> Unit)? = null,
    onDismiss: (() -> Unit)? = null,
    subtitle: String? = null,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .padding(
                start = if (onBack != null) 4.dp else 20.dp,
                end = if (onDismiss != null) 4.dp else 20.dp,
                top = 6.dp,
                bottom = 6.dp,
            ),
    ) {
        if (onBack != null) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                    contentDescription = "Back to playback settings",
                    tint = Color.White,
                )
            }
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = Color.White,
                fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    color = Color.White.copy(alpha = 0.56f),
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (onDismiss != null) {
            IconButton(onClick = onDismiss) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = "Close menu",
                    tint = Color.White.copy(alpha = 0.82f),
                )
            }
        }
    }
}

@Composable
internal fun PlayerSheetCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(18.dp))
            .background(PlayerSheetCardColor),
        content = content,
    )
}

@Composable
internal fun PlayerSheetSectionLabel(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text.uppercase(),
        color = Color.White.copy(alpha = 0.52f),
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.8.sp,
        modifier = modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 8.dp),
    )
}

@Composable
internal fun PlayerSheetDivider(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(PlayerSheetDividerColor),
    )
}
