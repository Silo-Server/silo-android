package org.siloserver.silo.common.i18n

/**
 * Case conversion for text a user reads, by the interface language's rules.
 *
 * Kotlin's `uppercase()` and `lowercase()` use `Locale.ROOT`, which writes
 * Turkish "dizi" as "DIZI" instead of "DİZİ". Text that is data (codes,
 * codecs, ids, wire values) keeps those, so "imax" never becomes "İMAX".
 */

/** [this] in capitals for display: "DİZİ" in a Turkish interface. */
fun String.displayUppercase(): String = uppercase(LocaleFormat.interfaceLocale())

/** [this] with its first letter lowercased for display, for text joined mid-sentence. */
fun String.displayLowercaseFirst(): String {
    val locale = LocaleFormat.interfaceLocale()
    return replaceFirstChar { it.toString().lowercase(locale) }
}
