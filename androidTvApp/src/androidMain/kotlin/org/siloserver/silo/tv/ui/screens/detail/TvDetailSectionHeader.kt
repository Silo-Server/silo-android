package org.siloserver.silo.tv.ui.screens.detail

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
 * `TVSectionHeader` at tvOS÷2 sizing (eyebrow 20pt bold tracking 3.0 → 10sp /
 * 1.5sp; title 42pt semibold → 21sp).
 *
 * Sizes are scoped locally (rather than via the shared `sectionEyebrow` /
 * `displaySmall` tokens) so the detail-page treatment doesn't shift the
 * other screens that share those tokens.
 */
@Composable
internal fun TvDetailSectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    /**
     * Optional tracked all-caps kicker ABOVE the title — only when it carries
     * context the title doesn't (tvOS TVSectionHeader: "Season 2" over
     * "Episodes"). Plain sections ("Cast & Crew", "Details") are single-line
     * (QA 2026-07-08 / Apple parity).
     */
    eyebrow: String? = null,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        if (!eyebrow.isNullOrBlank()) {
            Text(
                text = eyebrow.uppercase(),
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.Bold,
                    fontSize = 10.sp,
                    lineHeight = 12.sp,
                    letterSpacing = 1.5.sp,
                ),
                color = Color.White.copy(alpha = 0.55f),
            )
        }
        Text(
            text = title,
            style = MaterialTheme.typography.displaySmall.copy(
                fontWeight = FontWeight.SemiBold,
                fontSize = 21.sp,
                lineHeight = 24.sp,
            ),
            color = Color.White,
        )
    }
}
