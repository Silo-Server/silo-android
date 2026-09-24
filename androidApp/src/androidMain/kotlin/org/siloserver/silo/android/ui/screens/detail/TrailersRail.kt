package org.siloserver.silo.android.ui.screens.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.siloserver.silo.android.ui.theme.SiloSecondaryText
import org.siloserver.silo.android.ui.theme.SiloSurfaceElevated
import org.siloserver.silo.common.ui.components.DeferImagePresentationWhileScrolling
import org.siloserver.silo.common.ui.components.ThumbhashImage
import org.siloserver.silo.common.ui.openYoutubeTrailer
import org.siloserver.silo.model.catalog.ItemExtra
import org.siloserver.silo.model.catalog.TrailerRailEntry
import org.siloserver.silo.model.catalog.extraKindLabel
import org.siloserver.silo.model.catalog.trailerRailDurationLabel
import org.siloserver.silo.model.catalog.youtubeThumbnailUrl

/**
 * "Trailers & More" section — header plus a horizontal rail of remote
 * trailers and local extras (featurettes, behind the scenes, …). Mirrors
 * `PhoneTrailersRail.swift`.
 *
 * Remote trailers open in the YouTube app (or the browser when it isn't
 * installed). Local extras are ordinary playback targets, so those taps go
 * back up to the detail screen that owns player navigation.
 *
 * The whole section stays hidden when the item has neither, so there's no
 * orphaned header.
 */
@Composable
fun TrailersRail(
    entries: List<TrailerRailEntry>,
    onPlayExtra: (ItemExtra) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (entries.isEmpty()) return

    val context = LocalContext.current
    Column(
        verticalArrangement = Arrangement.spacedBy(14.dp),
        modifier = modifier,
    ) {
        SectionHeader(title = "Trailers & More")
        val rowState = rememberLazyListState()
        DeferImagePresentationWhileScrolling(rowState) {
        LazyRow(
            state = rowState,
            contentPadding = PaddingValues(horizontal = SafePadding),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            items(
                entries,
                key = { it.key },
                contentType = { if (it is TrailerRailEntry.Remote) "remote-trailer" else "local-extra" },
            ) { entry ->
                TrailerCard(
                    entry = entry,
                    onClick = {
                        when (entry) {
                            is TrailerRailEntry.Remote -> openYoutubeTrailer(context, entry.video)
                            is TrailerRailEntry.Local -> onPlayExtra(entry.extra)
                        }
                    },
                )
            }
        }
        }
    }
}

// iOS PhoneTrailersRail: card 240 wide, 16:9 thumbnail, corner radius 8 —
// matched to the episode rail so the two landscape rails line up.
private val TrailerCardWidth = 240.dp
private val TrailerThumbnailHeight = 135.dp
private val TrailerThumbnailShape = RoundedCornerShape(8.dp)

@Composable
private fun TrailerCard(
    entry: TrailerRailEntry,
    onClick: () -> Unit,
) {
    val kindLabel = extraKindLabel(entry.kind)
    val durationLabel = trailerRailDurationLabel(entry)
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .width(TrailerCardWidth)
            .clickable(onClick = onClick)
            .semantics { contentDescription = "${entry.title}, $kindLabel" },
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(TrailerCardWidth, TrailerThumbnailHeight)
                .clip(TrailerThumbnailShape)
                .background(SiloSurfaceElevated),
        ) {
            // Local extras have no artwork of their own — the scanner only
            // records the file — so they keep the surface tile.
            if (entry is TrailerRailEntry.Remote) {
                // hqdefault is 4:3 with letterbox bars; a 16:9 crop removes them.
                ThumbhashImage(
                    url = youtubeThumbnailUrl(entry.video.siteKey),
                    thumbhash = null,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.45f)),
            ) {
                Icon(
                    imageVector = Icons.Filled.PlayArrow,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = kindLabel.uppercase(),
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp,
                color = DetailPrimaryText.copy(alpha = 0.55f),
                maxLines = 1,
            )
            Text(
                text = entry.title,
                fontSize = 14.sp,
                lineHeight = 18.sp,
                fontWeight = FontWeight.SemiBold,
                color = DetailPrimaryText.copy(alpha = 0.92f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (durationLabel != null) {
                Text(
                    text = durationLabel,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = SiloSecondaryText,
                    maxLines = 1,
                )
            }
        }
    }
}
