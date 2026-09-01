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

fun canonicalSubtitleLanguage(language: String?): String? {
    val primary = language
        ?.trim()
        ?.takeUnless { it.isBlank() || it.equals("und", ignoreCase = true) }
        ?.lowercase()
        ?.replace('_', '-')
        ?.substringBefore('-')
        ?: return null
    return ISO_639_2_TO_639_1[primary] ?: primary
}

/**
 * ISO 639-2 (bibliographic and terminology) codes that have an ISO 639-1
 * equivalent. Media3 normalizes every mounted track language to 639-1 via
 * `Locale.getISOLanguages()`, while catalog metadata carries whatever ffprobe
 * reported (usually 639-2). Matching a catalog row against a mounted track
 * therefore needs the whole table, not a handful of common aliases; a missing
 * entry makes a byte-identical original file look like it does not contain
 * its own audio track.
 */
private val ISO_639_2_TO_639_1: Map<String, String> = mapOf(
    "aar" to "aa", "abk" to "ab", "afr" to "af", "aka" to "ak", "alb" to "sq", "sqi" to "sq",
    "amh" to "am", "ara" to "ar", "arg" to "an", "arm" to "hy", "hye" to "hy", "asm" to "as",
    "ava" to "av", "ave" to "ae", "aym" to "ay", "aze" to "az", "bak" to "ba", "bam" to "bm",
    "baq" to "eu", "eus" to "eu", "bel" to "be", "ben" to "bn", "bih" to "bh", "bis" to "bi",
    "bos" to "bs", "bre" to "br", "bul" to "bg", "bur" to "my", "mya" to "my", "cat" to "ca",
    "cha" to "ch", "che" to "ce", "chi" to "zh", "zho" to "zh", "chu" to "cu", "chv" to "cv",
    "cor" to "kw", "cos" to "co", "cre" to "cr", "cze" to "cs", "ces" to "cs", "dan" to "da",
    "div" to "dv", "dut" to "nl", "nld" to "nl", "dzo" to "dz", "eng" to "en", "epo" to "eo",
    "est" to "et", "ewe" to "ee", "fao" to "fo", "fij" to "fj", "fin" to "fi", "fre" to "fr",
    "fra" to "fr", "fry" to "fy", "ful" to "ff", "geo" to "ka", "kat" to "ka", "ger" to "de",
    "deu" to "de", "gla" to "gd", "gle" to "ga", "glg" to "gl", "glv" to "gv", "gre" to "el",
    "ell" to "el", "grn" to "gn", "guj" to "gu", "hat" to "ht", "hau" to "ha", "heb" to "he",
    "her" to "hz", "hin" to "hi", "hmo" to "ho", "hrv" to "hr", "hun" to "hu", "ibo" to "ig",
    "ice" to "is", "isl" to "is", "ido" to "io", "iii" to "ii", "iku" to "iu", "ile" to "ie",
    "ina" to "ia", "ind" to "id", "ipk" to "ik", "ita" to "it", "jav" to "jv", "jpn" to "ja",
    "kal" to "kl", "kan" to "kn", "kas" to "ks", "kau" to "kr", "kaz" to "kk", "khm" to "km",
    "kik" to "ki", "kin" to "rw", "kir" to "ky", "kom" to "kv", "kon" to "kg", "kor" to "ko",
    "kua" to "kj", "kur" to "ku", "lao" to "lo", "lat" to "la", "lav" to "lv", "lim" to "li",
    "lin" to "ln", "lit" to "lt", "ltz" to "lb", "lub" to "lu", "lug" to "lg", "mac" to "mk",
    "mkd" to "mk", "mah" to "mh", "mal" to "ml", "mao" to "mi", "mri" to "mi", "mar" to "mr",
    "may" to "ms", "msa" to "ms", "mlg" to "mg", "mlt" to "mt", "mon" to "mn", "nau" to "na",
    "nav" to "nv", "nbl" to "nr", "nde" to "nd", "ndo" to "ng", "nep" to "ne", "nno" to "nn",
    "nob" to "nb", "nor" to "no", "nya" to "ny", "oci" to "oc", "oji" to "oj", "ori" to "or",
    "orm" to "om", "oss" to "os", "pan" to "pa", "per" to "fa", "fas" to "fa", "pli" to "pi",
    "pol" to "pl", "por" to "pt", "pus" to "ps", "que" to "qu", "roh" to "rm", "rum" to "ro",
    "ron" to "ro", "run" to "rn", "rus" to "ru", "sag" to "sg", "san" to "sa", "sin" to "si",
    "slo" to "sk", "slk" to "sk", "slv" to "sl", "sme" to "se", "smo" to "sm", "sna" to "sn",
    "snd" to "sd", "som" to "so", "sot" to "st", "spa" to "es", "srd" to "sc", "srp" to "sr",
    "ssw" to "ss", "sun" to "su", "swa" to "sw", "swe" to "sv", "tah" to "ty", "tam" to "ta",
    "tat" to "tt", "tel" to "te", "tgk" to "tg", "tgl" to "tl", "tha" to "th", "tib" to "bo",
    "bod" to "bo", "tir" to "ti", "ton" to "to", "tsn" to "tn", "tso" to "ts", "tuk" to "tk",
    "tur" to "tr", "twi" to "tw", "uig" to "ug", "ukr" to "uk", "urd" to "ur", "uzb" to "uz",
    "ven" to "ve", "vie" to "vi", "vol" to "vo", "wel" to "cy", "cym" to "cy", "wln" to "wa",
    "wol" to "wo", "xho" to "xh", "yid" to "yi", "yor" to "yo", "zha" to "za", "zul" to "zu",
)
