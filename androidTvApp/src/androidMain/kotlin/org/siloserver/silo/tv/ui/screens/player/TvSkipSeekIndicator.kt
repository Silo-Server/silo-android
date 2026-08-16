package org.siloserver.silo.tv.ui.screens.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text

/** One hidden-controls D-pad skip: what it did and where it landed. */
data class SkipSeekFeedback(
    val deltaSeconds: Int,
    val targetSec: Double,
    val durationSec: Double,
    /** Bumped per press so consecutive skips restart the auto-hide timer. */
    val nonce: Int,
)

/**
 * Transient feedback for a D-pad or transport skip: a pill chip with the
 * signed delta ("+30s" / "−10s") and the landing time, above a thin
 * read-only progress line anchored where the transport scrubber lives (same
 * bottom geometry), so the position reads in the place users already look.
 *
 * Set [showTrack] false when the real scrubber is on screen. The chip then
 * carries only the delta — the thing the revealed transport cannot say — and
 * leaves position to the live bar rather than stacking a second track over it.
 *
 * The chip itself does NOT reveal the transport: while controls are hidden,
 * doing so would flip Left/Right from discrete skips into scrubber nudges
 * mid-sequence.
 */
@Composable
fun TvSkipSeekIndicator(
    feedback: SkipSeekFeedback?,
    modifier: Modifier = Modifier,
    showTrack: Boolean = true,
) {
    AnimatedVisibility(
        visible = feedback != null,
        enter = fadeIn() + slideInVertically { -it / 4 },
        exit = fadeOut(),
        modifier = modifier,
    ) {
        // Snapshot inside the visibility scope so the exit animation renders
        // the last real values instead of collapsing on null.
        val snapshot = feedback ?: return@AnimatedVisibility
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
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
                        text = formatSkipDelta(snapshot.deltaSeconds),
                        color = Color.White,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Black,
                        fontFamily = FontFamily.Monospace,
                    )
                    Text(
                        text = formatSkipTime(snapshot.targetSec, snapshot.durationSec),
                        color = Color.White.copy(alpha = 0.75f),
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }

            if (showTrack && snapshot.durationSec > 0.0) {
                val progress = (snapshot.targetSec / snapshot.durationSec).toFloat().coerceIn(0f, 1f)
                // Mirrors TvPlayerScrubber's resting track exactly: 3.5dp
                // capsule, White@0.24 rail, solid White fill — so the line is
                // visually the same bar the transport shows in this spot.
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(3.5.dp)
                        .clip(RoundedCornerShape(percent = 50))
                        .background(Color.White.copy(alpha = 0.24f)),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(progress)
                            .height(3.5.dp)
                            .clip(RoundedCornerShape(percent = 50))
                            .background(Color.White),
                    )
                }
            }
        }
    }
}

internal fun formatSkipDelta(deltaSeconds: Int): String =
    if (deltaSeconds < 0) "−${-deltaSeconds}s" else "+${deltaSeconds}s"

private fun formatSkipTime(seconds: Double, durationSec: Double): String {
    if (!seconds.isFinite() || seconds < 0) return "0:00"
    val total = seconds.toInt()
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    val showHours = h > 0 || durationSec >= 3600.0
    return if (showHours) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
