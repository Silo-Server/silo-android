package org.siloserver.silo.tv.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import org.siloserver.silo.common.ui.RatingEntry
import org.siloserver.silo.common.ui.WholeTokenRow
import org.siloserver.silo.model.catalog.DisplayRating
import org.siloserver.silo.tv.ui.theme.SuccessGreen

/**
 * Tokens for a hero facts row, mirroring tvOS `TVHeroFactToken`.
 *
 * - [TextToken] plain text (year / runtime / genre). [TextToken.truncates]
 *   lets a long value (an episode title) end in "…" instead of dropping.
 * - [ExternalRating] one external rating as its source's mark and score.
 * - [Rating] a maturity/check token: green check icon + label.
 * - [Chip] a playback-format value (4K / HDR / DOLBY VISION / ATMOS / CC).
 *   Detail renders these with the same quiet monospaced treatment as Home's
 *   format line rather than promoting every value to an outlined badge.
 */
sealed class TvHeroFactToken {
    data class TextToken(val value: String, val truncates: Boolean = false) : TvHeroFactToken()
    data class ExternalRating(val rating: DisplayRating) : TvHeroFactToken()
    data class Rating(val value: String) : TvHeroFactToken()
    data class Chip(val value: String) : TvHeroFactToken()
}

/**
 * One line of [tokens] with a "·" between them, used by the detail hero, the
 * Home marquee and request detail. Past the line's width, whole tokens drop
 * from the end; with [ratingsDropFirst], external ratings go before anything
 * else. [leading] (a content-rating chip) sits before the first token with no
 * divider.
 *
 * [style] should carry the text's color and font size. Ratings keep their mark
 * at that size too: the ten-foot text floor rules out the smaller mark
 * [RatingEntry] draws by default.
 */
@Composable
internal fun TvFactsRow(
    tokens: List<TvHeroFactToken>,
    style: TextStyle,
    modifier: Modifier = Modifier,
    spacing: Dp = 7.dp,
    dividerColor: Color = style.color,
    ratingsDropFirst: Boolean = false,
    leading: (@Composable () -> Unit)? = null,
) {
    WholeTokenRow(spacing = spacing, modifier = modifier) {
        leading?.invoke()
        tokens.forEachIndexed { index, token ->
            val tokenModifier = Modifier
                .then(
                    if (ratingsDropFirst && index > 0 && token is TvHeroFactToken.ExternalRating) {
                        Modifier.dropFirst()
                    } else {
                        Modifier
                    },
                )
                .then(if (token is TvHeroFactToken.TextToken && token.truncates) Modifier.shrinkable() else Modifier)
            // Each token carries its leading "·", so the two drop together.
            Row(
                modifier = tokenModifier,
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(spacing),
            ) {
                if (index > 0) {
                    Text(
                        text = "·",
                        style = style.merge(TextStyle(fontWeight = FontWeight.SemiBold, color = dividerColor)),
                        maxLines = 1,
                    )
                }
                FactToken(token = token, style = style)
            }
        }
    }
}

@Composable
private fun FactToken(token: TvHeroFactToken, style: TextStyle) {
    when (token) {
        is TvHeroFactToken.TextToken -> Text(
            text = token.value,
            style = style,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        is TvHeroFactToken.ExternalRating -> RatingEntry(
            rating = token.rating,
            style = style,
            markFontSize = style.fontSize,
        )
        is TvHeroFactToken.Rating -> Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Icon(
                imageVector = Icons.Filled.CheckCircle,
                contentDescription = null,
                tint = SuccessGreen.copy(alpha = 0.9f),
                modifier = Modifier.height(12.dp),
            )
            Text(text = token.value, style = style, maxLines = 1)
        }
        is TvHeroFactToken.Chip -> Text(
            text = formatChipLabel(token.value),
            style = style.merge(
                TextStyle(
                    fontFamily = FontFamily.Monospace,
                    letterSpacing = 0.52.sp,
                    color = Color.White.copy(alpha = 0.55f),
                ),
            ),
            maxLines = 1,
        )
    }
}

private fun formatChipLabel(value: String): String = when (value.uppercase()) {
    "DOLBY VISION" -> "Dolby Vision"
    "ATMOS" -> "Atmos"
    else -> value
}
