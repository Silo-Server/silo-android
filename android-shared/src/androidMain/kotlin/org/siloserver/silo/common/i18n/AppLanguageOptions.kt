package org.siloserver.silo.common.i18n

import org.siloserver.silo.model.settings.AppLanguage
import java.util.Locale

/** One row of the App language picker; [id] "" is Match device. */
data class AppLanguageOption(val id: String, val label: String)

/**
 * "Match device", then each shipped language by its own name. A stored tag
 * this build doesn't ship stays listed and selected under its own name,
 * so the picker never shows it as Match device and re-saving keeps it.
 */
fun appLanguageOptions(matchDeviceLabel: String, choice: String?): List<AppLanguageOption> {
    val tags = AppLanguage.SHIPPED + listOfNotNull(choice?.takeIf { it !in AppLanguage.SHIPPED })
    return listOf(AppLanguageOption("", matchDeviceLabel)) + tags.map { AppLanguageOption(it, endonym(it)) }
}

/** "Español", "Português (Brasil)", "中文 (简体)". */
private fun endonym(tag: String): String {
    val locale = Locale.forLanguageTag(tag)
    return locale.getDisplayName(locale).ifBlank { tag }.replaceFirstChar { it.titlecase(locale) }
}
