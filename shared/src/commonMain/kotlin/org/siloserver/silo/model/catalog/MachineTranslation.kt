package org.siloserver.silo.model.catalog

/**
 * Whether the overview a surface shows was machine-translated (the server's
 * `machine_translated_fields` on a detail, season, episode or card). Use this
 * wherever the "Translated by AI" mark sits with the overview alone: the
 * server can report `["tagline"]` for a provider overview with an AI tagline.
 */
fun hasMachineTranslatedOverview(fields: List<String>?): Boolean =
    fields.orEmpty().contains(MACHINE_TRANSLATED_OVERVIEW)

/**
 * For a view that shows the overview and the tagline together: either field
 * marks it, but the tagline counts only when [taglineShown].
 */
fun hasMachineTranslatedText(fields: List<String>?, taglineShown: Boolean): Boolean =
    hasMachineTranslatedOverview(fields) ||
        (taglineShown && fields.orEmpty().contains(MACHINE_TRANSLATED_TAGLINE))

const val MACHINE_TRANSLATED_OVERVIEW = "overview"
const val MACHINE_TRANSLATED_TAGLINE = "tagline"

/**
 * The language a season's episode rows are still missing, if any. A season's
 * translation job covers its episodes, so one pending row is enough to start it.
 */
fun List<EpisodeListItem>.pendingEpisodeTranslationLanguage(): String? =
    firstNotNullOfOrNull { episode -> episode.pendingTranslationLanguage?.takeIf { it.isNotBlank() } }
