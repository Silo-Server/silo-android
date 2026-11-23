package org.siloserver.silo.model.settings

import org.siloserver.silo.common.i18n.SharedString
import org.siloserver.silo.common.i18n.UiText
import org.siloserver.silo.playback.canonicalSubtitleLanguage

/** Alias-aware identity that retains explicit script and region subtags. */
internal expect fun canonicalLanguageIdentity(tag: String): String

/**
 * Presentation adapter for the generated settings language catalogs.
 *
 * `language_tag` stays open: the generated list is a stable floor, the server
 * may add deployment-observed tags, and an exact current value is always kept
 * selectable. Labels are the platform's language names in the interface
 * language ([UiText.LanguageName]) rather than a hand-written English table
 * that can drift between phone, TV, web, and Apple clients.
 */
object LanguageOptions {
    /** Wire value meaning "no preference"; the server stores this as null. */
    const val UNSET = ""

    /**
     * Contract revision whose language floor the pickers offer. Revision 8
     * added regional and script tags (`zh-Hans`, `en-US`, ...); this client
     * has not adopted them yet, so the picker keeps the revision-7 floor even
     * though the generated bindings now carry the newer entries. Exact current
     * and server-suggested values stay selectable regardless.
     */
    internal const val FLOOR_REVISION = 7

    fun namedOptions(
        key: String,
        currentValue: String? = null,
        runtimeValues: List<String> = emptyList(),
    ): List<Pair<String, UiText>> {
        val values = mutableListOf<String>()
        val indexByLanguage = mutableMapOf<String, Int>()

        fun add(rawValue: String, replaceAlias: Boolean) {
            val value = rawValue.trim()
            if (!isPreservableTag(value)) return
            val identity = canonicalLanguageIdentity(value)
            val existing = indexByLanguage[identity]
            if (existing != null) {
                if (replaceAlias) values[existing] = value
                return
            }
            indexByLanguage[identity] = values.size
            values += value
        }

        SettingPresentationMetadata.suggestedValues(key, FLOOR_REVISION).forEach { add(it, false) }
        runtimeValues.forEach { add(it, false) }
        currentValue?.let { add(it, true) }

        return values.map { it to UiText.LanguageName(it) }
    }

    /** Full picker list led by the contract's context-specific unset copy. */
    fun options(
        key: String,
        currentValue: String? = null,
        runtimeValues: List<String> = emptyList(),
    ): List<Pair<String, UiText>> =
        listOf(UNSET to unsetLabel(key)) + namedOptions(key, currentValue, runtimeValues)

    fun label(wire: String?, key: String): UiText {
        if (wire.isNullOrBlank()) return unsetLabel(key)
        return if (isPreservableTag(wire)) UiText.LanguageName(wire) else unsetLabel(key)
    }

    /**
     * Translates values written by older builds that persisted English labels.
     * This compatibility map is not a picker catalog; new choices come only
     * from the generated contract and server response.
     */
    fun migrateLegacyValue(stored: String?): String = when {
        stored.isNullOrBlank() -> UNSET
        isPreservableTag(stored) -> stored
        else -> legacyEnglishLabels[stored] ?: UNSET
    }

    /** The setting's own words for no language (the manifest's `unset_label`). */
    private fun unsetLabel(key: String): UiText = UiText.Key(
        when (key) {
            SettingKeys.PLAYBACK_AUDIO_LANGUAGE -> SharedString.AudioLanguageUnset
            SettingKeys.PLAYBACK_SUBTITLE_LANGUAGE -> SharedString.SubtitleLanguageUnset
            SettingKeys.CATALOG_METADATA_LANGUAGE -> SharedString.MetadataLanguageUnset
            SettingKeys.UI_APP_LANGUAGE -> SharedString.AppLanguageUnset
            else -> SharedString.LanguageUnset
        },
    )

    private fun isPreservableTag(value: String): Boolean =
        value.isNotBlank() &&
            !value.equals("Off", ignoreCase = true) &&
            !value.equals("Default", ignoreCase = true) &&
            Regex("^[a-zA-Z]{2,3}([-_][a-zA-Z0-9]{1,8})*$").matches(value) &&
            canonicalSubtitleLanguage(value) != null

    private val legacyEnglishLabels = mapOf(
        "English" to "en",
        "Spanish" to "es",
        "French" to "fr",
        "German" to "de",
        "Japanese" to "ja",
        "Korean" to "ko",
        "Chinese" to "zh",
        "Portuguese" to "pt",
        "Italian" to "it",
        "Russian" to "ru",
    )
}
