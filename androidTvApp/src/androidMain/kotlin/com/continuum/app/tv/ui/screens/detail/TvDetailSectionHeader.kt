package com.continuum.app.tv.ui.screens.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text

/**
 * Editorial section header used below the detail hero. Mirrors the tvOS
 * `TVSectionHeader` at Android TV canvas scale, with text lifted above raw
 * tvOS÷2 sizing so lower detail sections still read as premium 10-foot UI.
 *
 * Sizes are scoped locally (rather than via the shared `sectionEyebrow` /
 * `displaySmall` tokens) so the larger detail-page treatment doesn't shift the
 * other screens that share those tokens at their smaller scale.
 */
@Composable
internal fun TvDetailSectionHeader(
    eyebrow: String,
    title: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Text(
            text = eyebrow.uppercase(),
            style = MaterialTheme.typography.titleMedium.copy(
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp,
                lineHeight = 15.sp,
                letterSpacing = 1.5.sp,
            ),
            color = Color.White.copy(alpha = 0.55f),
        )
        Text(
            text = title,
            style = MaterialTheme.typography.displaySmall.copy(
                fontWeight = FontWeight.SemiBold,
                fontSize = 24.sp,
                lineHeight = 27.sp,
            ),
            color = Color.White,
        )
    }
}
