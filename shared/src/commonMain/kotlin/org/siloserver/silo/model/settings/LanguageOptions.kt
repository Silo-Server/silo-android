package org.siloserver.silo.model.settings

/**
 * The language choices the settings UI offers, and the wire values they map to.
 *
 * The server's settings contract declares `playback.audio_language` and the
 * profile's `subtitle_language` as BCP 47 language tags, and validates them as
 * such. Sending a display label ("English") is rejected outright — and was
 * never useful even when the server accepted it, because
 * `setPreferredAudioLanguage("English")` never matches a track tagged `eng`.
 *
 * One table rather than a list per screen: the phone and TV UIs, audio and
 * subtitle, previously carried four copies that had already drifted apart —
 * subtitles on TV stored codes while the phone stored labels, and audio stored
 * labels on both. A single source means adding a language cannot leave one
 * surface behind.
 */
object LanguageOptions {
    /** Wire value meaning "no preference"; the server stores this as null. */
    const val UNSET = ""

    /**
     * (wire tag, display label), in the order the pickers show them. The first
     * entry is the unset choice, whose label differs by context — "Default" for
     * audio, "Off" for subtitles — so callers supply it.
     */
    val TAGS: List<Pair<String, String>> = listOf(
        "en" to "English",
        "es" to "Spanish",
        "fr" to "French",
        "de" to "German",
        "ja" to "Japanese",
        "ko" to "Korean",
        "zh" to "Chinese",
        "pt" to "Portuguese",
        "it" to "Italian",
        "ru" to "Russian",
    )

    /** The full option list for a picker, led by [unsetLabel]. */
    fun options(unsetLabel: String): List<Pair<String, String>> =
        listOf(UNSET to unsetLabel) + TAGS

    /**
     * The label for a stored wire value.
     *
     * Anything unrecognized reads as unset rather than being echoed back: a
     * value stored by an older build is a display label the picker has no entry
     * for, and showing it would suggest a choice the server does not hold.
     */
    fun label(wire: String?, unsetLabel: String): String =
        TAGS.firstOrNull { it.first == wire }?.second ?: unsetLabel

    /**
     * The wire value for a label the user picked. Falls back to [UNSET], which
     * is the one value the server always accepts.
     */
    fun wireValue(label: String?): String =
        TAGS.firstOrNull { it.second == label }?.first ?: UNSET

    /**
     * Translates a value stored by a build that persisted display names.
     *
     * Those rows are already on devices in the field. They are not tags, so the
     * server rejects them and track matching never hit on them — but left alone
     * they would keep being read and re-sent. A value that is already a known
     * tag, or already unset, is returned unchanged; anything else that matches a
     * label becomes its tag, and an unrecognized value becomes [UNSET].
     */
    fun migrateLegacyValue(stored: String?): String = when {
        stored.isNullOrBlank() -> UNSET
        TAGS.any { it.first == stored } -> stored
        else -> wireValue(stored)
    }
}
