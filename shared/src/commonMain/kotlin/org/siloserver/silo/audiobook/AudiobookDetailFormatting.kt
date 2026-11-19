package org.siloserver.silo.audiobook

/**
 * Pure title and credit rules for the audiobook detail and player screens,
 * ported from Apple's `AudiobookDetailFormatting` so every client cleans the
 * same catalog strings the same way.
 *
 * Every rule is conservative: it rewrites only patterns it recognises and
 * otherwise returns the input, so an unfamiliar title never comes out worse.
 */
object AudiobookDetailFormatting {

    /** A trailing "(N of M)" locator lifted out of a raw title. */
    data class Volume(val index: Int?, val total: Int?)

    /**
     * A credit list cut down for one line: the first [shown] names, and how
     * many more were dropped. [shown] holds every name when [remaining] is 0.
     */
    data class PeopleSummary(val shown: List<String>, val remaining: Int)

    /**
     * Drops the series name and volume that servers often bake into a title
     * ("Stormlight Archive 5 - Wind and Truth (5 of 5)" → "Wind and Truth").
     */
    fun cleanTitle(rawTitle: String, seriesName: String?): String {
        val original = rawTitle.trim()
        var title = original
        if (!seriesName.isNullOrBlank()) {
            title = strippingSeriesPrefix(title, seriesName)
        }
        title = strippingTrailingVolume(title)
        title = title.trim { it.isWhitespace() || it in TrimEdges }
        return title.ifEmpty { original }
    }

    /**
     * Parses a trailing "(N of M)". The structured series group can include
     * alternate editions and narrations, so this is the reliable source of
     * the series total.
     */
    fun volume(rawTitle: String): Volume {
        val match = VolumeOf.find(rawTitle) ?: return Volume(null, null)
        return Volume(match.groupValues[1].toIntOrNull(), match.groupValues[2].toIntOrNull())
    }

    /**
     * This book's place in its series: the series entry for [contentId] when
     * it has a whole-number index, else the title's "(N of M)" locator.
     */
    fun seriesIndex(contentId: String, rawTitle: String, entries: List<Pair<String, Double?>>): Int? {
        val entryIndex = entries.firstOrNull { it.first == contentId }?.second
        if (entryIndex != null && entryIndex > 0 && entryIndex % 1.0 == 0.0) return entryIndex.toInt()
        return volume(rawTitle).index
    }

    /**
     * Splits comma-joined names (servers sometimes send a whole cast as one
     * string) and keeps [visible] of them. A list one longer than [visible]
     * is kept whole, so a line never ends in "& 1 more".
     */
    fun peopleSummary(names: List<String>, visible: Int = 2): PeopleSummary? {
        val cleaned = names
            .flatMap { it.split(",") }
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        if (cleaned.isEmpty()) return null
        if (cleaned.size <= visible + 1) return PeopleSummary(cleaned, 0)
        return PeopleSummary(cleaned.take(visible), cleaned.size - visible)
    }

    /** "A", "A & B", "A, B & C". */
    fun naturalJoin(names: List<String>): String = when (names.size) {
        0 -> ""
        1 -> names[0]
        2 -> "${names[0]} & ${names[1]}" // i18n-ignore
        else -> names.dropLast(1).joinToString(", ") + " & " + names.last() // i18n-ignore
    }

    private const val TrimEdges = " -–—:·,"

    private val VolumeOf = Regex("""\(\s*(\d+)\s+of\s+(\d+)\s*\)\s*$""", RegexOption.IGNORE_CASE)

    private val TrailingVolumes = listOf(
        Regex("""\s*\(\s*\d+\s+of\s+\d+\s*\)$""", RegexOption.IGNORE_CASE),
        Regex("""\s*\(\s*book\s+\d+\s*\)$""", RegexOption.IGNORE_CASE),
        Regex("""\s*\(\s*vol(?:ume)?\.?\s*\d+\s*\)$""", RegexOption.IGNORE_CASE),
    )

    /** Drops a leading "<Series>[ N][ -:–] " when the title opens with the series name. */
    private fun strippingSeriesPrefix(title: String, seriesName: String): String {
        val core = droppingLeadingArticle(seriesName).trim()
        if (core.isEmpty()) return title
        val hay = droppingLeadingArticle(title)
        if (!hay.lowercase().startsWith(core.lowercase())) return title
        // Only a volume number or a separator marks the series name as a
        // locator: "Dune Messiah" in the "Dune" series is the book's own title,
        // and "Dunes of Arrakis" doesn't open with "Dune" at a word boundary.
        val locator = SeriesLocator.find(hay.substring(core.length)) ?: return title
        val candidate = locator.groupValues[1].trim()
        // Consuming everything means the "prefix" was the title itself.
        return candidate.ifEmpty { title }
    }

    /** After the series name: a volume ("5", "#5", "2.5") and/or a separator, then the title. */
    private val SeriesLocator = Regex("""^\s*(?:#?\d+(?:\.\d+)?\s*[-–—:]?|[-–—:])\s*(.*)$""")

    private fun strippingTrailingVolume(title: String): String {
        var result = title
        for (pattern in TrailingVolumes) {
            result = pattern.replaceFirst(result, "")
        }
        return result
    }

    private fun droppingLeadingArticle(s: String): String {
        val lower = s.lowercase()
        for (article in listOf("the ", "a ", "an ")) {
            if (lower.startsWith(article)) return s.substring(article.length)
        }
        return s
    }
}
