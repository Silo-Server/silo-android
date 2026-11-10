package org.siloserver.silo.android.ui.components

import org.siloserver.silo.common.R as CommonR
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Small checkmark overlay badge indicating an item has been watched.
 *
 * Mirrors iOS MediaCard.swift watched indicator (Plezy style): a white 20pt
 * circle with a 10pt bold dark check and a soft drop shadow.
 *
 * Intended to be placed in the top-end corner of a media poster via a Box
 * with Alignment.TopEnd.
 *
 * @param modifier Compose modifier for positioning.
 */
@Composable
fun WatchedBadge(
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .padding(6.dp)
            .size(20.dp)
            .shadow(4.dp, CircleShape, ambientColor = Color.Black.copy(alpha = 0.3f), spotColor = Color.Black.copy(alpha = 0.3f))
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.onSurface),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Default.Check,
            contentDescription = stringResource(CommonR.string.shell_card_watched),
            tint = MaterialTheme.colorScheme.background,
            modifier = Modifier.size(10.dp),
        )
    }
}
