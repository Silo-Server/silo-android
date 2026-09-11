package org.siloserver.silo.android.ui.util

/**
 * ISO 639 language code → English display name, mirroring the web player's
 * `web/src/player/utils/languageNames.ts` so both clients label subtitle
 * languages identically. The 2-letter (639-1) codes are what the subtitle
 * search / AI translate APIs accept; the 3-letter (639-2) map exists because
 * embedded track metadata commonly carries "eng"/"ger"-style codes.
 */
object LanguageNames {

    private val twoLetter: Map<String, String> = mapOf(
        "en" to "English", "es" to "Spanish", "fr" to "French", "de" to "German",
        "it" to "Italian", "pt" to "Portuguese", "nl" to "Dutch", "pl" to "Polish",
        "ru" to "Russian", "zh" to "Chinese", "ja" to "Japanese", "ko" to "Korean",
        "ar" to "Arabic", "tr" to "Turkish", "sv" to "Swedish", "da" to "Danish",
        "no" to "Norwegian", "fi" to "Finnish", "hu" to "Hungarian", "cs" to "Czech",
        "ro" to "Romanian", "he" to "Hebrew", "th" to "Thai", "vi" to "Vietnamese",
        "el" to "Greek", "bg" to "Bulgarian", "hr" to "Croatian", "sk" to "Slovak",
        "sl" to "Slovenian", "uk" to "Ukrainian", "id" to "Indonesian", "ms" to "Malay",
        "hi" to "Hindi", "ta" to "Tamil", "te" to "Telugu", "bn" to "Bengali",
        "fa" to "Persian",
    )

    private val threeLetter: Map<String, String> = mapOf(
        "eng" to "English", "spa" to "Spanish", "fre" to "French", "fra" to "French",
        "ger" to "German", "deu" to "German", "ita" to "Italian", "por" to "Portuguese",
        "dut" to "Dutch", "nld" to "Dutch", "pol" to "Polish", "rus" to "Russian",
        "chi" to "Chinese", "zho" to "Chinese", "jpn" to "Japanese", "kor" to "Korean",
        "ara" to "Arabic", "tur" to "Turkish", "swe" to "Swedish", "dan" to "Danish",
        "nor" to "Norwegian", "fin" to "Finnish", "hun" to "Hungarian", "cze" to "Czech",
        "ces" to "Czech", "rum" to "Romanian", "ron" to "Romanian", "heb" to "Hebrew",
        "tha" to "Thai", "vie" to "Vietnamese", "gre" to "Greek", "ell" to "Greek",
        "bul" to "Bulgarian", "hrv" to "Croatian", "slo" to "Slovak", "slk" to "Slovak",
        "slv" to "Slovenian", "ukr" to "Ukrainian", "ind" to "Indonesian", "may" to "Malay",
        "msa" to "Malay", "hin" to "Hindi", "tam" to "Tamil", "tel" to "Telugu",
        "ben" to "Bengali", "per" to "Persian", "fas" to "Persian",
    )

    /**
     * Options for the language pickers (search + AI translate target):
     * (2-letter code, display name), sorted by display name — web parity.
     */
    val dropdownOptions: List<Pair<String, String>> =
        twoLetter.entries.map { it.key to it.value }.sortedBy { it.second }

    /**
     * Display name for any 2- or 3-letter code. Unknown codes fall back to
     * the uppercased code; null/blank renders "Unknown".
     */
    fun displayName(code: String?): String {
        val lower = code?.trim()?.lowercase().orEmpty()
        if (lower.isEmpty()) return "Unknown"
        return twoLetter[lower] ?: threeLetter[lower] ?: lower.uppercase()
    }

    /**
     * Normalizes a profile/track language code to a 2-letter code the
     * subtitle APIs accept. Unmappable codes default to "en" (web default).
     */
    fun searchCode(code: String?): String =
        org.siloserver.silo.language.canonicalLanguageTag(code).orEmpty()

}
