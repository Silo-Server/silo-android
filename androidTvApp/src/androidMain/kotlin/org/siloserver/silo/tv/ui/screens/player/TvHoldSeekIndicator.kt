package org.siloserver.silo.tv.ui.screens.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import org.siloserver.silo.common.R as CommonR
import org.siloserver.silo.common.i18n.LocaleFormat

/**
 * Floating chip shown during a hold-to-seek session. Mirrors the iOS
 * `HoldSeekIndicator` (see `iosApp/.../tvOS/HoldSeekIndicator.swift`) — a
 * top-anchored surface with the signed rate, a thin progress bar showing
 * where the preview is landing, and a hint row reminding the user which
 * buttons do what.
 *
 * The transport overlay is suppressed while a session is active (it would
 * shift focus away from the press-capture scrubber and lose the remote's
 * input), so this is the only visual feedback between hold-enter and
 * commit / cancel.
 */
@Composable
fun TvHoldSeekIndicator(
    isVisible: Boolean,
    rate: Int,
    previewTimeSec: Double,
    durationSec: Double,
    modifier: Modifier = Modifier,
    /** The server's preview of [previewTimeSec], above the chip as on tvOS; null shows the chip alone. */
    thumbnail: ImageBitmap? = null,
) {
    AnimatedVisibility(
        visible = isVisible,
        enter = fadeIn() + slideInVertically { -it / 4 },
        exit = fadeOut(),
        modifier = modifier,
    ) {
        // Once a preview has shown, its place stays for the rest of the
        // session, so the chip moves down once rather than with every sheet
        // that loads.
        var keepsPreviewSlot by remember { mutableStateOf(false) }
        if (thumbnail != null) SideEffect { keepsPreviewSlot = true }

        // Vertical preview-over-chip-over-bar-over-hint layout (mirrors tvOS
        // HoldSeekIndicator): the server's preview of the landing point, a
        // pill chip with a large rate label + preview time, a wide read-only
        // progress bar, and a dim hint row at the bottom.
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (thumbnail != null || keepsPreviewSlot) {
                val shape = RoundedCornerShape(10.dp)
                val frame = Modifier
                    .size(240.dp, 135.dp)
                    .shadow(9.dp, shape, clip = false)
                    .clip(shape)
                    .border(1.dp, Color.White.copy(alpha = 0.18f), shape)
                if (thumbnail != null) {
                    Image(
                        bitmap = thumbnail,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = frame,
                    )
                } else {
                    Box(frame.background(Color.Black.copy(alpha = 0.42f)))
                }
            }

            // Chip: direction-signed rate (large) + preview time.
            Surface(
                color = Color.Black.copy(alpha = 0.42f),
                shape = RoundedCornerShape(percent = 50),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(9.dp),
                ) {
                    Text(
                        text = if (rate == 0) stringResource(CommonR.string.player_seek_paused) else formatRate(rate),
                        color = Color.White,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Black,
                        fontFamily = FontFamily.Monospace,
                    )
                    Text(
                        text = formatTime(previewTimeSec, durationSec),
                        color = Color.White.copy(alpha = 0.75f),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }

            // Wide read-only progress bar.
            if (durationSec > 0.0) {
                val progress = (previewTimeSec / durationSec).toFloat().coerceIn(0f, 1f)
                Box(
                    modifier = Modifier
                        .width(260.dp)
                        .height(2.dp)
                        .clip(RoundedCornerShape(1.dp))
                        .background(Color.White.copy(alpha = 0.22f)),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(progress)
                            .height(2.dp)
                            .background(Color.White, RoundedCornerShape(1.dp)),
                    )
                }
            }

            // Hint row.
            Text(
                text = stringResource(CommonR.string.player_seek_holdHint),
                color = Color.White.copy(alpha = 0.55f),
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

/** The signed scan rate, such as "-4×"; a paused scan (0) shows its catalog word instead. */
private fun formatRate(rate: Int): String {
    val sign = if (rate < 0) "-" else ""
    return "$sign${kotlin.math.abs(rate)}×"
}

private fun formatTime(seconds: Double, durationSec: Double): String {
    if (!seconds.isFinite() || seconds < 0) return LocaleFormat.clock(0, 0, 0)
    val total = seconds.toInt()
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    val showHours = h > 0 || durationSec >= 3600.0
    return LocaleFormat.clock(h, m, s, showHours)
}
