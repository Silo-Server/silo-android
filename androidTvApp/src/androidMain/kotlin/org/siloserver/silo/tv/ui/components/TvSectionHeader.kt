package org.siloserver.silo.tv.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import org.siloserver.silo.common.R as CommonR
import org.siloserver.silo.tv.ui.theme.sectionEyebrow
import org.siloserver.silo.tv.ui.theme.siloFocus

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun TvSectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    iconSize: androidx.compose.ui.unit.Dp = 18.dp,
    onSeeAllClick: (() -> Unit)? = null,
    /** Optional uppercase eyebrow rendered above the title (e.g. "SILO PICKS"). */
    eyebrow: String? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Bottom,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (!eyebrow.isNullOrBlank()) {
                Text(
                    text = eyebrow.uppercase(),
                    style = sectionEyebrow,
                    color = Color.White.copy(alpha = 0.7f),
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (icon != null) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier
                            .size(iconSize)
                            .padding(end = 8.dp),
                    )
                }
                Text(
                    text = title,
                    style = MaterialTheme.typography.headlineSmall.copy(
                        fontSize = 18.sp,
                        lineHeight = 22.sp,
                    ),
                    color = Color.White,
                )
            }
        }
        if (onSeeAllClick != null) {
            val interactionSource = remember { MutableInteractionSource() }
            Row(
                modifier = Modifier
                    .siloFocus(interactionSource)
                    .clickable(
                        interactionSource = interactionSource,
                        indication = null,
                        onClick = onSeeAllClick,
                    )
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(CommonR.string.shell_section_seeAll),
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White.copy(alpha = 0.75f),
                )
                Spacer(modifier = Modifier.width(4.dp))
                Icon(
                    imageVector = androidx.compose.material.icons.Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.75f),
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}
