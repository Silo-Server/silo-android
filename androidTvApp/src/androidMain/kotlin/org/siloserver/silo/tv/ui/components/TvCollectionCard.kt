package org.siloserver.silo.tv.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import org.siloserver.silo.common.ui.components.ThumbhashImage
import org.siloserver.silo.model.section.LibraryCollection
import org.siloserver.silo.tv.ui.theme.SubtleSurface
import org.siloserver.silo.tv.ui.theme.siloCardDefaults

/**
 * A collection's 2:3 poster card: the library Collections grid and the Home
 * and library rows of collections.
 *
 * @param subtitle a second caption line under the title, set like
 *  [TvMediaCard]'s year line so the card sits in a row of media posters.
 * @param width a fixed card width for a row; null fills the grid cell.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun TvCollectionCard(
    collection: LibraryCollection,
    onClick: () -> Unit,
    focusRequester: FocusRequester? = null,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    width: Dp? = null,
) {
    // Same focus treatment (lift + white outline + shadow) and caption metrics
    // as `TvMediaCard`, so collection posters sit alongside Browse posters
    // without reading as a different card family.
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val cardFocus = siloCardDefaults(shape = TvCollectionCardShape)

    Column(modifier = if (width != null) Modifier.width(width) else Modifier.fillMaxWidth()) {
        Card(
            onClick = onClick,
            interactionSource = interactionSource,
            shape = CardDefaults.shape(shape = TvCollectionCardShape),
            scale = cardFocus.scale,
            border = cardFocus.border,
            glow = cardFocus.glow,
            modifier = modifier
                .let { if (focusRequester != null) it.focusRequester(focusRequester) else it }
                .fillMaxWidth()
                .aspectRatio(2f / 3f),
        ) {
            if (!collection.posterUrl.isNullOrBlank()) {
                ThumbhashImage(
                    url = collection.posterUrl,
                    thumbhash = collection.posterThumbhash,
                    contentDescription = collection.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(SubtleSurface),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Filled.VideoLibrary,
                        contentDescription = null,
                        tint = Color.White.copy(alpha = 0.8f),
                    )
                }
            }
        }

        // Title caption, start-aligned like every other poster caption in the
        // app. The grid shows no item count: it doubled the caption height and
        // made the rows read differently from Browse.
        Spacer(modifier = Modifier.height(11.dp))

        Text(
            text = collection.name,
            style = MaterialTheme.typography.titleSmall.copy(
                fontSize = 15.5.sp,
                lineHeight = 18.5.sp,
            ),
            color = if (isFocused) Color.White else Color.White.copy(alpha = 0.78f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth(),
        )

        if (subtitle != null) {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall.copy(
                    fontSize = 14.sp,
                    lineHeight = 18.sp,
                ),
                color = Color.White.copy(alpha = 0.70f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

private val TvCollectionCardShape = RoundedCornerShape(8.dp)
