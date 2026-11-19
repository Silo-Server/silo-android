package org.siloserver.silo.player

import java.util.Locale

private val fileLikeSubtitleExtensions = Regex(
    pattern = """(?i)\.(srt|ass|ssa|vtt|webvtt|sub|idx|sup|pgs|ttml)\b""",
)
private val releaseNoiseTokens = Regex(
    pattern = """(?i)\b(2160p|1080p|720p|480p|uhd|hdr|dv|bluray|blu-ray|brrip|web[-_. ]?dl|webrip|hdtv|remux|x26[45]|h\.?26[45]|hevc|avc|aac|ac3|eac3|ddp|dts|atmos|proper|repack|amzn|nf|hmax|itunes|yts|rarbg)\b""",
)
private val providerSuffix = Regex("""\(([^)]+)\)\s*$""")
private val forcedToken = Regex("""(?i)\bforced\b""")
private val sdhToken = Regex("""(?i)\b(sdh|hearing[ -]?impaired|closed captions?)\b""")

private val iso3ToIso2 = mapOf(
    "eng" to "en",
    "spa" to "es",
    "fre" to "fr",
    "fra" to "fr",
    "ger" to "de",
    "deu" to "de",
    "ita" to "it",
    "por" to "pt",
    "dut" to "nl",
    "nld" to "nl",
    "jpn" to "ja",
    "kor" to "ko",
    "chi" to "zh",
    "zho" to "zh",
    "dan" to "da",
    "fin" to "fi",
    "nor" to "no",
    "swe" to "sv",
)

private val commonSubtitleLanguageCodes = (
    Locale.getISOLanguages().toSet() + iso3ToIso2.keys
).map { it.lowercase(Locale.US) }.toSet()

fun formatSubtitleTrackDisplayLabel(
    rawLabel: String?,
    language: String?,
    codecOrMime: String?,
    isForced: Boolean,
    index: Int,
): String {
    // English throughout: players match tracks by this label (D44).
    val label = subtitleTrackLabelParts(rawLabel, language, codecOrMime, isForced, Locale.US)
    val parts = buildList {
        label.languageName?.let { add(it) }
        label.aiKind?.let { add(it.displayName) }
        if (label.forced) add("Forced")
        if (label.sdh) add("SDH")
        label.descriptor?.let { add(it) }
        label.format?.let { add(it) }
        label.provider?.let { add(it) }
    }.distinctBy { it.lowercase(Locale.US) }

    return parts.takeIf { it.isNotEmpty() }?.joinToString(" • ")
        ?: "Subtitle ${index + 1}"
}

/** How the server made an AI subtitle track. */
enum class AiSubtitleKind(val displayName: String) {
    TRANSLATION("AI Translation"),
    TRANSCRIPTION("AI Transcription"),
}

private val aiTranscribedLabel = Regex("""(?i)\bAI transcribed\b""")
private val aiTranslatedMarker = Regex("""(?i)\(\s*AI\s*\)""")
private val trailingAiMarkers = Regex("""(?i)(\s*\(\s*(?:AI|AI transcribed|translated|transcribed)\s*\))+\s*$""")

/**
 * The AI kind a server label names: stored AI tracks carry a "(translated)" or
 * "(transcribed)" provider suffix, and their release names read
 * "English → German (AI)" or "English (AI transcribed)". Null for any other track.
 */
fun aiSubtitleKind(rawLabel: String?): AiSubtitleKind? {
    val cleanRaw = rawLabel?.trim().takeUnless { it.isNullOrBlank() } ?: return null
    // The whole label: a bare "(AI transcribed)" also parses as a provider suffix.
    val providerId = providerIdentifier(cleanRaw)
    return when {
        providerId == "transcribed" || aiTranscribedLabel.containsMatchIn(cleanRaw) ->
            AiSubtitleKind.TRANSCRIPTION
        providerId == "translated" || aiTranslatedMarker.containsMatchIn(cleanRaw) ->
            AiSubtitleKind.TRANSLATION
        else -> null
    }
}

/**
 * An AI track's server label without its "(AI)" / "(translated)" markers
 * ("English → German", "English"), for a track with no language field to name it.
 */
fun aiSubtitleSourceLabel(rawLabel: String?): String? =
    rawLabel?.replace(trailingAiMarkers, "")?.trim()?.takeUnless { it.isBlank() }

