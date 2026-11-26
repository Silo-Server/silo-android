package org.siloserver.silo.tv.ui.components

import androidx.compose.runtime.Composable
import org.siloserver.silo.common.R as CommonR
import org.siloserver.silo.common.i18n.LocaleFormat
import org.siloserver.silo.common.i18n.isRightToLeftText
import org.siloserver.silo.model.catalog.EpisodeLabel
import org.siloserver.silo.common.i18n.text

/**
 * `S01E02` for a Home card or the hero marquee, or a half tag when only one
 * number is known. Zero-padded so a rail of episodes keeps its numbers in one
 * column instead of going ragged at episode 10.
 *
 * Deliberately narrower than the spellings the player, calendar and detail
 * screens use (`S1 · E2`, `S1:E2`): those sit inside prose or on a badge with
 * room to breathe, while these labels are scanned down a list.
 */
@Composable
internal fun tvEpisodeTag(
    season: Int?,
    episode: Int?,
    episodeLabels: String? = null,
    airDate: String? = null,
): String? = tvEpisodeTagToken(season, episode, episodeLabels, airDate)?.tagText()

/**
 * [tvEpisodeTag] as a token resolved when drawn: the zero-padded code (ASCII,
 * D78), or for a date-labeled series ([episodeLabels] `air_date`, sports) the
 * episode's air date in place of both numbers.
 */
internal fun tvEpisodeTagToken(
    season: Int?,
    episode: Int?,
    episodeLabels: String? = null,
    airDate: String? = null,
): TvHeroFactToken? {
    val s = season?.let { LocaleFormat.codeNumber(it, minDigits = 2) }
    val e = episode?.let { LocaleFormat.codeNumber(it, minDigits = 2) }
    return when {
        season != null && episode != null ->
            TvHeroFactToken.UiTextToken(EpisodeLabel.text(season, episode, episodeLabels, airDate))
        s != null -> TvHeroFactToken.ResToken(CommonR.string.calendar_seasonShort, listOf(s))
        e != null -> TvHeroFactToken.ResToken(CommonR.string.calendar_episodeShort, listOf(e))
        else -> null
    }
}

/** An episode tag token's text in the app language. */
@Composable
internal fun TvHeroFactToken.tagText(): String? = when (this) {
    is TvHeroFactToken.ResToken -> resolved()
    is TvHeroFactToken.UiTextToken -> text.text()
    else -> null
}

/**
 * `S01E02 • Title` for a card's second line, with the title isolated by
 * [bidiIsolated]. A title on its own stays bare so its own script still sets
 * the paragraph direction.
 */
@Composable
internal fun tvEpisodeTitleLine(
    season: Int?,
    episode: Int?,
    title: String,
    episodeLabels: String? = null,
    airDate: String? = null,
): String? {
    val tag = tvEpisodeTag(season, episode, episodeLabels, airDate)
    val name = title.takeIf { it.isNotBlank() }
    return when {
        tag == null -> name
        name == null -> tag
        else -> "$tag \u2022 ${bidiIsolated(name, isRightToLeftText())}"
    }
}

/**
 * Server text wrapped in Unicode isolates (FSI…PDI) when it contains a
 * right-to-left script, so an Arabic or Hebrew title keeps its punctuation at
 * its own end when joined after a left-to-right prefix such as `S01E02 • `.
 * In a right-to-left interface ([rightToLeftInterface], the language rather
 * than the TV's left-to-right layout) every title is isolated, as catalog
 * placeholders are (D28). Other text is returned unchanged. Isolate only
 * joined text: the paragraph direction skips isolated runs, so a lone
 * isolated title would fall back to left to right.
 */
internal fun bidiIsolated(text: String, rightToLeftInterface: Boolean): String =
    if (rightToLeftInterface || text.hasRightToLeftScript()) "\u2068$text\u2069" else text

/** Whether this has an Arabic or Hebrew letter, which can change how the text around it reads. */
internal fun String.hasRightToLeftScript(): Boolean = any(::isRightToLeft)

private fun isRightToLeft(char: Char): Boolean = when (Character.getDirectionality(char)) {
    Character.DIRECTIONALITY_RIGHT_TO_LEFT,
    Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC,
    -> true
    else -> false
}
