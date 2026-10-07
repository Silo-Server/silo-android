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
    val cleanRaw = rawLabel?.trim().takeUnless { it.isNullOrBlank() }
    val providerId = providerIdentifier(cleanRaw)
    val provider = providerDisplayName(cleanRaw)
    val labelWithoutProvider = cleanRaw?.replace(providerSuffix, "")?.trim()
    val inferredLanguage = languageCodeFor(language) ?: inferLanguageCode(labelWithoutProvider)
    val languageName = languageDisplayName(inferredLanguage)
    val format = subtitleFormatLabel(codecOrMime ?: labelWithoutProvider)
    val forced = isForced || labelWithoutProvider?.let { forcedToken.containsMatchIn(it) } == true
    val sdh = labelWithoutProvider?.let { sdhToken.containsMatchIn(it) } == true
    val aiGenerated = providerId == "translated" ||
        labelWithoutProvider?.contains(Regex("""(?i)\bAI\b""")) == true
    val descriptor = meaningfulDescriptor(
        rawLabel = labelWithoutProvider,
        languageName = languageName,
        forced = forced,
        sdh = sdh,
    ).takeUnless { aiGenerated }

    val parts = buildList {
        languageName?.let { add(it) }
        if (aiGenerated) add("AI Translation")
        if (forced) add("Forced")
        if (sdh) add("SDH")
        descriptor?.let { add(it) }
        format?.let { add(it) }
        if (!aiGenerated) provider?.let { add(it) }
    }.distinctBy { it.lowercase(Locale.US) }

    return parts.takeIf { it.isNotEmpty() }?.joinToString(" • ")
        ?: "Subtitle ${index + 1}"
}

/**
 * The facts [formatSubtitleTrackDisplayLabel] joins into one string, kept
 * apart so a picker can lay them out: the language as the row title, Forced
 * and SDH as chips, the format and provider as a detail line.
 */
data class SubtitleTrackLabelParts(
    val languageName: String?,
    val forced: Boolean,
    val sdh: Boolean,
    val aiGenerated: Boolean,
    val descriptor: String?,
    val format: String?,
    val provider: String?,
)

fun subtitleTrackLabelParts(
    rawLabel: String?,
    language: String?,
    codecOrMime: String?,
    isForced: Boolean,
): SubtitleTrackLabelParts {
    val cleanRaw = rawLabel?.trim().takeUnless { it.isNullOrBlank() }
    val providerId = providerIdentifier(cleanRaw)
    val labelWithoutProvider = cleanRaw?.replace(providerSuffix, "")?.trim()
    val inferredLanguage = languageCodeFor(language) ?: inferLanguageCode(labelWithoutProvider)
    val languageName = languageDisplayName(inferredLanguage)
    val forced = isForced || labelWithoutProvider?.let { forcedToken.containsMatchIn(it) } == true
    val sdh = labelWithoutProvider?.let { sdhToken.containsMatchIn(it) } == true
    val aiGenerated = providerId == "translated" ||
        labelWithoutProvider?.contains(Regex("""(?i)\bAI\b""")) == true
    return SubtitleTrackLabelParts(
        languageName = languageName,
        forced = forced,
        sdh = sdh,
        aiGenerated = aiGenerated,
        descriptor = meaningfulDescriptor(
            rawLabel = labelWithoutProvider,
            languageName = languageName,
            forced = forced,
            sdh = sdh,
        ).takeUnless { aiGenerated },
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
    languageName: String?,
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
    val languageLower = languageName?.lowercase(Locale.US)
    val semanticLanguageLower = languageDisplayName(semanticLower)
        ?.lowercase(Locale.US)
    val redundant = lower == languageLower ||
        semanticLower == languageLower ||
        semanticLanguageLower == languageLower ||
        semanticLower == "subtitle" ||
        semanticLower == "subtitles" ||
        (forced && semanticLower == "forced") ||
        (sdh && (semanticLower == "sdh" || semanticLower == "hearing impaired")) ||
        (languageLower != null && forced && semanticLower == "$languageLower forced") ||
        (languageLower != null && sdh && semanticLower == "$languageLower sdh")

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
 * Public accessor for track pickers: ISO 639-1 or 639-2/T/B code → English
 * display name ("ger"/"deu"/"de" → "German"), null when unresolvable so the
 * caller can fall back to its own treatment of the raw code.
 */
fun trackLanguageDisplayName(language: String?): String? = languageDisplayName(language)

private fun languageDisplayName(language: String?): String? {
    val code = languageCodeFor(language) ?: return null
    val locale = Locale.forLanguageTag(code)
    val name = locale.getDisplayLanguage(Locale.US)
        .takeUnless { it.isBlank() || it.equals(code, ignoreCase = true) }
        ?: return null
    return name.replaceFirstChar { it.titlecase(Locale.US) }
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
