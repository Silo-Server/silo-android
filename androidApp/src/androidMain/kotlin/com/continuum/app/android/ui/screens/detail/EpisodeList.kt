package com.continuum.app.android.ui.screens.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.continuum.app.common.ui.components.ThumbhashImage
import com.continuum.app.model.catalog.EpisodeListItem

/**
 * Vertical list of episodes for a season.
 *
 * Each row shows a 160×90 16:9 thumbnail with progress overlay,
 * then S/E label, title, runtime, and overview. Tapping the
 * thumbnail plays; tapping the text section opens episode detail.
 *
 * Embedded in a parent scrollable container — does not scroll itself.
 */
@Composable
fun EpisodeList(
    episodes: List<EpisodeListItem>,
    onEpisodePlayClick: (String) -> Unit,
    onEpisodeDetailClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(horizontal = SafePadding),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        episodes.forEach { episode ->
            EpisodeRow(
                episode = episode,
                onPlayClick = { onEpisodePlayClick(episode.contentId) },
                onDetailClick = { onEpisodeDetailClick(episode.contentId) },
            )
        }
    }
}

@Composable
private fun EpisodeRow(
    episode: EpisodeListItem,
    onPlayClick: () -> Unit,
    onDetailClick: () -> Unit,
) {
    val userData = episode.userData
    val isPlayed = userData?.played == true
    val pos = userData?.positionSeconds
    val dur = userData?.durationSeconds
    // Watched episodes store position 0 server-side, so a nonzero position is
    // a live resume point and shows its bar even on a played episode (rewatch).
    val progress = if (pos != null && dur != null && dur > 0 && pos > 0) {
        (pos / dur).toFloat().coerceIn(0f, 1f)
    } else {
        null
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
    ) {
        Box(
            modifier = Modifier
                .width(160.dp)
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(8.dp))
                .clickable(onClick = onPlayClick),
        ) {
            ThumbhashImage(
                url = episode.stillUrl,
                thumbhash = episode.stillThumbhash,
                contentDescription = episode.title,
                modifier = Modifier.fillMaxWidth(),
            )

            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.55f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Default.PlayArrow,
                    contentDescription = "Play",
                    tint = Color.White,
                    modifier = Modifier.size(22.dp),
                )
            }

            if (progress != null && progress > 0f) {
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .height(3.dp),
                    color = Color.White,
                    trackColor = Color.Black.copy(alpha = 0.55f),
                )
            }
        }

        Spacer(modifier = Modifier.width(12.dp))

        Column(
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(8.dp))
                .clickable(onClick = onDetailClick)
                .padding(vertical = 2.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = episodeNumberText(episode),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = DetailPrimaryText,
                )
                if (episode.runtime > 0) {
                    Text(text = "·", style = MaterialTheme.typography.labelSmall, color = DetailTertiaryText)
                    Text(
                        text = "${episode.runtime} min",
                        style = MaterialTheme.typography.labelSmall,
                        color = DetailTertiaryText,
                    )
                }
                if (isPlayed) {
                    Spacer(modifier = Modifier.weight(1f))
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = "Watched",
                        tint = DetailPrimaryText,
                        modifier = Modifier.size(14.dp),
                    )
                }
            }

            Text(
                text = episode.title ?: "Episode ${episode.episodeNumber}",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = DetailPrimaryText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

            val overview = episode.overview
            if (!overview.isNullOrBlank()) {
                Text(
                    text = overview,
                    style = MaterialTheme.typography.bodySmall,
                    color = DetailSecondaryText,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

private fun episodeNumberText(episode: EpisodeListItem): String {
    val s = episode.seasonNumber.toString().padStart(2, '0')
    val e = episode.episodeNumber.toString().padStart(2, '0')
    return "S${s}E${e}"
}