/**
 * The facts [formatSubtitleTrackDisplayLabel] joins into one string, kept
 * apart so a picker can lay them out: the language as the row title, Forced
 * and SDH as chips, the format and provider as a detail line. An AI track is
 * labelled by its [aiKind]: its provider is null, and its descriptor is null
 * unless the track has no language to name it. [languageName] is in the
 * locale the caller asked for.
 */
data class SubtitleTrackLabelParts(
    val languageName: String?,
    val forced: Boolean,
    val sdh: Boolean,
    val aiKind: AiSubtitleKind?,
    val descriptor: String?,
    val format: String?,
    val provider: String?,
) {
    val aiGenerated: Boolean get() = aiKind != null
}

fun subtitleTrackLabelParts(
    rawLabel: String?,
    language: String?,
    codecOrMime: String?,
    isForced: Boolean,
    languageLocale: Locale,
): SubtitleTrackLabelParts {
    val cleanRaw = rawLabel?.trim().takeUnless { it.isNullOrBlank() }
    val labelWithoutProvider = cleanRaw?.replace(providerSuffix, "")?.trim()
    val inferredLanguage = languageCodeFor(language) ?: inferLanguageCode(labelWithoutProvider)
    val languageName = languageDisplayName(inferredLanguage, languageLocale)
    // Server labels name the language in English ("English SDH").
    val languageNames = listOfNotNull(languageName, languageDisplayName(inferredLanguage)).distinct()
    val forced = isForced || labelWithoutProvider?.let { forcedToken.containsMatchIn(it) } == true
    val sdh = labelWithoutProvider?.let { sdhToken.containsMatchIn(it) } == true
    val aiKind = aiSubtitleKind(cleanRaw)
    val aiGenerated = aiKind != null
    return SubtitleTrackLabelParts(
        languageName = languageName,
        forced = forced,
        sdh = sdh,
        aiKind = aiKind,
        descriptor = when {
            !aiGenerated -> meaningfulDescriptor(
                rawLabel = labelWithoutProvider,
                languageNames = languageNames,
                forced = forced,
                sdh = sdh,
            )
            // Without a language the server label is the only readable name.
            languageName == null -> aiSubtitleSourceLabel(cleanRaw)
            else -> null
        },
        format = subtitleFormatLabel(codecOrMime ?: labelWithoutProvider),
        provider = providerDisplayName(cleanRaw).takeUnless { aiGenerated },
    )
}

private fun providerIdentifier(rawLabel: String?): String? =
    rawLabel?.let { providerSuffix.find(it)?.groupValues?.getOrNull(1) }
        ?.trim()
        ?.lowercase(Locale.US)
        ?.replace("_", "")
        ?.replace("-", "")
        ?.takeUnless { it.isBlank() }

private fun providerDisplayName(rawLabel: String?): String? {
    val id = rawLabel?.let { providerSuffix.find(it)?.groupValues?.getOrNull(1) }
        ?.trim()
        ?.takeUnless { it.isBlank() }
        ?: return null
    return when (providerIdentifier(rawLabel)) {
        "opensubtitles" -> "OpenSubtitles"
        "subdl" -> "SubDL"
        else -> id.split(Regex("""[\s._-]+"""))
            .filter { it.isNotBlank() }
            .joinToString(" ") { token ->
                token.lowercase(Locale.US).replaceFirstChar { it.titlecase(Locale.US) }
            }
            .takeIf { it.isNotBlank() }
    }
}

private fun meaningfulDescriptor(
    rawLabel: String?,
    languageNames: List<String>,
    forced: Boolean,
    sdh: Boolean,
): String? {
    val normalized = rawLabel
        ?.removeSubtitleExtension()
        ?.replace(Regex("""[._]+"""), " ")
        ?.replace(Regex("""\s+"""), " ")
        ?.trim()
        ?.takeUnless { it.isBlank() }
        ?: return null

    if (isFileLikeSubtitleLabel(rawLabel)) return null
    if (subtitleFormatLabel(normalized) != null) return null

    val lower = normalized.lowercase(Locale.US)
    val semanticLower = lower
        .replace(Regex("""[^a-z0-9]+"""), " ")
        .replace(Regex("""\s+"""), " ")
        .trim()
    val namesLower = languageNames.map { it.lowercase(Locale.US) }
    val semanticLanguageLower = languageDisplayName(semanticLower)
        ?.lowercase(Locale.US)
    val redundant = lower in namesLower ||
        semanticLower in namesLower ||
        // With no language either side, the label counts as redundant too (English unchanged).
        (if (semanticLanguageLower == null) namesLower.isEmpty() else semanticLanguageLower in namesLower) ||
        semanticLower == "subtitle" ||
        semanticLower == "subtitles" ||
        (forced && semanticLower == "forced") ||
        (sdh && (semanticLower == "sdh" || semanticLower == "hearing impaired")) ||
        (forced && namesLower.any { semanticLower == "$it forced" }) ||
        (sdh && namesLower.any { semanticLower == "$it sdh" })

    return normalized.takeUnless { redundant }
}

