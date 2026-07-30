package org.siloserver.silo.model.settings

import org.siloserver.silo.playback.canonicalSubtitleLanguage

/** Platform CLDR/ICU display name for a valid BCP 47 tag. */
internal expect fun localizedLanguageName(tag: String): String

/** Alias-aware identity that retains explicit script and region subtags. */
internal expect fun canonicalLanguageIdentity(tag: String): String

/**
 * Presentation adapter for the generated settings language catalogs.
 *
 * `language_tag` stays open: the generated list is a stable floor, the server
 * may add deployment-observed tags, and an exact current value is always kept
 * selectable. Labels come from the platform locale rather than a hand-written
 * English table that can drift between phone, TV, web, and Apple clients.
 */
object LanguageOptions {
    /** Wire value meaning "no preference"; the server stores this as null. */
    const val UNSET = ""

    fun namedOptions(
        key: String,
        currentValue: String? = null,
        runtimeValues: List<String> = emptyList(),
    ): List<Pair<String, String>> {
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

        SettingPresentationMetadata.suggestedValues(key).forEach { add(it, false) }
        runtimeValues.forEach { add(it, false) }
        currentValue?.let { add(it, true) }

        return values.map { it to localizedLanguageName(it) }
    }

    /** Full picker list led by the contract's context-specific unset copy. */
    fun options(
        key: String,
        currentValue: String? = null,
        runtimeValues: List<String> = emptyList(),
    ): List<Pair<String, String>> =
        listOf(UNSET to unsetLabel(key)) + namedOptions(key, currentValue, runtimeValues)

    fun label(wire: String?, key: String): String {
        if (wire.isNullOrBlank()) return unsetLabel(key)
        return if (isPreservableTag(wire)) localizedLanguageName(wire) else unsetLabel(key)
    }

    /** Resolve a selected display label against the exact options rendered. */
    fun wireValue(label: String?, options: List<Pair<String, String>>): String =
        options.firstOrNull { it.second == label }?.first ?: UNSET

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

    private fun unsetLabel(key: String): String =
        SettingPresentationMetadata.DEFINITIONS[key]?.unsetLabel ?: "Unset"

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
