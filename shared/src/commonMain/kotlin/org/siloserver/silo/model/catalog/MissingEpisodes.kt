package org.siloserver.silo.model.catalog

/**
 * How much of what the library lacks a season or episode list includes.
 *
 * Only the series page asks for more than [NONE]: playback, downloads and
 * subscriptions read the library alone, so they never offer an episode no
 * file backs.
 */
enum class MissingEpisodes {
    /** Only what a library holds. */
    NONE,

    /** Also the aired regular episodes no library holds. */
    AIRED,

    /**
     * Also the regular episodes still to air, for a viewer whose download
     * server can say whether they are already coming.
     */
    ALL;

    /** The catalog list query that asks for this scope. */
    val queryParameters: Map<String, String>
        get() = when (this) {
            NONE -> emptyMap()
            AIRED -> mapOf("include_missing" to "true")
            ALL -> mapOf("include_missing" to "true", "include_upcoming" to "true")
        }
}
