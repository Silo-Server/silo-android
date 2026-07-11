package org.siloserver.silo.tv.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.BorderStroke
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import org.siloserver.silo.tv.ui.theme.SiloBlue
import org.siloserver.silo.tv.ui.theme.DarkOnPrimary
import org.siloserver.silo.tv.ui.theme.FocusedContainer
import org.siloserver.silo.tv.ui.theme.FocusedContent

/**
 * Static pill-shaped chip for displaying metadata (year, runtime, genre, rating).
 * Not focusable — purely decorative. For interactive chips (filter selections),
 * see [TvFilterChip] below.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun TvMetadataChip(
    text: String,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(100.dp))
            .background(Color.White.copy(alpha = 0.1f))
            .padding(horizontal = 16.dp, vertical = 9.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onBackground,
        )
    }
}

/**
 * Focusable filter chip. Resting state stays quiet, selection reads as a white
 * capsule, and focus keeps that same inversion so chip rails feel closer to
 * tvOS than branded Android TV chrome.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun TvFilterChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(horizontal = 20.dp, vertical = 10.dp),
    textStyle: TextStyle = MaterialTheme.typography.labelLarge,
) {
    val shape = RoundedCornerShape(100.dp)
    Surface(
        onClick = onClick,
        shape = ClickableSurfaceDefaults.shape(shape = shape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (selected) SiloBlue else Color.Transparent,
            contentColor = if (selected) DarkOnPrimary else Color.White.copy(alpha = 0.82f),
            focusedContainerColor = FocusedContainer,
            focusedContentColor = FocusedContent,
            pressedContainerColor = FocusedContainer,
            pressedContentColor = FocusedContent,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.02f),
        border = ClickableSurfaceDefaults.border(
            border = Border(
                border = BorderStroke(
                    width = if (selected) 0.dp else 1.dp,
                    color = if (selected) Color.Transparent else Color.White.copy(alpha = 0.12f),
                ),
                shape = shape,
            ),
            focusedBorder = Border(
                border = BorderStroke(0.dp, Color.Transparent),
                shape = shape,
            ),
        ),
        modifier = modifier,
    ) {
        Box(
            modifier = Modifier
                .background(Color.Transparent)
                .padding(contentPadding),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = text,
                style = textStyle,
            )
        }
    }
}
