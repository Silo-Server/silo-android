package org.siloserver.silo.playback

/**
 * Canonical primary subtitle language shared by catalog persistence and
 * mounted-player identity matching.
 *
 * Servers commonly expose ISO 639-2 aliases while Android decoders expose
 * ISO 639-1 tags. Region/script suffixes do not identify a different subtitle
 * artifact for the selection fallback, so matching uses the primary language.
 */
/**
 * A resolved subtitle preference, with "" collapsed back to null.
 *
 * The two representations mean the same thing in the settings store — the
 * contract spells "no preference" as JSON null, the store spells it as the
 * empty string — but they mean opposite things to subtitle auto-selection: a
 * blank-but-present language is read as an explicit "off", while null means
 * "nothing chosen, decide normally". Any preference crossing from settings
 * into playback goes through here so the store's spelling cannot be mistaken
 * for a user's choice.
 */
fun String?.orNullIfBlank(): String? = this?.takeIf { it.isNotBlank() }

fun canonicalSubtitleLanguage(language: String?): String? =
    org.siloserver.silo.language.primaryLanguage(language)?.takeUnless { it == "und" || it == "x" }
