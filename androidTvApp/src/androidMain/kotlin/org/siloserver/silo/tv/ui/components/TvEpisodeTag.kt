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
