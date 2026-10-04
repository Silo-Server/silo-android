package org.siloserver.silo.tv.ui.screens.player

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.siloserver.silo.playback.SubtitleSyncNotice

private val SyncSuccessColor = Color(0xFF6EE7B7)
private val SyncInfoColor = Color(0xFF7DD3FC)
private val SyncWarningColor = Color(0xFFFCD34D)

/**
 * A card that follows a subtitle sync this viewer started: its progress while
 * it runs, then how it ended. It never takes D-pad focus; a finished card
 * leaves on its own. Sized for TV viewing distance.
 */
@Composable
internal fun TvSubtitleSyncCard(notice: SubtitleSyncNotice?, modifier: Modifier = Modifier) {
    // Keyed by the notice id, so one job's progress updates the card in place
    // and a finished card fades out with its last content.
    AnimatedContent(
        targetState = notice,
        transitionSpec = { fadeIn() togetherWith fadeOut() },
        contentKey = { it?.id },
        label = "TvSubtitleSyncCard",
        modifier = modifier,
    ) { current ->
        if (current != null) TvSubtitleSyncCardContent(current)
    }
}

@Composable
private fun TvSubtitleSyncCardContent(notice: SubtitleSyncNotice) {
    val running = notice.tone == SubtitleSyncNotice.Tone.Progress
    val accent = when (notice.tone) {
        SubtitleSyncNotice.Tone.Progress -> Color.White.copy(alpha = 0.15f)
        SubtitleSyncNotice.Tone.Success -> SyncSuccessColor.copy(alpha = 0.45f)
        SubtitleSyncNotice.Tone.Info -> SyncInfoColor.copy(alpha = 0.45f)
        SubtitleSyncNotice.Tone.Warning -> SyncWarningColor.copy(alpha = 0.55f)
    }
    Surface(
        color = Color.Black.copy(alpha = 0.82f),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, accent),
        modifier = Modifier.width(360.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            val iconModifier = Modifier.padding(top = 2.dp).size(22.dp)
            when (notice.tone) {
                SubtitleSyncNotice.Tone.Progress -> CircularProgressIndicator(
                    color = Color.White,
                    trackColor = Color.White.copy(alpha = 0.25f),
                    strokeWidth = 2.5.dp,
                    modifier = iconModifier,
                )
                SubtitleSyncNotice.Tone.Success ->
                    Icon(Icons.Outlined.CheckCircle, contentDescription = null, tint = SyncSuccessColor, modifier = iconModifier)
                SubtitleSyncNotice.Tone.Info ->
                    Icon(Icons.Outlined.Info, contentDescription = null, tint = SyncInfoColor, modifier = iconModifier)
                SubtitleSyncNotice.Tone.Warning ->
                    Icon(Icons.Outlined.Warning, contentDescription = null, tint = SyncWarningColor, modifier = iconModifier)
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(text = notice.title, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Medium)
                notice.detail?.let { Text(text = it, color = Color.White.copy(alpha = 0.72f), fontSize = 15.sp) }
                val percent = notice.percent
                if (running && percent != null) {
                    TvSyncProgressBar(percent = percent, modifier = Modifier.padding(top = 6.dp))
                }
            }
        }
    }
}

/** A thin bar and its percentage, for a running sync. */
@Composable
internal fun TvSyncProgressBar(percent: Int, modifier: Modifier = Modifier) {
    val progress by animateFloatAsState(targetValue = percent.coerceIn(0, 100) / 100f, label = "TvSubtitleSyncProgress")
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        LinearProgressIndicator(
            progress = { progress },
            color = Color.White.copy(alpha = 0.85f),
            trackColor = Color.White.copy(alpha = 0.15f),
            strokeCap = StrokeCap.Round,
            modifier = Modifier.weight(1f),
        )
        Text(text = "$percent%", color = Color.White.copy(alpha = 0.62f), fontSize = 14.sp)
    }
}
