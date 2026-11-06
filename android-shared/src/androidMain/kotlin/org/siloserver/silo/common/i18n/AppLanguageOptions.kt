package org.siloserver.silo.common.i18n

import java.util.Locale

/** One row of the App language picker; [id] "" is Match device. */
data class AppLanguageOption(val id: String, val label: String)

/**
 * "Match device", then each shipped language by its own name. A stored tag
 * this build doesn't ship stays listed and selected under its own name,
 * so the picker never shows it as Match device and re-saving keeps it.
 * [pseudoLocales] (debug builds only) come last, by tag.
 */
fun appLanguageOptions(
    matchDeviceLabel: String,
    state: AppLanguageState,
    pseudoLocales: List<String> = emptyList(),
): List<AppLanguageOption> {
    val choice = state.choice
    val tags = state.languages + listOfNotNull(choice?.takeIf { it !in state.languages })
    return listOf(AppLanguageOption("", matchDeviceLabel)) + tags.map { AppLanguageOption(it, endonym(it)) } +
        pseudoLocales.map { AppLanguageOption(it, it) }
}

/** "Español", "Português (Brasil)", "中文 (简体)". */
private fun endonym(tag: String): String {
    val locale = Locale.forLanguageTag(tag)
    return locale.getDisplayName(locale).ifBlank { tag }.replaceFirstChar { it.titlecase(locale) }
}
