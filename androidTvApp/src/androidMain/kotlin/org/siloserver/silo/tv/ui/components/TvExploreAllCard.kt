package org.siloserver.silo.tv.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import org.siloserver.silo.tv.ui.theme.siloCardDefaults

/**
 * The last card of a Home or library row: opens the row's full page. Takes
 * the row's card size and shape — a poster (square when the row's cards are),
 * or a 16:9 thumbnail — and the same focus lift as the cards beside it.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun TvExploreAllCard(
    style: TvRowStyle,
    cardLayout: TvRowCardLayout,
    artworkAspectRatio: Float?,
    posterWidth: Dp,
    onClick: () -> Unit,
    cardModifier: Modifier = Modifier,
) {
    val wide = style == TvRowStyle.Backdrop || cardLayout == TvRowCardLayout.ReferenceShelf
    val width = if (wide) tvEpisodeCardWidth() else posterWidth
    val aspectRatio = if (wide) 16f / 9f else artworkAspectRatio ?: (2f / 3f)
    val shape = TvExploreAllShape
    val focus = siloCardDefaults(shape = shape, focusedScale = if (wide) 1.04f else 1.10f)
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    Card(
        onClick = onClick,
        interactionSource = interactionSource,
        shape = CardDefaults.shape(shape = shape),
        colors = CardDefaults.colors(
            containerColor = Color.White.copy(alpha = 0.08f),
            focusedContainerColor = Color.White.copy(alpha = 0.16f),
        ),
        scale = focus.scale,
        border = focus.border,
        glow = focus.glow,
        modifier = cardModifier
            .width(width)
            .aspectRatio(aspectRatio),
    ) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .background(Color.White.copy(alpha = if (isFocused) 0.24f else 0.14f), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(24.dp),
                    )
                }
                Text(
                    text = "Explore all",
                    style = MaterialTheme.typography.titleSmall.copy(fontSize = 15.5.sp, lineHeight = 18.5.sp),
                    color = if (isFocused) Color.White else Color.White.copy(alpha = 0.85f),
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

private val TvExploreAllShape = RoundedCornerShape(8.dp)
