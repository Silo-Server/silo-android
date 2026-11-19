package org.siloserver.silo.model.catalog

/**
 * Whether the overview a surface shows was machine-translated (the server's
 * `machine_translated_fields` on a detail, season, episode or card). Use this
 * wherever the "Translated by AI" mark sits with the overview alone: the
 * server can report `["tagline"]` for a provider overview with an AI tagline.
 */
fun hasMachineTranslatedOverview(fields: List<String>?): Boolean =
    fields.orEmpty().contains(MACHINE_TRANSLATED_OVERVIEW)

const val MACHINE_TRANSLATED_OVERVIEW = "overview"

/**
 * The language a season's episode rows are still missing, if any. A season's
 * translation job covers its episodes, so one pending row is enough to start it.
 */
fun List<EpisodeListItem>.pendingEpisodeTranslationLanguage(): String? =
    firstNotNullOfOrNull { episode -> episode.pendingTranslationLanguage?.takeIf { it.isNotBlank() } }

/**
 * Whether a series page should show "Translating description…" for its shown
 * episode: the shown season's job is running and the episode still reports a
 * [pendingLanguage]. A job for another season, or an episode whose text already
 * arrived, shows no progress.
 */
fun isEpisodeDescriptionTranslating(
    pendingLanguage: String?,
    shownSeasonContentId: String?,
    translatingContentId: String?,
): Boolean = !pendingLanguage.isNullOrBlank() &&
    translatingContentId != null &&
    translatingContentId == shownSeasonContentId

/** Refresh description metadata without replacing playback or personal state. */
fun ItemDetail.withEpisodeDescription(row: EpisodeListItem): ItemDetail {
    if (row.contentId != contentId) return this
    // A detail read can finish after translation while the rail still holds
    // a pending snapshot. Keep that completed detail until the rail catches up.
    if (pendingTranslationLanguage.isNullOrBlank() && !row.pendingTranslationLanguage.isNullOrBlank()) return this
    if (row.overview.isNullOrBlank()) return copy(pendingTranslationLanguage = row.pendingTranslationLanguage)
    return copy(
        overview = row.overview,
        machineTranslatedFields = row.machineTranslatedFields,
        pendingTranslationLanguage = row.pendingTranslationLanguage,
    )
}

/** A translation poll owns description fields; the current row owns user state. */
fun EpisodeListItem.withTranslatedDescription(row: EpisodeListItem): EpisodeListItem {
    if (row.contentId != contentId) return this
    return copy(
        overview = row.overview,
        machineTranslatedFields = row.machineTranslatedFields,
        pendingTranslationLanguage = row.pendingTranslationLanguage,
    )
}