private fun isFileLikeSubtitleLabel(label: String): Boolean {
    if (label.contains('/') || label.contains('\\')) return true
    if (fileLikeSubtitleExtensions.containsMatchIn(label)) return true
    if (releaseNoiseTokens.containsMatchIn(label)) return true

    val separators = label.count { it == '.' || it == '_' }
    val hasSpaces = label.any { it.isWhitespace() }
    return separators >= 3 && !hasSpaces
}

private fun inferLanguageCode(rawLabel: String?): String? {
    if (rawLabel.isNullOrBlank()) return null
    val tokens = rawLabel
        .removeSubtitleExtension()
        .split(Regex("""[.\s_\-\[\]()]+"""))
        .map { it.lowercase(Locale.US) }

    return tokens.firstNotNullOfOrNull { token ->
        languageCodeFor(token).takeIf { token in commonSubtitleLanguageCodes }
    }
}

private fun languageCodeFor(language: String?): String? {
    val clean = language
        ?.trim()
        ?.replace('_', '-')
        ?.takeUnless { it.isBlank() || it.equals("und", ignoreCase = true) }
        ?: return null
    val lower = clean.lowercase(Locale.US)
    return iso3ToIso2[lower] ?: lower
}

/**
 * A track's language for a picker row: ISO 639-1 or 639-2/T/B code → its
 * name in [inLocale], the interface language, capitalized as a title
 * ("ger"/"deu"/"de" → "German", "Alemán"). Null when unresolvable, so the
 * caller can fall back to its own treatment of the raw code.
 */
fun trackLanguageDisplayName(language: String?, inLocale: Locale): String? =
    languageDisplayName(language, inLocale)

/**
 * The bare language tag behind a track's [language] ("ger" → "de", "en-US" →
 * "en"), for text that names the language when it is drawn
 * (`UiText.LanguageName`). Null when [trackLanguageDisplayName] would be.
 */
fun trackLanguageTag(language: String?): String? {
    val code = languageCodeFor(language) ?: return null
    if (languageDisplayName(code) == null) return null
    return Locale.forLanguageTag(code).language
}

/**
 * The English name ("German"), for matching the English that servers and
 * muxers write into track titles. Never shown (D44).
 */
fun trackLanguageEnglishName(language: String?): String? = languageDisplayName(language)

private fun languageDisplayName(language: String?, inLocale: Locale = Locale.US): String? {
    val code = languageCodeFor(language) ?: return null
    val locale = Locale.forLanguageTag(code)
    val name = locale.getDisplayLanguage(inLocale)
        .takeUnless { it.isBlank() || it.equals(code, ignoreCase = true) }
        ?: return null
    return name.replaceFirstChar { it.titlecase(inLocale) }
}

private fun subtitleFormatLabel(codecOrMime: String?): String? {
    val clean = codecOrMime
        ?.trim()
        ?.removeSubtitleExtension()
        ?.lowercase(Locale.US)
        ?.replace('_', '-')
        ?: return null
    return when {
        clean == "srt" || clean.contains("subrip") -> "SRT"
        clean == "ass" || clean == "ssa" || clean.contains("x-ssa") -> "ASS"
        clean == "vtt" || clean == "webvtt" || clean.contains("vtt") -> "VTT"
        clean == "ttml" || clean.contains("ttml") -> "TTML"
        clean == "pgs" || clean.contains("pgs") || clean.contains("hdmv") -> "PGS"
        clean == "sub" || clean.contains("dvd") || clean.contains("dvbsubs") -> "DVD"
        else -> null
    }
}

private fun String.removeSubtitleExtension(): String =
    replace(fileLikeSubtitleExtensions, "")
