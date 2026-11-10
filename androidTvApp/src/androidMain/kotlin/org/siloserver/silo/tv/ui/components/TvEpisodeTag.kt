package org.siloserver.silo.tv.ui.components

import androidx.compose.runtime.Composable
import org.siloserver.silo.common.R as CommonR

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
internal fun tvEpisodeTag(season: Int?, episode: Int?): String? = tvEpisodeTagToken(season, episode)?.resolved()

/** [tvEpisodeTag] as a catalog string and its zero-padded numbers, resolved when drawn. */
internal fun tvEpisodeTagToken(season: Int?, episode: Int?): TvHeroFactToken.ResToken? {
    val s = season?.toString()?.padStart(2, '0')
    val e = episode?.toString()?.padStart(2, '0')
    return when {
        s != null && e != null -> TvHeroFactToken.ResToken(CommonR.string.catalog_episodeTag, listOf(s, e))
        s != null -> TvHeroFactToken.ResToken(CommonR.string.calendar_seasonShort, listOf(s))
        e != null -> TvHeroFactToken.ResToken(CommonR.string.calendar_episodeShort, listOf(e))
        else -> null
    }
}

/**
 * `S01E02 • Title` for a card's second line, with the title isolated by
 * [bidiIsolated]. A title on its own stays bare so its own script still sets
 * the paragraph direction.
 */
@Composable
internal fun tvEpisodeTitleLine(season: Int?, episode: Int?, title: String): String? {
    val tag = tvEpisodeTag(season, episode)
    val name = title.takeIf { it.isNotBlank() }
    return when {
        tag == null -> name
        name == null -> tag
        else -> "$tag \u2022 ${bidiIsolated(name)}"
    }
}

/**
 * Server text that contains a right-to-left script, wrapped in Unicode isolates
 * (FSI…PDI) so an Arabic or Hebrew title keeps its punctuation at its own end
 * when joined after a left-to-right prefix such as `S01E02 • `. Other text is
 * returned unchanged. Isolate only joined text: the paragraph direction skips
 * isolated runs, so a lone isolated title would fall back to left to right.
 */
internal fun bidiIsolated(text: String): String =
    if (text.any(::isRightToLeft)) "\u2068$text\u2069" else text

private fun isRightToLeft(char: Char): Boolean = when (Character.getDirectionality(char)) {
    Character.DIRECTIONALITY_RIGHT_TO_LEFT,
    Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC,
    -> true
    else -> false
}
