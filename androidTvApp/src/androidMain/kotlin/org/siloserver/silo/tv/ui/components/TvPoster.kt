package org.siloserver.silo.tv.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import org.siloserver.silo.common.ui.components.DefaultArtworkKind
import org.siloserver.silo.common.ui.components.SpoilerImage

/**
 * Poster image that shows the default artwork when the URL is missing or fails to load.
 *
 * Sized according to the parent Modifier; the caller owns the aspect ratio. For 2:3 posters,
 * pass `Modifier.size(width, width * 3f / 2f)` (or use BoxWithConstraints).
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun TvPoster(
    imageUrl: String?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 8.dp,
    defaultArtwork: DefaultArtworkKind = DefaultArtworkKind.Video,
    /** Spoiler protection hides this artwork: an unwatched episode's still. */
    hidden: Boolean = false,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(cornerRadius))
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        SpoilerImage(
            url = imageUrl,
            thumbhash = null,
            hidden = hidden,
            contentDescription = contentDescription,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
            defaultArtwork = defaultArtwork,
        )
    }
}
