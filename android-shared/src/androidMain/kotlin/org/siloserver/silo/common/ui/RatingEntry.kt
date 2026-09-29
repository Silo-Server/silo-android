package org.siloserver.silo.common.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.unit.sp
import org.siloserver.silo.common.R
import org.siloserver.silo.model.catalog.DisplayRating
import org.siloserver.silo.model.catalog.ExternalRatings

/**
 * One external rating as its source's mark and its score: `IMDb 8.5`,
 * `RT 93%`. The mark is the source's name as plain text, never a star or the
 * source's artwork, except TMDB, whose approved logo its terms allow. The
 * score is [DisplayRating.display] as the server formatted it. Screen readers
 * hear "IMDb 8.5".
 *
 * [style] is the score's text style and should carry a color and font size;
 * the mark is drawn from it semibold and dimmed, at [markFontSize] or a
 * little smaller than the score, and the TMDB logo is sized to about the
 * score's cap height.
 *
 * Callers lay entries out themselves; [WholeTokenRow] keeps them on one line.
 * Do not add a FlowRow-based row here: FlowRow is experimental, and this
 * module compiles against an older Compose foundation than the apps ship, so
 * the call fails at runtime with NoSuchMethodError.
 */
@Composable
fun RatingEntry(
    rating: DisplayRating,
    style: TextStyle,
    modifier: Modifier = Modifier,
    markFontSize: TextUnit = TextUnit.Unspecified,
) {
    val fontSize = style.fontSize.takeIf { it.isSpecified } ?: DefaultScoreSize
    val scoreStyle = style.merge(
        TextStyle(
            fontSize = fontSize,
            fontWeight = FontWeight.Bold,
            fontFeatureSettings = "tnum",
        ),
    )
    Row(
        modifier = modifier.clearAndSetSemantics {
            contentDescription = "${rating.name} ${rating.display}"
        },
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (rating.source == ExternalRatings.SOURCE_TMDB) {
            val logoHeight = with(LocalDensity.current) { (fontSize * TmdbLogoCapHeight).toDp() }
            Image(
                painter = painterResource(R.drawable.tmdb_logo),
                contentDescription = rating.name,
                contentScale = ContentScale.Fit,
                modifier = Modifier.size(width = logoHeight * TmdbLogoAspectRatio, height = logoHeight),
            )
        } else {
            BasicText(
                text = rating.name,
                style = style.merge(
                    TextStyle(
                        fontSize = markFontSize.takeIf { it.isSpecified } ?: (fontSize * MarkScale),
                        fontWeight = FontWeight.SemiBold,
                    ),
                ),
                maxLines = 1,
                softWrap = false,
                modifier = Modifier.alpha(MarkAlpha),
            )
        }
        BasicText(
            text = rating.display,
            style = scoreStyle,
            maxLines = 1,
            softWrap = false,
        )
    }
}

private val DefaultScoreSize = 14.sp
private const val MarkScale = 0.87f
private const val MarkAlpha = 0.75f

/** About a cap height of the score text, like the web client's logo. */
private const val TmdbLogoCapHeight = 0.66f

/** The logo's own proportions (its viewBox), kept unmodified. */
private const val TmdbLogoAspectRatio = 273.42f / 35.52f
