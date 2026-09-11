package org.siloserver.silo.language

private val aliases = mapOf(
    "english" to "en", "spanish" to "es", "french" to "fr", "german" to "de", "italian" to "it",
    "portuguese" to "pt", "arabic" to "ar", "japanese" to "ja", "korean" to "ko", "chinese" to "zh",
    "eng" to "en", "spa" to "es", "fre" to "fr", "fra" to "fr", "ger" to "de", "deu" to "de",
    "ita" to "it", "por" to "pt", "ara" to "ar", "jpn" to "ja", "kor" to "ko", "chi" to "zh", "zho" to "zh",
    "dut" to "nl", "nld" to "nl", "dan" to "da",
)

/** Canonical BCP 47 value for API wires, including known legacy names. */
fun canonicalLanguageTag(value: String?): String? {
    val raw = value?.trim()?.replace('_', '-') ?: return null
    if (raw.isEmpty()) return null
    val mapped = aliases[raw.lowercase()] ?: raw
    val parts = mapped.split('-').toMutableList()
    if (parts.firstOrNull()?.length !in 2..3 || parts.any { it.isEmpty() || it.length > 8 || !it.all(Char::isLetterOrDigit) }) return null
    parts[0] = parts[0].lowercase()
    for (i in 1 until parts.size) {
        parts[i] = when {
            parts[i].length == 4 && parts[i].all(Char::isLetter) -> parts[i].replaceFirstChar { it.uppercase() }.lowercase().replaceFirstChar { it.uppercase() }
            parts[i].length == 2 && parts[i].all(Char::isLetter) -> parts[i].uppercase()
            else -> parts[i].lowercase()
        }
    }
    return parts.joinToString("-")
}

/** Primary language for matching and grouping; keeps full tags available to callers. */
fun primaryLanguage(value: String?): String? = canonicalLanguageTag(value)?.substringBefore('-')
