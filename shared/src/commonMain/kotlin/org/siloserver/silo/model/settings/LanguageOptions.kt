package org.siloserver.silo.model.settings

import org.siloserver.silo.playback.canonicalSubtitleLanguage

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
    val tags: List<Pair<String, String>> = listOf(
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
        listOf(UNSET to unsetLabel) + tags

    /**
     * The label for a stored wire value.
     *
     * A tag outside the picker table ("nl", "pt-BR" synced from another
     * surface) is echoed back as itself: it is a real, active preference, and
     * labeling it as unset would tell the user a preference playback still
     * applies is off. Only values that aren't tags at all — legacy display
     * labels — read as unset, since the server does not hold them.
     */
    fun label(wire: String?, unsetLabel: String): String {
        if (wire.isNullOrBlank()) return unsetLabel
        tags.firstOrNull { it.first == wire }?.let { return it.second }
        return if (isPreservableTag(wire)) wire else unsetLabel
    }

    /**
     * The wire value for a label the user picked. Falls back to [UNSET], which
     * is the one value the server always accepts.
     */
    fun wireValue(label: String?): String =
        tags.firstOrNull { it.second == label }?.first ?: UNSET

    /**
     * Translates a value stored by a build that persisted display names.
     *
     * Those rows are already on devices in the field. They are not tags, so the
     * server rejects them and track matching never hit on them — but left alone
     * they would keep being read and re-sent. A value that is already a known
     * tag, or already unset, is returned unchanged; a known label becomes its
     * tag. Anything else that is tag-shaped ("pt-BR", "nl", an alias like
     * "eng") passes through untouched — the table lists only the languages the
     * pickers offer, and a valid tag synced from another surface must not be
     * erased just because it is outside that list. Only values that are neither
     * a plausible tag nor a known label (i.e. legacy display names we no longer
     * recognize) become [UNSET].
     */
    fun migrateLegacyValue(stored: String?): String = when {
        stored.isNullOrBlank() -> UNSET
        tags.any { it.first == stored } -> stored
        tags.any { it.second == stored } -> wireValue(stored)
        isPreservableTag(stored) -> stored
        else -> UNSET
    }

    /**
     * Loose BCP 47 shape check: 2-3 letter primary subtag, optional script /
     * region subtags. Deliberately permissive — the server is the validator;
     * this only has to separate tags from display names like "English".
     * The old pickers' unset labels are excluded by name: "Off" is 3 letters
     * and would otherwise pass as a tag.
     */
    private fun isPreservableTag(value: String): Boolean =
        !value.equals("Off", ignoreCase = true) &&
            !value.equals("Default", ignoreCase = true) &&
            Regex("^[a-zA-Z]{2,3}([-_][a-zA-Z0-9]{2,8})*$").matches(value) &&
            canonicalSubtitleLanguage(value) != null
}
