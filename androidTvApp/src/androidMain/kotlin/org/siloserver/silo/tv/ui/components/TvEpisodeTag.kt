package org.siloserver.silo.tv.ui.components

/**
 * `S01E02` for a Home card or the hero marquee, or a half tag when only one
 * number is known. Zero-padded so a rail of episodes keeps its numbers in one
 * column instead of going ragged at episode 10.
 *
 * Deliberately narrower than the spellings the player, calendar and detail
 * screens use (`S1 · E2`, `S1:E2`): those sit inside prose or on a badge with
 * room to breathe, while these labels are scanned down a list.
 */
internal fun tvEpisodeTag(season: Int?, episode: Int?): String? {
    if (season == null && episode == null) return null
    val s = season?.let { "S${it.toString().padStart(2, '0')}" }.orEmpty()
    val e = episode?.let { "E${it.toString().padStart(2, '0')}" }.orEmpty()
    return "$s$e".ifBlank { null }
}

/** `S01E02 • Title` for a card's second line, with the title isolated by [bidiIsolated]. */
internal fun tvEpisodeTitleLine(season: Int?, episode: Int?, title: String): String? =
    listOfNotNull(
        tvEpisodeTag(season, episode),
        title.takeIf { it.isNotBlank() }?.let(::bidiIsolated),
    ).joinToString(" \u2022 ").ifBlank { null }

/**
 * Server text that contains a right-to-left script, wrapped in Unicode isolates
 * (FSI…PDI) so an Arabic or Hebrew title keeps its punctuation at its own end
 * when joined after a left-to-right prefix such as `S01E02 • `. Other text is
 * returned unchanged.
 */
internal fun bidiIsolated(text: String): String =
    if (text.any(::isRightToLeft)) "\u2068$text\u2069" else text

private fun isRightToLeft(char: Char): Boolean = when (Character.getDirectionality(char)) {
    Character.DIRECTIONALITY_RIGHT_TO_LEFT,
    Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC,
    -> true
    else -> false
}
