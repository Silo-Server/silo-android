package org.siloserver.silo.android.ui.screens.player

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.siloserver.silo.playback.SubtitleSyncNotice

private val SyncSuccessColor = Color(0xFF6EE7B7)
private val SyncInfoColor = Color(0xFF7DD3FC)
private val SyncWarningColor = Color(0xFFFCD34D)

/**
 * A small card that follows a subtitle sync this viewer started: its progress
 * while it runs, then how it ended. It does not block the player; a finished
 * card leaves on its own, or on a tap.
 */
@Composable
fun SubtitleSyncCard(
    notice: SubtitleSyncNotice?,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Keyed by the notice id, so one job's progress updates the card in place
    // and a finished card fades out with its last content.
    AnimatedContent(
        targetState = notice,
        transitionSpec = { fadeIn() togetherWith fadeOut() },
        contentKey = { it?.id },
        label = "SubtitleSyncCard",
        modifier = modifier,
    ) { current ->
        if (current != null) SubtitleSyncCardContent(current, onDismiss)
    }
}

@Composable
private fun SubtitleSyncCardContent(notice: SubtitleSyncNotice, onDismiss: () -> Unit) {
    val running = notice.tone == SubtitleSyncNotice.Tone.Progress
    val accent = when (notice.tone) {
        SubtitleSyncNotice.Tone.Progress -> Color.White.copy(alpha = 0.15f)
        SubtitleSyncNotice.Tone.Success -> SyncSuccessColor.copy(alpha = 0.45f)
        SubtitleSyncNotice.Tone.Info -> SyncInfoColor.copy(alpha = 0.45f)
        SubtitleSyncNotice.Tone.Warning -> SyncWarningColor.copy(alpha = 0.55f)
    }
    Surface(
        color = Color.Black.copy(alpha = 0.82f),
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, accent),
        modifier = Modifier
            .width(300.dp)
            .semantics { liveRegion = LiveRegionMode.Polite }
            .then(if (running) Modifier else Modifier.clickable(onClick = onDismiss)),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SyncToneIcon(notice.tone, Modifier.padding(top = 2.dp).size(18.dp))
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    text = notice.title,
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                )
                notice.detail?.let {
                    Text(text = it, color = Color.White.copy(alpha = 0.70f), fontSize = 12.sp)
                }
                if (running && notice.percent != null) {
                    SyncProgressRow(percent = notice.percent ?: 0, modifier = Modifier.padding(top = 4.dp))
                }
            }
        }
    }
}

@Composable
private fun SyncToneIcon(tone: SubtitleSyncNotice.Tone, modifier: Modifier) {
    when (tone) {
        SubtitleSyncNotice.Tone.Progress -> CircularProgressIndicator(
            color = Color.White,
            trackColor = Color.White.copy(alpha = 0.25f),
            strokeWidth = 2.dp,
            modifier = modifier,
        )
        SubtitleSyncNotice.Tone.Success ->
            Icon(Icons.Outlined.CheckCircle, contentDescription = null, tint = SyncSuccessColor, modifier = modifier)
        SubtitleSyncNotice.Tone.Info ->
            Icon(Icons.Outlined.Info, contentDescription = null, tint = SyncInfoColor, modifier = modifier)
        SubtitleSyncNotice.Tone.Warning ->
            Icon(Icons.Outlined.Warning, contentDescription = null, tint = SyncWarningColor, modifier = modifier)
    }
}

/** A thin bar and its percentage, for a running sync. */
@Composable
fun SyncProgressRow(percent: Int, modifier: Modifier = Modifier, label: String? = null) {
    val progress by animateFloatAsState(targetValue = percent.coerceIn(0, 100) / 100f, label = "SubtitleSyncProgress")
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LinearProgressIndicator(
                progress = { progress },
                color = Color.White.copy(alpha = 0.85f),
                trackColor = Color.White.copy(alpha = 0.15f),
                strokeCap = StrokeCap.Round,
                modifier = Modifier.weight(1f),
            )
            if (label == null) {
                Text(text = "$percent%", color = Color.White.copy(alpha = 0.60f), fontSize = 11.sp)
            }
        }
        if (label != null) {
            Row(horizontalArrangement = Arrangement.SpaceBetween) {
                Text(text = label, color = Color.White.copy(alpha = 0.55f), fontSize = 12.sp, modifier = Modifier.weight(1f))
                Text(text = "$percent%", color = Color.White.copy(alpha = 0.55f), fontSize = 12.sp)
            }
        }
    }
}
