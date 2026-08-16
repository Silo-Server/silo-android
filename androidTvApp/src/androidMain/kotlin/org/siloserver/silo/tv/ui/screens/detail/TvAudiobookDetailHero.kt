package org.siloserver.silo.tv.ui.screens.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import org.siloserver.silo.common.ui.components.ThumbhashImage
import org.siloserver.silo.model.catalog.ItemDetail
import org.siloserver.silo.tv.ui.navigation.TvSubtitleLaunchSelection
import org.siloserver.silo.tv.ui.navigation.explicitTvSubtitleLaunchSelection
import org.siloserver.silo.tv.ui.components.TvPoster
import org.siloserver.silo.tv.ui.components.TvPrimaryPillButton
import org.siloserver.silo.tv.ui.components.TvSecondaryPillButton
import org.siloserver.silo.tv.ui.screens.audiobook.formatAudiobookTime
import org.siloserver.silo.tv.ui.theme.DarkBackground
import org.siloserver.silo.tv.ui.theme.DarkSurface
import org.siloserver.silo.tv.ui.theme.Spacing

@Composable
internal fun TvAudiobookDetailHero(
    detail: ItemDetail,
    state: TvItemDetailUiState,
    playFocus: FocusRequester,
    onPlay: (contentId: String, fileId: Int?, audioTrackIndex: Int?, audioPickedThisSession: Boolean, subtitleSelection: TvSubtitleLaunchSelection?, itemType: String?, resumePositionSeconds: Double?) -> Unit,
    overview: String?,
    modifier: Modifier = Modifier,
) {
    // Play/Resume launch with a book-global position and NO part fileId: the
    // shared player VM stitches the whole-book timeline and resolves which part
    // contains the position (pinning Part 1's fileId here seeks it past the end
    // of Part 1). Mirrors Apple `TVAudiobookViewModel.performPrimary` and the
    // phone AudiobookDetailContent.
    val resumePosition = audiobookResumePositionSeconds(detail)
    val isFinished = audiobookIsFinished(detail)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 380.dp),
    ) {
        if (!detail.posterUrl.isNullOrBlank() || !detail.posterThumbhash.isNullOrBlank()) {
            ThumbhashImage(
                url = detail.posterUrl,
                thumbhash = detail.posterThumbhash,
                contentDescription = detail.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .matchParentSize()
                    .blur(30.dp)
                    .alpha(0.5f),
            )
        } else {
            Box(modifier = Modifier.matchParentSize().background(DarkSurface))
        }

        Box(
            modifier = Modifier
                .matchParentSize()
                .background(
                    Brush.verticalGradient(
                        0.00f to DarkBackground.copy(alpha = 0.55f),
                        0.50f to DarkBackground.copy(alpha = 0.30f),
                        1.00f to DarkBackground,
                    ),
                ),
        )

        Row(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .padding(horizontal = Spacing.safeArea, vertical = 32.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(28.dp),
        ) {
            TvPoster(
                imageUrl = detail.posterUrl,
                contentDescription = detail.title,
                modifier = Modifier
                    .size(180.dp)
                    .shadow(20.dp, RoundedCornerShape(14.dp)),
                cornerRadius = 14.dp,
            )

            Column(
                modifier = Modifier.widthIn(max = 520.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                AudiobookEyebrow()

                Text(
                    text = detail.title,
                    style = MaterialTheme.typography.headlineLarge.copy(fontWeight = FontWeight.Bold),
                    color = Color.White,
                    maxLines = 2,
                )

                audiobookMetadataLine(detail).takeIf { it.isNotBlank() }?.let { metadata ->
                    Text(
                        text = metadata,
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White.copy(alpha = 0.72f),
                        maxLines = 2,
                    )
                }

                detail.audiobook?.publisher?.takeIf { it.isNotBlank() }?.let { publisher ->
                    Text(
                        text = publisher,
                        style = MaterialTheme.typography.bodyLarge,
                        color = Color.White.copy(alpha = 0.58f),
                        maxLines = 1,
                    )
                }

                overview?.takeIf { it.isNotBlank() }?.let { description ->
                    Text(
                        text = description,
                        style = MaterialTheme.typography.bodyLarge.copy(lineHeight = 21.sp),
                        color = Color.White.copy(alpha = 0.76f),
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                Row(
                    modifier = Modifier.padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TvPrimaryPillButton(
                        icon = Icons.Filled.PlayArrow,
                        title = audiobookPlayLabel(resumePosition, isFinished),
                        focusRequester = playFocus,
                        onClick = {
                            // Resume → stored whole-book position; finished with no
                            // resume → restart at 0; fresh → null (VM resolves from
                            // its own furthest-position snapshot). Never a part fileId.
                            val startPosition = when {
                                resumePosition != null -> resumePosition
                                isFinished -> 0.0
                                else -> null
                            }
                            onPlay(
                                detail.contentId,
                                null,
                                state.selectedAudioIndex,
                                state.audioPickedThisSession,
                                explicitTvSubtitleLaunchSelection(state.selectedSubtitleIndex),
                                detail.type,
                                startPosition,
                            )
                        },
                    )
                    TvSecondaryPillButton(
                        icon = Icons.Filled.Replay,
                        title = "Start Over",
                        onClick = {
                            onPlay(
                                detail.contentId,
                                null,
                                state.selectedAudioIndex,
                                state.audioPickedThisSession,
                                explicitTvSubtitleLaunchSelection(state.selectedSubtitleIndex),
                                detail.type,
                                0.0,
                            )
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun AudiobookEyebrow() {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = Modifier
                .size(width = 17.dp, height = 2.dp)
                .background(Color.White.copy(alpha = 0.85f), RoundedCornerShape(1.dp)),
        )
        Text(
            text = "AUDIOBOOK",
            style = MaterialTheme.typography.labelLarge.copy(
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.5.sp,
            ),
            color = Color.White.copy(alpha = 0.78f),
        )
    }
}

private fun audiobookMetadataLine(detail: ItemDetail): String {
    val audiobook = detail.audiobook
    val duration = audiobook?.totalDurationSeconds?.toDouble()
        ?: detail.userData?.durationSeconds
        ?: detail.versions.sumOf { it.duration }.takeIf { it > 0.0 }
    return listOfNotNull(
        audiobook?.authorNames,
        audiobook?.narratorNames?.let { "Narrated by $it" },
        audiobookRuntimeLabel(duration),
    )
        .filter { it.isNotBlank() }
        .joinToString("  ")
}

private fun audiobookRuntimeLabel(seconds: Double?): String? {
    val total = seconds?.takeIf { it.isFinite() && it > 0.0 } ?: return null
    val hours = (total / 3600).toInt()
    val minutes = ((total % 3600) / 60).toInt()
    return when {
        hours > 0 && minutes > 0 -> "${hours}h ${minutes}m"
        hours > 0 -> "${hours}h"
        minutes > 0 -> "${minutes}m"
        else -> null
    }
}

private fun audiobookResumePositionSeconds(detail: ItemDetail): Double? {
    val userData = detail.userData ?: return null
    val position = userData.positionSeconds ?: return null
    val duration = userData.durationSeconds ?: return null
    if (position <= 30 || duration <= 0 || position >= duration - 5) return null
    return position
}

/** A book counts as finished when the server marks it played, or the stored
 *  whole-book position sits within 5s of the end. Mirrors the phone
 *  AudiobookDetailContent `isFinished` gating. */
private fun audiobookIsFinished(detail: ItemDetail): Boolean {
    val userData = detail.userData ?: return false
    if (userData.played == true) return true
    val position = userData.positionSeconds ?: return false
    val duration = userData.durationSeconds ?: return false
    return duration > 0 && position > 0 && position >= duration - 5
}

private fun audiobookPlayLabel(resumePosition: Double?, isFinished: Boolean): String {
    return when {
        resumePosition != null -> "Resume ${formatAudiobookTime(resumePosition)}"
        isFinished -> "Play Again"
        else -> "Play"
    }
}
